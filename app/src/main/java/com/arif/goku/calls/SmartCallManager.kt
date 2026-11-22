package com.arif.goku.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.CallSummaryDao
import com.arif.goku.data.db.CallSummaryEntity
import com.arif.goku.data.db.MemoryCategory
import com.arif.goku.data.db.MemoryManager
import com.example.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SmartCallPhase {
    IDLE,
    RINGING_COUNTDOWN,
    AUTO_ANSWERING,
    SPEAKING_GREETING,
    LISTENING_CALLER,
    COMPLETED
}

enum class SmartCallAnswerMode(val label: String) {
    INCALL_SERVICE("Default Dialer (InCallService)"),
    TELECOM_FALLBACK("Stock Dialer (TelecomManager)"),
    SIMULATED_TEST("30s Timer Test")
}

data class CallerIdentity(
    val displayName: String,
    val phoneNumber: String,
    val isKnownContact: Boolean,
    val isSpamSuspected: Boolean,
    val spamReason: String = ""
)

data class SmartCallUiState(
    val phase: SmartCallPhase = SmartCallPhase.IDLE,
    val callerName: String = "",
    val callerNumber: String = "",
    val isSpamOrUnknown: Boolean = false,
    val spamAlertMessage: String? = null,
    val countdownSecondsLeft: Int = 0,
    val totalDelaySeconds: Int = SecurePrefs.DEFAULT_SMART_CALL_DELAY_SECONDS,
    val isSpeakerphoneActive: Boolean = false,
    val activeAnswerMode: SmartCallAnswerMode? = null,
    val capturedTranscript: String = "",
    val statusHindi: String = "Smart Call standby.",
    val isDefaultDialer: Boolean = false
)

data class SmartCallCompletionEvent(
    val summaryEntity: CallSummaryEntity,
    val spokenNoticeHindi: String
)

interface CallAnswerDelegate {
    fun answerCall(isTestSimulation: Boolean): SmartCallAnswerMode?
    fun endCallIfPossible(isTestSimulation: Boolean)
}

interface CallAudioRouter {
    fun enableSpeakerphone(): Boolean
    fun restoreNormalAudio()
}

interface CallVoiceDelegate {
    fun speakGreetingThenListen(greetingHindi: String, onReadyToListen: () -> Unit)
    fun speakAlertHindi(messageHindi: String)
    fun stopVoice()
}

interface CallerLookupDelegate {
    suspend fun resolveCaller(phoneNumber: String): CallerIdentity
}

/**
 * Phase 6 Smart Call Manager — 30-Second Auto-Answer & Talk Engine.
 *
 * Lifecycle:
 * 1) Incoming call rings (`GokuInCallService` or `CallReceiver` -> `SmartCallBridge`).
 * 2) Runs Spam / Unknown Number Alert check (contacts + Room Memory Vault + spam prefixes).
 * 3) Starts configurable countdown (default 30s). Cancels immediately on user answer (`OFFHOOK`),
 *    decline/end (`IDLE`), or `"Goku stop"`.
 * 4) If still ringing at 0s (and Smart Call is ON):
 *    - Answers via `GokuInCallService` (Default Dialer) or `TelecomManager.acceptRingingCall()` fallback.
 *    - Routes audio to **Speakerphone** (`ROUTE_SPEAKER` + `AudioManager`).
 *    - Speaks polite Hindi greeting via `GokuVoiceManager` and listens for caller's message.
 *    - Saves `CallSummaryEntity` to Room DB + `MemoryManager`, posts notification, and emits Hindi summary.
 */
