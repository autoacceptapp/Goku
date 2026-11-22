package com.arif.goku.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.data.db.SkillMode
import com.arif.goku.skills.SkillEngineUiState
import com.arif.goku.skills.TeachSessionState
import com.arif.goku.system.DiagnosisCategory
import com.arif.goku.system.LogcatDiagnosisReport
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.DeepHudSurface
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

@Composable
fun SkillsAndBackupSettingsSection(
    userSettings: GokuUserSettings,
    skills: List<SkillEntity>,
    skillEngineState: SkillEngineUiState,
    teachState: TeachSessionState,
    logcatReport: LogcatDiagnosisReport?,
    isDiagnosingLogcat: Boolean,
    isBackupBusy: Boolean,
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
    onRestoreBackupFromGist: (String) -> Unit
) {
    var teachHintInput by remember { mutableStateOf("") }
    var newSkillGoalInput by remember { mutableStateOf("") }
    var backupPassphrase by remember { mutableStateOf("") }
    var editingSkill by remember { mutableStateOf<SkillEntity?>(null) }

    // 1. Smart Teach-by-Example Card
    Phase7Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.School, contentDescription = null, tint = GokuAmber, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Smart Teach-by-Example", style = MaterialTheme.typography.titleSmall)
            }
            Phase7Pill(
                label = when {
                    teachState.isRecording -> "REC (${teachState.recordedEventCount})"
                    teachState.isSynthesizing -> "OPTIMIZING"
                    else -> "READY"
                },
                color = when {
                    teachState.isRecording -> CrimsonEmergency
                    teachState.isSynthesizing -> GokuAmber
                    else -> EmeraldOnline
                }
            )
        }

        Text(
            text = "Bolein \"Goku, main jo kar raha hoon woh seekh lo\" ya Floating Orb par TEACH dabayein. Poora hone par \"Seekh liya\" bolein — Goku waste taps hata kar Intent/ADB speed mein upgrade karega.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        if (!teachState.isRecording) {
            OutlinedTextField(
                value = teachHintInput,
                onValueChange = { teachHintInput = it },
                label = { Text("Optional Skill Name (e.g. Subah News Kholo)") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("teach_skill_hint_input")
            )
            Button(
                onClick = {
                    onStartTeachMode(teachHintInput)
                    teachHintInput = ""
                },
                enabled = !teachState.isSynthesizing,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start_teach_mode_button")
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Start Teach-by-Example")
            }
        } else {
            Text(
                text = teachState.statusHindi,
                style = MaterialTheme.typography.bodySmall,
                color = GokuAmber
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onFinishTeachMode,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("finish_teach_mode_button")
                ) {
                    Text("Seekh Liya (Save)")
                }
                OutlinedButton(
                    onClick = onCancelTeachMode,
                    border = BorderStroke(1.dp, CrimsonEmergency),
                    modifier = Modifier.testTag("cancel_teach_mode_button")
                ) {
                    Text("Cancel", color = CrimsonEmergency)
                }
            }
        }
    }

    // 2. Evolving & Self-Healing Skills Explorer Card
    Phase7Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Evolving Skills (${skills.size})", style = MaterialTheme.typography.titleSmall)
            }
            if (skillEngineState.isRunning) {
                OutlinedButton(
                    onClick = onStopSkillEngine,
                    border = BorderStroke(1.dp, CrimsonEmergency),
                    modifier = Modifier.testTag("stop_skill_engine_button")
                ) {
                    Icon(Icons.Filled.StopCircle, contentDescription = null, tint = CrimsonEmergency, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("STOP", color = CrimsonEmergency)
                }
            }
        }

        if (skillEngineState.isRunning || skillEngineState.statusHindi.isNotBlank()) {
            Text(
                text = skillEngineState.statusHindi,
                style = MaterialTheme.typography.bodySmall,
                color = if (skillEngineState.isHealing) GokuAmber else ArcCyan
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = newSkillGoalInput,
                onValueChange = { newSkillGoalInput = it },
                label = { Text("Learn new skill (e.g. YouTube lo-fi)") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("synthesize_skill_input")
            )
            Button(
                onClick = {
                    val goal = newSkillGoalInput.trim()
                    if (goal.isNotEmpty()) {
                        onSynthesizeSkill(goal)
                        newSkillGoalInput = ""
                    }
                },
                enabled = !skillEngineState.isRunning && newSkillGoalInput.isNotBlank(),
                modifier = Modifier.testTag("synthesize_skill_button")
            ) {
                Text("Learn")
            }
        }

        if (skills.isEmpty()) {
            Text(
                text = "Abhi koi learned skill save nahi hai. Teach-by-Example ya upar 'Learn' se apna pehla offline skill banayein.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            HorizontalDivider(color = HudOutline.copy(alpha = 0.5f))
            skills.forEach { skill ->
                val modeEnum = skill.modeEnum()
                val modeColor = when (modeEnum) {
                    SkillMode.ADB_FAST -> EmeraldOnline
                    SkillMode.INTENT -> ArcCyan
                    SkillMode.UI_TAP -> GokuAmber
                }
                val scoreColor = when {
                    skill.evolutionScore >= 80 -> EmeraldOnline
                    skill.evolutionScore >= 55 -> ArcCyan
                    else -> CrimsonEmergency
                }
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, modeColor.copy(alpha = 0.4f)),
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
                                    text = skill.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Triggers: ${skill.triggerPhrases}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Phase7Pill(label = modeEnum.label, color = modeColor)
                                Phase7Pill(label = "${skill.evolutionScore}/100", color = scoreColor)
                            }
                        }

                        LinearProgressIndicator(
                            progress = { (skill.evolutionScore / 100f).coerceIn(0f, 1f) },
                            color = scoreColor,
                            trackColor = HudOutline.copy(alpha = 0.35f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "✓${skill.successCount}  ✗${skill.failCount}  •  ${skill.avgDurationMs}ms  •  ${skill.parsedSteps().size} steps",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onRunSkill(skill) },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("run_skill_${skill.id}")
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow,
                                        contentDescription = "Run skill ${skill.name}",
                                        tint = EmeraldOnline
                                    )
                                }
                                IconButton(
                                    onClick = { editingSkill = skill },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("edit_skill_${skill.id}")
                                ) {
                                    Icon(
                                        Icons.Filled.Edit,
                                        contentDescription = "Edit skill ${skill.name}",
                                        tint = ArcCyan
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSkill(skill.id) },
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("delete_skill_${skill.id}")
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = "Delete skill ${skill.name}",
                                        tint = CrimsonEmergency
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 3. Logcat Self-Diagnosis Doctor Card
    Phase7Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.BugReport, contentDescription = null, tint = CrimsonEmergency, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("logcat Self-Diagnosis", style = MaterialTheme.typography.titleSmall)
            }
            OutlinedButton(
                onClick = onRunLogcatDoctor,
                enabled = !isDiagnosingLogcat,
                modifier = Modifier.testTag("run_logcat_doctor_button")
            ) {
                Text(if (isDiagnosingLogcat) "Scanning…" else "Scan Logcat")
            }
        }

        if (logcatReport != null) {
            val badgeColor = if (logcatReport.category == DiagnosisCategory.HEALTHY) EmeraldOnline else GokuAmber
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Phase7Pill(label = logcatReport.category.badge, color = badgeColor)
                Text(
                    text = logcatReport.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Text(
                text = "Cause: ${logcatReport.rootCauseHindi}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Fix: ${logcatReport.suggestedFixHindi}",
                style = MaterialTheme.typography.bodySmall,
                color = ArcCyan
            )
            if (logcatReport.autoRecoveryAttempted) {
                Text(
                    text = if (logcatReport.autoRecoverySucceeded) {
                        "Auto-Recovery succeeded!"
                    } else {
                        "Auto-Recovery tried — manual action needed."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (logcatReport.autoRecoverySucceeded) EmeraldOnline else CrimsonEmergency
                )
            }
        } else {
            Text(
                text = "Repeated failures par Goku automatically `logcat -d -t 150` padh kar root cause aur Hindi fix batata hai.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }

    // 4. Dream Mode (Overnight Self-Optimization) Card
    Phase7Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Dream Mode (Overnight)", style = MaterialTheme.typography.titleSmall)
            }
            Switch(
                checked = userSettings.dreamModeEnabled,
                onCheckedChange = onToggleDreamMode,
                modifier = Modifier.testTag("toggle_dream_mode_switch")
            )
        }

        Text(
            text = "Charging + Unmetered Wi-Fi par raat ko low-score UI_TAP skills ko INTENT/ADB_FAST mein upgrade karta hai aur frequent habits ke skills pre-build karta hai.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        OutlinedButton(
            onClick = onRunDreamModeNow,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("run_dream_mode_now_button")
        ) {
            Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Run Dream Optimization Now")
        }

        if (userSettings.lastDreamSummary.isNotBlank()) {
            Surface(
                color = ObsidianVoid.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, ArcCyan.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = userSettings.lastDreamSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmeraldOnline,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }

    // 5. Encrypted Brain Backup & Restore Card
    Phase7Card {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = EmeraldOnline, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Encrypted Brain Backup (.goku)", style = MaterialTheme.typography.titleSmall)
            }
            Phase7Pill(label = "AES-256-GCM", color = EmeraldOnline)
        }

        Text(
            text = "Saare Skills, Personal Memories aur Habits ko AES-256 encrypted `.goku` file mein Google Drive / Downloads ya Private GitHub Gist par backup & restore karein.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        OutlinedTextField(
            value = backupPassphrase,
            onValueChange = { backupPassphrase = it },
            label = { Text("Encryption Passphrase (min 4 chars)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("backup_passphrase_input")
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onExportEncryptedBackup(backupPassphrase) },
                enabled = !isBackupBusy && backupPassphrase.trim().length >= 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag("export_goku_backup_button")
            ) {
                Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Export .goku")
            }
            OutlinedButton(
                onClick = { onRestoreEncryptedBackup(backupPassphrase) },
                enabled = !isBackupBusy && backupPassphrase.trim().length >= 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag("restore_goku_backup_button")
            ) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Restore .goku")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onSyncBackupToGist(backupPassphrase) },
                enabled = !isBackupBusy && userSettings.hasGithubToken && backupPassphrase.trim().length >= 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag("sync_gist_backup_button")
            ) {
                Text(if (userSettings.backupGistId.isBlank()) "Sync GitHub Gist" else "Update Gist")
            }
            OutlinedButton(
                onClick = { onRestoreBackupFromGist(backupPassphrase) },
                enabled = !isBackupBusy && userSettings.hasGithubToken &&
                    userSettings.backupGistId.isNotBlank() && backupPassphrase.trim().length >= 4,
                modifier = Modifier
                    .weight(1f)
                    .testTag("restore_gist_backup_button")
            ) {
                Text("Pull from Gist")
            }
        }

        if (!userSettings.hasGithubToken) {
            Text(
                text = "Tip: Private GitHub Gist sync ke liye Dev Mode tab mein GitHub Token add karein.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        if (userSettings.lastBackupSummary.isNotBlank()) {
            Text(
                text = userSettings.lastBackupSummary,
                style = MaterialTheme.typography.labelSmall,
                color = ArcCyan
            )
        }
    }

    // Skill Edit Dialog
    editingSkill?.let { skillToEdit ->
        var editName by remember(skillToEdit.id) { mutableStateOf(skillToEdit.name) }
        var editTriggers by remember(skillToEdit.id) { mutableStateOf(skillToEdit.triggerPhrases) }
        var editPackage by remember(skillToEdit.id) { mutableStateOf(skillToEdit.targetPackage) }
        var editStepsJson by remember(skillToEdit.id) { mutableStateOf(skillToEdit.stepsJson) }

        AlertDialog(
            onDismissRequest = { editingSkill = null },
            containerColor = DeepHudSurface,
            title = { Text("Edit Skill", color = ArcCyan) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Skill Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editTriggers,
                        onValueChange = { editTriggers = it },
                        label = { Text("Trigger Phrases (comma separated)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPackage,
                        onValueChange = { editPackage = it },
                        label = { Text("Target Package (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editStepsJson,
                        onValueChange = { editStepsJson = it },
                        label = { Text("Steps JSON") },
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveEditedSkill(
                            skillToEdit.copy(
                                name = editName.trim(),
                                triggerPhrases = editTriggers.trim(),
                                targetPackage = editPackage.trim(),
                                stepsJson = editStepsJson.trim()
                            )
                        )
                        editingSkill = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ArcCyan, contentColor = ObsidianVoid)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { editingSkill = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun Phase7Card(content: @Composable () -> Unit) {
    Surface(
        color = ElevatedHudCard,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, HudOutline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun Phase7Pill(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.14f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
