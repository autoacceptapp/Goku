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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.arif.goku.calls.SmartCallPhase
import com.arif.goku.calls.SmartCallUiState
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.data.db.CallSummaryEntity
import com.arif.goku.habits.PredictedHabitSuggestion
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallsAndHabitsSettingsSection(
    uiState: DashboardUiState,
    userSettings: GokuUserSettings,
    smartCallState: SmartCallUiState,
    callSummaries: List<CallSummaryEntity>,
    predictedHabits: List<PredictedHabitSuggestion>,
    onToggleSmartCall: (Boolean) -> Unit,
    onSetSmartCallDelay: (Int) -> Unit,
    onToggleSpamAlert: (Boolean) -> Unit,
    onSaveOwnerName: (String) -> Unit,
    onRequestPhonePermissions: () -> Unit,
    onRequestDefaultDialerRole: () -> Unit,
    onStartSmartCallTestTimer: () -> Unit,
    onCancelSmartCall: () -> Unit,
    onDeleteCallSummary: (Long) -> Unit,
    onToggleHabitPredictor: (Boolean) -> Unit,
    onRefreshHabitPredictions: () -> Unit,
    onRunPredictedHabit: (PredictedHabitSuggestion) -> Unit,
    onIgnorePredictedHabit: (PredictedHabitSuggestion) -> Unit,
    onClearHabits: () -> Unit
) {
    var ownerNameInput by remember(userSettings.smartCallOwnerName) {
        mutableStateOf(userSettings.smartCallOwnerName)
    }
    val allCallPermsGranted = uiState.hasReadPhoneStatePermission &&
        uiState.hasReadCallLogPermission &&
        uiState.hasAnswerCallsPermission

    // 1. Smart Call (30-Second Auto-Answer & Talk)
    Phase6Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PhoneInTalk, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Smart Call (30s Auto-Answer)", style = MaterialTheme.typography.titleSmall)
            }
            Phase6MiniPill(
                label = when {
                    smartCallState.phase == SmartCallPhase.RINGING_COUNTDOWN -> "RING ${smartCallState.countdownSecondsLeft}s"
                    smartCallState.phase == SmartCallPhase.SPEAKING_GREETING ||
                        smartCallState.phase == SmartCallPhase.LISTENING_CALLER -> "ON CALL"
                    userSettings.smartCallEnabled -> "ARMED (${userSettings.smartCallDelaySeconds}s)"
                    else -> "OFF"
                },
                color = when {
                    smartCallState.phase != SmartCallPhase.IDLE && smartCallState.phase != SmartCallPhase.COMPLETED -> GokuAmber
                    userSettings.smartCallEnabled -> EmeraldOnline
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
                Text("Auto-Answer Unanswered Calls", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "If unanswered after ${userSettings.smartCallDelaySeconds}s, answers on Speakerphone, speaks Hindi greeting, records caller's message & summarizes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = userSettings.smartCallEnabled,
                onCheckedChange = onToggleSmartCall,
                modifier = Modifier.testTag("smart_call_enabled_switch")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = GokuAmber, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Spam / Unknown Number Alert", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "Flags 140/1600 telemarketers & numbers not in Contacts or Memory Vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = userSettings.smartCallSpamAlertEnabled,
                onCheckedChange = onToggleSpamAlert,
                modifier = Modifier.testTag("smart_call_spam_switch")
            )
        }

        HorizontalDivider(color = HudOutline)

        // Configurable countdown timer (default 30s)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Timer, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Auto-Answer Delay Timer", style = MaterialTheme.typography.labelMedium, color = ArcCyan)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(15, 30, 45).forEach { seconds ->
                FilterChip(
                    selected = userSettings.smartCallDelaySeconds == seconds,
                    onClick = { onSetSmartCallDelay(seconds) },
                    label = { Text("${seconds}s", style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = ArcCyan
                    ),
                    modifier = Modifier.testTag("smart_call_delay_${seconds}s")
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = ownerNameInput,
                onValueChange = { ownerNameInput = it },
                label = { Text("Greeting Name") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("smart_call_owner_name_input")
            )
            OutlinedButton(
                onClick = { onSaveOwnerName(ownerNameInput) },
                modifier = Modifier.testTag("smart_call_owner_save_button")
            ) {
                Text("Save")
            }
        }

        // Permissions & Default Dialer Role status
        Surface(
            color = ObsidianVoid,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, HudOutline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CallStatusRow("Phone State (READ_PHONE_STATE)", uiState.hasReadPhoneStatePermission)
                CallStatusRow("Caller ID / Log (READ_CALL_LOG)", uiState.hasReadCallLogPermission)
                CallStatusRow("Auto-Answer (ANSWER_PHONE_CALLS)", uiState.hasAnswerCallsPermission)
                CallStatusRow(
                    label = if (smartCallState.isDefaultDialer) {
                        "Default Dialer (InCallService Active)"
                    } else {
                        "Dialer Mode (TelecomManager Fallback)"
                    },
                    active = smartCallState.isDefaultDialer
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!allCallPermsGranted) {
                Button(
                    onClick = onRequestPhonePermissions,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("request_smart_call_permissions_button")
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Grant Call Perms", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = onRequestDefaultDialerRole,
                modifier = Modifier
                    .weight(1f)
                    .testTag("request_default_dialer_button")
            ) {
                Icon(Icons.Filled.Dialpad, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (smartCallState.isDefaultDialer) "Default Dialer OK" else "Set Default Dialer",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = smartCallState.statusHindi,
            style = MaterialTheme.typography.bodySmall,
            color = if (smartCallState.phase != SmartCallPhase.IDLE) GokuAmber else TextSecondary
        )

        val isCallFlowActive = smartCallState.phase == SmartCallPhase.RINGING_COUNTDOWN ||
            smartCallState.phase == SmartCallPhase.AUTO_ANSWERING ||
            smartCallState.phase == SmartCallPhase.SPEAKING_GREETING ||
            smartCallState.phase == SmartCallPhase.LISTENING_CALLER

        if (isCallFlowActive) {
            Button(
                onClick = onCancelSmartCall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrimsonEmergency,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("smart_call_cancel_active_button")
            ) {
                Icon(Icons.Filled.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cancel Smart Call (${smartCallState.countdownSecondsLeft}s)")
            }
        } else {
            OutlinedButton(
                onClick = onStartSmartCallTestTimer,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("smart_call_test_timer_button")
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Test ${userSettings.smartCallDelaySeconds}s Auto-Answer Flow")
            }
        }
    }

    // 2. Call Summaries Card (Room DB CallSummaryEntity)
    Phase6Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.History, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Recent Call Summaries (${callSummaries.size})", style = MaterialTheme.typography.titleSmall)
            }
        }

        if (callSummaries.isEmpty()) {
            Text(
                text = "Abhi koi auto-answered call summary saved nahi hai.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            val timeFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                callSummaries.take(8).forEach { summary ->
                    Surface(
                        color = ObsidianVoid,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (summary.isSpamOrUnknown) GokuAmber.copy(alpha = 0.55f) else HudOutline
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${summary.callerName} • ${timeFmt.format(Date(summary.timestamp))}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (summary.isSpamOrUnknown) GokuAmber else ArcCyan
                                    )
                                    Text(
                                        text = "${summary.callerNumber} (${summary.durationSeconds}s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteCallSummary(summary.id) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("delete_call_summary_${summary.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete call summary",
                                        tint = CrimsonEmergency
                                    )
                                }
                            }
                            Text(
                                text = summary.summaryHindi,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // 3. Habit Predictor Card
    Phase6Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GokuAmber, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Habit Predictor", style = MaterialTheme.typography.titleSmall)
            }
            Switch(
                checked = userSettings.habitPredictorEnabled,
                onCheckedChange = onToggleHabitPredictor,
                modifier = Modifier.testTag("habit_predictor_switch")
            )
        }

        Text(
            text = "Passively learns recurring apps & commands (3+ times within ±20 min) and gently suggests them via Floating Orb & Dashboard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onRefreshHabitPredictions,
                modifier = Modifier
                    .weight(1f)
                    .testTag("habit_scan_now_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Check Patterns Now")
            }
            OutlinedButton(
                onClick = onClearHabits,
                modifier = Modifier.testTag("habit_clear_all_button")
            ) {
                Text("Reset", color = CrimsonEmergency)
            }
        }

        if (predictedHabits.isEmpty()) {
            Text(
                text = "Abhi is time-window (±20 min) ke liye koi 3+ repeated habit pattern nahi mila. Normal use jari rakhein!",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                predictedHabits.forEach { habit ->
                    Surface(
                        color = ObsidianVoid,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.45f)),
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
                                Text(
                                    text = "${habit.displayLabel} (${habit.matchCount}x @ ~${habit.typicalTimeLabel})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = ArcCyan,
                                    modifier = Modifier.weight(1f)
                                )
                                Phase6MiniPill(label = habit.actionType.label, color = GokuAmber)
                            }
                            Text(
                                text = habit.promptHindi,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onRunPredictedHabit(habit) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("habit_run_${habit.id}")
                                ) {
                                    Text("Run")
                                }
                                OutlinedButton(
                                    onClick = { onIgnorePredictedHabit(habit) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("habit_ignore_${habit.id}")
                                ) {
                                    Text("Ignore")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallStatusRow(label: String, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Phase6MiniPill(
            label = if (active) "READY" else "NEEDED",
            color = if (active) EmeraldOnline else GokuAmber
        )
    }
}

@Composable
private fun Phase6Card(content: @Composable () -> Unit) {
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
private fun Phase6MiniPill(label: String, color: Color) {
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
