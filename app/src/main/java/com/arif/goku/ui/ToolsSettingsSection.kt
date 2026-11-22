package com.arif.goku.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arif.goku.adb.LocalAdbState
import com.arif.goku.adb.LocalAdbStatus
import com.arif.goku.system.FreezableAppInfo
import com.arif.goku.system.GhostSettingType
import com.arif.goku.system.PhoneDoctorReport
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

/**
 * Phase 2 System Power Tools Tab inside SettingsSheet:
 * - Ghost Mode silent ADB toggles
 * - Auto-Repair Phone Doctor RAM/CPU scan & force-stop
 * - App Freezer (`pm disable-user --user 0` / `pm enable`)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsSettingsSection(
    adbStatus: LocalAdbStatus,
    phoneDoctorReport: PhoneDoctorReport?,
    isScanningDoctor: Boolean,
    freezableApps: List<FreezableAppInfo>,
    isLoadingApps: Boolean,
    onApplyGhostSetting: (GhostSettingType, Boolean?, Int?) -> Unit,
    onScanPhoneDoctor: () -> Unit,
    onCleanBackgroundApps: () -> Unit,
    onRefreshApps: () -> Unit,
    onToggleFreeze: (FreezableAppInfo) -> Unit
) {
    val adbReady = adbStatus.state == LocalAdbState.CONNECTED

    if (!adbReady) {
        Surface(
            color = GokuAmber.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, GokuAmber.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Local ADB (127.0.0.1:5555) disconnected. Pair in Local ADB tab to unlock Ghost Mode, Phone Doctor, and App Freezer.",
                style = MaterialTheme.typography.bodySmall,
                color = GokuAmber,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    // 1. Ghost Mode Silent System Toggles
    PowerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Memory, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ghost Mode (Silent ADB Toggles)", style = MaterialTheme.typography.titleSmall)
        }
        Text(
            text = "Applies settings silently via shell without opening Android Settings.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.WIFI, true, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_wifi_on")
            ) {
                Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Wi-Fi ON")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.WIFI, false, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_wifi_off")
            ) {
                Text("Wi-Fi OFF")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.BLUETOOTH, true, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_bt_on")
            ) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("BT ON")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.BLUETOOTH, false, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_bt_off")
            ) {
                Text("BT OFF")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.DARK_MODE, true, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_dark_on")
            ) {
                Icon(Icons.Filled.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Dark ON")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.DND, true, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_dnd_on")
            ) {
                Icon(Icons.Filled.DoNotDisturbOn, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("DND ON")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.DND, false, null) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_dnd_off")
            ) {
                Text("DND OFF")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.BRIGHTNESS, null, 70) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_brightness_low")
            ) {
                Icon(Icons.Filled.Brightness6, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Low Bright")
            }
            OutlinedButton(
                onClick = { onApplyGhostSetting(GhostSettingType.BRIGHTNESS, null, 220) },
                enabled = adbReady,
                modifier = Modifier.testTag("ghost_brightness_high")
            ) {
                Text("High Bright")
            }
        }
    }

    // 2. Auto-Repair Phone Doctor
    PowerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.HealthAndSafety, contentDescription = null, tint = EmeraldOnline, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Auto-Repair Phone Doctor", style = MaterialTheme.typography.titleSmall)
            }
            if (isScanningDoctor) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ArcCyan)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onScanPhoneDoctor,
                enabled = adbReady && !isScanningDoctor,
                modifier = Modifier
                    .weight(1f)
                    .testTag("phone_doctor_scan_button")
            ) {
                Text("Scan RAM / CPU")
            }
            OutlinedButton(
                onClick = onCleanBackgroundApps,
                enabled = adbReady && (phoneDoctorReport?.totalReclaimableCandidates ?: 0) > 0,
                modifier = Modifier.testTag("phone_doctor_clean_button")
            ) {
                Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clean")
            }
        }

        phoneDoctorReport?.let { report ->
            Text(
                text = report.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = ArcCyan
            )
            report.topProcesses.take(6).forEach { proc ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = proc.processName,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${proc.memoryMb} MB" + if (proc.isSafeToStop) " • Cleanable" else " • System",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (proc.isSafeToStop) GokuAmber else TextSecondary
                    )
                }
            }
        }
    }

    // 3. App Freezer
    PowerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AcUnit, contentDescription = null, tint = ArcCyan, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("App Freezer (User Apps)", style = MaterialTheme.typography.titleSmall)
            }
            IconButton(
                onClick = onRefreshApps,
                modifier = Modifier.testTag("refresh_freezer_apps_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh apps", tint = ArcCyan)
            }
        }

        if (isLoadingApps) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ArcCyan)
                Text("Loading third-party apps…", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        } else if (freezableApps.isEmpty()) {
            Text(
                text = "No third-party apps loaded yet. Tap refresh to scan.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        } else {
            freezableApps.take(15).forEachIndexed { idx, app ->
                if (idx > 0) HorizontalDivider(color = HudOutline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = app.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = app.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onToggleFreeze(app) },
                        enabled = adbReady,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (app.isFrozen) EmeraldOnline else CrimsonEmergency,
                            contentColor = ObsidianVoid
                        ),
                        modifier = Modifier.testTag("freeze_toggle_${app.packageName}")
                    ) {
                        Text(
                            text = if (app.isFrozen) "Unfreeze" else "Freeze",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerCard(content: @Composable () -> Unit) {
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
