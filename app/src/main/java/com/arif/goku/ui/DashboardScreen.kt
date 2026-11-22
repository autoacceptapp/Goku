package com.arif.goku.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arif.goku.adb.LocalAdbState
import com.arif.goku.adb.LocalAdbStatus
import com.arif.goku.brain.ActiveBrainTier
import com.arif.goku.safety.PendingSafetyAction
import com.arif.goku.voice.VoiceEngineState
import com.arif.goku.worker.BackgroundTaskStatus
import com.example.R
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.ArcCyanContainer
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.DeepHudSurface
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.GokuAmberContainer
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

@Composable
fun DashboardScreen(
    autoListenTrigger: Int = 0,
    viewModel: GokuDashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val userSettings by viewModel.settingsFlow.collectAsStateWithLifecycle()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    val safetyState by viewModel.safetyState.collectAsStateWithLifecycle()
    val adbStatus by viewModel.adbStatus.collectAsStateWithLifecycle()
    val accessibilityActive by viewModel.accessibilityActive.collectAsStateWithLifecycle()
    val accessibilityLog by viewModel.accessibilityLog.collectAsStateWithLifecycle()
    val notificationListenerActive by viewModel.notificationListenerActive.collectAsStateWithLifecycle()
    val recentNotifications by viewModel.recentNotifications.collectAsStateWithLifecycle()
    val isOverlayRunning by viewModel.isOverlayRunning.collectAsStateWithLifecycle()
    val smartContextState by viewModel.smartContextState.collectAsStateWithLifecycle()
    val focusShieldState by viewModel.focusShieldState.collectAsStateWithLifecycle()
    val kwsModelState by viewModel.kwsModelState.collectAsStateWithLifecycle()
    val isWakeWordRunning by viewModel.isWakeWordRunning.collectAsStateWithLifecycle()
    val wakeWordEngineMode by viewModel.wakeWordEngineMode.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val offlineLlmState by viewModel.offlineLlmState.collectAsStateWithLifecycle()
    val latestGitHubBuild by viewModel.latestGitHubBuild.collectAsStateWithLifecycle()
    val isCheckingGitHubBuild by viewModel.isCheckingGitHubBuild.collectAsStateWithLifecycle()
    val pilotState by viewModel.pilotState.collectAsStateWithLifecycle()
    val blockedMistakeRecords by viewModel.blockedMistakeRecords.collectAsStateWithLifecycle()
    val blockedMistakeCount by viewModel.blockedMistakeCount.collectAsStateWithLifecycle()
    val backgroundTasks by viewModel.backgroundTasks.collectAsStateWithLifecycle()

    var textInput by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMicPermissionResult(granted)
    }

    val multiPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onMultiplePermissionsResult(results)
    }

    val localLlmPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importOfflineLlmModel(uri)
        }
    }

    // Trigger mic if opened from Floating Overlay Orb tap
    LaunchedEffect(autoListenTrigger) {
        if (autoListenTrigger > 0) {
            viewModel.onOrbTapped {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    // Refresh live statuses on resume and release active mic cleanly on background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.refreshSystemStatuses()
                Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = ObsidianVoid,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .drawBehind {
                    drawRect(ObsidianVoid)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(ArcCyan.copy(alpha = 0.12f), Color.Transparent),
                            center = Offset(size.width * 0.5f, size.height * 0.22f),
                            radius = size.maxDimension * 0.58f
                        ),
                        radius = size.maxDimension * 0.58f,
                        center = Offset(size.width * 0.5f, size.height * 0.22f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(GokuAmber.copy(alpha = 0.06f), Color.Transparent),
                            center = Offset(size.width * 0.88f, size.height * 0.78f),
                            radius = size.maxDimension * 0.42f
                        ),
                        radius = size.maxDimension * 0.42f,
                        center = Offset(size.width * 0.88f, size.height * 0.78f)
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                HudHeader(
                    hasMessages = uiState.messages.isNotEmpty(),
                    onClearChat = viewModel::clearConversation,
                    onOpenSettings = { viewModel.openSettings(SettingsTab.BRAIN) }
                )

                TopStatusRow(
                    activeBrainTier = uiState.activeBrainTier,
                    adbStatus = adbStatus,
                    accessibilityActive = accessibilityActive,
                    onBrainClick = { viewModel.openSettings(SettingsTab.BRAIN) },
                    onAdbClick = { viewModel.openSettings(SettingsTab.ADB) },
                    onAccessClick = { viewModel.openSettings(SettingsTab.CONTROL) }
                )

                // Phase 3 Compact Telemetry Strip (24x7 Wake Word • Smart Context • Deep Focus Shield)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val wakeColor = if (isWakeWordRunning) EmeraldOnline else TextSecondary
                    Surface(
                        color = ElevatedHudCard.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, wakeColor.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { viewModel.openSettings(SettingsTab.WAKE_FOCUS) }
                            .testTag("hud_chip_wake_word")
                    ) {
                        Text(
                            text = if (isWakeWordRunning) "HEY GOKU 24x7" else "WAKE OFF",
                            style = MaterialTheme.typography.labelSmall,
                            color = wakeColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    val ctxColor = when {
                        smartContextState.isLowBatteryMode -> CrimsonEmergency
                        smartContextState.isNightQuietMode -> GokuAmber
                        smartContextState.isShortFastReplyMode -> ArcCyan
                        else -> TextSecondary
                    }
                    Surface(
                        color = ElevatedHudCard.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(999.dp),
                        border = BorderStroke(1.dp, ctxColor.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { viewModel.openSettings(SettingsTab.WAKE_FOCUS) }
                            .testTag("hud_chip_smart_context")
                    ) {
                        Text(
                            text = smartContextState.activeProfileBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = ctxColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    if (focusShieldState.isActive) {
                        Surface(
                            color = GokuAmberContainer,
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(1.dp, GokuAmber.copy(alpha = 0.65f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { viewModel.openSettings(SettingsTab.WAKE_FOCUS) }
                                .testTag("hud_chip_focus_shield")
                        ) {
                            Text(
                                text = "KAAM MODE (${focusShieldState.mutedNotificationsCount})",
                                style = MaterialTheme.typography.labelSmall,
                                color = GokuAmber,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = ElevatedHudCard.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(999.dp),
                            border = BorderStroke(
                                1.dp,
                                (if (userSettings.mistakeBlockerEnabled) EmeraldOnline else TextSecondary).copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .clickable { viewModel.openSettings(SettingsTab.PILOT_QUEUE) }
                                .testTag("hud_chip_pilot_shield")
                        ) {
                            val activeQueueCount = backgroundTasks.count {
                                it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING
                            }
                            val label = when {
                                pilotState.isRunning -> "PILOT ${pilotState.currentStep}/${pilotState.maxSteps}"
                                activeQueueCount > 0 -> "QUEUE ($activeQueueCount)"
                                userSettings.mistakeBlockerEnabled -> "SHIELD ON"
                                else -> "PILOT OFF"
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    pilotState.isRunning || activeQueueCount > 0 -> GokuAmber
                                    userSettings.mistakeBlockerEnabled -> EmeraldOnline
                                    else -> TextSecondary
                                },
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Phase 5 Live Universal App Pilot / Background Queue Status Banner
                val activeQueueTasks = backgroundTasks.filter {
                    it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING
                }
                AnimatedVisibility(
                    visible = pilotState.isRunning || activeQueueTasks.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = if (pilotState.isRunning) GokuAmberContainer else ElevatedHudCard,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (pilotState.isRunning) GokuAmber.copy(alpha = 0.7f) else ArcCyan.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("phase5_live_banner")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (pilotState.isRunning) {
                                        "PILOT STEP ${pilotState.currentStep}/${pilotState.maxSteps} • ${pilotState.lastObservationMode.badge}"
                                    } else {
                                        "BG QUEUE (${activeQueueTasks.size} running)"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (pilotState.isRunning) GokuAmber else ArcCyan
                                )
                                Text(
                                    text = if (pilotState.isRunning) {
                                        pilotState.lastStatusHindi
                                    } else {
                                        activeQueueTasks.firstOrNull()?.let {
                                            "${it.type.label}: ${it.summaryHindi} (${it.progressPercent}%)"
                                        }.orEmpty()
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (pilotState.isRunning) {
                                OutlinedButton(
                                    onClick = viewModel::stopUniversalPilot,
                                    border = BorderStroke(1.dp, CrimsonEmergency),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("banner_stop_pilot_button")
                                ) {
                                    Text("STOP", style = MaterialTheme.typography.labelSmall, color = CrimsonEmergency)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { viewModel.openSettings(SettingsTab.PILOT_QUEUE) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("banner_view_queue_button")
                                ) {
                                    Text("VIEW", style = MaterialTheme.typography.labelSmall, color = ArcCyan)
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.transientBanner != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.transientBanner?.let { banner ->
                        Surface(
                            color = ElevatedHudCard,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { viewModel.dismissBanner() }
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall,
                                color = ArcCyan,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    val isWideScreen = maxWidth >= 700.dp

                    if (isWideScreen) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            OrbControlSection(
                                voiceState = voiceStatus.engineState,
                                isThinking = uiState.isThinking || uiState.isExecutingAction,
                                audioLevel = voiceStatus.audioLevel,
                                continuousSecondsLeft = voiceStatus.continuousSecondsLeft,
                                continuousEnabled = userSettings.continuousListeningEnabled,
                                partialTranscript = voiceStatus.partialTranscript,
                                emergencyActive = safetyState.emergencyStopActive,
                                onOrbClick = {
                                    viewModel.onOrbTapped {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onEmergencyStop = { viewModel.triggerEmergencyStop() },
                                modifier = Modifier
                                    .weight(0.44f)
                                    .fillMaxHeight()
                            )

                            Column(
                                modifier = Modifier
                                    .weight(0.56f)
                                    .fillMaxHeight()
                            ) {
                                ChatTranscriptList(
                                    messages = uiState.messages,
                                    modifier = Modifier.weight(1f)
                                )
                                ChatInputBar(
                                    value = textInput,
                                    onValueChange = { textInput = it },
                                    onSend = {
                                        viewModel.handleUserInput(textInput, fromVoice = false)
                                        textInput = ""
                                    }
                                )
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            OrbControlSection(
                                voiceState = voiceStatus.engineState,
                                isThinking = uiState.isThinking || uiState.isExecutingAction,
                                audioLevel = voiceStatus.audioLevel,
                                continuousSecondsLeft = voiceStatus.continuousSecondsLeft,
                                continuousEnabled = userSettings.continuousListeningEnabled,
                                partialTranscript = voiceStatus.partialTranscript,
                                emergencyActive = safetyState.emergencyStopActive,
                                onOrbClick = {
                                    viewModel.onOrbTapped {
                                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                onEmergencyStop = { viewModel.triggerEmergencyStop() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )

                            ChatTranscriptList(
                                messages = uiState.messages,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )

                            ChatInputBar(
                                value = textInput,
                                onValueChange = { textInput = it },
                                onSend = {
                                    viewModel.handleUserInput(textInput, fromVoice = false)
                                    textInput = ""
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    safetyState.pendingAction?.let { action ->
        SafetyConfirmationDialog(
            action = action,
            onConfirm = viewModel::confirmPendingSafetyAction,
            onCancel = viewModel::cancelPendingSafetyAction
        )
    }

    if (uiState.isSettingsOpen) {
        SettingsSheet(
            uiState = uiState,
            userSettings = userSettings,
            voiceStatus = voiceStatus,
            adbStatus = adbStatus,
            accessibilityActive = accessibilityActive,
            accessibilityLog = accessibilityLog,
            notificationListenerActive = notificationListenerActive,
            recentNotificationCount = recentNotifications.size,
            isOverlayRunning = isOverlayRunning,
            isWakeWordRunning = isWakeWordRunning,
            wakeWordEngineMode = wakeWordEngineMode,
            kwsModelState = kwsModelState,
            smartContextState = smartContextState,
            focusShieldState = focusShieldState,
            memories = memories,
            offlineLlmState = offlineLlmState,
            latestGitHubBuild = latestGitHubBuild,
            isCheckingGitHubBuild = isCheckingGitHubBuild,
            pilotState = pilotState,
            blockedMistakeCount = blockedMistakeCount,
            blockedMistakeRecords = blockedMistakeRecords,
            backgroundTasks = backgroundTasks,
            onSelectTab = viewModel::selectSettingsTab,
            onDismiss = viewModel::closeSettings,
            onSaveApiKey = viewModel::saveApiKey,
            onClearApiKey = viewModel::clearApiKey,
            onSelectModel = viewModel::selectGeminiModel,
            onToggleSearchGrounding = viewModel::toggleGoogleSearchGrounding,
            onPickLocalLlmFile = { localLlmPickerLauncher.launch(arrayOf("*/*")) },
            onDownloadLocalLlm = viewModel::downloadOfflineLlmModel,
            onCancelLocalLlmDownload = viewModel::cancelOfflineLlmDownload,
            onDeleteLocalLlm = viewModel::deleteOfflineLlmModel,
            onToggleContinuous = viewModel::toggleContinuousListening,
            onTestHindiVoice = viewModel::testHindiVoice,
            onToggleUniversalPilot = viewModel::toggleUniversalPilot,
            onToggleScreenVision = viewModel::toggleScreenVisionFallback,
            onStartPilotGoal = viewModel::startUniversalPilot,
            onStopPilot = viewModel::stopUniversalPilot,
            onToggleMistakeBlocker = viewModel::toggleMistakeBlocker,
            onExplainScreen = { viewModel.runExplainScreen() },
            onAutoFillForm = viewModel::runAutoFillForm,
            onToggleZeroCopyBridge = viewModel::toggleZeroCopyBridge,
            onQuickZeroCopyBillToNotes = { viewModel.runZeroCopyBridge() },
            onEnqueueBackgroundTask = { type, query -> viewModel.enqueueBackgroundTask(type, query) },
            onCancelBackgroundTask = viewModel::cancelBackgroundTask,
            onClearFinishedBackgroundTasks = viewModel::clearFinishedBackgroundTasks,
            onAddMemory = viewModel::addMemory,
            onUpdateMemory = viewModel::updateMemory,
            onDeleteMemory = viewModel::deleteMemory,
            onSelectGithubRepo = viewModel::selectGithubRepo,
            onAddGithubRepo = viewModel::addGithubRepo,
            onRemoveGithubRepo = viewModel::removeGithubRepo,
            onSaveGithubToken = viewModel::saveGithubToken,
            onClearGithubToken = viewModel::clearGithubToken,
            onCheckLatestGithubBuild = viewModel::checkLatestGithubBuild,
            onOpenLatestBuildDownload = viewModel::openLatestBuildDownload,
            onRunTermuxCommand = viewModel::requestTermuxCommand,
            onToggleWakeWord = viewModel::toggleWakeWord24x7,
            onDownloadKwsModel = viewModel::downloadKwsModel,
            onCancelKwsDownload = viewModel::cancelKwsDownload,
            onDeleteKwsModel = viewModel::deleteKwsModel,
            onToggleShortFastReply = viewModel::toggleShortFastReply,
            onToggleFocusShield = viewModel::toggleFocusShield,
            onToggleFocusUseAdb = viewModel::toggleFocusUseAdbSuspend,
            onToggleFocusApp = viewModel::toggleFocusApp,
            onPrepareAdbPairing = viewModel::prepareAdbPairing,
            onProbeLocalAdb = viewModel::probeLocalAdb,
            onOpenDevOptions = viewModel::openDeveloperOptions,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
            onTestAccessibilityAction = viewModel::runAccessibilityTestAction,
            onOpenNotificationSettings = viewModel::openNotificationListenerSettings,
            onReadNotificationsAloud = viewModel::readRecentNotificationsAloud,
            onToggleOverlay = viewModel::toggleFloatingOverlay,
            onRequestMicPermission = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onRequestActionPermissions = {
                val perms = buildList {
                    add(Manifest.permission.CALL_PHONE)
                    add(Manifest.permission.SEND_SMS)
                    add(Manifest.permission.READ_CONTACTS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                multiPermissionLauncher.launch(perms)
            },
            onApplyGhostSetting = viewModel::applyGhostSetting,
            onScanPhoneDoctor = viewModel::scanPhoneDoctor,
            onCleanBackgroundApps = viewModel::cleanHeavyBackgroundApps,
            onRefreshApps = viewModel::loadFreezableApps,
            onToggleFreeze = viewModel::toggleAppFreeze
        )
    }
}

@Composable
private fun HudHeader(
    hasMessages: Boolean,
    onClearChat: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = GokuAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "GOKU AGENT",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = stringResource(R.string.hud_subtitle),
                style = MaterialTheme.typography.labelSmall,
                color = ArcCyan.copy(alpha = 0.85f)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hasMessages) {
                IconButton(
                    onClick = onClearChat,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("clear_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.cd_clear_chat),
                        tint = TextSecondary
                    )
                }
            }
            Surface(
                color = ElevatedHudCard,
                shape = CircleShape,
                border = BorderStroke(1.dp, HudOutline)
            ) {
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("open_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.cd_open_settings),
                        tint = ArcCyan
                    )
                }
            }
        }
    }
}

@Composable
private fun TopStatusRow(
    activeBrainTier: ActiveBrainTier,
    adbStatus: LocalAdbStatus,
    accessibilityActive: Boolean,
    onBrainClick: () -> Unit,
    onAdbClick: () -> Unit,
    onAccessClick: () -> Unit
) {
    val (brainText, brainColor) = when (activeBrainTier) {
        ActiveBrainTier.ONLINE_GEMINI -> activeBrainTier.badgeText to EmeraldOnline
        ActiveBrainTier.OFFLINE_LOCAL_AI -> activeBrainTier.badgeText to ArcCyan
        ActiveBrainTier.OFFLINE_REFLEX -> activeBrainTier.badgeText to GokuAmber
    }

    val (adbText, adbColor) = when (adbStatus.state) {
        LocalAdbState.CONNECTED -> stringResource(R.string.status_adb_connected) to EmeraldOnline
        LocalAdbState.PAIRING_READY -> "Pair Ready" to ArcCyan
        LocalAdbState.PROBING -> "Checking…" to GokuAmber
        LocalAdbState.DISCONNECTED -> "127.0.0.1:5555" to TextSecondary
    }

    val accessText = if (accessibilityActive) {
        stringResource(R.string.status_active)
    } else {
        stringResource(R.string.status_inactive)
    }
    val accessColor = if (accessibilityActive) EmeraldOnline else GokuAmber

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HudStatusBadge(
            title = stringResource(R.string.status_brain_label),
            value = brainText,
            accent = brainColor,
            icon = Icons.Filled.Psychology,
            onClick = onBrainClick,
            modifier = Modifier
                .weight(1.2f)
                .testTag("status_badge_brain")
        )
        HudStatusBadge(
            title = stringResource(R.string.status_adb_label),
            value = adbText,
            accent = adbColor,
            icon = Icons.Filled.Terminal,
            onClick = onAdbClick,
            modifier = Modifier
                .weight(1.15f)
                .testTag("status_badge_adb")
        )
        HudStatusBadge(
            title = stringResource(R.string.status_access_label),
            value = accessText,
            accent = accessColor,
            icon = Icons.Filled.AccessibilityNew,
            onClick = onAccessClick,
            modifier = Modifier
                .weight(1f)
                .testTag("status_badge_access")
        )
    }
}

@Composable
private fun HudStatusBadge(
    title: String,
    value: String,
    accent: Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = ElevatedHudCard.copy(alpha = 0.9f),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun OrbControlSection(
    voiceState: VoiceEngineState,
    isThinking: Boolean,
    audioLevel: Float,
    continuousSecondsLeft: Int,
    continuousEnabled: Boolean,
    partialTranscript: String,
    emergencyActive: Boolean,
    onOrbClick: () -> Unit,
    onEmergencyStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isActiveNow = isThinking || voiceState != VoiceEngineState.IDLE

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GokuOrb(
            voiceState = voiceState,
            isThinking = isThinking,
            audioLevel = audioLevel,
            continuousSecondsLeft = continuousSecondsLeft,
            emergencyActive = emergencyActive,
            onClick = onOrbClick,
            orbSize = 166.dp
        )

        Spacer(modifier = Modifier.height(10.dp))

        AnimatedVisibility(visible = partialTranscript.isNotBlank()) {
            Surface(
                color = ArcCyanContainer,
                shape = RoundedCornerShape(999.dp),
                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.5f)),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = ArcCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "\"$partialTranscript\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = ArcCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Button(
                onClick = onEmergencyStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActiveNow) CrimsonEmergency else CrimsonContainer,
                    contentColor = if (isActiveNow) Color.White else CrimsonEmergency
                ),
                border = BorderStroke(1.dp, CrimsonEmergency.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                modifier = Modifier.testTag("emergency_stop_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = stringResource(R.string.cd_emergency_stop),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.kill_switch_label),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (continuousEnabled) {
                Surface(
                    color = ElevatedHudCard,
                    shape = RoundedCornerShape(999.dp),
                    border = BorderStroke(1.dp, HudOutline)
                ) {
                    Text(
                        text = if (voiceState == VoiceEngineState.CONTINUOUS_LISTENING) {
                            "6s AUTO • ${continuousSecondsLeft}s"
                        } else {
                            "6s AUTO MIC"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (voiceState == VoiceEngineState.CONTINUOUS_LISTENING) {
                            EmeraldOnline
                        } else {
                            TextSecondary
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatTranscriptList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = modifier.padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = ElevatedHudCard.copy(alpha = 0.72f),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, HudOutline),
                modifier = Modifier.widthIn(max = 520.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = ArcCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.chat_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }
        return
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.testTag("chat_transcript_list")
    ) {
        items(messages, key = { it.id }) { msg ->
            ChatBubble(message = msg)
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.sender == ChatSender.USER
    val isSystem = message.sender == ChatSender.SYSTEM

    val alignment = when {
        isUser -> Alignment.End
        isSystem -> Alignment.CenterHorizontally
        else -> Alignment.Start
    }

    val containerColor = when {
        message.isAlert -> CrimsonContainer.copy(alpha = 0.85f)
        isUser -> ArcCyanContainer
        isSystem -> GokuAmberContainer.copy(alpha = 0.8f)
        else -> ElevatedHudCard
    }

    val borderColor = when {
        message.isAlert -> CrimsonEmergency.copy(alpha = 0.65f)
        isUser -> ArcCyan.copy(alpha = 0.45f)
        isSystem -> GokuAmber.copy(alpha = 0.45f)
        else -> HudOutline
    }

    val senderLabel = when (message.sender) {
        ChatSender.USER -> "YOU"
        ChatSender.GOKU -> "GOKU"
        ChatSender.SYSTEM -> "ENGINE / SAFETY"
    }

    val senderColor = when {
        message.isAlert -> CrimsonEmergency
        isUser -> ArcCyan
        isSystem -> GokuAmber
        else -> GokuAmber
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            color = containerColor,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = senderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = senderColor
                    )
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = DeepHudSurface.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, HudOutline),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = {
                    Text(
                        text = stringResource(R.string.chat_input_hint),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ArcCyan,
                    unfocusedBorderColor = HudOutline,
                    focusedContainerColor = ObsidianVoid,
                    unfocusedContainerColor = ObsidianVoid
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("chat_text_input")
            )

            Surface(
                color = if (value.isNotBlank()) ArcCyan else ElevatedHudCard,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                IconButton(
                    onClick = onSend,
                    enabled = value.isNotBlank(),
                    modifier = Modifier.testTag("send_message_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.cd_send_message),
                        tint = if (value.isNotBlank()) ObsidianVoid else TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun SafetyConfirmationDialog(
    action: PendingSafetyAction,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = DeepHudSurface,
        icon = {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = GokuAmber,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.safety_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                color = GokuAmber
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = GokuAmberContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, GokuAmber.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "${action.type.displayTitle}: \"${action.rawCommand}\"",
                        style = MaterialTheme.typography.labelMedium,
                        color = GokuAmber,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Text(
                    text = action.riskNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Aap voice mein \"Haan\" ya \"Nahi\" bhi bol sakte hain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ArcCyan
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = GokuAmber, contentColor = ObsidianVoid),
                modifier = Modifier.testTag("safety_confirm_button")
            ) {
                Text(stringResource(R.string.safety_confirm_button))
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(1.dp, CrimsonEmergency),
                modifier = Modifier.testTag("safety_cancel_button")
            ) {
                Text(stringResource(R.string.safety_cancel_button), color = CrimsonEmergency)
            }
        }
    )
}
