package com.arif.goku.ui

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.arif.goku.action.ActionExecutor
import com.arif.goku.action.GokuAction
import com.arif.goku.action.GokuActionType
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.brain.ActiveBrainTier
import com.arif.goku.brain.BrainRouteResult
import com.arif.goku.brain.BrainRouter
import com.arif.goku.brain.ContextBrain
import com.arif.goku.brain.GeminiChatClient
import com.arif.goku.brain.OfflineLlmEngine
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.dev.GokuDevManager
import com.arif.goku.safety.SafetyGuard
import com.arif.goku.system.FocusShieldManager
import com.arif.goku.system.SmartContextManager
import com.arif.goku.system.SystemPowerTools
import com.arif.goku.voice.GokuVoiceManager
import com.arif.goku.voice.VoiceEngineState
import com.arif.goku.voice.WakeWordBridge
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAgentTheme
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class LockWakeUiState(
    val isLocked: Boolean = true,
    val isNightQuiet: Boolean = false,
    val isThinking: Boolean = false,
    val userHeardText: String = "",
    val gokuReplyText: String = "Boliye, main sun raha hoon…",
    val statusNote: String = "LOCK SCREEN VOICE READY",
    val requiresUnlock: Boolean = false
)

/**
 * Phase 3 Lock-Screen Instant Activation Activity.
 *
 * Launched by `WakeWordService` when "HEY GOKU" is detected while the screen is off or locked.
 * Displays the glowing Goku Orb over the lock screen (`setShowWhenLocked(true)` +
 * `setTurnScreenOn(true)`), answers questions and executes background/API tasks in Hindi, and
 * politely prompts the user in Hindi to unlock the phone if a task requires launching another app.
 */
class LockScreenWakeActivity : ComponentActivity() {

    private lateinit var securePrefs: SecurePrefs
    private lateinit var safetyGuard: SafetyGuard
    private lateinit var smartContextManager: SmartContextManager
    private lateinit var adbManager: LocalAdbManager
    private lateinit var contextBrain: ContextBrain
    private lateinit var powerTools: SystemPowerTools
    private lateinit var focusShieldManager: FocusShieldManager
    private lateinit var memoryManager: MemoryManager
    private lateinit var offlineLlmEngine: OfflineLlmEngine
    private lateinit var devManager: GokuDevManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var geminiClient: GeminiChatClient
    private lateinit var brainRouter: BrainRouter
    private lateinit var voiceManager: GokuVoiceManager

    private val uiState = MutableStateFlow(LockWakeUiState())
    private var activeTaskJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show cleanly over the lock screen and wake display
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        WakeWordBridge.setCapturePaused(true)

        securePrefs = SecurePrefs(applicationContext)
        safetyGuard = SafetyGuard()
        smartContextManager = SmartContextManager(applicationContext, securePrefs)
        adbManager = LocalAdbManager(applicationContext, securePrefs)
        contextBrain = ContextBrain(applicationContext)
        powerTools = SystemPowerTools(applicationContext, adbManager)
        focusShieldManager = FocusShieldManager(applicationContext, securePrefs, adbManager)
        val db = GokuDatabase.getInstance(applicationContext)
        memoryManager = MemoryManager(db.memoryDao(), db.chatHistoryDao())
        offlineLlmEngine = OfflineLlmEngine(applicationContext)
        devManager = GokuDevManager(applicationContext, securePrefs, adbManager)
        actionExecutor = ActionExecutor(
            context = applicationContext,
            adbManager = adbManager,
            contextBrain = contextBrain,
            powerTools = powerTools,
            focusShieldManager = focusShieldManager,
            memoryManager = memoryManager,
            devManager = devManager
        )
        geminiClient = GeminiChatClient(applicationContext, securePrefs)
        brainRouter = BrainRouter(geminiClient, offlineLlmEngine, memoryManager)

        smartContextManager.refresh()
        val smartCtx = smartContextManager.state.value
        val keyguardLocked = isDeviceLocked()

        uiState.value = LockWakeUiState(
            isLocked = keyguardLocked,
            isNightQuiet = smartCtx.isNightQuietMode,
            statusNote = if (smartCtx.isNightQuietMode) {
                "NIGHT / DND QUIET MODE"
            } else {
                "HEY GOKU • LOCK SCREEN ACTIVE"
            }
        )

        voiceManager = GokuVoiceManager(
            context = applicationContext,
            scope = lifecycleScope,
            isContinuousModeEnabled = { securePrefs.isContinuousListeningEnabled() },
            isEmergencyStopPhrase = { safetyGuard.isEmergencyStopCommand(it) },
            onEmergencyStopHeard = {
                triggerEmergencyStopAndClose()
            },
            onFinalSpeechResult = { spoken ->
                handleLockScreenCommand(spoken)
            },
            onVoiceError = { err ->
                uiState.update { it.copy(gokuReplyText = err) }
            }
        )
        voiceManager.setAudioProfile(
            volume = smartCtx.recommendedTtsVolume,
            speechRate = smartCtx.recommendedSpeechRate
        )

