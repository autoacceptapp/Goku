package com.arif.goku.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.arif.goku.adb.LocalAdbState
import com.arif.goku.adb.LocalAdbStatus
import com.arif.goku.brain.OfflineLlmStage
import com.arif.goku.brain.OfflineLlmState
import com.arif.goku.calls.SmartCallPhase
import com.arif.goku.calls.SmartCallUiState
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.CallSummaryEntity
import com.arif.goku.data.db.MemoryCategory
import com.arif.goku.data.db.MemoryEntity
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.dev.GitHubBuildReport
import com.arif.goku.habits.PredictedHabitSuggestion
import com.arif.goku.pilot.PilotState
import com.arif.goku.safety.BlockedMistakeRecord
import com.arif.goku.skills.SkillEngineUiState
import com.arif.goku.skills.TeachSessionState
import com.arif.goku.system.FocusShieldState
import com.arif.goku.system.FreezableAppInfo
import com.arif.goku.system.GhostSettingType
import com.arif.goku.system.LogcatDiagnosisReport
import com.arif.goku.system.PhoneDoctorReport
import com.arif.goku.system.SmartContextState
import com.arif.goku.voice.KwsModelState
import com.arif.goku.voice.VoiceUiStatus
import com.arif.goku.worker.BackgroundTaskItem
import com.arif.goku.worker.BackgroundTaskStatus
import com.arif.goku.worker.BackgroundTaskType
import com.example.R
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.DeepHudSurface
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    uiState: DashboardUiState,
    userSettings: GokuUserSettings,
    voiceStatus: VoiceUiStatus,
    adbStatus: LocalAdbStatus,
    accessibilityActive: Boolean,
    accessibilityLog: String,
    notificationListenerActive: Boolean,
    recentNotificationCount: Int,
    isOverlayRunning: Boolean,
    isWakeWordRunning: Boolean,
    wakeWordEngineMode: String,
    kwsModelState: KwsModelState,
    smartContextState: SmartContextState,
    focusShieldState: FocusShieldState,
    memories: List<MemoryEntity>,
    offlineLlmState: OfflineLlmState,
    latestGitHubBuild: GitHubBuildReport?,
    isCheckingGitHubBuild: Boolean,
    pilotState: PilotState,
    blockedMistakeCount: Int,
    blockedMistakeRecords: List<BlockedMistakeRecord>,
    backgroundTasks: List<BackgroundTaskItem>,
    smartCallState: SmartCallUiState,
    callSummaries: List<CallSummaryEntity>,
    predictedHabits: List<PredictedHabitSuggestion>,
    skills: List<SkillEntity>,
    skillEngineState: SkillEngineUiState,
    teachState: TeachSessionState,
    logcatReport: LogcatDiagnosisReport?,
    isDiagnosingLogcat: Boolean,
    isBackupBusy: Boolean,
    onSelectTab: (SettingsTab) -> Unit,
    onDismiss: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleSearchGrounding: (Boolean) -> Unit,
    onPickLocalLlmFile: () -> Unit,
    onDownloadLocalLlm: (String) -> Unit,
    onCancelLocalLlmDownload: () -> Unit,
    onDeleteLocalLlm: () -> Unit,
    onToggleContinuous: (Boolean) -> Unit,
    onTestHindiVoice: () -> Unit,
    onStartTeachMode: (String) -> Unit,
    onFinishTeachMode: () -> Unit,
    onCancelTeachMode: () -> Unit,
    onSynthesizeSkill: (String) -> Unit,
    onRunSkill: (SkillEntity) -> Unit,
    onSaveEditedSkill: (SkillEntity) -> Unit,
    onDeleteSkill: (Long) -> Unit,
    onStopSkillEngine: () -> Unit,
    onRunLogcatDoctor: () -> Unit,
    onToggleDreamMode: (Boolean) -> Unit,
    onRunDreamModeNow: () -> Unit,
    onExportEncryptedBackup: (String) -> Unit,
    onRestoreEncryptedBackup: (String) -> Unit,
    onSyncBackupToGist: (String) -> Unit,
    onRestoreBackupFromGist: (String) -> Unit,
    onToggleUniversalPilot: (Boolean) -> Unit,
    onToggleScreenVision: (Boolean) -> Unit,
    onStartPilotGoal: (String) -> Unit,
    onStopPilot: () -> Unit,
    onToggleMistakeBlocker: (Boolean) -> Unit,
    onExplainScreen: () -> Unit,
    onAutoFillForm: () -> Unit,
    onToggleZeroCopyBridge: (Boolean) -> Unit,
    onQuickZeroCopyBillToNotes: () -> Unit,
    onEnqueueBackgroundTask: (BackgroundTaskType, String) -> Unit,
    onCancelBackgroundTask: (String) -> Unit,
    onClearFinishedBackgroundTasks: () -> Unit,
    onToggleSmartCall: (Boolean) -> Unit,
    onSetSmartCallDelay: (Int) -> Unit,
    onToggleSmartCallSpamAlert: (Boolean) -> Unit,
    onSaveSmartCallOwnerName: (String) -> Unit,
    onRequestPhonePermissions: () -> Unit,
    onRequestDefaultDialerRole: () -> Unit,
    onStartSmartCallTestTimer: () -> Unit,
    onCancelSmartCall: () -> Unit,
    onDeleteCallSummary: (Long) -> Unit,
    onToggleHabitPredictor: (Boolean) -> Unit,
    onRefreshHabitPredictions: () -> Unit,
    onRunPredictedHabit: (PredictedHabitSuggestion) -> Unit,
    onIgnorePredictedHabit: (PredictedHabitSuggestion) -> Unit,
    onClearHabits: () -> Unit,
    onAddMemory: (MemoryCategory, String, String) -> Unit,
    onUpdateMemory: (MemoryEntity) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onSelectGithubRepo: (String) -> Unit,
    onAddGithubRepo: (String) -> Unit,
    onRemoveGithubRepo: (String) -> Unit,
    onSaveGithubToken: (String) -> Unit,
    onClearGithubToken: () -> Unit,
    onCheckLatestGithubBuild: () -> Unit,
    onOpenLatestBuildDownload: () -> Unit,
    onRunTermuxCommand: (String) -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onDownloadKwsModel: () -> Unit,
    onCancelKwsDownload: () -> Unit,
    onDeleteKwsModel: () -> Unit,
    onToggleShortFastReply: (Boolean) -> Unit,
    onToggleFocusShield: (Boolean) -> Unit,
    onToggleFocusUseAdb: (Boolean) -> Unit,
    onToggleFocusApp: (String) -> Unit,
    onPrepareAdbPairing: (String, String, String) -> Unit,
    onProbeLocalAdb: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onTestAccessibilityAction: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onReadNotificationsAloud: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestActionPermissions: () -> Unit,
    onApplyGhostSetting: (GhostSettingType, Boolean?, Int?) -> Unit,
    onScanPhoneDoctor: () -> Unit,
    onCleanBackgroundApps: () -> Unit,
    onRefreshApps: () -> Unit,
    onToggleFreeze: (FreezableAppInfo) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DeepHudSurface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.titleLarge,
                color = ArcCyan
            )
            Spacer(modifier = Modifier.height(12.dp))

            ScrollableTabRow(
                selectedTabIndex = uiState.activeSettingsTab.ordinal,
                containerColor = ElevatedHudCard,
                contentColor = ArcCyan,
                edgePadding = 4.dp
            ) {
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.BRAIN,
                    onClick = { onSelectTab(SettingsTab.BRAIN) },
                    text = { Text("Brain", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_brain")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.SKILLS_BACKUP,
                    onClick = { onSelectTab(SettingsTab.SKILLS_BACKUP) },
                    text = {
                        Text(
                            text = if (skills.isNotEmpty()) "Skills & Backup (${skills.size})" else "Skills & Backup",
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    modifier = Modifier.testTag("settings_tab_skills_backup")
                )
                val activeBgCount = backgroundTasks.count {
                    it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING
                }
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.PILOT_QUEUE,
                    onClick = { onSelectTab(SettingsTab.PILOT_QUEUE) },
                    text = {
                        Text(
                            text = if (activeBgCount > 0) "Pilot & Queue ($activeBgCount)" else "Pilot & Queue",
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    modifier = Modifier.testTag("settings_tab_pilot_queue")
                )
                val callsBadge = when {
                    smartCallState.phase == SmartCallPhase.RINGING_COUNTDOWN -> " (${smartCallState.countdownSecondsLeft}s)"
                    callSummaries.isNotEmpty() -> " (${callSummaries.size})"
                    else -> ""
                }
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.CALLS_HABITS,
                    onClick = { onSelectTab(SettingsTab.CALLS_HABITS) },
                    text = {
                        Text(
                            text = "Calls & Habits$callsBadge",
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    modifier = Modifier.testTag("settings_tab_calls_habits")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.MEMORY,
                    onClick = { onSelectTab(SettingsTab.MEMORY) },
                    text = { Text("Memory (${memories.size})", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_memory")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.WAKE_FOCUS,
                    onClick = { onSelectTab(SettingsTab.WAKE_FOCUS) },
                    text = { Text("Wake & Focus", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_wake_focus")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.DEV,
                    onClick = { onSelectTab(SettingsTab.DEV) },
                    text = { Text("Dev Mode", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_dev")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.ADB,
                    onClick = { onSelectTab(SettingsTab.ADB) },
                    text = { Text("Local ADB", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_adb")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.CONTROL,
                    onClick = { onSelectTab(SettingsTab.CONTROL) },
                    text = { Text("Control", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_control")
                )
                Tab(
                    selected = uiState.activeSettingsTab == SettingsTab.TOOLS,
                    onClick = { onSelectTab(SettingsTab.TOOLS) },
                    text = { Text("Power Tools", style = MaterialTheme.typography.labelLarge) },
                    modifier = Modifier.testTag("settings_tab_tools")
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (uiState.activeSettingsTab) {
                    SettingsTab.BRAIN -> BrainSettingsSection(
                        userSettings = userSettings,
                        voiceStatus = voiceStatus,
                        offlineLlmState = offlineLlmState,
                        onSaveApiKey = onSaveApiKey,
                        onClearApiKey = onClearApiKey,
                        onSelectModel = onSelectModel,
                        onToggleSearchGrounding = onToggleSearchGrounding,
                        onPickLocalLlmFile = onPickLocalLlmFile,
                        onDownloadLocalLlm = onDownloadLocalLlm,
                        onCancelLocalLlmDownload = onCancelLocalLlmDownload,
                        onDeleteLocalLlm = onDeleteLocalLlm,
                        onToggleContinuous = onToggleContinuous,
                        onTestHindiVoice = onTestHindiVoice
                    )

                    SettingsTab.SKILLS_BACKUP -> SkillsAndBackupSettingsSection(
                        userSettings = userSettings,
                        skills = skills,
                        skillEngineState = skillEngineState,
                        teachState = teachState,
                        logcatReport = logcatReport,
                        isDiagnosingLogcat = isDiagnosingLogcat,
                        isBackupBusy = isBackupBusy,
                        onStartTeachMode = onStartTeachMode,
                        onFinishTeachMode = onFinishTeachMode,
                        onCancelTeachMode = onCancelTeachMode,
                        onSynthesizeSkill = onSynthesizeSkill,
                        onRunSkill = onRunSkill,
                        onSaveEditedSkill = onSaveEditedSkill,
                        onDeleteSkill = onDeleteSkill,
                        onStopSkillEngine = onStopSkillEngine,
                        onRunLogcatDoctor = onRunLogcatDoctor,
                        onToggleDreamMode = onToggleDreamMode,
                        onRunDreamModeNow = onRunDreamModeNow,
                        onExportEncryptedBackup = onExportEncryptedBackup,
                        onRestoreEncryptedBackup = onRestoreEncryptedBackup,
                        onSyncBackupToGist = onSyncBackupToGist,
                        onRestoreBackupFromGist = onRestoreBackupFromGist
                    )

                    SettingsTab.PILOT_QUEUE -> PilotAndQueueSettingsSection(
                        userSettings = userSettings,
                        pilotState = pilotState,
                        blockedMistakeCount = blockedMistakeCount,
                        blockedMistakeRecords = blockedMistakeRecords,
                        backgroundTasks = backgroundTasks,
                        onToggleUniversalPilot = onToggleUniversalPilot,
                        onToggleScreenVision = onToggleScreenVision,
                        onStartPilotGoal = onStartPilotGoal,
                        onStopPilot = onStopPilot,
                        onToggleMistakeBlocker = onToggleMistakeBlocker,
                        onExplainScreen = onExplainScreen,
                        onAutoFillForm = onAutoFillForm,
                        onToggleZeroCopyBridge = onToggleZeroCopyBridge,
                        onQuickZeroCopyBillToNotes = onQuickZeroCopyBillToNotes,
                        onEnqueueBackgroundTask = onEnqueueBackgroundTask,
                        onCancelBackgroundTask = onCancelBackgroundTask,
                        onClearFinishedBackgroundTasks = onClearFinishedBackgroundTasks
                    )

                    SettingsTab.CALLS_HABITS -> CallsAndHabitsSettingsSection(
                        uiState = uiState,
                        userSettings = userSettings,
                        smartCallState = smartCallState,
                        callSummaries = callSummaries,
                        predictedHabits = predictedHabits,
                        onToggleSmartCall = onToggleSmartCall,
                        onSetSmartCallDelay = onSetSmartCallDelay,
                        onToggleSpamAlert = onToggleSmartCallSpamAlert,
                        onSaveOwnerName = onSaveSmartCallOwnerName,
                        onRequestPhonePermissions = onRequestPhonePermissions,
                        onRequestDefaultDialerRole = onRequestDefaultDialerRole,
                        onStartSmartCallTestTimer = onStartSmartCallTestTimer,
                        onCancelSmartCall = onCancelSmartCall,
                        onDeleteCallSummary = onDeleteCallSummary,
                        onToggleHabitPredictor = onToggleHabitPredictor,
                        onRefreshHabitPredictions = onRefreshHabitPredictions,
                        onRunPredictedHabit = onRunPredictedHabit,
                        onIgnorePredictedHabit = onIgnorePredictedHabit,
                        onClearHabits = onClearHabits
                    )

                    SettingsTab.MEMORY -> MemoryVaultSettingsSection(
                        memories = memories,
                        onAddMemory = onAddMemory,
                        onUpdateMemory = onUpdateMemory,
                        onDeleteMemory = onDeleteMemory
                    )

                    SettingsTab.WAKE_FOCUS -> WakeFocusSettingsSection(
                        userSettings = userSettings,
                        hasMicPermission = uiState.hasMicPermission,
                        isBatteryUnrestricted = uiState.isBatteryUnrestricted,
                        isWakeWordRunning = isWakeWordRunning,
                        wakeWordEngineMode = wakeWordEngineMode,
                        kwsModelState = kwsModelState,
                        smartContext = smartContextState,
                        focusShieldState = focusShieldState,
                        adbStatus = adbStatus,
                        installedApps = uiState.freezableApps,
                        isLoadingApps = uiState.isLoadingApps,
                        onRequestMicPermission = onRequestMicPermission,
                        onToggleWakeWord = onToggleWakeWord,
                        onDownloadKwsModel = onDownloadKwsModel,
                        onCancelKwsDownload = onCancelKwsDownload,
                        onDeleteKwsModel = onDeleteKwsModel,
                        onToggleShortFastReply = onToggleShortFastReply,
                        onToggleFocusShield = onToggleFocusShield,
                        onToggleFocusUseAdb = onToggleFocusUseAdb,
                        onToggleFocusApp = onToggleFocusApp,
                        onRefreshApps = onRefreshApps
                    )

                    SettingsTab.DEV -> DevModeSettingsSection(
                        userSettings = userSettings,
                        latestBuild = latestGitHubBuild,
                        isCheckingBuild = isCheckingGitHubBuild,
                        onSelectRepo = onSelectGithubRepo,
                        onAddRepo = onAddGithubRepo,
                        onRemoveRepo = onRemoveGithubRepo,
                        onSaveGithubToken = onSaveGithubToken,
                        onClearGithubToken = onClearGithubToken,
                        onCheckLatestBuild = onCheckLatestGithubBuild,
                        onOpenBuildDownload = onOpenLatestBuildDownload,
                        onRunTermuxCommand = onRunTermuxCommand
                    )

                    SettingsTab.ADB -> LocalAdbSettingsSection(
                        userSettings = userSettings,
                        adbStatus = adbStatus,
                        onPrepareAdbPairing = onPrepareAdbPairing,
                        onProbeLocalAdb = onProbeLocalAdb,
                        onOpenDevOptions = onOpenDevOptions
                    )

                    SettingsTab.CONTROL -> ControlSettingsSection(
                        uiState = uiState,
                        accessibilityActive = accessibilityActive,
                        accessibilityLog = accessibilityLog,
                        notificationListenerActive = notificationListenerActive,
                        recentNotificationCount = recentNotificationCount,
                        isOverlayRunning = isOverlayRunning,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onTestAccessibilityAction = onTestAccessibilityAction,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onReadNotificationsAloud = onReadNotificationsAloud,
                        onToggleOverlay = onToggleOverlay,
                        onRequestMicPermission = onRequestMicPermission,
                        onRequestActionPermissions = onRequestActionPermissions
                    )

                    SettingsTab.TOOLS -> ToolsSettingsSection(
                        adbStatus = adbStatus,
                        phoneDoctorReport = uiState.phoneDoctorReport,
                        isScanningDoctor = uiState.isScanningDoctor,
                        freezableApps = uiState.freezableApps,
                        isLoadingApps = uiState.isLoadingApps,
                        onApplyGhostSetting = onApplyGhostSetting,
                        onScanPhoneDoctor = onScanPhoneDoctor,
                        onCleanBackgroundApps = onCleanBackgroundApps,
                        onRefreshApps = onRefreshApps,
                        onToggleFreeze = onToggleFreeze
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrainSettingsSection(
    userSettings: GokuUserSettings,
    voiceStatus: VoiceUiStatus,
    offlineLlmState: OfflineLlmState,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onSelectModel: (String) -> Unit,
    onToggleSearchGrounding: (Boolean) -> Unit,
    onPickLocalLlmFile: () -> Unit,
    onDownloadLocalLlm: (String) -> Unit,
    onCancelLocalLlmDownload: () -> Unit,
    onDeleteLocalLlm: () -> Unit,
    onToggleContinuous: (Boolean) -> Unit,
    onTestHindiVoice: () -> Unit
) {
    var apiKeyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var modelUrlInput by remember(userSettings.offlineLlmModelUrl) {
        mutableStateOf(userSettings.offlineLlmModelUrl)
    }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Key, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_api_key_label), style = MaterialTheme.typography.titleSmall)
        }

        if (userSettings.hasApiKey) {
            Text(
                text = "Saved (AES-256 Encrypted): ${userSettings.maskedApiKey}",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldOnline
            )
        } else {
            Text(
                text = "No API key stored yet. Free key available at aistudio.google.com.",
                style = MaterialTheme.typography.bodySmall,
                color = GokuAmber
            )
        }

        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            label = { Text(stringResource(R.string.settings_api_key_placeholder)) },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showKey) "Hide API Key" else "Show API Key"
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("gemini_api_key_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    onSaveApiKey(apiKeyInput)
                    apiKeyInput = ""
                },
                enabled = apiKeyInput.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("save_api_key_button")
            ) {
                Text(stringResource(R.string.settings_save_key))
            }
            if (userSettings.hasApiKey) {
                OutlinedButton(
                    onClick = onClearApiKey,
                    modifier = Modifier.testTag("clear_api_key_button")
                ) {
                    Text(stringResource(R.string.settings_clear_key), color = CrimsonEmergency)
                }
            }
        }
    }

    SettingsCard {
        Text("Gemini Model & Grounding", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecurePrefs.SUPPORTED_MODELS.forEach { model ->
                FilterChip(
                    selected = userSettings.geminiModel == model,
                    onClick = { onSelectModel(model) },
                    label = { Text(model, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = ArcCyan
                    )
                )
            }
        }

        HorizontalDivider(color = HudOutline)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Public, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Google Search Grounding", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Live news, weather, cricket scores & real-time web facts via Gemini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = userSettings.googleSearchGroundingEnabled,
                onCheckedChange = onToggleSearchGrounding,
                modifier = Modifier.testTag("google_search_grounding_switch")
            )
        }
    }

    // Phase 4: Offline Local AI (LiteRT-LM / MediaPipe Tasks GenAI)
    SettingsCard {
        val llmPill = when (offlineLlmState.stage) {
            OfflineLlmStage.READY -> "LOCAL AI READY" to EmeraldOnline
            OfflineLlmStage.DOWNLOADING -> "DOWNLOADING ${offlineLlmState.progressPercent}%" to ArcCyan
            OfflineLlmStage.IMPORTING -> "IMPORTING…" to ArcCyan
            OfflineLlmStage.ERROR -> "ERROR" to CrimsonEmergency
            OfflineLlmStage.NOT_INSTALLED -> "REFLEX ONLY" to GokuAmber
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Memory, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Offline Local AI (LiteRT-LM)", style = MaterialTheme.typography.titleSmall)
            }
            StatusPillMini(label = llmPill.first, color = llmPill.second)
        }

        Text(
            text = offlineLlmState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (offlineLlmState.stage == OfflineLlmStage.DOWNLOADING || offlineLlmState.stage == OfflineLlmStage.IMPORTING) {
            LinearProgressIndicator(
                progress = { (offlineLlmState.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = ArcCyan
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onPickLocalLlmFile,
                enabled = offlineLlmState.stage != OfflineLlmStage.DOWNLOADING &&
                    offlineLlmState.stage != OfflineLlmStage.IMPORTING,
                modifier = Modifier
                    .weight(1f)
                    .testTag("offline_llm_import_button")
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Import .task / .litertlm")
            }

            if (offlineLlmState.stage == OfflineLlmStage.READY) {
                OutlinedButton(
                    onClick = onDeleteLocalLlm,
                    modifier = Modifier.testTag("offline_llm_delete_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete offline LLM",
                        tint = CrimsonEmergency,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        OutlinedTextField(
            value = modelUrlInput,
            onValueChange = { modelUrlInput = it },
            label = { Text("Optional Wi-Fi HTTPS Model Link (.task/.litertlm)") },
            placeholder = { Text("https://.../gemma-3-1b-it-int4.task") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("offline_llm_url_input")
        )

        if (offlineLlmState.stage == OfflineLlmStage.DOWNLOADING) {
            OutlinedButton(
                onClick = onCancelLocalLlmDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("offline_llm_cancel_button")
            ) {
                Text("Cancel Download", color = CrimsonEmergency)
            }
        } else {
            OutlinedButton(
                onClick = { onDownloadLocalLlm(modelUrlInput) },
                enabled = modelUrlInput.startsWith("https://", ignoreCase = true),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("offline_llm_download_button")
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Download Model Over Wi-Fi")
            }
        }
    }

    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_continuous_title), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_continuous_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = userSettings.continuousListeningEnabled,
                onCheckedChange = onToggleContinuous,
                modifier = Modifier.testTag("continuous_listening_switch")
            )
        }

        HorizontalDivider(color = HudOutline)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Hindi Voice Engine (hi-IN)", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = voiceStatus.ttsNotice
                        ?: if (voiceStatus.isHindiTtsReady) "Locale(hi, IN) active & ready" else "Initializing TTS…",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (voiceStatus.isHindiTtsReady) EmeraldOnline else GokuAmber
                )
            }
            OutlinedButton(
                onClick = onTestHindiVoice,
                modifier = Modifier.testTag("test_hindi_voice_button")
            ) {
                Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Test")
            }
        }
    }
}

@Composable
private fun LocalAdbSettingsSection(
    userSettings: GokuUserSettings,
    adbStatus: LocalAdbStatus,
    onPrepareAdbPairing: (String, String, String) -> Unit,
    onProbeLocalAdb: () -> Unit,
    onOpenDevOptions: () -> Unit
) {
    val context = LocalContext.current
    var pairPort by remember(userSettings.adbPairPort, adbStatus.discoveredPairPort) {
        mutableStateOf(adbStatus.discoveredPairPort?.toString() ?: userSettings.adbPairPort)
    }
    var pairCode by remember { mutableStateOf("") }
    var connectPort by remember(userSettings.adbConnectPort, adbStatus.discoveredConnectPort) {
        mutableStateOf(adbStatus.discoveredConnectPort?.toString() ?: userSettings.adbConnectPort)
    }

    val badgeColor = when (adbStatus.state) {
        LocalAdbState.CONNECTED -> EmeraldOnline
        LocalAdbState.PAIRING_READY -> ArcCyan
        LocalAdbState.PROBING -> GokuAmber
        LocalAdbState.DISCONNECTED -> CrimsonEmergency
    }
    val badgeText = when (adbStatus.state) {
        LocalAdbState.CONNECTED -> "CONNECTED • 127.0.0.1:5555"
        LocalAdbState.PAIRING_READY -> "PAIRED / PORT OPEN"
        LocalAdbState.PROBING -> "CONNECTING…"
        LocalAdbState.DISCONNECTED -> "DISCONNECTED"
    }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Terminal, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_adb_title), style = MaterialTheme.typography.titleSmall)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPillMini(label = badgeText, color = badgeColor)
            if (adbStatus.permissionsAutoGranted) {
                StatusPillMini(label = "PM GRANTS OK", color = EmeraldOnline)
            }
        }

        // mDNS auto-discovery status
        Surface(
            color = ObsidianVoid,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, HudOutline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Radar, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (adbStatus.isMdnsScanning) "mDNS Auto-Discovery Active" else "mDNS Idle",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan
                    )
                    Text(
                        text = "Pair Port: ${adbStatus.discoveredPairPort ?: "—"} • Connect Port: ${adbStatus.discoveredConnectPort ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            text = adbStatus.statusDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = pairPort,
                onValueChange = { pairPort = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.settings_adb_pair_port)) },
                placeholder = { Text("37123") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag("adb_pair_port_input")
            )
            OutlinedTextField(
                value = pairCode,
                onValueChange = { pairCode = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.settings_adb_pair_code)) },
                placeholder = { Text("482910") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .weight(1f)
                    .testTag("adb_pair_code_input")
            )
        }

        OutlinedTextField(
            value = connectPort,
            onValueChange = { connectPort = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.settings_adb_connect_port)) },
            placeholder = { Text("41235") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adb_connect_port_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onPrepareAdbPairing(pairPort, pairCode, connectPort) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("adb_pair_submit_button")
            ) {
                Text("Pair + TCP/IP 5555")
            }
            OutlinedButton(
                onClick = onProbeLocalAdb,
                modifier = Modifier.testTag("adb_probe_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Probe")
            }
        }

        OutlinedButton(
            onClick = onOpenDevOptions,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adb_open_dev_options_button")
        ) {
            Icon(Icons.Filled.DeveloperMode, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_adb_dev_options))
        }
    }

    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Fallback ADB Commands", style = MaterialTheme.typography.titleSmall)
            IconButton(
                onClick = {
                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clip?.setPrimaryClip(
                        ClipData.newPlainText(
                            "GokuAgent ADB Commands",
                            adbStatus.generatedCommands.joinToString("\n")
                        )
                    )
                },
                modifier = Modifier.testTag("copy_adb_commands_button")
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy ADB commands", tint = ArcCyan)
            }
        }
        Surface(
            color = ObsidianVoid,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, HudOutline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                adbStatus.generatedCommands.forEach { cmd ->
                    Text(
                        text = "$ $cmd",
                        style = MaterialTheme.typography.labelMedium,
                        color = ArcCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlSettingsSection(
    uiState: DashboardUiState,
    accessibilityActive: Boolean,
    accessibilityLog: String,
    notificationListenerActive: Boolean,
    recentNotificationCount: Int,
    isOverlayRunning: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onTestAccessibilityAction: (String) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onReadNotificationsAloud: () -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onRequestMicPermission: () -> Unit,
    onRequestActionPermissions: () -> Unit
) {
    val context = LocalContext.current

    // 1. Floating System Overlay Orb
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Layers, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Floating Goku Orb Overlay", style = MaterialTheme.typography.titleSmall)
            }
            StatusPillMini(
                label = if (isOverlayRunning) "RUNNING" else if (uiState.hasOverlayPermission) "READY" else "NEEDS ACCESS",
                color = if (isOverlayRunning) EmeraldOnline else GokuAmber
            )
        }

        if (!uiState.hasOverlayPermission) {
            OutlinedButton(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("grant_overlay_permission_button")
            ) {
                Text(stringResource(R.string.settings_overlay_open))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Draggable mini orb + 1-tap red STOP over any app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = isOverlayRunning,
                    onCheckedChange = onToggleOverlay,
                    modifier = Modifier.testTag("floating_overlay_switch")
                )
            }
        }
    }

    // 2. Notification Listener Brain
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Notification Listener Brain", style = MaterialTheme.typography.titleSmall)
            }
            StatusPillMini(
                label = if (notificationListenerActive) "ACTIVE ($recentNotificationCount)" else "OFF",
                color = if (notificationListenerActive) EmeraldOnline else GokuAmber
            )
        }
        Text(
            text = "Captures WhatsApp, SMS & Missed Calls, filters spam, and reads them aloud in Hindi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenNotificationSettings,
                modifier = Modifier
                    .weight(1f)
                    .testTag("open_notification_listener_button")
            ) {
                Text("Notification Access")
            }
            OutlinedButton(
                onClick = onReadNotificationsAloud,
                enabled = notificationListenerActive,
                modifier = Modifier.testTag("read_notifications_aloud_button")
            ) {
                Text("Read Aloud")
            }
        }
    }

    // 3. Accessibility Service Engine
    SettingsCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccessibilityNew, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings_accessibility_title), style = MaterialTheme.typography.titleSmall)
            }
            StatusPillMini(
                label = if (accessibilityActive) "ACTIVE" else "INACTIVE",
                color = if (accessibilityActive) EmeraldOnline else GokuAmber
            )
        }

        Text(
            text = accessibilityLog,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_accessibility_settings_button")
        ) {
            Text(stringResource(R.string.settings_accessibility_open))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onTestAccessibilityAction("HOME") },
                enabled = accessibilityActive,
                modifier = Modifier
                    .weight(1f)
                    .testTag("test_action_home")
            ) {
                Icon(Icons.Filled.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Home")
            }
            OutlinedButton(
                onClick = { onTestAccessibilityAction("BACK") },
                enabled = accessibilityActive,
                modifier = Modifier
                    .weight(1f)
                    .testTag("test_action_back")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Back")
            }
            OutlinedButton(
                onClick = { onTestAccessibilityAction("NOTIFICATIONS") },
                enabled = accessibilityActive,
                modifier = Modifier
                    .weight(1f)
                    .testTag("test_action_notifications")
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Notif")
            }
        }
    }

    // 4. Runtime Action Permissions (Mic, Call, SMS, Contacts, Post Notifications)
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = ArcCyan)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Runtime Action Permissions", style = MaterialTheme.typography.titleSmall)
        }

        PermissionRow("Microphone (Voice)", uiState.hasMicPermission)
        PermissionRow("Phone Call (CALL_PHONE)", uiState.hasCallPermission)
        PermissionRow("SMS Dispatch (SEND_SMS)", uiState.hasSmsPermission)
        PermissionRow("Contacts Lookup (READ_CONTACTS)", uiState.hasContactsPermission)
        PermissionRow("Notifications (POST_NOTIFICATIONS)", uiState.hasPostNotifPermission)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!uiState.hasMicPermission) {
                Button(
                    onClick = onRequestMicPermission,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("request_mic_permission_button")
                ) {
                    Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Mic")
                }
            }
            Button(
                onClick = onRequestActionPermissions,
                modifier = Modifier
                    .weight(1f)
                    .testTag("request_action_permissions_button")
            ) {
                Text("Grant Call / SMS / Contacts")
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        StatusPillMini(
            label = if (granted) "GRANTED" else "DENIED",
            color = if (granted) EmeraldOnline else CrimsonEmergency
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        color = ElevatedHudCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, HudOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun StatusPillMini(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
