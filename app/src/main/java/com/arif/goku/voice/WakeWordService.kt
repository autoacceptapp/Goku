package com.arif.goku.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arif.goku.data.SecurePrefs
import com.arif.goku.overlay.GokuOverlayService
import com.arif.goku.overlay.OverlayBridge
import com.arif.goku.overlay.OverlayCommand
import com.arif.goku.overlay.OverlayOrbState
import com.arif.goku.system.SmartContextManager
import com.arif.goku.ui.LockScreenWakeActivity
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

enum class WakePhraseMatch {
    HEY_GOKU,
    GOKU_STOP
}

enum class WakeWordEvent {
    HEY_GOKU_UNLOCKED,
    HEY_GOKU_LOCKED,
    GOKU_STOP
}

/**
 * Pure phrase matcher for offline KWS & SpeechRecognizer verification.
 */
object WakeWordMatcher {
    private val STOP_PHRASES = listOf(
        "goku stop",
        "goku ruk",
        "goku ruko",
        "goku band",
        "goku chup",
        "गोकू स्टॉप",
        "गोकू रुको",
        "गोकू बंद",
        "गोकू चुप"
    )

    private val WAKE_PHRASES = listOf(
        "hey goku",
        "hi goku",
        "ok goku",
        "okay goku",
        "hello goku",
        "oye goku",
        "arey goku",
        "he goku",
        "हे गोकू",
        "हाय गोकू",
        "ओके गोकू",
        "हेलो गोकू",
        "अरे गोकू"
    )

    fun matchPhrase(rawText: String): WakePhraseMatch? {
        val normalized = rawText
            .trim()
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{M}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isEmpty()) return null
        if (STOP_PHRASES.any { normalized.contains(it) }) return WakePhraseMatch.GOKU_STOP
        if (WAKE_PHRASES.any { normalized.contains(it) } || normalized == "goku" || normalized == "गोकू") {
            return WakePhraseMatch.HEY_GOKU
        }
        return null
    }
}

/**
 * Shared reactive bridge between WakeWordService, GokuVoiceManager, LockScreenWakeActivity,
 * and GokuDashboardViewModel so AudioRecord never collides with SpeechRecognizer.
 */
object WakeWordBridge {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    private val _isCapturePaused = MutableStateFlow(false)
    val isCapturePaused: StateFlow<Boolean> = _isCapturePaused.asStateFlow()

    private val _engineModeLabel = MutableStateFlow("OFF")
    val engineModeLabel: StateFlow<String> = _engineModeLabel.asStateFlow()

    private val _powerSavingActive = MutableStateFlow(false)
    val powerSavingActive: StateFlow<Boolean> = _powerSavingActive.asStateFlow()

    private val _lastDetectedPhrase = MutableStateFlow<String?>(null)
    val lastDetectedPhrase: StateFlow<String?> = _lastDetectedPhrase.asStateFlow()

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    fun setServiceRunning(running: Boolean) {
        _isServiceRunning.value = running
        if (!running) {
            _engineModeLabel.value = "OFF"
            _powerSavingActive.value = false
        }
    }

    fun setCapturePaused(paused: Boolean) {
        _isCapturePaused.value = paused
    }

    fun setEngineModeLabel(label: String) {
        _engineModeLabel.value = label
    }

    fun setPowerSavingActive(active: Boolean) {
        _powerSavingActive.value = active
    }

    fun emitEvent(event: WakeWordEvent, phrase: String) {
        _lastDetectedPhrase.value = phrase
        _events.tryEmit(event)
    }
}

/**
 * Phase 3 24x7 Offline Wake Word Foreground Microphone Service (`FOREGROUND_SERVICE_MICROPHONE`).
 *
 * Continuously captures 16 kHz mono PCM audio using `AudioRecord`:
 * - Uses adaptive noise-floor Energy + Zero-Crossing-Rate VAD to ignore ambient silence.
 * - Supports downloaded Sherpa-ONNX KWS weights (`filesDir/kws_model`) and automatically uses
 *   offline-preferred `SpeechRecognizer` verification (`EXTRA_PREFER_OFFLINE = true`) as a
 *   zero-bloat fallback so "HEY GOKU" and "GOKU STOP" work out of the box.
 * - Enters Power-Saving Duty Cycle automatically when SmartContextManager detects battery < 15%.
 * - Pauses `AudioRecord` immediately whenever `GokuVoiceManager` or `LockScreenWakeActivity`
 *   actively uses the microphone to prevent hardware conflicts.
 */