class SmartCallManager(
    context: Context,
    private val securePrefs: SecurePrefs,
    private val callSummaryDao: CallSummaryDao,
    private val memoryManager: MemoryManager,
    private val scope: CoroutineScope,
    private val answerDelegate: CallAnswerDelegate = AndroidCallAnswerDelegate(context.applicationContext),
    private val audioRouter: CallAudioRouter = AndroidCallAudioRouter(context.applicationContext),
    private val voiceDelegate: CallVoiceDelegate,
    private val callerLookup: CallerLookupDelegate = AndroidCallerLookupDelegate(context.applicationContext, memoryManager),
    private val tickIntervalMs: Long = 1_000L,
    private val callerListenTimeoutMs: Long = 16_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(SmartCallUiState(isDefaultDialer = isDefaultDialer(appContext)))
    val state: StateFlow<SmartCallUiState> = _state.asStateFlow()

    val recentSummaries: Flow<List<CallSummaryEntity>> = callSummaryDao.observeCallSummaries(limit = 30)

    private val _completions = MutableSharedFlow<SmartCallCompletionEvent>(extraBufferCapacity = 8)
    val completions: SharedFlow<SmartCallCompletionEvent> = _completions.asSharedFlow()

    private val _spamAlerts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val spamAlerts: SharedFlow<String> = _spamAlerts.asSharedFlow()

    private var countdownJob: Job? = null
    private var listenTimeoutJob: Job? = null
    private var autoAnswerSessionActive = false
    private var isCurrentSessionSimulation = false
    private var sessionAnsweredAtMs = 0L
    private var currentCallerIdentity: CallerIdentity? = null
    private var transcriptParts = mutableListOf<String>()

    fun refreshRoleStatus() {
        _state.update { it.copy(isDefaultDialer = isDefaultDialer(appContext)) }
    }

    fun isListeningForCallerMessage(): Boolean {
        return autoAnswerSessionActive && _state.value.phase == SmartCallPhase.LISTENING_CALLER
    }

    /**
     * Entry point for `SmartCallBridge` events (from `GokuInCallService` or `CallReceiver`)
     * as well as unit tests.
     */
    fun onIncomingSignal(signal: IncomingCallSignal) {
        when (signal.type) {
            CallSignalType.RINGING -> handleRinging(signal.phoneNumber, isSimulation = false)
            CallSignalType.OFFHOOK -> handleOffhook()
            CallSignalType.IDLE -> handleCallEnded()
        }
    }

    /**
     * Runs a safe, self-contained 30-second (or configured) Smart Call countdown simulation so the
     * user can test countdown, cancellation, speakerphone greeting, and Room summary saving on-device.
     */
    fun startTestCountdownSimulation(
        testNumber: String = "+91 9876543210",
        overrideDelaySeconds: Int? = null
    ) {
        handleRinging(
            phoneNumber = testNumber,
            isSimulation = true,
            overrideDelaySeconds = overrideDelaySeconds
        )
    }

    /**
     * Feeds recognized speech captured while Goku is listening to the caller on speakerphone.
     */
    fun onCallerTranscriptCaptured(spokenText: String) {
        if (!autoAnswerSessionActive) return
        val cleaned = spokenText.trim()
        if (cleaned.isEmpty()) return
        transcriptParts.add(cleaned)
        _state.update {
            it.copy(
                capturedTranscript = transcriptParts.joinToString(" "),
                statusHindi = "Caller message note kar liya gaya: \"${cleaned.take(60)}\""
            )
        }
        // In simulated mode or once a full message is captured, finalize cleanly
        if (isCurrentSessionSimulation) {
            finalizeAutoAnsweredSession(callerSpokeNow = true)
        }
    }

    /**
     * Instant emergency stop (`"Goku stop"` or user Cancel button): cancels countdown, voice, and audio route.
     */
    fun cancelEmergencyStop(reason: String = "Goku Stop") {
        countdownJob?.cancel()
        countdownJob = null
        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        voiceDelegate.stopVoice()
        if (autoAnswerSessionActive) {
            audioRouter.restoreNormalAudio()
            autoAnswerSessionActive = false
            isCurrentSessionSimulation = false
        }
        _state.update {
            it.copy(
                phase = SmartCallPhase.IDLE,
                countdownSecondsLeft = 0,
                isSpeakerphoneActive = false,
                statusHindi = "Smart Call cancel kiya gaya ($reason)."
            )
        }
    }

    suspend fun deleteCallSummary(id: Long) {
        callSummaryDao.deleteById(id)
    }

    private fun handleRinging(
        phoneNumber: String,
        isSimulation: Boolean,
        overrideDelaySeconds: Int? = null
    ) {
        // Deduplicate if already ringing for the same call (e.g., both InCallService & CallReceiver fired)
        if (_state.value.phase == SmartCallPhase.RINGING_COUNTDOWN &&
            _state.value.callerNumber == phoneNumber.trim() &&
            !isSimulation
        ) {
            return
        }

        countdownJob?.cancel()
        listenTimeoutJob?.cancel()
        autoAnswerSessionActive = false
        isCurrentSessionSimulation = isSimulation
        transcriptParts.clear()

        val delaySeconds = (overrideDelaySeconds ?: securePrefs.getSmartCallDelaySeconds()).coerceIn(1, 60)
        val smartCallOn = isSimulation || securePrefs.isSmartCallEnabled()

        countdownJob = scope.launch {
            val identity = try {
                callerLookup.resolveCaller(phoneNumber)
            } catch (_: Exception) {
                CallerIdentity(
                    displayName = if (phoneNumber.isBlank()) "Unknown Caller" else phoneNumber,
                    phoneNumber = phoneNumber,
                    isKnownContact = false,
                    isSpamSuspected = isSpamNumber(phoneNumber),
                    spamReason = if (isSpamNumber(phoneNumber)) "Telemarketing / Spam pattern" else ""
                )
            }
            currentCallerIdentity = identity

            val spamFlag = identity.isSpamSuspected || !identity.isKnownContact
            val spamAlertText = if (securePrefs.isSmartCallSpamAlertEnabled() && spamFlag) {
                if (identity.isSpamSuspected) {
                    "Savdhaan! Yeh spam ya telemarketing call lag raha hai (${identity.displayName} - ${identity.spamReason})."
                } else {
                    "Unknown number se call aa rahi hai (${identity.displayName})."
                }
            } else {
                null
            }

            if (spamAlertText != null) {
                _spamAlerts.tryEmit(spamAlertText)
                if (!smartCallOn) {
                    voiceDelegate.speakAlertHindi(spamAlertText)
                }
            }

            if (!smartCallOn) {
                _state.update {
                    it.copy(
                        phase = SmartCallPhase.IDLE,
                        callerName = identity.displayName,
                        callerNumber = identity.phoneNumber,
                        isSpamOrUnknown = spamFlag,
                        spamAlertMessage = spamAlertText,
                        countdownSecondsLeft = 0,
                        statusHindi = "Smart Call OFF hai (${identity.displayName} calling)."
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    phase = SmartCallPhase.RINGING_COUNTDOWN,
                    callerName = identity.displayName,
                    callerNumber = identity.phoneNumber,
                    isSpamOrUnknown = spamFlag,
                    spamAlertMessage = spamAlertText,
                    countdownSecondsLeft = delaySeconds,
                    totalDelaySeconds = delaySeconds,
                    capturedTranscript = "",
                    statusHindi = "${identity.displayName} ki call aa rahi hai — ${delaySeconds}s mein Goku auto-answer karega."
                )
            }

            try {
                for (remaining in delaySeconds downTo 1) {
                    _state.update {
                        it.copy(
                            countdownSecondsLeft = remaining,
                            statusHindi = "${identity.displayName} ringing… ${remaining}s mein auto-answer."
                        )
                    }
                    delay(tickIntervalMs)
                }
                // Timer hit 0 while still unanswered -> execute auto-answer + speakerphone + Hindi talk
                triggerAutoAnswerFlow(identity, isSimulation)
            } catch (ce: CancellationException) {
                throw ce
            }
        }
    }

    private fun handleOffhook() {
        // If our own auto-answer already transitioned into AUTO_ANSWERING / SPEAKING / LISTENING, ignore this OFFHOOK
        if (autoAnswerSessionActive) return

        // User answered the call manually before the 30s timer expired -> cancel countdown immediately!
        if (countdownJob?.isActive == true || _state.value.phase == SmartCallPhase.RINGING_COUNTDOWN) {
            countdownJob?.cancel()
            countdownJob = null
            _state.update {
                it.copy(
                    phase = SmartCallPhase.IDLE,
                    countdownSecondsLeft = 0,
                    statusHindi = "Aapne call utha liya — Smart Call timer cancel ho gaya."
                )
            }
        }
    }

    private fun handleCallEnded() {
        if (countdownJob?.isActive == true && !autoAnswerSessionActive) {
            countdownJob?.cancel()
            countdownJob = null
            _state.update {
                it.copy(
                    phase = SmartCallPhase.IDLE,
                    countdownSecondsLeft = 0,
                    statusHindi = "Call end / decline ho gayi — 30s timer cancel."
                )
            }
            return
        }

        if (autoAnswerSessionActive) {
            finalizeAutoAnsweredSession(callerSpokeNow = false)
        }
    }

    private fun triggerAutoAnswerFlow(identity: CallerIdentity, isSimulation: Boolean) {
        autoAnswerSessionActive = true
        sessionAnsweredAtMs = System.currentTimeMillis()

        _state.update {
            it.copy(
                phase = SmartCallPhase.AUTO_ANSWERING,
                countdownSecondsLeft = 0,
                statusHindi = "30s poora hua — Goku call pick karke speakerphone on kar raha hai…"
            )
        }

        val answerMode = answerDelegate.answerCall(isTestSimulation = isSimulation)
        if (answerMode == null) {
            autoAnswerSessionActive = false
            _state.update {
                it.copy(
                    phase = SmartCallPhase.IDLE,
                    statusHindi = "Auto-answer fail hua — ANSWER_PHONE_CALLS permission ya Default Dialer role grant karein."
                )
            }
            return
        }

        val speakerEnabled = audioRouter.enableSpeakerphone()
        val ownerName = securePrefs.getSmartCallOwnerName()
        val greetingHindi =
            "Namaste, main Goku bol raha hoon, $ownerName abhi phone nahi utha pa rahe — aap apna message bata dein."

        _state.update {
            it.copy(
                phase = SmartCallPhase.SPEAKING_GREETING,
                isSpeakerphoneActive = speakerEnabled,
                activeAnswerMode = answerMode,
                statusHindi = "Speakerphone ON (${answerMode.label}) — Hindi greeting bol raha hoon…"
            )
        }

        voiceDelegate.speakGreetingThenListen(greetingHindi) {
            if (!autoAnswerSessionActive) return@speakGreetingThenListen
            _state.update {
                it.copy(
                    phase = SmartCallPhase.LISTENING_CALLER,
                    statusHindi = "Caller ka message sun raha hoon (Speakerphone ON)…"
                )
            }
            // Start bounded listening window so session always concludes & saves cleanly even if caller is silent
            listenTimeoutJob?.cancel()
            listenTimeoutJob = scope.launch {
                delay(callerListenTimeoutMs)
                if (autoAnswerSessionActive) {
                    finalizeAutoAnsweredSession(callerSpokeNow = false)
                }
            }
        }
    }

    private fun finalizeAutoAnsweredSession(callerSpokeNow: Boolean) {
        if (!autoAnswerSessionActive) return
        autoAnswerSessionActive = false
        val wasSimulation = isCurrentSessionSimulation
        isCurrentSessionSimulation = false

        listenTimeoutJob?.cancel()
        listenTimeoutJob = null
        voiceDelegate.stopVoice()
        audioRouter.restoreNormalAudio()

        val identity = currentCallerIdentity ?: CallerIdentity(
            displayName = _state.value.callerName.ifBlank { "Unknown Caller" },
            phoneNumber = _state.value.callerNumber,
            isKnownContact = false,
            isSpamSuspected = false
        )
        val transcript = transcriptParts.joinToString(" ").trim()
        val durationSec = ((System.currentTimeMillis() - sessionAnsweredAtMs).coerceAtLeast(1_000L) / 1000L).toInt()
        val ownerName = securePrefs.getSmartCallOwnerName()

        val summaryHindi = if (transcript.isNotBlank()) {
            "${identity.displayName} ka call aaya tha (Goku ne auto-answer kiya). Unhone kaha: \"$transcript\"."
        } else {
            "${identity.displayName} ka call aaya tha, Goku ne $ownerName ki taraf se greeting diya par caller ne koi voice message nahi chhoda."
        }

        val entity = CallSummaryEntity(
            callerName = identity.displayName,
            callerNumber = identity.phoneNumber.ifBlank { "Private / Hidden" },
            transcript = transcript.ifBlank { "(No voice message recorded)" },
            summaryHindi = summaryHindi,
            isSpamOrUnknown = identity.isSpamSuspected || !identity.isKnownContact,
            autoAnswered = true,
            answerMode = (_state.value.activeAnswerMode ?: SmartCallAnswerMode.TELECOM_FALLBACK).name,
            durationSeconds = durationSec
        )

        if (callerSpokeNow || wasSimulation) {
            answerDelegate.endCallIfPossible(isTestSimulation = wasSimulation)
        }

        _state.update {
            it.copy(
                phase = SmartCallPhase.COMPLETED,
                isSpeakerphoneActive = false,
                countdownSecondsLeft = 0,
                capturedTranscript = entity.transcript,
                statusHindi = summaryHindi
            )
        }

        scope.launch(ioDispatcher) {
            val savedId = runCatching { callSummaryDao.insertCallSummary(entity) }.getOrDefault(0L)
            val persisted = entity.copy(id = savedId)
            runCatching {
                memoryManager.saveOrUpsertMemory(
                    category = MemoryCategory.FACT,
                    aliasKey = "Last Call: ${identity.displayName}",
                    content = "${identity.phoneNumber} — ${entity.transcript}"
                )
            }
            runCatching { postCallSummaryNotification(persisted) }
            _completions.tryEmit(
                SmartCallCompletionEvent(
                    summaryEntity = persisted,
                    spokenNoticeHindi = summaryHindi
                )
            )
        }
    }

    private fun postCallSummaryNotification(summary: CallSummaryEntity) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CALL_CHANNEL_ID,
                "GokuAgent Smart Call Summaries",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Hindi summaries of calls auto-answered by GokuAgent."
            }
            nm.createNotificationChannel(channel)

            val notif = NotificationCompat.Builder(appContext, CALL_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Smart Call: ${summary.callerName}")
                .setContentText(summary.summaryHindi)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.summaryHindi))
                .setAutoCancel(true)
                .build()
            nm.notify(summary.timestamp.toInt(), notif)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CALL_CHANNEL_ID = "goku_smart_call_channel"

        private val SPAM_PREFIXES = listOf("140", "+91140", "1600", "+911600", "1800")

        fun isSpamNumber(rawNumber: String): Boolean {
            val digits = rawNumber.filter { it.isDigit() || it == '+' }
            if (digits.isBlank()) return false
            if (SPAM_PREFIXES.any { digits.startsWith(it) }) return true
            val pureDigits = digits.filter { it.isDigit() }
            if (pureDigits.length in 3..6) return true
            // Repeated single-digit spoof patterns like 9999999999
            if (pureDigits.length >= 8 && pureDigits.toSet().size <= 2) return true
            return false
        }

        fun isDefaultDialer(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                    roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
                } else {
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    telecom?.defaultDialerPackage == context.packageName
                }
            } catch (_: Exception) {
                false
            }
        }

        fun createRequestDialerRoleIntent(context: Context): Intent? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                        roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    } else {
                        null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                        putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

class AndroidCallAnswerDelegate(
    private val appContext: Context
) : CallAnswerDelegate {

    override fun answerCall(isTestSimulation: Boolean): SmartCallAnswerMode? {
        if (isTestSimulation) return SmartCallAnswerMode.SIMULATED_TEST

        // Mode 1: Default Dialer InCallService (highest reliability + direct audio routing)
        if (GokuInCallService.isAvailable() && GokuInCallService.answerRingingCall()) {
            return SmartCallAnswerMode.INCALL_SERVICE
        }

        // Mode 2: Standard TelecomManager.acceptRingingCall() fallback (stock dialer)
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAnswerPerm) return null

        val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        return try {
            @Suppress("DEPRECATION", "MissingPermission")
            telecom.acceptRingingCall()
            SmartCallAnswerMode.TELECOM_FALLBACK
        } catch (_: Exception) {
            null
        }
    }

    override fun endCallIfPossible(isTestSimulation: Boolean) {
        if (isTestSimulation) return
        if (GokuInCallService.isAvailable()) {
            GokuInCallService.disconnectActiveCall()
            return
        }
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAnswerPerm) return
        val telecom = appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
        try {
            @Suppress("DEPRECATION", "MissingPermission")
            telecom.endCall()
        } catch (_: Exception) {
        }
    }
}