        setContent {
            GokuAgentTheme {
                val state by uiState.collectAsState()
                val voice by voiceManager.status.collectAsState()
                LockScreenWakeContent(
                    state = state,
                    voiceState = voice.engineState,
                    audioLevel = voice.audioLevel,
                    partialTranscript = voice.partialTranscript,
                    continuousSecondsLeft = voice.continuousSecondsLeft,
                    onOrbClick = {
                        if (state.isThinking || voice.engineState == VoiceEngineState.SPEAKING) {
                            stopActiveSpeechAndTask()
                        } else {
                            voiceManager.startListening(isContinuousWindow = false)
                        }
                    },
                    onRequestUnlock = { requestDeviceUnlock() },
                    onEmergencyStop = { triggerEmergencyStopAndClose() },
                    onClose = { finish() }
                )
            }
        }

        // Start listening immediately when woken over lock screen
        lifecycleScope.launch {
            adbManager.probeLocalTcpip5555()
        }
        voiceManager.startListening(isContinuousWindow = false)
    }

    private fun isDeviceLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    private fun requestDeviceUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                uiState.update {
                    it.copy(
                        isLocked = false,
                        requiresUnlock = false,
                        statusNote = "PHONE UNLOCKED • READY"
                    )
                }
            }
        })
    }

    private fun handleLockScreenCommand(spoken: String) {
        if (safetyGuard.isEmergencyStopCommand(spoken)) {
            triggerEmergencyStopAndClose()
            return
        }

        smartContextManager.refresh()
        val smartCtx = smartContextManager.state.value
        voiceManager.setAudioProfile(smartCtx.recommendedTtsVolume, smartCtx.recommendedSpeechRate)

        uiState.update {
            it.copy(
                userHeardText = spoken,
                isThinking = true,
                requiresUnlock = false,
                statusNote = "PROCESSING…"
            )
        }

        activeTaskJob?.cancel()
        activeTaskJob = lifecycleScope.launch {
            val activeTier = brainRouter.resolveActiveTier()
            val snapshot = if (activeTier == ActiveBrainTier.ONLINE_GEMINI) {
                contextBrain.buildSnapshot(adbManager)
            } else {
                null
            }
            val result = brainRouter.route(
                userInput = spoken,
                liveContext = snapshot,
                smartContextDirective = smartCtx.toGeminiStyleDirective()
            )
            uiState.update { it.copy(isThinking = false) }

            when (result) {
                is BrainRouteResult.MemoryHandled -> {
                    uiState.update {
                        it.copy(
                            gokuReplyText = result.replyHindi,
                            statusNote = "MEMORY VAULT"
                        )
                    }
                    voiceManager.speakHindi(result.replyHindi, reopenMicAfterSpeaking = true)
                }

                is BrainRouteResult.FastReflexAction -> {
                    handleLockScreenAction(
                        action = result.action,
                        replyText = result.introReplyHindi.ifBlank { "Executing ${result.action.type.name}…" },
                        spokenIntro = result.introReplyHindi
                    )
                }

                is BrainRouteResult.BrainResponse -> {
                    val action = result.action
                    if (action != null) {
                        handleLockScreenAction(action, result.text, result.spokenText)
                    } else {
                        uiState.update {
                            it.copy(
                                gokuReplyText = result.text,
                                statusNote = "${result.tier.badgeText} • ${smartCtx.activeProfileBadge}"
                            )
                        }
                        voiceManager.speakHindi(result.spokenText, reopenMicAfterSpeaking = true)
                    }
                }

                is BrainRouteResult.Error -> {
                    if (!result.isCancelled) {
                        uiState.update {
                            it.copy(
                                gokuReplyText = result.userMessage,
                                statusNote = brainRouter.resolveActiveTier().badgeText
                            )
                        }
                        voiceManager.speakHindi(result.userMessage, reopenMicAfterSpeaking = false)
                    }
                }
            }
        }
    }

    private fun handleLockScreenAction(action: GokuAction, replyText: String, spokenIntro: String) {
        val lockedNow = isDeviceLocked()
        val needsUnlock = action.type in ACTIONS_REQUIRING_UNLOCK || action.requiresSafetyConfirmation()

        if (lockedNow && needsUnlock) {
            val unlockPrompt = "Yeh action ya app kholne ke liye pehle phone unlock kar dijiye."
            uiState.update {
                it.copy(
                    isLocked = true,
                    requiresUnlock = true,
                    gokuReplyText = unlockPrompt,
                    statusNote = "UNLOCK REQUIRED FOR ${action.type.name}"
                )
            }
            voiceManager.speakHindi(unlockPrompt, reopenMicAfterSpeaking = false)
            requestDeviceUnlock()
            return
        }

        uiState.update { it.copy(gokuReplyText = replyText, statusNote = "EXECUTING ${action.type.name}") }
        if (spokenIntro.isNotBlank()) {
            voiceManager.speakHindi(spokenIntro, reopenMicAfterSpeaking = false)
        }
        runLockSafeAction(action)
    }

    private fun runLockSafeAction(action: GokuAction) {
        activeTaskJob?.cancel()
        activeTaskJob = lifecycleScope.launch {
            uiState.update { it.copy(isThinking = true) }
            val execResult = actionExecutor.execute(action)
            uiState.update {
                it.copy(
                    isThinking = false,
                    gokuReplyText = execResult.spokenReplyHindi,
                    statusNote = execResult.engineUsed.badge
                )
            }
            voiceManager.speakHindi(execResult.spokenReplyHindi, reopenMicAfterSpeaking = true)
        }
    }

    private fun stopActiveSpeechAndTask() {
        activeTaskJob?.cancel()
        geminiClient.cancelActiveRequest()
        voiceManager.emergencyStop()
        uiState.update {
            it.copy(
                isThinking = false,
                statusNote = "STOPPED • TAP ORB TO TALK"
            )
        }
    }

    private fun triggerEmergencyStopAndClose() {
        stopActiveSpeechAndTask()
        finish()
    }

    override fun onDestroy() {
        activeTaskJob?.cancel()
        geminiClient.cancelActiveRequest()
        CoroutineScope(Dispatchers.IO).launch {
            offlineLlmEngine.closeEngine()
        }
        voiceManager.destroy()
        WakeWordBridge.setCapturePaused(false)
        super.onDestroy()
    }

    companion object {
        private val ACTIONS_REQUIRING_UNLOCK = setOf(
            GokuActionType.OPEN_APP,
            GokuActionType.CLICK_TEXT,
            GokuActionType.TYPE_TEXT,
            GokuActionType.SCROLL,
            GokuActionType.TAP,
            GokuActionType.CALL,
            GokuActionType.SEND_SMS,
            GokuActionType.SEND_WHATSAPP,
            GokuActionType.ADB_SHELL,
            GokuActionType.FREEZE_APP,
            GokuActionType.DEV_TERMUX_CMD
        )
    }
}