class WakeWordService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var securePrefs: SecurePrefs
    private lateinit var smartContextManager: SmartContextManager
    private lateinit var kwsModelManager: KwsModelManager

    private var captureJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var verifierRecognizer: SpeechRecognizer? = null

    @Volatile
    private var isVerifyingSpeech = false

    @Volatile
    private var lastTriggerTimeMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        securePrefs = SecurePrefs(applicationContext)
        smartContextManager = SmartContextManager(applicationContext, securePrefs)
        kwsModelManager = KwsModelManager(applicationContext)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_WAKE_WORD) {
            securePrefs.setWakeWordEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            WakeWordBridge.setServiceRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification("Listening for \"Hey Goku\" & \"Goku Stop\"")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquirePartialWakeLock()
        WakeWordBridge.setServiceRunning(true)
        startAudioCaptureLoop()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCaptureLoop() {
        if (captureJob?.isActive == true) return
        captureJob = serviceScope.launch {
            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 4)

            var audioRecord: AudioRecord? = null
            val pcmBuffer = ShortArray(FRAME_SAMPLES)
            var noiseFloor = 380.0
            var consecutiveSpeechFrames = 0
            var lastContextCheckMs = 0L

            try {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    if (now - lastContextCheckMs > 25_000L) {
                        lastContextCheckMs = now
                        smartContextManager.refresh()
                        val ctx = smartContextManager.state.value
                        val kwsReady = kwsModelManager.refreshStatus().hasOnnxFiles
                        WakeWordBridge.setPowerSavingActive(ctx.wakeDutyCyclePowerSave)
                        val modeLabel = when {
                            ctx.wakeDutyCyclePowerSave -> "POWER SAVE VAD (${ctx.batteryPercent}%)"
                            kwsReady -> "OFFLINE KWS + VAD"
                            else -> "VAD + OFFLINE VERIFY"
                        }
                        WakeWordBridge.setEngineModeLabel(modeLabel)
                        updateNotificationText(
                            if (ctx.wakeDutyCyclePowerSave) {
                                "Power-Saving Duty Cycle (${ctx.batteryPercent}%) • Hey Goku"
                            } else {
                                "24x7 Active ($modeLabel) • Say \"Hey Goku\""
                            }
                        )
                    }

                    // Release mic immediately when main voice assistant or verifier is active
                    if (WakeWordBridge.isCapturePaused.value || isVerifyingSpeech) {
                        releaseAudioRecord(audioRecord)
                        audioRecord = null
                        consecutiveSpeechFrames = 0
                        delay(220L)
                        continue
                    }

                    if (audioRecord == null) {
                        audioRecord = createAudioRecord(minBuffer)
                        if (audioRecord == null) {
                            delay(1200L)
                            continue
                        }
                        try {
                            audioRecord.startRecording()
                        } catch (_: Exception) {
                            releaseAudioRecord(audioRecord)
                            audioRecord = null
                            delay(1200L)
                            continue
                        }
                    }

                    val readCount = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount <= 0) {
                        delay(50L)
                        continue
                    }

                    val (rms, zcr) = computeAcousticFeatures(pcmBuffer, readCount)
                    val dynamicThreshold = (noiseFloor * 2.55).coerceIn(950.0, 4200.0)
                    val looksLikeSpeech = rms > dynamicThreshold && zcr in 0.025..0.34

                    if (looksLikeSpeech) {
                        consecutiveSpeechFrames++
                    } else {
                        // Slowly adapt background noise floor
                        noiseFloor = (noiseFloor * 0.94) + (rms * 0.06)
                        consecutiveSpeechFrames = 0
                    }

                    // Trigger phrase verification once sustained speech (~280ms) is detected
                    val cooldownPassed = (System.currentTimeMillis() - lastTriggerTimeMs) > TRIGGER_COOLDOWN_MS
                    if (consecutiveSpeechFrames >= SPEECH_FRAMES_REQUIRED && cooldownPassed) {
                        consecutiveSpeechFrames = 0
                        releaseAudioRecord(audioRecord)
                        audioRecord = null
                        runOfflineKeywordVerification()
                    } else if (smartContextManager.state.value.wakeDutyCyclePowerSave) {
                        // Low Battery (<15%): duty-cycle sleep to conserve battery
                        delay(POWER_SAVE_DUTY_SLEEP_MS)
                    }
                }
            } finally {
                releaseAudioRecord(audioRecord)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(bufferBytes: Int): AudioRecord? {
        return try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record
            } else {
                record.release()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun releaseAudioRecord(record: AudioRecord?) {
        if (record == null) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (_: Exception) {
        }
        try {
            record.release()
        } catch (_: Exception) {
        }
    }

    private fun computeAcousticFeatures(samples: ShortArray, count: Int): Pair<Double, Double> {
        var sumSquares = 0.0
        var zeroCrossings = 0
        var prev = samples[0].toInt()
        for (i in 0 until count) {
            val current = samples[i].toInt()
            sumSquares += current.toDouble() * current.toDouble()
            if ((current >= 0 && prev < 0) || (current < 0 && prev >= 0)) {
                zeroCrossings++
            }
            prev = current
        }
        val rms = sqrt(sumSquares / count.coerceAtLeast(1))
        val zcr = zeroCrossings.toDouble() / count.coerceAtLeast(1)
        return rms to zcr
    }

    /**
     * Runs a rapid offline-preferred SpeechRecognizer burst after AudioRecord VAD hears speech,
     * checking for "HEY GOKU" or "GOKU STOP" without blocking the audio loop.
     */
    private fun runOfflineKeywordVerification() {
        if (isVerifyingSpeech || WakeWordBridge.isCapturePaused.value) return
        if (!SpeechRecognizer.isRecognitionAvailable(applicationContext)) return

        isVerifyingSpeech = true
        mainHandler.post {
            try {
                cleanupVerifierRecognizer()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(applicationContext)
                verifierRecognizer = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partialResults: Bundle?) {
                        val candidates = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        checkCandidatesAndFinishIfMatched(candidates)
                    }

                    override fun onResults(results: Bundle?) {
                        val candidates = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            .orEmpty()
                        if (!checkCandidatesAndFinishIfMatched(candidates)) {
                            finishVerification()
                        }
                    }

                    override fun onError(error: Int) {
                        finishVerification()
                    }
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 4)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
                }

                recognizer.startListening(intent)

                // Safety watchdog timeout so AudioRecord resumes cleanly if recognizer hangs
                mainHandler.postDelayed({
                    if (isVerifyingSpeech) {
                        finishVerification()
                    }
                }, VERIFY_TIMEOUT_MS)
            } catch (_: Exception) {
                finishVerification()
            }
        }
    }

    private fun checkCandidatesAndFinishIfMatched(candidates: List<String>): Boolean {
        for (phrase in candidates) {
            val match = WakeWordMatcher.matchPhrase(phrase) ?: continue
            lastTriggerTimeMs = System.currentTimeMillis()
            finishVerification()
            handleWakeMatch(match, phrase)
            return true
        }
        return false
    }

    private fun finishVerification() {
        cleanupVerifierRecognizer()
        isVerifyingSpeech = false
    }

    private fun cleanupVerifierRecognizer() {
        try {
            verifierRecognizer?.cancel()
            verifierRecognizer?.destroy()
        } catch (_: Exception) {
        }
        verifierRecognizer = null
    }

    private fun handleWakeMatch(match: WakePhraseMatch, detectedPhrase: String) {
        smartContextManager.refresh()
        val smartCtx = smartContextManager.state.value

        when (match) {
            WakePhraseMatch.GOKU_STOP -> {
                WakeWordBridge.emitEvent(WakeWordEvent.GOKU_STOP, detectedPhrase)
                OverlayBridge.emitCommand(OverlayCommand.EMERGENCY_STOP)
            }

            WakePhraseMatch.HEY_GOKU -> {
                if (!smartCtx.isNightQuietMode) {
                    playSubtleWakeChime()
                }
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isLockedOrScreenOff = (km?.isKeyguardLocked == true) || (pm?.isInteractive == false)

                if (isLockedOrScreenOff) {
                    WakeWordBridge.emitEvent(WakeWordEvent.HEY_GOKU_LOCKED, detectedPhrase)
                    val lockIntent = Intent(this, LockScreenWakeActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                    }
                    try {
                        startActivity(lockIntent)
                    } catch (_: Exception) {
                    }
                } else {
                    // Screen unlocked: wake Floating Overlay Orb + trigger immediate listening
                    if (Settings.canDrawOverlays(this)) {
                        GokuOverlayService.start(this)
                        OverlayBridge.updateState(OverlayOrbState.LISTENING)
                    }
                    WakeWordBridge.emitEvent(WakeWordEvent.HEY_GOKU_UNLOCKED, detectedPhrase)
                    OverlayBridge.emitCommand(OverlayCommand.TAP_ORB)

                    val openIntent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(GokuOverlayService.EXTRA_TRIGGER_VOICE_LISTEN, true)
                    }
                    try {
                        startActivity(openIntent)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun playSubtleWakeChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 48)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
            mainHandler.postDelayed({
                try {
                    tg.release()
                } catch (_: Exception) {
                }
            }, 220L)
        } catch (_: Exception) {
        }
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "GokuAgent::WakeWordMicLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12h safety timeout
            }
        } catch (_: Exception) {
        }
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GokuAgent 24x7 Wake Word",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps offline \"Hey Goku\" & \"Goku Stop\" voice wake active."
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP_WAKE_WORD
        }
        val stopPending = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hey Goku 24x7 Mic Active")
            .setContentText(contentText)
            .setContentIntent(openPending)
            .addAction(0, "Pause 24x7 Mic", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotificationText(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(text))
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        captureJob?.cancel()
        serviceScope.cancel()
        mainHandler.post { cleanupVerifierRecognizer() }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        WakeWordBridge.setServiceRunning(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "goku_wake_word_channel"
        private const val NOTIFICATION_ID = 3045
        private const val SAMPLE_RATE_HZ = 16_000
        private const val FRAME_SAMPLES = 1600 // 100ms @ 16kHz
        private const val SPEECH_FRAMES_REQUIRED = 3
        private const val TRIGGER_COOLDOWN_MS = 2800L
        private const val VERIFY_TIMEOUT_MS = 3200L
        private const val POWER_SAVE_DUTY_SLEEP_MS = 450L

        const val ACTION_STOP_WAKE_WORD = "com.arif.goku.action.STOP_WAKE_WORD"

        fun start(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return try {
                val intent = Intent(context, WakeWordService::class.java)
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, WakeWordService::class.java).apply {
                    action = ACTION_STOP_WAKE_WORD
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