class AndroidCallAudioRouter(
    private val appContext: Context
) : CallAudioRouter {

    private var previousAudioMode: Int = AudioManager.MODE_NORMAL

    override fun enableSpeakerphone(): Boolean {
        val inCallRouted = GokuInCallService.routeAudioToSpeaker()
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return inCallRouted

        return try {
            previousAudioMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speakerDevice = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
            true
        } catch (_: Exception) {
            inCallRouted
        }
    }

    override fun restoreNormalAudio() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = previousAudioMode
        } catch (_: Exception) {
        }
    }
}

class AndroidCallerLookupDelegate(
    private val appContext: Context,
    private val memoryManager: MemoryManager
) : CallerLookupDelegate {

    override suspend fun resolveCaller(phoneNumber: String): CallerIdentity = withContext(Dispatchers.IO) {
        val cleaned = phoneNumber.trim()
        if (cleaned.isEmpty()) {
            return@withContext CallerIdentity(
                displayName = "Unknown / Private Number",
                phoneNumber = "",
                isKnownContact = false,
                isSpamSuspected = false
            )
        }

        val spamSuspected = SmartCallManager.isSpamNumber(cleaned)

        // 1. Check Android Contacts if READ_CONTACTS is granted
        val hasContactsPerm = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasContactsPerm) {
            val contactName = lookupContactName(cleaned)
            if (!contactName.isNullOrBlank()) {
                return@withContext CallerIdentity(
                    displayName = contactName,
                    phoneNumber = cleaned,
                    isKnownContact = true,
                    isSpamSuspected = false
                )
            }
        }

        // 2. Check Personal Memory Vault for saved numbers/aliases (e.g., Papa, Ammi, Office)
        val last10 = cleaned.filter(Char::isDigit).takeLast(10)
        if (last10.length >= 7) {
            val memories = memoryManager.retrieveRelevantMemories(last10, limit = 5)
            val memHit = memories.firstOrNull { it.content.filter(Char::isDigit).contains(last10) }
            if (memHit != null) {
                return@withContext CallerIdentity(
                    displayName = memHit.aliasKey,
                    phoneNumber = cleaned,
                    isKnownContact = true,
                    isSpamSuspected = false
                )
            }
        }

        CallerIdentity(
            displayName = cleaned,
            phoneNumber = cleaned,
            isKnownContact = false,
            isSpamSuspected = spamSuspected,
            spamReason = if (spamSuspected) "Telemarketing / Spam prefix" else ""
        )
    }

    private fun lookupContactName(phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            appContext.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}