@Composable
private fun LockScreenWakeContent(
    state: LockWakeUiState,
    voiceState: VoiceEngineState,
    audioLevel: Float,
    partialTranscript: String,
    continuousSecondsLeft: Int,
    onOrbClick: () -> Unit,
    onRequestUnlock: () -> Unit,
    onEmergencyStop: () -> Unit,
    onClose: () -> Unit
) {
    // Dimmed non-glaring brightness at night/DND to avoid blinding flashes
    val visualAlpha = if (state.isNightQuiet) 0.76f else 1.0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
            .alpha(visualAlpha)
    ) {
        // Top header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = ElevatedHudCard,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, if (state.isNightQuiet) GokuAmber else ArcCyan.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when {
                            state.isNightQuiet -> Icons.Filled.NightlightRound
                            state.isLocked -> Icons.Filled.Lock
                            else -> Icons.Filled.LockOpen
                        },
                        contentDescription = null,
                        tint = if (state.isNightQuiet) GokuAmber else ArcCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = state.statusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.isNightQuiet) GokuAmber else ArcCyan
                    )
                }
            }

            IconButton(
                onClick = onClose,
                modifier = Modifier.testTag("lock_wake_close_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close Goku Lock Screen HUD",
                    tint = TextSecondary
                )
            }
        }

        // Center glowing Goku Orb + live transcript + Hindi answer card
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 460.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GokuOrb(
                voiceState = voiceState,
                isThinking = state.isThinking,
                audioLevel = audioLevel,
                continuousSecondsLeft = continuousSecondsLeft,
                emergencyActive = false,
                onClick = onOrbClick,
                orbSize = 184.dp
            )

            if (partialTranscript.isNotBlank()) {
                Text(
                    text = "\"$partialTranscript\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ArcCyan,
                    textAlign = TextAlign.Center
                )
            } else if (state.userHeardText.isNotBlank()) {
                Text(
                    text = "You: \"${state.userHeardText}\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
            }

            Surface(
                color = ElevatedHudCard,
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, if (state.requiresUnlock) GokuAmber else HudOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = state.gokuReplyText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    if (state.requiresUnlock) {
                        OutlinedButton(
                            onClick = onRequestUnlock,
                            border = BorderStroke(1.dp, EmeraldOnline),
                            modifier = Modifier.testTag("lock_wake_unlock_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LockOpen,
                                contentDescription = null,
                                tint = EmeraldOnline,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Unlock Phone", color = EmeraldOnline)
                        }
                    }
                }
            }
        }

        // Bottom Emergency GOKU STOP Kill Switch
        Button(
            onClick = onEmergencyStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = CrimsonEmergency,
                contentColor = androidx.compose.ui.graphics.Color.White
            ),
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .testTag("lock_wake_stop_button")
        ) {
            Icon(
                imageVector = Icons.Filled.StopCircle,
                contentDescription = "Goku Stop",
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("GOKU STOP", style = MaterialTheme.typography.labelLarge)
        }
    }
}
