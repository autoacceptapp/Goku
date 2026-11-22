package com.arif.goku.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.pilot.PilotState
import com.arif.goku.safety.BlockedMistakeRecord
import com.arif.goku.worker.BackgroundTaskItem
import com.arif.goku.worker.BackgroundTaskStatus
import com.arif.goku.worker.BackgroundTaskType
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PilotAndQueueSettingsSection(
    userSettings: GokuUserSettings,
    pilotState: PilotState,
    blockedMistakeCount: Int,
    blockedMistakeRecords: List<BlockedMistakeRecord>,
    backgroundTasks: List<BackgroundTaskItem>,
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
    onClearFinishedBackgroundTasks: () -> Unit
) {
    var pilotGoalInput by remember { mutableStateOf("") }
    var selectedTaskType by remember { mutableStateOf(BackgroundTaskType.WEB_RESEARCH) }
    var queueQueryInput by remember { mutableStateOf("") }

    // 1. Universal App Pilot & Screen Vision Fallback
    PilotCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.RocketLaunch, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Universal App Pilot", style = MaterialTheme.typography.titleSmall)
            }
            PilotMiniPill(
                label = if (pilotState.isRunning) {
                    "STEP ${pilotState.currentStep}/${pilotState.maxSteps}"
                } else if (userSettings.universalPilotEnabled) {
                    "STANDBY"
                } else {
                    "OFF"
                },
                color = when {
                    pilotState.isRunning -> GokuAmber
                    userSettings.universalPilotEnabled -> EmeraldOnline
                    else -> TextSecondary
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Autonomous Observe -> Think -> Act", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Operates any app via Accessibility / ADB with 12-step safety cap & loop guard.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = userSettings.universalPilotEnabled,
                onCheckedChange = onToggleUniversalPilot,
                modifier = Modifier.testTag("pilot_enabled_switch")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Screen Vision Fallback (JPEG)", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Falls back to Accessibility takeScreenshot / ADB screencap -> Gemini Vision for WebViews & custom UI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = userSettings.screenVisionFallbackEnabled,
                onCheckedChange = onToggleScreenVision,
                enabled = userSettings.universalPilotEnabled,
                modifier = Modifier.testTag("pilot_vision_fallback_switch")
            )
        }

        Text(
            text = "${pilotState.lastStatusHindi} (${pilotState.lastObservationMode.badge})",
            style = MaterialTheme.typography.labelMedium,
            color = if (pilotState.isRunning) GokuAmber else ArcCyan
        )

        OutlinedTextField(
            value = pilotGoalInput,
            onValueChange = { pilotGoalInput = it },
            label = { Text("Pilot Goal (e.g., Settings mein Dark Mode on karo)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("pilot_goal_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pilotState.isRunning) {
                Button(
                    onClick = onStopPilot,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CrimsonEmergency,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_abort_button")
                ) {
                    Icon(Icons.Filled.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Abort Pilot (Goku Stop)")
                }
            } else {
                Button(
                    onClick = {
                        onStartPilotGoal(pilotGoalInput)
                        pilotGoalInput = ""
                    },
                    enabled = userSettings.universalPilotEnabled && pilotGoalInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pilot_run_button")
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Run Universal Pilot")
                }
            }
        }

        if (pilotState.stepLogs.isNotEmpty()) {
            Surface(
                color = ObsidianVoid,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, HudOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Recent Pilot Steps", style = MaterialTheme.typography.labelSmall, color = ArcCyan)
                    pilotState.stepLogs.takeLast(5).forEach { log ->
                        Text(
                            text = "• $log",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // 2. Live Screen Co-Pilot & Mistake Blocker
    PilotCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = EmeraldOnline, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Mistake Blocker & Screen Co-Pilot", style = MaterialTheme.typography.titleSmall)
            }
            PilotMiniPill(
                label = if (userSettings.mistakeBlockerEnabled) {
                    "SHIELD ($blockedMistakeCount)"
                } else {
                    "OFF"
                },
                color = if (userSettings.mistakeBlockerEnabled) EmeraldOnline else CrimsonEmergency
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Real-Time Mistake Blocker", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Warns before Delete, Uninstall, Clear All, Format, unknown APK/phishing links & high-risk UPI payments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = userSettings.mistakeBlockerEnabled,
                onCheckedChange = onToggleMistakeBlocker,
                modifier = Modifier.testTag("mistake_blocker_switch")
            )
        }

        HorizontalDivider(color = HudOutline)

        Text("Screen Co-Pilot Quick Actions", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onExplainScreen,
                modifier = Modifier
                    .weight(1f)
                    .testTag("copilot_explain_screen_button")
            ) {
                Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Explain Screen", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onAutoFillForm,
                modifier = Modifier
                    .weight(1f)
                    .testTag("copilot_autofill_form_button")
            ) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Auto-Fill Form", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (blockedMistakeRecords.isNotEmpty()) {
            Surface(
                color = ObsidianVoid,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, HudOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("Recent Shield Events", style = MaterialTheme.typography.labelSmall, color = GokuAmber)
                    blockedMistakeRecords.take(4).forEach { rec ->
                        Text(
                            text = "• [${rec.decision}] ${rec.title} (${rec.packageName})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // 3. Zero-Copy App Bridge
    PilotCardContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Zero-Copy App Bridge", style = MaterialTheme.typography.titleSmall)
            }
            Switch(
                checked = userSettings.zeroCopyBridgeEnabled,
                onCheckedChange = onToggleZeroCopyBridge,
                modifier = Modifier.testTag("zero_copy_bridge_switch")
            )
        }
        Text(
            text = "Say \"Screen par jo bill hai uska total karke Notes/WhatsApp mein daal do\" — extracts, sums & injects directly without copy-paste.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onQuickZeroCopyBillToNotes,
            enabled = userSettings.zeroCopyBridgeEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("zero_copy_quick_bill_button")
        ) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Total Active Screen Bill -> Notes")
        }
    }

    // 4. Background Task Queue
    PilotCardContainer {
        val activeCount = backgroundTasks.count {
            it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlaylistAddCheck, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Background Task Queue", style = MaterialTheme.typography.titleSmall)
            }
            PilotMiniPill(
                label = if (activeCount > 0) "RUNNING ($activeCount)" else "IDLE (${backgroundTasks.size})",
                color = if (activeCount > 0) GokuAmber else EmeraldOnline
            )
        }

        Text(
            text = "Silent WorkManager + Coroutine worker for research, GitHub builds, price checks & cleanup with Floating Orb progress + Hindi voice summary.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            BackgroundTaskType.entries.forEach { taskType ->
                FilterChip(
                    selected = selectedTaskType == taskType,
                    onClick = { selectedTaskType = taskType },
                    label = { Text(taskType.label, style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = ArcCyan
                    ),
                    modifier = Modifier.testTag("bg_task_type_${taskType.name.lowercase()}")
                )
            }
        }

        OutlinedTextField(
            value = queueQueryInput,
            onValueChange = { queueQueryInput = it },
            label = { Text("Task Query (optional for Cleanup / GitHub Build)") },
            placeholder = { Text("e.g., Pixel 9a India price or latest build status") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("bg_queue_query_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onEnqueueBackgroundTask(selectedTaskType, queueQueryInput)
                    queueQueryInput = ""
                },
                enabled = selectedTaskType == BackgroundTaskType.FILE_CLEANUP ||
                    selectedTaskType == BackgroundTaskType.GITHUB_BUILD ||
                    queueQueryInput.isNotBlank(),
                modifier = Modifier
                    .weight(1f)
                    .testTag("bg_queue_add_button")
            ) {
                Text("Add to Queue")
            }
            if (backgroundTasks.any {
                    it.status != BackgroundTaskStatus.QUEUED && it.status != BackgroundTaskStatus.RUNNING
                }
            ) {
                OutlinedButton(
                    onClick = onClearFinishedBackgroundTasks,
                    modifier = Modifier.testTag("bg_queue_clear_done_button")
                ) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear finished tasks", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear")
                }
            }
        }

        if (backgroundTasks.isEmpty()) {
            Text(
                text = "No background tasks queued yet.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                backgroundTasks.take(8).forEach { task ->
                    BackgroundTaskRowCard(
                        task = task,
                        onCancel = { onCancelBackgroundTask(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundTaskRowCard(
    task: BackgroundTaskItem,
    onCancel: () -> Unit
) {
    val statusColor = when (task.status) {
        BackgroundTaskStatus.QUEUED -> ArcCyan
        BackgroundTaskStatus.RUNNING -> GokuAmber
        BackgroundTaskStatus.COMPLETED -> EmeraldOnline
        BackgroundTaskStatus.FAILED -> CrimsonEmergency
        BackgroundTaskStatus.CANCELLED -> TextSecondary
    }
    val isActive = task.status == BackgroundTaskStatus.QUEUED || task.status == BackgroundTaskStatus.RUNNING

    Surface(
        color = ObsidianVoid,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${task.type.label} • ${task.status.name}",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor
                    )
                    Text(
                        text = task.query,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (isActive) {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("cancel_bg_task_${task.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = "Cancel background task",
                            tint = CrimsonEmergency
                        )
                    }
                }
            }

            if (isActive) {
                LinearProgressIndicator(
                    progress = { (task.progressPercent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = statusColor
                )
            }

            Text(
                text = task.summaryHindi,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PilotCardContainer(content: @Composable () -> Unit) {
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
private fun PilotMiniPill(label: String, color: Color) {
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
