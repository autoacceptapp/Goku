package com.arif.goku.ui

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arif.goku.adb.LocalAdbState
import com.arif.goku.adb.LocalAdbStatus
import com.arif.goku.data.GokuUserSettings
import com.arif.goku.system.FocusShieldState
import com.arif.goku.system.FreezableAppInfo
import com.arif.goku.system.SmartContextState
import com.arif.goku.voice.KwsDownloadStage
import com.arif.goku.voice.KwsModelState
import com.example.ui.theme.ArcCyan
import com.example.ui.theme.CrimsonEmergency
import com.example.ui.theme.ElevatedHudCard
import com.example.ui.theme.EmeraldOnline
import com.example.ui.theme.GokuAmber
import com.example.ui.theme.HudOutline
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.TextSecondary

/**
 * Phase 3 Wake & Focus Settings Tab:
 * - 24x7 Offline Wake Word ("Hey Goku" & "Goku Stop") toggle + Sherpa-ONNX model downloader
 * - Battery Optimization Unrestricted prompt
 * - Smart Context & Battery Mode (Night/DND, Low Battery <15%, Short/Fast Reply toggle)
 * - Deep Focus Shield ("Kaam Mode") + ADB App Suspend toggle + Distraction App Picker
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WakeFocusSettingsSection(
    userSettings: GokuUserSettings,
    hasMicPermission: Boolean,
    isBatteryUnrestricted: Boolean,
    isWakeWordRunning: Boolean,
    wakeWordEngineMode: String,
    kwsModelState: KwsModelState,
    smartContext: SmartContextState,
    focusShieldState: FocusShieldState,
    adbStatus: LocalAdbStatus,
    installedApps: List<FreezableAppInfo>,
    isLoadingApps: Boolean,
    onRequestMicPermission: () -> Unit,
    onToggleWakeWord: (Boolean) -> Unit,
    onDownloadKwsModel: () -> Unit,
    onCancelKwsDownload: () -> Unit,
    onDeleteKwsModel: () -> Unit,
    onToggleShortFastReply: (Boolean) -> Unit,
    onToggleFocusShield: (Boolean) -> Unit,
    onToggleFocusUseAdb: (Boolean) -> Unit,
    onToggleFocusApp: (String) -> Unit,
    onRefreshApps: () -> Unit
) {
    val context = LocalContext.current
    val adbConnected = adbStatus.state == LocalAdbState.CONNECTED

    // 1. 24x7 Offline Wake Word Service
    WakeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Hearing, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("24x7 \"Hey Goku\" Wake Mic", style = MaterialTheme.typography.titleSmall)
            }
            WakePill(
                label = if (isWakeWordRunning) wakeWordEngineMode else "OFF",
                color = if (isWakeWordRunning) EmeraldOnline else TextSecondary
            )
        }

        Text(
            text = "Detects \"HEY GOKU\" (Lock & Unlock) and \"GOKU STOP\" via 16kHz Foreground Microphone Service.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!hasMicPermission) {
            Button(
                onClick = onRequestMicPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("wake_request_mic_button")
            ) {
                Text("Grant Microphone Permission First")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (userSettings.wakeWordEnabled) "24x7 Listening Enabled" else "24x7 Listening Paused",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "Auto-resumes on phone reboot via BootReceiver.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                Switch(
                    checked = userSettings.wakeWordEnabled,
                    onCheckedChange = onToggleWakeWord,
                    modifier = Modifier.testTag("wake_word_24x7_switch")
                )
            }
        }

        HorizontalDivider(color = HudOutline)

        // Sherpa-ONNX KWS One-Time Model Downloader
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = GokuAmber, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sherpa-ONNX KWS Model (~5.4 MB)", style = MaterialTheme.typography.labelLarge)
            }
            WakePill(
                label = when (kwsModelState.stage) {
                    KwsDownloadStage.READY -> "INSTALLED"
                    KwsDownloadStage.DOWNLOADING -> "${kwsModelState.progressPercent}%"
                    KwsDownloadStage.EXTRACTING -> "UNPACKING"
                    KwsDownloadStage.ERROR -> "RETRY"
                    KwsDownloadStage.NOT_DOWNLOADED -> "FALLBACK"
                },
                color = when (kwsModelState.stage) {
                    KwsDownloadStage.READY -> EmeraldOnline
                    KwsDownloadStage.DOWNLOADING, KwsDownloadStage.EXTRACTING -> ArcCyan
                    KwsDownloadStage.ERROR -> CrimsonEmergency
                    KwsDownloadStage.NOT_DOWNLOADED -> GokuAmber
                }
            )
        }

        Text(
            text = kwsModelState.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (kwsModelState.stage == KwsDownloadStage.DOWNLOADING ||
            kwsModelState.stage == KwsDownloadStage.EXTRACTING
        ) {
            LinearProgressIndicator(
                progress = { (kwsModelState.progressPercent / 100f).coerceIn(0f, 1f) },
                color = ArcCyan,
                trackColor = ObsidianVoid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .testTag("kws_download_progress_bar")
            )
            Text(
                text = String.format("%.1f MB / %.1f MB", kwsModelState.downloadedMb, kwsModelState.totalMb),
                style = MaterialTheme.typography.labelSmall,
                color = ArcCyan
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (kwsModelState.stage == KwsDownloadStage.DOWNLOADING) {
                OutlinedButton(
                    onClick = onCancelKwsDownload,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("kws_cancel_download_button")
                ) {
                    Text("Cancel Download", color = CrimsonEmergency)
                }
            } else {
                Button(
                    onClick = onDownloadKwsModel,
                    enabled = kwsModelState.stage != KwsDownloadStage.EXTRACTING,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("kws_download_model_button")
                ) {
                    Text(if (kwsModelState.hasOnnxFiles) "Re-Download Model" else "Download Offline KWS")
                }
            }
            if (kwsModelState.hasOnnxFiles) {
                OutlinedButton(
                    onClick = onDeleteKwsModel,
                    modifier = Modifier.testTag("kws_delete_model_button")
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete KWS model", modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    // 2. Battery Optimization Unrestricted Prompt
    WakeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BatteryChargingFull, contentDescription = null, tint = EmeraldOnline)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Battery Unrestricted", style = MaterialTheme.typography.titleSmall)
            }
            WakePill(
                label = if (isBatteryUnrestricted) "UNRESTRICTED" else "OPTIMIZED",
                color = if (isBatteryUnrestricted) EmeraldOnline else GokuAmber
            )
        }

        Text(
            text = "Prevents Android from killing the 24x7 Hey Goku mic service in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        @SuppressLint("BatteryLife")
        OutlinedButton(
            onClick = {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("battery_unrestricted_button")
        ) {
            Text(
                if (isBatteryUnrestricted) "Battery Settings (Already Unrestricted)"
                else "Allow Unrestricted Battery"
            )
        }
    }

    // 3. Smart Context & Battery Mode
    WakeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NightlightRound, contentDescription = null, tint = ArcCyan)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Smart Context & Battery", style = MaterialTheme.typography.titleSmall)
            }
            WakePill(
                label = smartContext.activeProfileBadge,
                color = when {
                    smartContext.isLowBatteryMode -> CrimsonEmergency
                    smartContext.isNightQuietMode -> GokuAmber
                    smartContext.isShortFastReplyMode -> ArcCyan
                    else -> EmeraldOnline
                }
            )
        }

        Surface(
            color = ObsidianVoid,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, HudOutline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Night/DND Quiet (11PM–6AM): ${if (smartContext.isNightQuietMode) "ACTIVE (Soft 42% TTS)" else "OFF (Normal)"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (smartContext.isNightQuietMode) GokuAmber else TextSecondary
                )
                Text(
                    text = "Battery: ${smartContext.batteryPercent}% ${if (smartContext.isCharging) "(Charging)" else ""} • Power Save (<15%): ${if (smartContext.isLowBatteryMode) "ACTIVE" else "Standby"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (smartContext.isLowBatteryMode) CrimsonEmergency else TextSecondary
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.FlashOn, contentDescription = null, tint = GokuAmber, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text("Short / Fast Reply Mode", style = MaterialTheme.typography.labelLarge)
                    Text(
                        text = "Ultra-crisp 1-line replies for driving & outdoor hands-free use.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Switch(
                checked = userSettings.shortFastReplyMode,
                onCheckedChange = onToggleShortFastReply,
                modifier = Modifier.testTag("short_fast_reply_switch")
            )
        }
    }

    // 4. Deep Focus Shield ("Goku, kaam mode ON")
    WakeCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shield, contentDescription = null, tint = GokuAmber)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Deep Focus Shield (Kaam Mode)", style = MaterialTheme.typography.titleSmall)
            }
            WakePill(
                label = if (focusShieldState.isActive) "SHIELD ON" else "OFF",
                color = if (focusShieldState.isActive) GokuAmber else TextSecondary
            )
        }

        Text(
            text = "Say \"Goku, kaam mode ON\" / \"Focus mode OFF\". Auto-mutes social/game notifications while letting phone calls through.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (focusShieldState.isActive) "Kaam Mode ACTIVE" else "Kaam Mode Inactive",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (focusShieldState.isActive) GokuAmber else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Muted: ${focusShieldState.mutedNotificationsCount} • ADB Suspended: ${focusShieldState.suspendedViaAdbCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            Switch(
                checked = focusShieldState.isActive,
                onCheckedChange = onToggleFocusShield,
                modifier = Modifier.testTag("focus_shield_master_switch")
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Also Suspend Apps via Local ADB", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = if (adbConnected) "ADB ready: runs pm suspend / unsuspend cleanly."
                    else "Connect Local ADB 127.0.0.1:5555 to enable app suspension.",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (adbConnected) EmeraldOnline else TextSecondary
                )
            }
            Switch(
                checked = focusShieldState.useAdbSuspend,
                onCheckedChange = onToggleFocusUseAdb,
                modifier = Modifier.testTag("focus_adb_suspend_switch")
            )
        }

        HorizontalDivider(color = HudOutline)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Blocked Distraction Apps (${focusShieldState.blockedPackages.size})",
                style = MaterialTheme.typography.labelMedium,
                color = ArcCyan
            )
            OutlinedButton(
                onClick = onRefreshApps,
                enabled = !isLoadingApps,
                modifier = Modifier.testTag("focus_refresh_apps_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isLoadingApps) "Loading…" else "Scan Apps")
            }
        }

        // Show selectable chips for installed apps + preset distraction packages
        val chipPackages: List<Pair<String, String>> = buildList {
            if (installedApps.isNotEmpty()) {
                installedApps.take(18).forEach { add(it.label to it.packageName) }
            }
            val alreadyIncluded = map { it.second }.toSet()
            focusShieldState.blockedPackages.forEach { pkg ->
                if (pkg !in alreadyIncluded) {
                    val shortLabel = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                    add(shortLabel to pkg)
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            chipPackages.forEach { (label, pkg) ->
                val selected = pkg in focusShieldState.blockedPackages
                FilterChip(
                    selected = selected,
                    onClick = { onToggleFocusApp(pkg) },
                    label = {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.DoNotDisturbOn,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    } else null,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = GokuAmber.copy(alpha = 0.2f),
                        selectedLabelColor = GokuAmber,
                        selectedLeadingIconColor = GokuAmber
                    )
                )
            }
        }

        if (focusShieldState.isActive) {
            Button(
                onClick = { onToggleFocusShield(false) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrimsonEmergency,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("focus_shield_turn_off_button")
            ) {
                Text("Turn Focus Shield OFF & Restore Apps")
            }
        }
    }
}

@Composable
private fun WakeCard(content: @Composable () -> Unit) {
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
private fun WakePill(label: String, color: Color) {
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
