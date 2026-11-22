package com.arif.goku.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arif.goku.accessibility.GokuAccessibilityService
import com.arif.goku.action.ActionExecutor
import com.arif.goku.action.GokuAction
import com.arif.goku.action.GokuActionType
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.adb.LocalAdbStatus
import com.arif.goku.backup.BrainBackupManager
import com.arif.goku.brain.ActiveBrainTier
import com.arif.goku.brain.BrainConnectionStatus
import com.arif.goku.brain.BrainRouteResult
import com.arif.goku.brain.BrainRouter
import com.arif.goku.brain.ContextBrain
import com.arif.goku.brain.GeminiChatClient
import com.arif.goku.brain.OfflineLlmEngine
import com.arif.goku.brain.OfflineLlmState
import com.arif.goku.brain.ZeroCopyBridge
import com.arif.goku.calls.CallVoiceDelegate
import com.arif.goku.calls.SmartCallBridge
import com.arif.goku.calls.SmartCallManager
import com.arif.goku.calls.SmartCallUiState
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.CallSummaryEntity
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.HabitActionType
import com.arif.goku.data.db.MemoryCategory
import com.arif.goku.data.db.MemoryEntity
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.dev.GitHubBuildReport
import com.arif.goku.dev.GokuDevManager
import com.arif.goku.habits.HabitPredictor
import com.arif.goku.habits.PredictedHabitSuggestion
import com.arif.goku.notifications.CapturedNotification
import com.arif.goku.notifications.GokuNotificationListener
import com.arif.goku.overlay.GokuOverlayService
import com.arif.goku.overlay.OverlayBridge
import com.arif.goku.overlay.OverlayCommand
import com.arif.goku.overlay.OverlayOrbState
import com.arif.goku.pilot.PilotRunOutcome
import com.arif.goku.pilot.PilotState
import com.arif.goku.pilot.UniversalAppPilot
import com.arif.goku.safety.BlockedMistakeRecord
import com.arif.goku.safety.PendingSafetyAction
import com.arif.goku.safety.SafetyGuard
import com.arif.goku.safety.SafetyState
import com.arif.goku.safety.ScreenCoPilot
import com.arif.goku.safety.ScreenMistakeBlocker
import com.arif.goku.safety.VoiceConfirmationDecision
import com.arif.goku.skills.SkillEngine
import com.arif.goku.skills.SkillEngineUiState
import com.arif.goku.skills.TeachByExampleRecorder
import com.arif.goku.skills.TeachSessionState
import com.arif.goku.system.FocusShieldManager
import com.arif.goku.system.FocusShieldRuntime
import com.arif.goku.system.FocusShieldState
import com.arif.goku.system.FreezableAppInfo
import com.arif.goku.system.GhostSettingType
import com.arif.goku.system.LogcatDiagnosisReport
import com.arif.goku.system.LogcatDoctor
import com.arif.goku.system.PhoneDoctorReport
import com.arif.goku.system.SmartContextManager
import com.arif.goku.system.SmartContextState
import com.arif.goku.system.SystemPowerTools
import com.arif.goku.voice.GokuVoiceManager
import com.arif.goku.voice.KwsModelManager
import com.arif.goku.voice.KwsModelState
import com.arif.goku.voice.VoiceEngineState
import com.arif.goku.voice.VoiceUiStatus
import com.arif.goku.voice.WakeWordBridge
import com.arif.goku.voice.WakeWordEvent
import com.arif.goku.voice.WakeWordService
import com.arif.goku.worker.BackgroundTaskItem
import com.arif.goku.worker.BackgroundTaskQueue
import com.arif.goku.worker.BackgroundTaskStatus
import com.arif.goku.worker.BackgroundTaskType
import com.arif.goku.worker.DreamModeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ChatSender {
    USER,
    GOKU,
    SYSTEM
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: ChatSender,
    val text: String,
    val timestamp: String = currentTimestamp(),
    val isAlert: Boolean = false
) {
    companion object {
        private fun currentTimestamp(): String {
            return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }
}

enum class SettingsTab {
    BRAIN,
    SKILLS_BACKUP,
    PILOT_QUEUE,
    CALLS_HABITS,
    MEMORY,
    WAKE_FOCUS,
    DEV,
    ADB,
    CONTROL,
    TOOLS
}

data class DashboardUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val isExecutingAction: Boolean = false,
    val brainStatus: BrainConnectionStatus = BrainConnectionStatus.NO_API_KEY,
    val activeBrainTier: ActiveBrainTier = ActiveBrainTier.OFFLINE_REFLEX,
    val hasMicPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasCallPermission: Boolean = false,
    val hasSmsPermission: Boolean = false,
    val hasContactsPermission: Boolean = false,
    val hasPostNotifPermission: Boolean = true,
    val hasReadPhoneStatePermission: Boolean = false,
    val hasReadCallLogPermission: Boolean = false,
    val hasAnswerCallsPermission: Boolean = false,
    val isBatteryUnrestricted: Boolean = false,
    val isSettingsOpen: Boolean = false,
    val activeSettingsTab: SettingsTab = SettingsTab.BRAIN,
    val phoneDoctorReport: PhoneDoctorReport? = null,
    val isScanningDoctor: Boolean = false,
    val freezableApps: List<FreezableAppInfo> = emptyList(),
    val isLoadingApps: Boolean = false,
    val transientBanner: String? = null
)

class GokuDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    val securePrefs = SecurePrefs(appContext)
    val safetyGuard = SafetyGuard()
    val gokuDatabase = GokuDatabase.getInstance(appContext)
    val memoryManager = MemoryManager(
        memoryDao = gokuDatabase.memoryDao(),
        chatHistoryDao = gokuDatabase.chatHistoryDao()
    )
    val offlineLlmEngine = OfflineLlmEngine(appContext)
    val geminiClient = GeminiChatClient(appContext, securePrefs)
    val localAdbManager = LocalAdbManager(appContext, securePrefs)
    val devManager = GokuDevManager(appContext, securePrefs, localAdbManager)
    val brainRouter = BrainRouter(geminiClient, offlineLlmEngine, memoryManager)
    val contextBrain = ContextBrain(appContext)
    val powerTools = SystemPowerTools(appContext, localAdbManager)
    val smartContextManager = SmartContextManager(appContext, securePrefs)
    val focusShieldManager = FocusShieldManager(appContext, securePrefs, localAdbManager)
    val kwsModelManager = KwsModelManager(appContext)
    val actionExecutor = ActionExecutor(
        context = appContext,
        adbManager = localAdbManager,
        contextBrain = contextBrain,
        powerTools = powerTools,
        focusShieldManager = focusShieldManager,
        memoryManager = memoryManager,
        devManager = devManager
    )
    val universalPilot = UniversalAppPilot.create(
        context = appContext,
        adbManager = localAdbManager,
        brainRouter = brainRouter,
        actionExecutor = actionExecutor
    )
    val mistakeBlocker = ScreenMistakeBlocker(appContext, securePrefs)
    val screenCoPilot = ScreenCoPilot(brainRouter, memoryManager)
    val zeroCopyBridge = ZeroCopyBridge(appContext, brainRouter)
    val backgroundQueue = BackgroundTaskQueue.create(
        context = appContext,
        brainRouter = brainRouter,
        devManager = devManager,
        scope = viewModelScope
    )

    val settingsFlow: StateFlow<GokuUserSettings> = securePrefs.settingsFlow
    val safetyState: StateFlow<SafetyState> = safetyGuard.state
    val adbStatus: StateFlow<LocalAdbStatus> = localAdbManager.status
    val accessibilityActive: StateFlow<Boolean> = GokuAccessibilityService.isServiceActive
    val accessibilityLog: StateFlow<String> = GokuAccessibilityService.lastActionStatus
    val notificationListenerActive: StateFlow<Boolean> = GokuNotificationListener.isConnected
    val recentNotifications: StateFlow<List<CapturedNotification>> = GokuNotificationListener.recentNotifications
    val isOverlayRunning: StateFlow<Boolean> = OverlayBridge.isOverlayRunning
    val smartContextState: StateFlow<SmartContextState> = smartContextManager.state
    val focusShieldState: StateFlow<FocusShieldState> = focusShieldManager.state
    val kwsModelState: StateFlow<KwsModelState> = kwsModelManager.state
    val isWakeWordRunning: StateFlow<Boolean> = WakeWordBridge.isServiceRunning
    val wakeWordEngineMode: StateFlow<String> = WakeWordBridge.engineModeLabel
    val memories: StateFlow<List<MemoryEntity>> = memoryManager.allMemories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
    val offlineLlmState: StateFlow<OfflineLlmState> = offlineLlmEngine.state
    val latestGitHubBuild: StateFlow<GitHubBuildReport?> = devManager.latestBuild
    val isCheckingGitHubBuild: StateFlow<Boolean> = devManager.isCheckingBuild
    val pilotState: StateFlow<PilotState> = universalPilot.state
    val blockedMistakeRecords: StateFlow<List<BlockedMistakeRecord>> = mistakeBlocker.recentRecords
    val blockedMistakeCount: StateFlow<Int> = mistakeBlocker.blockedCount
    val backgroundTasks: StateFlow<List<BackgroundTaskItem>> = backgroundQueue.tasks

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val voiceManager: GokuVoiceManager = GokuVoiceManager(
        context = appContext,
        scope = viewModelScope,
        isContinuousModeEnabled = { securePrefs.isContinuousListeningEnabled() },
        isEmergencyStopPhrase = { safetyGuard.isEmergencyStopCommand(it) },
        onEmergencyStopHeard = { phrase ->
            triggerEmergencyStop("Voice kill switch heard: \"$phrase\"")
        },
        onFinalSpeechResult = { spoken ->
            if (smartCallManager.isListeningForCallerMessage()) {
                smartCallManager.onCallerTranscriptCaptured(spoken)
            } else {
                handleUserInput(spoken, fromVoice = true)
            }
        },
        onVoiceError = { errorMsg ->
            showBanner(errorMsg)
        }
    )

    val smartCallManager = SmartCallManager(
        context = appContext,
        securePrefs = securePrefs,
        callSummaryDao = gokuDatabase.callSummaryDao(),
        memoryManager = memoryManager,
        scope = viewModelScope,
        voiceDelegate = object : CallVoiceDelegate {
            override fun speakGreetingThenListen(greetingHindi: String, onReadyToListen: () -> Unit) {
                voiceManager.speakHindiWithCallback(greetingHindi) {
                    onReadyToListen()
                    if (_uiState.value.hasMicPermission) {
                        voiceManager.startListening(isContinuousWindow = false)
                    }
                }
            }

            override fun speakAlertHindi(messageHindi: String) {
                voiceManager.speakHindi(messageHindi, reopenMicAfterSpeaking = false)
            }

            override fun stopVoice() {
                voiceManager.emergencyStop()
            }
        }
    )

    val habitPredictor = HabitPredictor(
        context = appContext,
        habitDao = gokuDatabase.habitDao(),
        securePrefs = securePrefs
    )

    val skillEngine = SkillEngine(
        context = appContext,
        skillDao = gokuDatabase.skillDao(),
        actionExecutor = actionExecutor,
        adbManager = localAdbManager,
        brainRouter = brainRouter,
        universalPilot = universalPilot,
        onSpeakStep = { spoken ->
            voiceManager.speakHindi(spoken, reopenMicAfterSpeaking = false)
        }
    )

    val teachRecorder = TeachByExampleRecorder(
        selfPackageName = appContext.packageName,
        skillDao = gokuDatabase.skillDao(),
        brainRouter = brainRouter
    )

    val logcatDoctor = LogcatDoctor(
        context = appContext,
        adbManager = localAdbManager,
        brainRouter = brainRouter
    )

    val brainBackupManager = BrainBackupManager(
        context = appContext,
        db = gokuDatabase,
        securePrefs = securePrefs
    )

    val voiceStatus: StateFlow<VoiceUiStatus> = voiceManager.status
    val smartCallState: StateFlow<SmartCallUiState> = smartCallManager.state
    val callSummaries: StateFlow<List<CallSummaryEntity>> = smartCallManager.recentSummaries.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
    val activeHabitSuggestion: StateFlow<PredictedHabitSuggestion?> = habitPredictor.activeSuggestion
    val predictedHabits: StateFlow<List<PredictedHabitSuggestion>> = habitPredictor.allPredictedHabits

    val skills: StateFlow<List<SkillEntity>> = skillEngine.allSkills.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )
    val skillEngineState: StateFlow<SkillEngineUiState> = skillEngine.state
    val teachState: StateFlow<TeachSessionState> = teachRecorder.state
    val logcatReport: StateFlow<LogcatDiagnosisReport?> = logcatDoctor.latestReport
    val isDiagnosingLogcat: StateFlow<Boolean> = logcatDoctor.isDiagnosing
    private val _isBackupBusy = MutableStateFlow(false)
    val isBackupBusy: StateFlow<Boolean> = _isBackupBusy.asStateFlow()

    private var activeChatJob: Job? = null
    private var activePilotJob: Job? = null
    private var activeSkillJob: Job? = null
    private var lastOrbTapMs: Long = 0L
    private var lastSpokenHabitId: String? = null
    private var pendingBackupPassphrase: String = ""

    init {
        localAdbManager.startMdnsDiscovery()
        observeOverlayBridge()
        observePhase3Bridges()
        observePhase5Bridges()
        observePhase6Bridges()
        observePhase7Bridges()
        restorePersistedChatHistory()
        refreshSystemStatuses()
        if (securePrefs.isWakeWordEnabled() && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            WakeWordService.start(appContext)
        }
        if (securePrefs.isDreamModeEnabled()) {
            DreamModeScheduler.scheduleOvernight(appContext)
        }
    }

    private fun restorePersistedChatHistory() {
        viewModelScope.launch {
            val saved = memoryManager.loadRecentChatHistory(limit = 60)
            if (saved.isNotEmpty()) {
                val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
                val restored = saved.map { entity ->
                    val sender = when (entity.role.uppercase()) {
                        "USER" -> ChatSender.USER
                        "SYSTEM" -> ChatSender.SYSTEM
                        else -> ChatSender.GOKU
                    }
                    ChatMessage(
                        id = "db_${entity.id}",
                        sender = sender,
                        text = entity.message,
                        timestamp = formatter.format(Date(entity.timestamp)),
                        isAlert = entity.isAlert
                    )
                }
                _uiState.update { state ->
                    if (state.messages.isEmpty()) state.copy(messages = restored) else state
                }
            }
        }
    }

    private fun observePhase3Bridges() {
        viewModelScope.launch {
            WakeWordBridge.events.collect { event ->
                when (event) {
                    WakeWordEvent.GOKU_STOP -> triggerEmergencyStop("Offline Wake Engine heard \"Goku Stop\"")
                    WakeWordEvent.HEY_GOKU_UNLOCKED -> {
                        if (_uiState.value.hasMicPermission &&
                            voiceStatus.value.engineState == VoiceEngineState.IDLE
                        ) {
                            voiceManager.startListening(isContinuousWindow = false)
                        }
                    }
                    WakeWordEvent.HEY_GOKU_LOCKED -> Unit
                }
            }
        }

        viewModelScope.launch {
            FocusShieldRuntime.mutedCount.collect {
                focusShieldManager.refreshMutedCount()
            }
        }
    }

    private fun observePhase5Bridges() {
        // 1. Live Accessibility signals -> Real-time Mistake Blocker
        viewModelScope.launch {
            GokuAccessibilityService.screenSignals.collect { signal ->
                val eval = mistakeBlocker.onAccessibilitySignal(signal)
                if (eval != null) {
                    appendMessage(
                        ChatSender.SYSTEM,
                        "[Mistake Blocker] ${eval.title}: ${eval.reasonHindi}",
                        isAlert = true
                    )
                    voiceManager.speakHindi(eval.reasonHindi, reopenMicAfterSpeaking = false)
                }
            }
        }

        // 2. Overlay Mistake Blocker user decision ("Rok Do" / "Allow Once")
        viewModelScope.launch {
            OverlayBridge.mistakeDecisions.collect { decision ->
                val replyHindi = mistakeBlocker.handleUserDecision(decision.signature, decision.allowOnce)
                appendMessage(
                    ChatSender.SYSTEM,
                    "[Mistake Blocker] $replyHindi",
                    isAlert = !decision.allowOnce
                )
                voiceManager.speakHindi(replyHindi, reopenMicAfterSpeaking = false)
            }
        }

        // 3. Background Task Queue completions -> Chat summary + Hindi voice readout
        viewModelScope.launch {
            backgroundQueue.completions.collect { task ->
                val isFailed = task.status == BackgroundTaskStatus.FAILED
                appendMessage(
                    sender = ChatSender.GOKU,
                    text = "[Background ${task.type.label}] ${task.summaryHindi}",
                    isAlert = isFailed
                )
                showBanner("Background ${task.type.label} complete!")
                if (voiceStatus.value.engineState == VoiceEngineState.IDLE && !pilotState.value.isRunning) {
                    val spoken = if (isFailed) {
                        "Background ${task.type.label} fail ho gaya: ${task.summaryHindi}"
                    } else {
                        "Background kaam poora ho gaya. ${task.summaryHindi.take(240)}"
                    }
                    voiceManager.speakHindi(spoken, reopenMicAfterSpeaking = false)
                }
            }
        }
    }

    private fun observePhase6Bridges() {
        // 1. Live Telephony / InCallService signals -> SmartCallManager 30s Auto-Answer Engine
        viewModelScope.launch {
            SmartCallBridge.signals.collect { signal ->
                smartCallManager.onIncomingSignal(signal)
            }
        }

        // 2. Smart Call spam / unknown caller alert -> Chat + Banner
        viewModelScope.launch {
            smartCallManager.spamAlerts.collect { alertHindi ->
                showBanner(alertHindi)
                appendMessage(ChatSender.SYSTEM, "[Smart Call Alert] $alertHindi", isAlert = true)
            }
        }

        // 3. Smart Call auto-answer completion -> Post-call Hindi voice & card summary for Arif
        viewModelScope.launch {
            smartCallManager.completions.collect { event ->
                appendMessage(
                    sender = ChatSender.GOKU,
                    text = "[Smart Call Summary] ${event.spokenNoticeHindi}",
                    isAlert = event.summaryEntity.isSpamOrUnknown
                )
                showBanner("Call summary saved: ${event.summaryEntity.callerName}")
                voiceManager.speakHindi(event.spokenNoticeHindi, reopenMicAfterSpeaking = false)
            }
        }

        // 4. Passive App Launch logging from GokuAccessibilityService -> HabitPredictor
        viewModelScope.launch {
            GokuAccessibilityService.currentForegroundPackage.collect { pkg ->
                if (pkg.isNotBlank()) {
                    val logged = habitPredictor.logAppLaunch(pkg)
                    if (logged) {
                        habitPredictor.evaluatePredictions()
                    }
                }
            }
        }

        // 5. Floating Orb Habit Suggestion 1-tap Run / Ignore decisions
        viewModelScope.launch {
            OverlayBridge.habitDecisions.collect { decision ->
                val match = habitPredictor.allPredictedHabits.value
                    .firstOrNull { it.id == decision.suggestionId }
                    ?: habitPredictor.activeSuggestion.value?.takeIf { it.id == decision.suggestionId }
                if (decision.runNow && match != null) {
                    runPredictedHabit(match)
                } else {
                    habitPredictor.dismissSuggestion(decision.suggestionId)
                }
            }
        }

        // 6. Gentle periodic Habit Predictor scan every 5 minutes
        viewModelScope.launch {
            while (true) {
                val suggestion = habitPredictor.evaluatePredictions()
                if (suggestion != null && suggestion.id != lastSpokenHabitId &&
                    voiceStatus.value.engineState == VoiceEngineState.IDLE &&
                    !pilotState.value.isRunning
                ) {
                    lastSpokenHabitId = suggestion.id
                    showBanner(suggestion.promptHindi)
                }
                delay(5 * 60 * 1000L)
            }
        }
    }

    private fun observePhase7Bridges() {
        // Feed live Accessibility events into Smart Teach-by-Example while recording
        viewModelScope.launch {
            GokuAccessibilityService.screenSignals.collect { signal ->
                if (teachRecorder.state.value.isRecording) {
                    teachRecorder.onAccessibilitySignal(signal)
                }
            }
        }

        // Sync Teach Mode recording flag to Floating Orb button (TEACH <-> DONE)
        viewModelScope.launch {
            teachRecorder.state.collect { state ->
                OverlayBridge.setTeaching(state.isRecording)
            }
        }
    }

    private fun observeOverlayBridge() {
        // Sync live Orb state (Idle / Listening / Acting / Pilot) to the Floating Overlay Orb
        viewModelScope.launch {
            combine(voiceStatus, _uiState, pilotState) { voice, ui, pilot ->
                when {
                    pilot.isRunning -> OverlayOrbState.PILOT
                    ui.isThinking || ui.isExecutingAction || voice.engineState == VoiceEngineState.SPEAKING ->
                        OverlayOrbState.ACTING
                    voice.engineState == VoiceEngineState.LISTENING ||
                        voice.engineState == VoiceEngineState.CONTINUOUS_LISTENING ->
                        OverlayOrbState.LISTENING
                    else -> OverlayOrbState.IDLE
                }
            }.collect { overlayState ->
                OverlayBridge.updateState(overlayState)
            }
        }

        // Listen for 1-tap STOP, Orb tap, or TEACH events coming from the Floating Overlay Orb
        viewModelScope.launch {
            OverlayBridge.commands.collect { cmd ->
                when (cmd) {
                    OverlayCommand.EMERGENCY_STOP -> triggerEmergencyStop("Floating Orb STOP button tapped")
                    OverlayCommand.TAP_ORB -> {
                        if (_uiState.value.hasMicPermission) {
                            voiceManager.startListening(isContinuousWindow = false)
                        }
                    }
                    OverlayCommand.TOGGLE_TEACH -> {
                        if (teachRecorder.state.value.isRecording) {
                            finishTeachMode()
                        } else {
                            startTeachMode()
                        }
                    }
                }
            }
        }
    }

    fun refreshSystemStatuses() {
        val micGranted = hasPermission(Manifest.permission.RECORD_AUDIO)
        val callGranted = hasPermission(Manifest.permission.CALL_PHONE)
        val smsGranted = hasPermission(Manifest.permission.SEND_SMS)
        val contactsGranted = hasPermission(Manifest.permission.READ_CONTACTS)
        val readPhoneStateGranted = hasPermission(Manifest.permission.READ_PHONE_STATE)
        val readCallLogGranted = hasPermission(Manifest.permission.READ_CALL_LOG)
        val answerCallsGranted = hasPermission(Manifest.permission.ANSWER_PHONE_CALLS)
        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val overlayGranted = Settings.canDrawOverlays(appContext)
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(appContext.packageName) == true

        val lowBatteryHindiAlert = smartContextManager.refresh()
        val smartCtx = smartContextManager.state.value
        voiceManager.setAudioProfile(smartCtx.recommendedTtsVolume, smartCtx.recommendedSpeechRate)
        if (smartCtx.isLowBatteryMode) {
            localAdbManager.stopMdnsDiscovery()
        } else {
            localAdbManager.startMdnsDiscovery()
        }

        val brain = geminiClient.checkBrainStatus()
        offlineLlmEngine.refreshState()
        val activeTier = brainRouter.resolveActiveTier()
        GokuAccessibilityService.refreshActiveStatus(appContext)
        GokuNotificationListener.refreshListenerStatus(appContext)
        kwsModelManager.refreshStatus()
        focusShieldManager.refreshMutedCount()
        smartCallManager.refreshRoleStatus()

        _uiState.update {
            it.copy(
                brainStatus = brain,
                activeBrainTier = activeTier,
                hasMicPermission = micGranted,
                hasOverlayPermission = overlayGranted,
                hasCallPermission = callGranted,
                hasSmsPermission = smsGranted,
                hasContactsPermission = contactsGranted,
                hasPostNotifPermission = postNotifGranted,
                hasReadPhoneStatePermission = readPhoneStateGranted,
                hasReadCallLogPermission = readCallLogGranted,
                hasAnswerCallsPermission = answerCallsGranted,
                isBatteryUnrestricted = batteryUnrestricted
            )
        }

        if (lowBatteryHindiAlert != null) {
            showBanner(lowBatteryHindiAlert)
            appendMessage(ChatSender.SYSTEM, lowBatteryHindiAlert, isAlert = true)
            voiceManager.speakHindi(lowBatteryHindiAlert, reopenMicAfterSpeaking = false)
        }

        viewModelScope.launch {
            habitPredictor.evaluatePredictions()
        }

        if (!smartCtx.isLowBatteryMode) {
            viewModelScope.launch {
                localAdbManager.probeLocalTcpip5555()
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun onAppBackgrounded() {
        val current = voiceStatus.value.engineState
        if (current == VoiceEngineState.LISTENING ||
            current == VoiceEngineState.CONTINUOUS_LISTENING
        ) {
            voiceManager.stopListening()
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = granted) }
        if (granted) {
            showBanner("Microphone permission granted! Orb dabakar Hindi mein baat karein.")
            voiceManager.startListening(isContinuousWindow = false)
        } else {
            showBanner("Microphone permission denied. Aap text input se bhi Goku se baat kar sakte hain.")
        }
    }

    fun onMultiplePermissionsResult(results: Map<String, Boolean>) {
        refreshSystemStatuses()
        val grantedCount = results.values.count { it }
        if (grantedCount > 0) {
            showBanner("$grantedCount permission(s) updated.")
        }
    }

    fun onOrbTapped(requestMicPermission: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastOrbTapMs < 350L) return
        lastOrbTapMs = now

        safetyGuard.clearEmergencyFlag()

        val currentVoice = voiceStatus.value.engineState
        if (_uiState.value.isThinking || _uiState.value.isExecutingAction || currentVoice == VoiceEngineState.SPEAKING) {
            triggerEmergencyStop("Stopped by Orb tap")
            return
        }

        if (currentVoice == VoiceEngineState.LISTENING ||
            currentVoice == VoiceEngineState.CONTINUOUS_LISTENING
        ) {
            voiceManager.stopListening()
            return
        }

        if (!_uiState.value.hasMicPermission) {
            requestMicPermission()
            return
        }

        voiceManager.startListening(isContinuousWindow = false)
    }

    fun triggerEmergencyStop(reason: String = "Goku Stop activated") {
        activeChatJob?.cancel()
        activeChatJob = null
        activePilotJob?.cancel()
        activePilotJob = null
        activeSkillJob?.cancel()
        activeSkillJob = null
        universalPilot.cancel(reason)
        skillEngine.cancel(reason)
        if (teachRecorder.state.value.isRecording) {
            teachRecorder.cancelRecording(reason)
        }
        smartCallManager.cancelEmergencyStop(reason)
        OverlayBridge.dismissMistakeWarning()
        OverlayBridge.dismissHabitSuggestion()
        backgroundQueue.cancelAllActive()
        geminiClient.cancelActiveRequest()
        voiceManager.emergencyStop()
        safetyGuard.triggerEmergencyStop(reason)

        _uiState.update {
            it.copy(
                isThinking = false,
                isExecutingAction = false,
                transientBanner = "Emergency Stop: Sab kuch turant rok diya gaya hai."
            )
        }
        appendMessage(
            ChatSender.SYSTEM,
            "Goku Stop triggered ($reason) — TTS, mic, Pilot, Smart Call, aur running actions turant band kar diye gaye.",
            isAlert = true
        )
    }

    fun handleUserInput(rawText: String, fromVoice: Boolean = false) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        safetyGuard.clearEmergencyFlag()

        // 1. Instant Emergency Kill Switch check
        if (safetyGuard.isEmergencyStopCommand(text)) {
            appendMessage(ChatSender.USER, text)
            triggerEmergencyStop("Kill switch command: \"$text\"")
            return
        }

        // 2. Resolve pending SafetyGuard confirmation via spoken/typed Haan / Nahi
        val pending = safetyState.value.pendingAction
        if (pending != null) {
            when (safetyGuard.parseVoiceConfirmation(text)) {
                VoiceConfirmationDecision.CONFIRMED -> {
                    appendMessage(ChatSender.USER, text)
                    confirmPendingSafetyAction()
                    return
                }
                VoiceConfirmationDecision.CANCELLED -> {
                    appendMessage(ChatSender.USER, text)
                    cancelPendingSafetyAction()
                    return
                }
                VoiceConfirmationDecision.UNKNOWN -> {
                    appendMessage(ChatSender.USER, text)
                    val reminder = "Kripya pehle pending action ke liye Haan ya Naa boliye, ya dialog button dabaiye."
                    appendMessage(ChatSender.GOKU, reminder)
                    voiceManager.speakHindi(reminder, reopenMicAfterSpeaking = true)
                    return
                }
            }
        }

        appendMessage(ChatSender.USER, text)

        // Phase 6 passive habit logging for repeated commands
        viewModelScope.launch {
            habitPredictor.logVoiceCommand(text)
        }

        // 3. Explicit Personal Memory Vault check ("Yaad rakhna..." / "Bhool jaao...") before SafetyGuard
        val lower = text.lowercase()
        val looksLikeMemoryCmd = lower.contains("yaad rakh") || lower.contains("remember") ||
            lower.contains("bhool jaa") || lower.contains("bhool jao") || lower.contains("bhul jao") ||
            lower.contains("forget") || lower.contains("याद रख") || lower.contains("भूल जा") ||
            lower.contains("kya kya yaad hai") || lower.contains("meri memory batao") ||
            lower.contains("list memories")

        if (!looksLikeMemoryCmd) {
            // 4. Direct SafetyGuard Sensitive Action Gate (calls, SMS, WhatsApp, ADB shell — works 100% offline!)
            val sensitive = safetyGuard.inspectForSensitiveAction(text)
            if (sensitive != null) {
                safetyGuard.requestConfirmation(sensitive)
                appendMessage(
                    ChatSender.SYSTEM,
                    "SafetyGuard Gate (${sensitive.type.displayTitle}): ${sensitive.summary}",
                    isAlert = true
                )
                voiceManager.speakHindi(sensitive.hindiVoicePrompt, reopenMicAfterSpeaking = true)
                return
            }
        }

        // 4.5 Phase 5 Direct Triggers (Screen Co-Pilot, Zero-Copy Bridge, Background Queue, Universal App Pilot)
        if (ScreenCoPilot.isExplainScreenCommand(text)) {
            runExplainScreen(text)
            return
        }
        if (ScreenCoPilot.isAutoFillFormCommand(text)) {
            runAutoFillForm()
            return
        }
        if (securePrefs.isZeroCopyBridgeEnabled() && ZeroCopyBridge.isZeroCopyCommand(text)) {
            runZeroCopyBridge(text)
            return
        }
        val bgCommand = BackgroundTaskQueue.parseBackgroundQueueCommand(text)
        if (bgCommand != null) {
            enqueueBackgroundTask(bgCommand.first, bgCommand.second, speakConfirmation = true)
            return
        }
        if (securePrefs.isUniversalPilotEnabled() && UniversalAppPilot.isPilotCommand(text)) {
            startUniversalPilot(UniversalAppPilot.extractPilotGoal(text))
            return
        }

        // 4.6 Phase 7 Voice Triggers: Smart Teach-by-Example ("seekh lo" -> "seekh liya") + Saved Skills
        if (isStartTeachCommand(lower)) {
            val hint = extractTeachHint(text)
            startTeachMode(hint)
            return
        }
        if (teachRecorder.state.value.isRecording && isFinishTeachCommand(lower)) {
            finishTeachMode()
            return
        }
        if (lower.contains("logcat") && (lower.contains("check") || lower.contains("diagnos") || lower.contains("doctor") || lower.contains("error"))) {
            runLogcatSelfDiagnosis("Voice logcat request")
            return
        }

        // Check offline Learned Skills first before hitting online BrainRouter
        val matchedSkill = SkillEngine.matchBestSkill(text, skills.value)
        if (matchedSkill != null) {
            runLearnedSkill(matchedSkill)
            return
        }

        // 5. Phase 4 Hybrid 3-Tier BrainRouter (Tier 1 Offline Reflex -> Tier 2 LiteRT-LM -> Tier 3 Online Gemini + Memory Vault)
        routeThroughHybridBrain(text, speakReply = fromVoice || voiceStatus.value.isHindiTtsReady)
    }

    private fun routeThroughHybridBrain(userText: String, speakReply: Boolean) {
        activeChatJob?.cancel()
        smartContextManager.refresh()
        val smartCtx = smartContextManager.state.value
        voiceManager.setAudioProfile(smartCtx.recommendedTtsVolume, smartCtx.recommendedSpeechRate)

        _uiState.update {
            it.copy(
                isThinking = true,
                brainStatus = geminiClient.checkBrainStatus(),
                activeBrainTier = brainRouter.resolveActiveTier(),
                transientBanner = null
            )
        }

        activeChatJob = viewModelScope.launch {
            val activeTierBefore = brainRouter.resolveActiveTier()
            val snapshot = if (activeTierBefore == ActiveBrainTier.ONLINE_GEMINI) {
                contextBrain.buildSnapshot(localAdbManager)
            } else {
                null
            }

            val routeResult = brainRouter.route(
                userInput = userText,
                liveContext = snapshot,
                smartContextDirective = smartCtx.toGeminiStyleDirective()
            )

            _uiState.update {
                it.copy(
                    isThinking = false,
                    brainStatus = geminiClient.checkBrainStatus(),
                    activeBrainTier = brainRouter.resolveActiveTier()
                )
            }

            when (routeResult) {
                is BrainRouteResult.MemoryHandled -> {
                    appendMessage(ChatSender.GOKU, routeResult.replyHindi)
                    if (speakReply) {
                        voiceManager.speakHindi(routeResult.replyHindi, reopenMicAfterSpeaking = true)
                    }
                }

                is BrainRouteResult.FastReflexAction -> {
                    val action = routeResult.action
                    val confirmation = safetyGuard.buildConfirmationForAction(action, userText)
                    if (confirmation != null) {
                        safetyGuard.requestConfirmation(confirmation)
                        appendMessage(
                            ChatSender.SYSTEM,
                            "SafetyGuard Gate (${confirmation.type.displayTitle}): ${confirmation.summary}",
                            isAlert = true
                        )
                        voiceManager.speakHindi(confirmation.hindiVoicePrompt, reopenMicAfterSpeaking = true)
                    } else {
                        executeStructuredAction(action, speakResult = true)
                    }
                }

                is BrainRouteResult.BrainResponse -> {
                    appendMessage(ChatSender.GOKU, routeResult.text)
                    val action = routeResult.action
                    if (action != null) {
                        handleGeminiStructuredAction(action, userText, routeResult.spokenText, speakReply)
                    } else if (speakReply) {
                        voiceManager.speakHindi(routeResult.spokenText, reopenMicAfterSpeaking = true)
                    }
                }

                is BrainRouteResult.Error -> {
                    if (!routeResult.isCancelled) {
                        appendMessage(ChatSender.GOKU, routeResult.userMessage, isAlert = true)
                        if (speakReply) {
                            voiceManager.speakHindi(routeResult.userMessage, reopenMicAfterSpeaking = false)
                        }
                    }
                }
            }
        }
    }

    private fun handleGeminiStructuredAction(
        action: GokuAction,
        originalCommand: String,
        spokenIntro: String,
        speakReply: Boolean
    ) {
        val confirmation = safetyGuard.buildConfirmationForAction(action, originalCommand)
        if (confirmation != null) {
            safetyGuard.requestConfirmation(confirmation)
            appendMessage(
                ChatSender.SYSTEM,
                "SafetyGuard Gate (${confirmation.type.displayTitle}): ${confirmation.summary}",
                isAlert = true
            )
            voiceManager.speakHindi(confirmation.hindiVoicePrompt, reopenMicAfterSpeaking = true)
        } else {
            if (speakReply) {
                voiceManager.speakHindi(spokenIntro, reopenMicAfterSpeaking = false)
            }
            executeStructuredAction(action, speakResult = false)
        }
    }

    private fun executeStructuredAction(action: GokuAction, speakResult: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingAction = true) }
            val result = actionExecutor.execute(action)
            _uiState.update { it.copy(isExecutingAction = false) }

            appendMessage(
                sender = ChatSender.SYSTEM,
                text = "[${result.engineUsed.badge}] ${result.logDetail}",
                isAlert = !result.success
            )
            if (speakResult || !result.success) {
                appendMessage(ChatSender.GOKU, result.spokenReplyHindi, isAlert = !result.success)
                voiceManager.speakHindi(result.spokenReplyHindi, reopenMicAfterSpeaking = true)
            }
        }
    }

    /**
     * Phase 2 Real Dispatch: Executes the SafetyGuard-confirmed action via Dual-Engine ActionExecutor
     * (Phone Call, SMS, WhatsApp, App Launch, ADB Shell, App Freeze).
     */
    fun confirmPendingSafetyAction() {
        val pending: PendingSafetyAction = safetyGuard.resolvePendingAction() ?: return
        val structured = pending.structuredAction
        if (structured == null) {
            appendMessage(ChatSender.SYSTEM, "Confirmed action had no executable payload.")
            return
        }
        executeStructuredAction(structured, speakResult = true)
    }

    fun cancelPendingSafetyAction() {
        safetyGuard.dismissPendingAction()
        voiceManager.emergencyStop()
        val msg = "Theek hai, action cancel kar diya gaya hai. Koi change nahi hua."
        appendMessage(ChatSender.SYSTEM, msg)
        voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
    }

    // --- Settings, Overlay, Notification Listener & Power Tools Actions ---

    fun openSettings(tab: SettingsTab = SettingsTab.BRAIN) {
        refreshSystemStatuses()
        _uiState.update {
            it.copy(isSettingsOpen = true, activeSettingsTab = tab)
        }
        if ((tab == SettingsTab.TOOLS || tab == SettingsTab.WAKE_FOCUS) &&
            _uiState.value.freezableApps.isEmpty()
        ) {
            loadFreezableApps()
        }
    }

    fun closeSettings() {
        _uiState.update { it.copy(isSettingsOpen = false) }
        refreshSystemStatuses()
    }

    fun selectSettingsTab(tab: SettingsTab) {
        _uiState.update { it.copy(activeSettingsTab = tab) }
        if ((tab == SettingsTab.TOOLS || tab == SettingsTab.WAKE_FOCUS) &&
            _uiState.value.freezableApps.isEmpty()
        ) {
            loadFreezableApps()
        }
    }

    // --- Phase 3 Wake Word, Smart Context & Deep Focus Shield Controls ---

    fun toggleWakeWord24x7(enable: Boolean) {
        if (enable && !_uiState.value.hasMicPermission) {
            showBanner("Pehle Microphone permission grant karein.")
            return
        }
        securePrefs.setWakeWordEnabled(enable)
        if (enable) {
            val started = WakeWordService.start(appContext)
            showBanner(
                if (started) "24x7 \"Hey Goku\" offline wake mic active!"
                else "WakeWordService start nahi hua. Mic permission check karein."
            )
        } else {
            WakeWordService.stop(appContext)
            showBanner("24x7 \"Hey Goku\" wake mic paused.")
        }
    }

    fun downloadKwsModel() {
        viewModelScope.launch {
            val res = kwsModelManager.downloadAndInstallModel()
            res.onSuccess { msg -> showBanner(msg) }
                .onFailure { err -> showBanner(err.message ?: "KWS model download failed.") }
        }
    }

    fun cancelKwsDownload() {
        kwsModelManager.cancelDownload()
        showBanner("KWS model download cancelled.")
    }

    fun deleteKwsModel() {
        kwsModelManager.deleteModel()
        showBanner("Offline KWS model deleted (VAD fallback active).")
    }

    fun toggleShortFastReply(enabled: Boolean) {
        securePrefs.setShortFastReplyMode(enabled)
        smartContextManager.refresh()
        val ctx = smartContextManager.state.value
        voiceManager.setAudioProfile(ctx.recommendedTtsVolume, ctx.recommendedSpeechRate)
        showBanner(
            if (enabled) "Short / Fast Reply Mode ON (1-line driving answers)."
            else "Short / Fast Reply Mode OFF."
        )
    }

    fun toggleFocusShield(enable: Boolean) {
        viewModelScope.launch {
            val res = focusShieldManager.setFocusMode(enable)
            res.onSuccess { msg ->
                showBanner(msg)
                appendMessage(ChatSender.SYSTEM, "[Deep Focus Shield] $msg")
                voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                showBanner(err.message ?: "Focus Shield toggle failed.")
            }
        }
    }

    fun toggleFocusUseAdbSuspend(enabled: Boolean) {
        focusShieldManager.setUseAdbSuspend(enabled)
        showBanner(
            if (enabled) "ADB App Suspension enabled for Kaam Mode."
            else "ADB App Suspension disabled (Notification mute only)."
        )
    }

    fun toggleFocusApp(packageName: String) {
        focusShieldManager.toggleAppInFocusList(packageName)
    }

    fun saveApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        if (trimmed.isEmpty()) {
            showBanner("Valid Gemini API Key paste karein.")
            return
        }
        securePrefs.saveGeminiApiKey(trimmed)
        refreshSystemStatuses()
        showBanner("Gemini API Key securely encrypted & saved!")
    }

    fun clearApiKey() {
        securePrefs.clearGeminiApiKey()
        refreshSystemStatuses()
        showBanner("Stored Gemini API Key cleared.")
    }

    fun selectGeminiModel(model: String) {
        securePrefs.saveGeminiModel(model)
        showBanner("Active model set to $model")
    }

    fun toggleContinuousListening(enabled: Boolean) {
        securePrefs.setContinuousListeningEnabled(enabled)
        showBanner(
            if (enabled) "Continuous Listening (6s window) enabled."
            else "Continuous Listening disabled."
        )
    }

    fun testHindiVoice() {
        voiceManager.speakHindi(
            "Namaste! Main Goku Phase 2 Dual-Engine hoon. Hindi voice aur control ready hai.",
            reopenMicAfterSpeaking = false
        )
    }

    fun probeLocalAdb() {
        viewModelScope.launch {
            val connected = localAdbManager.probeLocalTcpip5555()
            showBanner(
                if (connected) "Local ADB 127.0.0.1:5555 connected & verified!"
                else "127.0.0.1:5555 abhi unreachable hai."
            )
        }
    }

    fun prepareAdbPairing(pairPort: String, pairCode: String, connectPort: String) {
        viewModelScope.launch {
            val result = localAdbManager.prepareAndProbePairing(pairPort, pairCode, connectPort)
            result.onSuccess { msg -> showBanner(msg) }
                .onFailure { err -> showBanner(err.message ?: "ADB pairing failed.") }
        }
    }

    fun openDeveloperOptions() {
        if (!localAdbManager.openDeveloperOptions()) {
            showBanner("Settings open nahi ho paya. Manually Developer Options -> Wireless Debugging kholein.")
        }
    }

    fun openAccessibilitySettings() {
        if (!GokuAccessibilityService.openAccessibilitySettings(appContext)) {
            showBanner("Accessibility Settings open nahi ho paya.")
        }
    }

    fun openNotificationListenerSettings() {
        if (!GokuNotificationListener.openNotificationListenerSettings(appContext)) {
            showBanner("Notification Listener Settings open nahi ho paya.")
        }
    }

    fun readRecentNotificationsAloud() {
        val digest = GokuNotificationListener.buildHindiDigest(limit = 5)
        appendMessage(ChatSender.GOKU, digest)
        voiceManager.speakHindi(digest, reopenMicAfterSpeaking = false)
    }

    fun toggleFloatingOverlay(enable: Boolean) {
        if (enable) {
            if (!Settings.canDrawOverlays(appContext)) {
                showBanner("Pehle Overlay Permission grant karein.")
                return
            }
            GokuOverlayService.start(appContext)
            showBanner("Floating Goku Orb active over all apps!")
        } else {
            GokuOverlayService.stop(appContext)
            showBanner("Floating Goku Orb hidden.")
        }
    }

    fun runAccessibilityTestAction(action: String) {
        val ok = when (action) {
            "HOME" -> GokuAccessibilityService.performHomeAction()
            "BACK" -> GokuAccessibilityService.performBackAction()
            "NOTIFICATIONS" -> GokuAccessibilityService.performNotificationsAction()
            else -> false
        }
        if (!ok) {
            showBanner("Pehle GokuAgent Accessibility Service ko ON karein.")
        }
    }

    // --- Phase 2 System Power Tools (Ghost Mode, Phone Doctor, App Freezer) ---

    fun applyGhostSetting(setting: GhostSettingType, enabled: Boolean? = null, numericValue: Int? = null) {
        viewModelScope.launch {
            val res = powerTools.applyGhostSetting(setting, enabled, numericValue)
            res.onSuccess { msg ->
                showBanner(msg)
                appendMessage(ChatSender.SYSTEM, "[ADB Ghost Mode] $msg")
            }.onFailure { err ->
                showBanner(err.message ?: "Local ADB 127.0.0.1:5555 connect karein.")
            }
        }
    }

    fun scanPhoneDoctor() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningDoctor = true) }
            val res = powerTools.runPhoneDoctorScan()
            _uiState.update { it.copy(isScanningDoctor = false) }
            res.onSuccess { report ->
                _uiState.update { it.copy(phoneDoctorReport = report) }
                showBanner(report.summaryText)
            }.onFailure { err ->
                showBanner(err.message ?: "Phone Doctor ke liye Local ADB connect karein.")
            }
        }
    }

    fun cleanHeavyBackgroundApps() {
        val candidates = _uiState.value.phoneDoctorReport?.topProcesses
            ?.filter { it.isSafeToStop }
            ?.map { it.processName }
            .orEmpty()
        if (candidates.isEmpty()) {
            showBanner("Pehle Phone Doctor Scan chalayein.")
            return
        }
        viewModelScope.launch {
            val res = powerTools.cleanBackgroundProcesses(candidates)
            res.onSuccess { msg ->
                showBanner(msg)
                appendMessage(ChatSender.SYSTEM, "[Phone Doctor] $msg")
                scanPhoneDoctor()
            }.onFailure { err ->
                showBanner(err.message ?: "Clean fail hua.")
            }
        }
    }

    fun loadFreezableApps() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true) }
            val apps = powerTools.listThirdPartyApps()
            _uiState.update { it.copy(freezableApps = apps, isLoadingApps = false) }
        }
    }

    fun toggleAppFreeze(app: FreezableAppInfo) {
        val freeze = !app.isFrozen
        viewModelScope.launch {
            val res = powerTools.setAppFrozen(app.packageName, freeze)
            res.onSuccess { msg ->
                showBanner(msg)
                appendMessage(ChatSender.SYSTEM, "[App Freezer] $msg")
                loadFreezableApps()
            }.onFailure { err ->
                showBanner(err.message ?: "Local ADB 127.0.0.1:5555 required for App Freezer.")
            }
        }
    }

    // --- Phase 4 Hybrid Brain, Personal Memory Vault & Goku Dev Mode Controls ---

    fun toggleGoogleSearchGrounding(enabled: Boolean) {
        securePrefs.setGoogleSearchGroundingEnabled(enabled)
        showBanner(
            if (enabled) "Google Search Grounding ON (real-time web facts)."
            else "Google Search Grounding OFF."
        )
    }

    fun importOfflineLlmModel(uri: Uri) {
        viewModelScope.launch {
            val res = offlineLlmEngine.importModelFromUri(uri)
            refreshSystemStatuses()
            res.onSuccess { msg -> showBanner(msg) }
                .onFailure { err -> showBanner(err.message ?: "Local AI model import failed.") }
        }
    }

    fun downloadOfflineLlmModel(url: String) {
        val trimmed = url.trim()
        securePrefs.saveOfflineLlmModelUrl(trimmed)
        viewModelScope.launch {
            val res = offlineLlmEngine.downloadModelOverWifi(trimmed)
            refreshSystemStatuses()
            res.onSuccess { msg -> showBanner(msg) }
                .onFailure { err -> showBanner(err.message ?: "Local AI model download failed.") }
        }
    }

    fun cancelOfflineLlmDownload() {
        offlineLlmEngine.cancelDownload()
        showBanner("Offline Local AI download cancelled.")
    }

    fun deleteOfflineLlmModel() {
        viewModelScope.launch {
            offlineLlmEngine.deleteModel()
            refreshSystemStatuses()
            showBanner("Offline Local AI model deleted (Offline Reflex remains active).")
        }
    }

    fun addMemory(category: MemoryCategory, aliasKey: String, content: String) {
        viewModelScope.launch {
            memoryManager.saveOrUpsertMemory(category, aliasKey, content)
            showBanner("Memory saved: $aliasKey")
        }
    }

    fun updateMemory(memory: MemoryEntity) {
        viewModelScope.launch {
            memoryManager.updateMemory(memory)
            showBanner("Memory updated: ${memory.aliasKey}")
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryManager.deleteMemoryById(id)
            showBanner("Memory deleted from Vault.")
        }
    }

    fun selectGithubRepo(repo: String) {
        securePrefs.setActiveGithubRepo(repo)
        showBanner("Active GitHub repo: $repo")
    }

    fun addGithubRepo(rawRepo: String) {
        val cleaned = rawRepo.trim()
        if (!GokuDevManager.isValidRepoSlug(cleaned)) {
            showBanner("Valid 'owner/repo' format daalein (e.g., autoacceptapp/goku_agent).")
            return
        }
        val next = (securePrefs.getGithubRepos() + cleaned).distinct()
        securePrefs.saveGithubRepos(next)
        securePrefs.setActiveGithubRepo(cleaned)
        showBanner("GitHub repo added: $cleaned")
    }

    fun removeGithubRepo(repo: String) {
        val remaining = securePrefs.getGithubRepos().filterNot { it == repo }
        if (remaining.isNotEmpty()) {
            securePrefs.saveGithubRepos(remaining)
            showBanner("Removed repo: $repo")
        }
    }

    fun saveGithubToken(rawToken: String) {
        val trimmed = rawToken.trim()
        if (trimmed.isEmpty()) {
            showBanner("Valid GitHub token paste karein.")
            return
        }
        securePrefs.saveGithubToken(trimmed)
        showBanner("GitHub token securely encrypted & saved!")
    }

    fun clearGithubToken() {
        securePrefs.clearGithubToken()
        showBanner("Stored GitHub token cleared.")
    }

    fun checkLatestGithubBuild() {
        viewModelScope.launch {
            val res = devManager.checkLatestGitHubBuild()
            res.onSuccess { report ->
                showBanner(report.hindiSummary)
                appendMessage(ChatSender.GOKU, report.hindiSummary)
                voiceManager.speakHindi(report.hindiSummary, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                showBanner(err.message ?: "GitHub build check failed.")
            }
        }
    }

    fun openLatestBuildDownload() {
        if (!devManager.openLatestBuildDownload()) {
            showBanner("Pehle latest GitHub build check karein.")
        }
    }

    fun requestTermuxCommand(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) return
        val action = GokuAction(GokuActionType.DEV_TERMUX_CMD, text = cmd)
        val confirmation = safetyGuard.buildConfirmationForAction(action, "Termux command: $cmd")
        if (confirmation != null) {
            closeSettings()
            safetyGuard.requestConfirmation(confirmation)
            appendMessage(
                ChatSender.SYSTEM,
                "SafetyGuard Gate (${confirmation.type.displayTitle}): ${confirmation.summary}",
                isAlert = true
            )
            voiceManager.speakHindi(confirmation.hindiVoicePrompt, reopenMicAfterSpeaking = true)
        }
    }

    // --- Phase 5 Universal App Pilot, Screen Co-Pilot, Zero-Copy Bridge & Background Queue ---

    fun toggleUniversalPilot(enabled: Boolean) {
        securePrefs.setUniversalPilotEnabled(enabled)
        if (!enabled) {
            stopUniversalPilot()
        }
        showBanner(
            if (enabled) "Universal App Pilot enabled (12-step autonomous loop)."
            else "Universal App Pilot disabled."
        )
    }

    fun toggleScreenVisionFallback(enabled: Boolean) {
        securePrefs.setScreenVisionFallbackEnabled(enabled)
        showBanner(
            if (enabled) "Screen Vision Fallback (JPEG -> Gemini Vision) ON."
            else "Screen Vision Fallback OFF (UI Tree only)."
        )
    }

    fun toggleMistakeBlocker(enabled: Boolean) {
        securePrefs.setMistakeBlockerEnabled(enabled)
        if (!enabled) {
            OverlayBridge.dismissMistakeWarning()
        }
        showBanner(
            if (enabled) "Real-Time Mistake Blocker Shield ON."
            else "Mistake Blocker Shield OFF."
        )
    }

    fun toggleZeroCopyBridge(enabled: Boolean) {
        securePrefs.setZeroCopyBridgeEnabled(enabled)
        showBanner(
            if (enabled) "Zero-Copy App Bridge ON."
            else "Zero-Copy App Bridge OFF."
        )
    }

    fun startUniversalPilot(rawGoal: String) {
        val goal = rawGoal.trim()
        if (goal.isEmpty()) {
            showBanner("Pilot ke liye clear goal enter karein.")
            return
        }
        if (!securePrefs.isUniversalPilotEnabled()) {
            showBanner("Pehle Settings -> Pilot & Queue mein Universal App Pilot ON karein.")
            return
        }
        closeSettings()
        activePilotJob?.cancel()
        val startReply = "Theek hai, Universal App Pilot shuru kar raha hoon: $goal. Rokne ke liye Goku stop boliye."
        appendMessage(ChatSender.GOKU, startReply)
        voiceManager.speakHindi(startReply, reopenMicAfterSpeaking = false)

        activePilotJob = viewModelScope.launch {
            _uiState.update { it.copy(isExecutingAction = true) }
            val outcome = universalPilot.runGoal(
                goal = goal,
                allowVisionFallback = securePrefs.isScreenVisionFallbackEnabled()
            ) { stepLine ->
                showBanner(stepLine)
            }
            _uiState.update { it.copy(isExecutingAction = false) }

            val (summary, isAlert) = when (outcome) {
                is PilotRunOutcome.Completed -> outcome.summaryHindi to false
                is PilotRunOutcome.StoppedByUser -> outcome.summaryHindi to true
                is PilotRunOutcome.StepLimitReached -> outcome.summaryHindi to true
                is PilotRunOutcome.LoopDetected -> outcome.summaryHindi to true
                is PilotRunOutcome.RiskBlocked -> outcome.summaryHindi to true
                is PilotRunOutcome.Failed -> outcome.summaryHindi to true
            }
            appendMessage(ChatSender.GOKU, "[Universal Pilot] $summary", isAlert = isAlert)
            showBanner(summary)
            if (outcome !is PilotRunOutcome.StoppedByUser) {
                voiceManager.speakHindi(summary, reopenMicAfterSpeaking = false)
            }
        }
    }

    fun stopUniversalPilot() {
        activePilotJob?.cancel()
        activePilotJob = null
        universalPilot.cancel("User stopped Pilot")
        _uiState.update { it.copy(isExecutingAction = false) }
        showBanner("Universal App Pilot stopped.")
    }

    fun runExplainScreen(question: String = "Goku, yeh screen par kya likha hai?") {
        closeSettings()
        viewModelScope.launch {
            _uiState.update { it.copy(isThinking = true) }
            val res = screenCoPilot.explainCurrentScreen(
                userQuestion = question,
                allowVisionFallback = securePrefs.isScreenVisionFallbackEnabled()
            )
            _uiState.update { it.copy(isThinking = false) }
            res.onSuccess { explanation ->
                appendMessage(ChatSender.GOKU, "[Screen Co-Pilot] $explanation")
                voiceManager.speakHindi(explanation, reopenMicAfterSpeaking = true)
            }.onFailure { err ->
                val msg = err.message ?: "Screen explain nahi ho paya."
                appendMessage(ChatSender.GOKU, msg, isAlert = true)
                showBanner(msg)
                voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
            }
        }
    }

    fun runAutoFillForm() {
        closeSettings()
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingAction = true) }
            val res = screenCoPilot.autoFillCurrentForm()
            _uiState.update { it.copy(isExecutingAction = false) }
            res.onSuccess { msg ->
                appendMessage(ChatSender.GOKU, "[Screen Co-Pilot] $msg")
                showBanner(msg)
                voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                val msg = err.message ?: "Form auto-fill fail hua."
                appendMessage(ChatSender.GOKU, msg, isAlert = true)
                showBanner(msg)
                voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
            }
        }
    }

    fun runZeroCopyBridge(command: String = "Screen par jo bill hai uska total karke Notes mein daal do") {
        closeSettings()
        viewModelScope.launch {
            _uiState.update { it.copy(isExecutingAction = true) }
            val res = zeroCopyBridge.executeWorkflow(
                userCommand = command,
                allowVisionFallback = securePrefs.isScreenVisionFallbackEnabled()
            )
            _uiState.update { it.copy(isExecutingAction = false) }
            res.onSuccess { bridgeResult ->
                appendMessage(
                    ChatSender.GOKU,
                    "[Zero-Copy -> ${bridgeResult.targetAppLabel}]\n${bridgeResult.computedPayload}"
                )
                showBanner(bridgeResult.spokenReplyHindi)
                voiceManager.speakHindi(bridgeResult.spokenReplyHindi, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                val msg = err.message ?: "Zero-Copy Bridge fail hua."
                appendMessage(ChatSender.GOKU, msg, isAlert = true)
                showBanner(msg)
                voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
            }
        }
    }

    fun enqueueBackgroundTask(
        type: BackgroundTaskType,
        rawQuery: String,
        speakConfirmation: Boolean = false
    ) {
        val item = backgroundQueue.enqueue(type, rawQuery)
        val confirm = "Theek hai, ${type.label} background queue mein daal diya hai. Poora hote hi main aapko bata dunga."
        showBanner("Queued: ${item.type.label} (${item.query})")
        if (speakConfirmation) {
            appendMessage(ChatSender.GOKU, confirm)
            voiceManager.speakHindi(confirm, reopenMicAfterSpeaking = false)
        }
    }

    fun cancelBackgroundTask(taskId: String) {
        backgroundQueue.cancelTask(taskId)
        showBanner("Background task cancelled.")
    }

    fun clearFinishedBackgroundTasks() {
        backgroundQueue.clearFinished()
        showBanner("Finished background tasks cleared.")
    }

    // -------------------------------------------------------------------------
    // Phase 6: Smart Call (30s Auto-Answer & Talk) + Habit Predictor Actions
    // -------------------------------------------------------------------------

    fun toggleSmartCall(enabled: Boolean) {
        securePrefs.setSmartCallEnabled(enabled)
        if (!enabled) {
            smartCallManager.cancelEmergencyStop("Smart Call turned OFF")
        }
        val msg = if (enabled) {
            "Smart Call ON hai (${securePrefs.getSmartCallDelaySeconds()}s baad auto-answer + speakerphone)."
        } else {
            "Smart Call OFF kar diya gaya."
        }
        showBanner(msg)
    }

    fun setSmartCallDelay(seconds: Int) {
        securePrefs.setSmartCallDelaySeconds(seconds)
        showBanner("Smart Call countdown ${seconds.coerceIn(5, 60)} seconds set ho gaya.")
    }

    fun toggleSmartCallSpamAlert(enabled: Boolean) {
        securePrefs.setSmartCallSpamAlertEnabled(enabled)
        showBanner(if (enabled) "Spam / Unknown caller alert ON." else "Spam / Unknown caller alert OFF.")
    }

    fun saveSmartCallOwnerName(name: String) {
        securePrefs.setSmartCallOwnerName(name)
        val saved = securePrefs.getSmartCallOwnerName()
        showBanner("Smart Call greeting owner name '$saved' save ho gaya.")
    }

    fun startSmartCallTestTimer() {
        closeSettings()
        val delaySec = securePrefs.getSmartCallDelaySeconds()
        smartCallManager.startTestCountdownSimulation(
            testNumber = "+91 9876543210",
            overrideDelaySeconds = delaySec
        )
        showBanner("Smart Call ${delaySec}s test countdown shuru hua. Cancel ya 'Goku stop' bolkar rok sakte hain.")
    }

    fun cancelSmartCall() {
        smartCallManager.cancelEmergencyStop("User cancelled")
        showBanner("Smart Call countdown / session cancel kar diya gaya.")
    }

    fun deleteCallSummary(id: Long) {
        viewModelScope.launch {
            smartCallManager.deleteCallSummary(id)
            showBanner("Call summary delete kar diya gaya.")
        }
    }

    fun toggleHabitPredictor(enabled: Boolean) {
        securePrefs.setHabitPredictorEnabled(enabled)
        if (!enabled) {
            OverlayBridge.dismissHabitSuggestion()
            showBanner("Habit Predictor OFF.")
        } else {
            viewModelScope.launch {
                habitPredictor.evaluatePredictions()
            }
            showBanner("Habit Predictor ON. Goku aapke routine apps/commands observe karega.")
        }
    }

    fun refreshHabitPredictions() {
        viewModelScope.launch {
            val top = habitPredictor.evaluatePredictions()
            showBanner(
                top?.promptHindi ?: "Abhi is time window (±20 min) mein koi 3+ repeated habit nahi mili."
            )
        }
    }

    fun runPredictedHabit(habit: PredictedHabitSuggestion) {
        habitPredictor.markExecuted(habit.id)
        closeSettings()
        when (habit.actionType) {
            HabitActionType.APP_LAUNCH -> {
                val launchIntent = appContext.packageManager.getLaunchIntentForPackage(habit.target)
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        appContext.startActivity(launchIntent)
                        val msg = "${habit.displayLabel} chalu kar diya hai."
                        appendMessage(ChatSender.GOKU, msg)
                        showBanner(msg)
                        voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
                    }.onFailure {
                        executeStructuredAction(
                            GokuAction(GokuActionType.OPEN_APP, target = habit.displayLabel),
                            speakResult = true
                        )
                    }
                } else {
                    executeStructuredAction(
                        GokuAction(GokuActionType.OPEN_APP, target = habit.displayLabel),
                        speakResult = true
                    )
                }
            }

            HabitActionType.VOICE_COMMAND -> {
                handleUserInput(habit.displayLabel, fromVoice = false)
            }
        }
    }

    fun ignorePredictedHabit(habit: PredictedHabitSuggestion) {
        habitPredictor.dismissSuggestion(habit.id)
        showBanner("Theek hai, '${habit.displayLabel}' suggestion 4 ghante ke liye snooze kar diya.")
    }

    fun clearHabits() {
        viewModelScope.launch {
            habitPredictor.clearAllHabits()
            showBanner("Habit history clear kar di gayi.")
        }
    }

    // -------------------------------------------------------------------------
    // Phase 7: Evolving Skills, Smart Teach-by-Example, Logcat Doctor, Dream Mode & Backup
    // -------------------------------------------------------------------------

    fun startTeachMode(hintName: String = "") {
        closeSettings()
        teachRecorder.startRecording(hintName)
        val msg = "Theek hai! Teach Mode ON hai. Aap apna kaam karke dikhayein, poora hone par 'Seekh liya' bolein ya DONE dabayein."
        appendMessage(ChatSender.GOKU, msg)
        showBanner("Teach-by-Example recording ON…")
        voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
    }

    fun finishTeachMode(customName: String = "") {
        viewModelScope.launch {
            val result = teachRecorder.finishAndSaveSkill(customName)
            result.onSuccess { (skill, cleaned) ->
                val msg = "Seekh liya! '${skill.name}' (${skill.mode}, Score ${skill.evolutionScore}/100) save ho gaya — ${cleaned.removedWasteCount} waste steps hata diye gaye."
                appendMessage(ChatSender.GOKU, msg)
                showBanner(msg)
                voiceManager.speakHindi(msg, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                val msg = err.message ?: "Teach Mode mein koi step record nahi ho paya."
                appendMessage(ChatSender.SYSTEM, msg, isAlert = true)
                showBanner(msg)
            }
        }
    }

    fun cancelTeachMode() {
        teachRecorder.cancelRecording("User cancelled")
        showBanner("Teach-by-Example cancel kar diya gaya.")
    }

    fun runLearnedSkill(skill: SkillEntity) {
        closeSettings()
        activeSkillJob?.cancel()
        activeSkillJob = viewModelScope.launch {
            val report = skillEngine.runSkill(
                skill = skill,
                allowSelfHeal = true,
                allowVisionFallback = securePrefs.isScreenVisionFallbackEnabled()
            )
            appendMessage(ChatSender.GOKU, report.summaryHindi, isAlert = !report.success)
            showBanner(report.summaryHindi)
            voiceManager.speakHindi(report.summaryHindi, reopenMicAfterSpeaking = false)

            val actionKey = "skill:${skill.id}"
            if (report.success) {
                logcatDoctor.recordSuccess(actionKey)
            } else if (logcatDoctor.recordFailureAndShouldDiagnose(actionKey)) {
                runLogcatSelfDiagnosis(
                    contextLabel = "Skill '${skill.name}' repeated failure",
                    lastErrorDetail = report.failureReason
                )
            }
        }
    }

    fun synthesizeNewSkill(goal: String) {
        closeSettings()
        activeSkillJob?.cancel()
        activeSkillJob = viewModelScope.launch {
            val result = skillEngine.synthesizeAndSaveSkill(userGoal = goal, executeImmediately = true)
            result.onSuccess { report ->
                appendMessage(ChatSender.GOKU, report.summaryHindi, isAlert = !report.success)
                showBanner(report.summaryHindi)
                voiceManager.speakHindi(report.summaryHindi, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                val msg = err.message ?: "Naya skill synthesize nahi ho paya."
                appendMessage(ChatSender.SYSTEM, msg, isAlert = true)
                showBanner(msg)
            }
        }
    }

    fun saveEditedSkill(skill: SkillEntity) {
        viewModelScope.launch {
            val saved = skillEngine.saveOrUpdateManualSkill(skill)
            showBanner("Skill '${saved.name}' (${saved.mode}) update ho gaya.")
        }
    }

    fun deleteSkill(skillId: Long) {
        viewModelScope.launch {
            skillEngine.deleteSkill(skillId)
            showBanner("Skill delete kar diya gaya.")
        }
    }

    fun stopSkillEngine() {
        activeSkillJob?.cancel()
        activeSkillJob = null
        skillEngine.cancel("User tapped STOP")
        showBanner("Skill execution rok diya gaya.")
    }

    fun runLogcatSelfDiagnosis(
        contextLabel: String = "Manual Logcat Self-Diagnosis",
        lastErrorDetail: String = ""
    ) {
        viewModelScope.launch {
            val report = logcatDoctor.runSelfDiagnosis(contextLabel, lastErrorDetail)
            val msg = "[Logcat Doctor • ${report.category.badge}] Cause: ${report.rootCauseHindi} Fix: ${report.suggestedFixHindi}"
            appendMessage(ChatSender.SYSTEM, msg, isAlert = report.category.name != "HEALTHY")
            showBanner(report.rootCauseHindi)
            voiceManager.speakHindi("${report.rootCauseHindi} ${report.suggestedFixHindi}", reopenMicAfterSpeaking = false)
        }
    }

    fun toggleDreamMode(enabled: Boolean) {
        securePrefs.setDreamModeEnabled(enabled)
        if (enabled) {
            DreamModeScheduler.scheduleOvernight(appContext)
            showBanner("Dream Mode ON — Charging + Wi-Fi par raat ko skills auto-optimize honge.")
        } else {
            DreamModeScheduler.cancelOvernight(appContext)
            showBanner("Dream Mode OFF kar diya gaya.")
        }
    }

    fun runDreamModeNow() {
        DreamModeScheduler.triggerImmediateDream(appContext)
        showBanner("Dream Mode immediate optimization background mein shuru ho gaya!")
    }

    fun prepareBackupPassphrase(passphrase: String) {
        pendingBackupPassphrase = passphrase.trim()
    }

    fun exportEncryptedBackupToUri(uri: Uri) {
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = ""
        viewModelScope.launch {
            _isBackupBusy.value = true
            val res = brainBackupManager.exportToUri(uri, passphrase)
            _isBackupBusy.value = false
            res.onSuccess { report ->
                appendMessage(ChatSender.GOKU, report.detailHindi)
                showBanner(report.detailHindi)
                voiceManager.speakHindi(report.detailHindi, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                val msg = err.message ?: "Encrypted backup export fail hua."
                appendMessage(ChatSender.SYSTEM, msg, isAlert = true)
                showBanner(msg)
            }
        }
    }

    fun restoreEncryptedBackupFromUri(uri: Uri) {
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = ""
        viewModelScope.launch {
            _isBackupBusy.value = true
            val res = brainBackupManager.restoreFromUri(uri, passphrase)
            _isBackupBusy.value = false
            res.onSuccess { report ->
                appendMessage(ChatSender.GOKU, report.detailHindi)
                showBanner(report.detailHindi)
                voiceManager.speakHindi(report.detailHindi, reopenMicAfterSpeaking = false)
            }.onFailure { err ->
                val msg = err.message ?: "Encrypted backup restore fail hua (passphrase check karein)."
                appendMessage(ChatSender.SYSTEM, msg, isAlert = true)
                showBanner(msg)
            }
        }
    }

    fun syncBackupToGitHubGist(passphrase: String) {
        viewModelScope.launch {
            _isBackupBusy.value = true
            val res = brainBackupManager.syncToPrivateGitHubGist(passphrase)
            _isBackupBusy.value = false
            res.onSuccess { report ->
                appendMessage(ChatSender.GOKU, report.detailHindi)
                showBanner(report.detailHindi)
            }.onFailure { err ->
                val msg = err.message ?: "GitHub Gist sync fail hua."
                appendMessage(ChatSender.SYSTEM, msg, isAlert = true)
                showBanner(msg)
            }
        }
    }

    fun restoreBackupFromGitHubGist(passphrase: String) {
        viewModelScope.launch {
            _isBackupBusy.value = true
            val res = brainBackupManager.restoreFromPrivateGitHubGist(passphrase)
            _isBackupBusy.value = false
            res.onSuccess { report ->
                appendMessage(ChatSender.GOKU, report.detailHindi)
                showBanner(report.detailHindi)
            }.onFailure { err ->
                val msg = err.message ?: "GitHub Gist restore fail hua."
                appendMessage(ChatSender.SYSTEM, msg, isAlert = true)
                showBanner(msg)
            }
        }
    }

    private fun isStartTeachCommand(lower: String): Boolean {
        return lower.contains("seekh lo") ||
            lower.contains("sikh lo") ||
            lower.contains("woh seekh") ||
            lower.contains("सीख लो") ||
            lower.contains("teach mode start") ||
            lower.contains("teach by example")
    }

    private fun isFinishTeachCommand(lower: String): Boolean {
        return lower.contains("seekh liya") ||
            lower.contains("sikh liya") ||
            lower.contains("सीख लिया") ||
            lower.contains("teach done") ||
            lower.contains("recording band")
    }

    private fun extractTeachHint(raw: String): String {
        val cleaned = raw.replace(
            Regex("(?i)(?:hey\\s+)?goku[,\\s]*|main jo kar raha hoon|woh seekh lo|seekh lo|teach mode start"),
            ""
        ).trim()
        return if (cleaned.length in 3..42) cleaned else ""
    }

    fun clearConversation() {
        geminiClient.clearHistory()
        viewModelScope.launch {
            memoryManager.clearChatHistory()
        }
        _uiState.update { it.copy(messages = emptyList(), transientBanner = "Chat history cleared.") }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(transientBanner = null) }
    }

    private fun showBanner(message: String) {
        _uiState.update { it.copy(transientBanner = message) }
    }

    private fun appendMessage(sender: ChatSender, text: String, isAlert: Boolean = false) {
        _uiState.update { state ->
            val updated = (state.messages + ChatMessage(sender = sender, text = text, isAlert = isAlert))
                .takeLast(80)
            state.copy(messages = updated)
        }
        viewModelScope.launch {
            memoryManager.recordChatTurn(role = sender.name, message = text, isAlert = isAlert)
        }
    }

    override fun onCleared() {
        activeChatJob?.cancel()
        activePilotJob?.cancel()
        activeSkillJob?.cancel()
        universalPilot.cancel("App closed")
        skillEngine.cancel("App closed")
        backgroundQueue.cancelAllActive()
        smartCallManager.cancelEmergencyStop("App closed")
        geminiClient.cancelActiveRequest()
        localAdbManager.stopMdnsDiscovery()
        offlineLlmEngine.cancelDownload()
        CoroutineScope(Dispatchers.IO).launch {
            offlineLlmEngine.closeEngine()
        }
        voiceManager.destroy()
        super.onCleared()
    }
}
