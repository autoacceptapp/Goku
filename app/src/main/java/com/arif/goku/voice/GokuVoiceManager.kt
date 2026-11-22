package com.arif.goku.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceEngineState {
    IDLE,
    LISTENING,
    CONTINUOUS_LISTENING,
    SPEAKING
}

data class VoiceUiStatus(
    val engineState: VoiceEngineState = VoiceEngineState.IDLE,
    val partialTranscript: String = "",
    val audioLevel: Float = 0f,
    val continuousSecondsLeft: Int = 0,
    val isSpeechRecognizerAvailable: Boolean = true,
    val isHindiTtsReady: Boolean = false,
    val ttsNotice: String? = null
)

/**
 * Manages Android SpeechRecognizer (hi-IN) + TextToSpeech locked to Locale("hi", "IN")
 * with a 6-second Continuous Listening Mode after Goku finishes speaking.
 */
class GokuVoiceManager(
    context: Context,
    private val scope: CoroutineScope,
    private val isContinuousModeEnabled: () -> Boolean,
    private val isEmergencyStopPhrase: (String) -> Boolean,
    private val onEmergencyStopHeard: (String) -> Unit,
    private val onFinalSpeechResult: (String) -> Unit,
    private val onVoiceError: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionGeneration = AtomicInteger(0)

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var continuousTimerJob: Job? = null
    private var speechDetectedInCurrentTurn = false
    private var isDestroyed = false
    private var currentTtsVolume: Float = 1.0f
    private var currentSpeechRate: Float = 1.0f
    @Volatile
    private var pendingUtteranceCallback: Pair<Int, () -> Unit>? = null

    private val _status = MutableStateFlow(
        VoiceUiStatus(
            isSpeechRecognizerAvailable = SpeechRecognizer.isRecognitionAvailable(appContext)
        )
    )
    val status: StateFlow<VoiceUiStatus> = _status.asStateFlow()

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(appContext) { initStatus ->
                if (isDestroyed) return@TextToSpeech
                if (initStatus == TextToSpeech.SUCCESS) {
                    val tts = textToSpeech ?: return@TextToSpeech
                    val result = tts.setLanguage(HINDI_LOCALE)
                    val hindiReady = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED
                    val notice = if (hindiReady) {
                        null
                    } else {
                        "Hindi (hi-IN) TTS voice pack not installed on device; using default engine fallback."
                    }
                    tts.setSpeechRate(1.0f)
                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _status.value = _status.value.copy(
                                engineState = VoiceEngineState.SPEAKING,
                                continuousSecondsLeft = 0
                            )
                        }

                        override fun onDone(utteranceId: String?) {
                            val expectedGen = utteranceId?.substringBefore(":")?.toIntOrNull()
                            val allowContinuous = utteranceId?.endsWith(":cont=1") == true
                            handleTtsFinished(expectedGen, allowContinuous)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            _status.value = _status.value.copy(
                                engineState = VoiceEngineState.IDLE,
                                audioLevel = 0f
                            )
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _status.value = _status.value.copy(
                                engineState = VoiceEngineState.IDLE,
                                audioLevel = 0f
                            )
                        }
                    })
                    _status.value = _status.value.copy(
                        isHindiTtsReady = hindiReady,
                        ttsNotice = notice
                    )
                } else {
                    _status.value = _status.value.copy(
                        isHindiTtsReady = false,
                        ttsNotice = "Text-to-Speech engine unavailable on this device."
                    )
                }
            }
        } catch (_: Exception) {
            _status.value = _status.value.copy(
                isHindiTtsReady = false,
                ttsNotice = "Could not initialize Hindi TTS."
            )
        }
    }

    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.6f, 1.6f)
        mainHandler.post {
            textToSpeech?.setSpeechRate(currentSpeechRate)
        }
    }

    /**
     * Phase 3 Smart Context audio profile: adjusts TTS volume (soft at Night/DND) and speech rate
     * without mutating the phone's global media volume.
     */
    fun setAudioProfile(volume: Float, speechRate: Float) {
        currentTtsVolume = volume.coerceIn(0.2f, 1.0f)
        currentSpeechRate = speechRate.coerceIn(0.65f, 1.45f)
        mainHandler.post {
            textToSpeech?.setSpeechRate(currentSpeechRate)
        }
    }

    /**
     * Speaks Hindi/Hinglish text using TextToSpeech locked to Locale("hi", "IN").
     * When [reopenMicAfterSpeaking] is true and Continuous Listening Mode is enabled,
     * automatically opens the mic for 6 seconds upon completion.
     */
    fun speakHindi(text: String, reopenMicAfterSpeaking: Boolean = true) {
        speakHindiInternal(text, reopenMicAfterSpeaking, onDoneCallback = null)
    }

    /**
     * Phase 6 Smart Call hook: speaks Hindi greeting on speakerphone and invokes [onFinished]
     * as soon as TTS finishes (so SmartCallManager can start listening for the caller's message).
     */
    fun speakHindiWithCallback(text: String, onFinished: () -> Unit) {
        speakHindiInternal(text, reopenMicAfterSpeaking = false, onDoneCallback = onFinished)
    }

    private fun speakHindiInternal(
        text: String,
        reopenMicAfterSpeaking: Boolean,
        onDoneCallback: (() -> Unit)?
    ) {
        val cleaned = text.trim()
        if (cleaned.isEmpty() || isDestroyed) {
            onDoneCallback?.invoke()
            return
        }

        val gen = sessionGeneration.incrementAndGet()
        pendingUtteranceCallback = if (onDoneCallback != null) gen to onDoneCallback else null
        cancelContinuousTimer()

        mainHandler.post {
            stopRecognizerInternal()
            WakeWordBridge.setCapturePaused(true)
            val tts = textToSpeech
            if (tts == null) {
                WakeWordBridge.setCapturePaused(false)
                _status.value = _status.value.copy(engineState = VoiceEngineState.IDLE)
                val cb = pendingUtteranceCallback?.takeIf { it.first == gen }?.second
                pendingUtteranceCallback = null
                cb?.invoke()
                return@post
            }
            // Enforce Hindi locale & Smart Context volume/rate before speaking
            tts.language = HINDI_LOCALE
            tts.setSpeechRate(currentSpeechRate)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentTtsVolume)
            }
            val contFlag = if (reopenMicAfterSpeaking && isContinuousModeEnabled()) "1" else "0"
            val utteranceId = "$gen:${UUID.randomUUID()}:cont=$contFlag"
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.SPEAKING,
                partialTranscript = "",
                audioLevel = 0.55f,
                continuousSecondsLeft = 0
            )
            tts.speak(cleaned, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }
    }

    fun startListening(isContinuousWindow: Boolean = false) {
        if (isDestroyed) return
        val available = SpeechRecognizer.isRecognitionAvailable(appContext)
        _status.value = _status.value.copy(isSpeechRecognizerAvailable = available)
        if (!available) {
            onVoiceError("Speech recognition service device par available nahi hai. Neeche text input use karein.")
            return
        }

        val gen = sessionGeneration.incrementAndGet()
        cancelContinuousTimer()
        speechDetectedInCurrentTurn = false

        mainHandler.post {
            WakeWordBridge.setCapturePaused(true)
            textToSpeech?.stop()
            stopRecognizerInternal()

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
                speechRecognizer = recognizer
                recognizer.setRecognitionListener(createListener(gen))

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, HINDI_LOCALE_TAG)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, HINDI_LOCALE_TAG)
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        2200L
                    )
                }

                _status.value = _status.value.copy(
                    engineState = if (isContinuousWindow) {
                        VoiceEngineState.CONTINUOUS_LISTENING
                    } else {
                        VoiceEngineState.LISTENING
                    },
                    partialTranscript = "",
                    audioLevel = 0.15f,
                    continuousSecondsLeft = if (isContinuousWindow) CONTINUOUS_WINDOW_SECONDS else 0
                )

                recognizer.startListening(intent)

                if (isContinuousWindow) {
                    startContinuousWindowCountdown(gen)
                }
            } catch (_: Exception) {
                _status.value = _status.value.copy(
                    engineState = VoiceEngineState.IDLE,
                    audioLevel = 0f,
                    continuousSecondsLeft = 0
                )
                onVoiceError("Mic start nahi ho paya. Permission aur Google Speech service check karein.")
            }
        }
    }

    fun stopListening() {
        sessionGeneration.incrementAndGet()
        cancelContinuousTimer()
        mainHandler.post {
            stopRecognizerInternal()
            if (_status.value.engineState != VoiceEngineState.SPEAKING) {
                WakeWordBridge.setCapturePaused(false)
                _status.value = _status.value.copy(
                    engineState = VoiceEngineState.IDLE,
                    partialTranscript = "",
                    audioLevel = 0f,
                    continuousSecondsLeft = 0
                )
            }
        }
    }

    /**
     * Immediately halts TextToSpeech, SpeechRecognizer, and any active Continuous Listening timer.
     */
    fun emergencyStop() {
        sessionGeneration.incrementAndGet()
        pendingUtteranceCallback = null
        cancelContinuousTimer()
        mainHandler.post {
            try {
                textToSpeech?.stop()
            } catch (_: Exception) {
            }
            stopRecognizerInternal()
            WakeWordBridge.setCapturePaused(false)
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.IDLE,
                partialTranscript = "",
                audioLevel = 0f,
                continuousSecondsLeft = 0
            )
        }
    }

    fun destroy() {
        isDestroyed = true
        emergencyStop()
        mainHandler.post {
            try {
                textToSpeech?.shutdown()
            } catch (_: Exception) {
            }
            textToSpeech = null
        }
    }

    private fun handleTtsFinished(expectedGen: Int?, allowContinuous: Boolean) {
        if (isDestroyed) return
        if (expectedGen == null || expectedGen != sessionGeneration.get()) return

        val callback = pendingUtteranceCallback?.takeIf { it.first == expectedGen }?.second
        if (callback != null) {
            pendingUtteranceCallback = null
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.IDLE,
                audioLevel = 0f,
                continuousSecondsLeft = 0
            )
            scope.launch {
                delay(280L)
                if (!isDestroyed && sessionGeneration.get() == expectedGen) {
                    callback()
                }
            }
            return
        }

        if (allowContinuous && isContinuousModeEnabled()) {
            // Brief 280ms acoustic guard so mic doesn't pick up trailing speaker echo
            scope.launch {
                delay(280L)
                if (!isDestroyed && sessionGeneration.get() == expectedGen) {
                    startListening(isContinuousWindow = true)
                }
            }
        } else {
            WakeWordBridge.setCapturePaused(false)
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.IDLE,
                audioLevel = 0f,
                continuousSecondsLeft = 0
            )
        }
    }

    private fun startContinuousWindowCountdown(gen: Int) {
        cancelContinuousTimer()
        continuousTimerJob = scope.launch {
            for (sec in CONTINUOUS_WINDOW_SECONDS downTo 1) {
                if (sessionGeneration.get() != gen || speechDetectedInCurrentTurn) return@launch
                _status.value = _status.value.copy(continuousSecondsLeft = sec)
                delay(1000L)
            }
            if (sessionGeneration.get() == gen && !speechDetectedInCurrentTurn) {
                stopListening()
            }
        }
    }

    private fun cancelContinuousTimer() {
        continuousTimerJob?.cancel()
        continuousTimerJob = null
    }

    private fun stopRecognizerInternal() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    private fun createListener(gen: Int): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (sessionGeneration.get() != gen) return
            _status.value = _status.value.copy(audioLevel = 0.2f)
        }

        override fun onBeginningOfSpeech() {
            if (sessionGeneration.get() != gen) return
            speechDetectedInCurrentTurn = true
            cancelContinuousTimer()
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.LISTENING,
                continuousSecondsLeft = 0
            )
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (sessionGeneration.get() != gen) return
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.08f, 1f)
            _status.value = _status.value.copy(audioLevel = normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (sessionGeneration.get() != gen) return
            _status.value = _status.value.copy(audioLevel = 0.1f)
        }

        override fun onError(error: Int) {
            if (sessionGeneration.get() != gen) return
            cancelContinuousTimer()
            stopRecognizerInternal()
            WakeWordBridge.setCapturePaused(false)
            val wasContinuous = _status.value.engineState == VoiceEngineState.CONTINUOUS_LISTENING
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.IDLE,
                partialTranscript = "",
                audioLevel = 0f,
                continuousSecondsLeft = 0
            )

            // Silent timeout during 6s continuous window is expected normal exit
            if (wasContinuous && (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                return
            }

            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Mic audio record karne mein error aaya."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission zaroori hai."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice recognition ke liye network issue aaya."
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Kuch sunai nahi diya. Orb dabakar dobara boliye."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mic abhi busy hai, ek second baad try karein."
                else -> "Voice listen error ($error)."
            }
            onVoiceError(message)
        }

        override fun onResults(results: Bundle?) {
            if (sessionGeneration.get() != gen) return
            cancelContinuousTimer()
            stopRecognizerInternal()
            WakeWordBridge.setCapturePaused(false)

            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val spoken = matches?.firstOrNull().orEmpty().trim()
            _status.value = _status.value.copy(
                engineState = VoiceEngineState.IDLE,
                partialTranscript = "",
                audioLevel = 0f,
                continuousSecondsLeft = 0
            )

            if (spoken.isEmpty()) return

            if (isEmergencyStopPhrase(spoken)) {
                emergencyStop()
                onEmergencyStopHeard(spoken)
            } else {
                onFinalSpeechResult(spoken)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (sessionGeneration.get() != gen) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()

            if (partial.isNotEmpty()) {
                speechDetectedInCurrentTurn = true
                cancelContinuousTimer()
                _status.value = _status.value.copy(
                    partialTranscript = partial,
                    continuousSecondsLeft = 0
                )
                // Instant mid-utterance emergency kill switch
                if (isEmergencyStopPhrase(partial)) {
                    emergencyStop()
                    onEmergencyStopHeard(partial)
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        val HINDI_LOCALE: Locale = Locale("hi", "IN")
        private const val HINDI_LOCALE_TAG = "hi-IN"
        const val CONTINUOUS_WINDOW_SECONDS = 6
    }
}
