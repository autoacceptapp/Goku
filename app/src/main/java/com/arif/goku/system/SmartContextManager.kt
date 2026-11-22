package com.arif.goku.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.arif.goku.data.SecurePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

data class SmartContextState(
    val hourOfDay: Int = 12,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isDndActive: Boolean = false,
    val isNightQuietMode: Boolean = false,
    val isLowBatteryMode: Boolean = false,
    val isShortFastReplyMode: Boolean = false,
    val recommendedTtsVolume: Float = 1.0f,
    val recommendedSpeechRate: Float = 1.0f,
    val wakeDutyCyclePowerSave: Boolean = false,
    val dimVisualsForNight: Boolean = false,
    val activeProfileBadge: String = "NORMAL"
) {
    fun toGeminiStyleDirective(): String {
        val rules = mutableListOf<String>()
        if (isShortFastReplyMode) {
            rules.add("ULTRA-SHORT FAST REPLY MODE ON: Answer in max 1 crisp sentence (under 14 words).")
        }
        if (isNightQuietMode) {
            rules.add("NIGHT/DND QUIET MODE ON: Keep tone very gentle, calm, and brief.")
        }
        if (isLowBatteryMode) {
            rules.add("LOW BATTERY (<15%): Keep responses concise to save power.")
        }
        return rules.joinToString(" ")
    }
}

/**
 * Phase 3 Smart Context & Battery Mode Manager.
 *
 * Monitors:
 * - Time of day (23:00–06:00) & Android DND state -> Soft low-volume TTS, slower calm speech rate,
 *   dimmed visuals (no bright screen flashes).
 * - Battery level (< 15% and discharging) -> Pauses heavy continuous background polling, switches
 *   WakeWordService to power-saving duty cycle, and alerts user once in Hindi.
 * - Short/Fast Reply Mode -> Ultra-short voice replies for driving or outdoor use.
 */
class SmartContextManager(
    private val context: Context,
    private val securePrefs: SecurePrefs
) {
    private val appContext = context.applicationContext
    private var lowBatteryAlertSentThisCycle = false

    private val _state = MutableStateFlow(SmartContextState())
    val state: StateFlow<SmartContextState> = _state.asStateFlow()

    /**
     * Refreshes time-of-day, DND, and battery telemetry.
     * Returns an optional one-time Hindi alert message if the phone just entered Low Battery Mode (<15%).
     */
    fun refresh(): String? {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (batteryPct, charging) = readBatteryStatus()
        val dndActive = readDndActive()
        val shortMode = securePrefs.isShortFastReplyMode()

        val computed = evaluateProfile(
            hourOfDay = hour,
            batteryPercent = batteryPct,
            isCharging = charging,
            isDndActive = dndActive,
            shortFastReply = shortMode
        )
        _state.value = computed

        if (!computed.isLowBatteryMode) {
            lowBatteryAlertSentThisCycle = false
            return null
        }

        if (!lowBatteryAlertSentThisCycle) {
            lowBatteryAlertSentThisCycle = true
            return "Battery ${computed.batteryPercent} percent bachi hai. Main Power Saving mode on kar raha hoon."
        }
        return null
    }

    private fun readBatteryStatus(): Pair<Int, Boolean> {
        return try {
            val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = when {
                level >= 0 && scale > 0 -> ((level * 100f) / scale).toInt().coerceIn(0, 100)
                bm != null -> bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
                else -> 100
            }
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct to charging
        } catch (_: Exception) {
            100 to false
        }
    }

    private fun readDndActive(): Boolean {
        return try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            val filter = nm.currentInterruptionFilter
            filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
                filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val LOW_BATTERY_THRESHOLD = 15

        fun evaluateProfile(
            hourOfDay: Int,
            batteryPercent: Int,
            isCharging: Boolean,
            isDndActive: Boolean,
            shortFastReply: Boolean
        ): SmartContextState {
            val isNightHours = hourOfDay >= 23 || hourOfDay < 6
            val nightQuiet = isNightHours || isDndActive
            val lowBattery = batteryPercent in 1 until LOW_BATTERY_THRESHOLD && !isCharging

            val ttsVolume = when {
                nightQuiet -> 0.42f
                else -> 1.0f
            }
            val speechRate = when {
                nightQuiet -> 0.88f
                shortFastReply -> 1.12f
                else -> 1.0f
            }

            val badge = when {
                lowBattery -> "POWER SAVE ($batteryPercent%)"
                nightQuiet -> if (isDndActive) "DND QUIET" else "NIGHT QUIET"
                shortFastReply -> "FAST REPLY"
                else -> "ADAPTIVE ($batteryPercent%)"
            }

            return SmartContextState(
                hourOfDay = hourOfDay,
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                isDndActive = isDndActive,
                isNightQuietMode = nightQuiet,
                isLowBatteryMode = lowBattery,
                isShortFastReplyMode = shortFastReply,
                recommendedTtsVolume = ttsVolume,
                recommendedSpeechRate = speechRate,
                wakeDutyCyclePowerSave = lowBattery,
                dimVisualsForNight = nightQuiet,
                activeProfileBadge = badge
            )
        }
    }
}
