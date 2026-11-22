package com.arif.goku.habits

import android.content.Context
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.HabitActionType
import com.arif.goku.data.db.HabitDao
import com.arif.goku.data.db.HabitEventEntity
import com.arif.goku.overlay.OverlayBridge
import com.arif.goku.overlay.OverlayHabitSuggestion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min

data class PredictedHabitSuggestion(
    val id: String,
    val actionType: HabitActionType,
    val target: String,
    val displayLabel: String,
    val matchCount: Int,
    val typicalTimeLabel: String,
    val promptHindi: String
)

/**
 * Phase 6 Habit Predictor Engine.
 *
 * Passively logs app launches (from `GokuAccessibilityService`) and repeated voice commands into
 * Room `habit_events`, analyzes recurring time-window patterns (3+ occurrences within ±20 minutes
 * of the current time of day), and proactively surfaces a non-intrusive Floating Orb suggestion
 * or soft Hindi prompt (*"Samay ho gaya hai, kya main [Task/App] chalu kar doon?"*) with 1-tap
 * **Run** or **Ignore**.
 */
class HabitPredictor(
    private val context: Context,
    private val habitDao: HabitDao,
    private val securePrefs: SecurePrefs
) {
    private val appContext = context.applicationContext
    private val lastLoggedTargetMs = ConcurrentHashMap<String, Long>()
    private val snoozedUntilMs = ConcurrentHashMap<String, Long>()

    private val _activeSuggestion = MutableStateFlow<PredictedHabitSuggestion?>(null)
    val activeSuggestion: StateFlow<PredictedHabitSuggestion?> = _activeSuggestion.asStateFlow()

    private val _allPredictedHabits = MutableStateFlow<List<PredictedHabitSuggestion>>(emptyList())
    val allPredictedHabits: StateFlow<List<PredictedHabitSuggestion>> = _allPredictedHabits.asStateFlow()

    /**
     * Passively records an app foreground transition observed by `GokuAccessibilityService`.
     */
    suspend fun logAppLaunch(
        packageName: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!securePrefs.isHabitPredictorEnabled()) return false
        val pkg = packageName.trim()
        if (pkg.isEmpty() || isIgnoredPackage(pkg, appContext.packageName)) return false

        val key = "${HabitActionType.APP_LAUNCH.name}|$pkg"
        val last = lastLoggedTargetMs[key] ?: 0L
        if (timestampMs - last < APP_LOG_DEBOUNCE_MS) return false
        lastLoggedTargetMs[key] = timestampMs

        val label = resolveAppLabel(pkg)
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        habitDao.insertHabitEvent(
            HabitEventEntity(
                actionType = HabitActionType.APP_LAUNCH.name,
                targetPackageOrCommand = pkg,
                displayLabel = label,
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                minuteBucket = cal.get(Calendar.MINUTE),
                dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                timestamp = timestampMs
            )
        )
        return true
    }

    /**
     * Passively records a meaningful repeated voice/text command.
     */
    suspend fun logVoiceCommand(
        rawCommand: String,
        timestampMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!securePrefs.isHabitPredictorEnabled()) return false
        val normalized = normalizeCommand(rawCommand)
        if (normalized.length < 5) return false

        val key = "${HabitActionType.VOICE_COMMAND.name}|$normalized"
        val last = lastLoggedTargetMs[key] ?: 0L
        if (timestampMs - last < COMMAND_LOG_DEBOUNCE_MS) return false
        lastLoggedTargetMs[key] = timestampMs

        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val cleanLabel = rawCommand.trim().take(48)
        habitDao.insertHabitEvent(
            HabitEventEntity(
                actionType = HabitActionType.VOICE_COMMAND.name,
                targetPackageOrCommand = normalized,
                displayLabel = cleanLabel,
                hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                minuteBucket = cal.get(Calendar.MINUTE),
                dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
                timestamp = timestampMs
            )
        )
        return true
    }

    /**
     * Evaluates recurring habits matching the current time of day (±20 minutes, 3+ occurrences)
     * and updates both Compose UI flows and the Floating Orb non-intrusive suggestion card.
     */
    suspend fun evaluatePredictions(
        nowMillis: Long = System.currentTimeMillis(),
        publishToOverlay: Boolean = true
    ): PredictedHabitSuggestion? {
        if (!securePrefs.isHabitPredictorEnabled()) {
            _activeSuggestion.value = null
            if (publishToOverlay) OverlayBridge.dismissHabitSuggestion()
            return null
        }

        val events = habitDao.getRecentEventsSnapshot(limit = 600)
        val discovered = computePredictionsForTime(
            events = events,
            nowMillis = nowMillis,
            windowMinutes = DEFAULT_WINDOW_MINUTES,
            minOccurrences = DEFAULT_MIN_OCCURRENCES
        )
        _allPredictedHabits.value = discovered

        val topActionable = discovered.firstOrNull { suggestion ->
            val snoozeUntil = snoozedUntilMs[suggestion.id] ?: 0L
            nowMillis >= snoozeUntil
        }

        _activeSuggestion.value = topActionable
        if (publishToOverlay) {
            if (topActionable != null) {
                OverlayBridge.showHabitSuggestion(
                    OverlayHabitSuggestion(
                        id = topActionable.id,
                        displayLabel = topActionable.displayLabel,
                        promptHindi = topActionable.promptHindi,
                        actionType = topActionable.actionType.name,
                        target = topActionable.target
                    )
                )
            } else {
                OverlayBridge.dismissHabitSuggestion()
            }
        }
        return topActionable
    }

    fun dismissSuggestion(suggestionId: String, nowMillis: Long = System.currentTimeMillis()) {
        snoozedUntilMs[suggestionId] = nowMillis + SNOOZE_DURATION_MS
        if (_activeSuggestion.value?.id == suggestionId) {
            _activeSuggestion.value = null
        }
        OverlayBridge.dismissHabitSuggestion()
    }

    fun markExecuted(suggestionId: String, nowMillis: Long = System.currentTimeMillis()) {
        snoozedUntilMs[suggestionId] = nowMillis + SNOOZE_DURATION_MS
        if (_activeSuggestion.value?.id == suggestionId) {
            _activeSuggestion.value = null
        }
        OverlayBridge.dismissHabitSuggestion()
    }

    suspend fun clearAllHabits() {
        habitDao.clearAll()
        snoozedUntilMs.clear()
        _activeSuggestion.value = null
        _allPredictedHabits.value = emptyList()
        OverlayBridge.dismissHabitSuggestion()
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val pm = appContext.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString()?.trim().takeIf { !it.isNullOrBlank() }
                ?: packageName.substringAfterLast('.')
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MINUTES = 20
        const val DEFAULT_MIN_OCCURRENCES = 3
        private const val APP_LOG_DEBOUNCE_MS = 180_000L       // 3 minutes
        private const val COMMAND_LOG_DEBOUNCE_MS = 60_000L    // 1 minute
        private const val SNOOZE_DURATION_MS = 4 * 60 * 60 * 1000L // 4 hours

        private val IGNORED_PACKAGE_FRAGMENTS = listOf(
            "launcher",
            "systemui",
            "inputmethod",
            "keyboard",
            "gboard",
            "permissioncontroller",
            "packageinstaller"
        )

        fun isIgnoredPackage(packageName: String, selfPackage: String): Boolean {
            val lower = packageName.lowercase(Locale.US)
            if (lower == selfPackage.lowercase(Locale.US) || lower == "android") return true
            return IGNORED_PACKAGE_FRAGMENTS.any { lower.contains(it) }
        }

        fun normalizeCommand(raw: String): String {
            return raw.trim()
                .lowercase(Locale.US)
                .replace(Regex("^(?:hey\\s+)?goku[,\\s]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Circular distance in minutes between two time-of-day values (handles 23:55 vs 00:05!).
         */
        fun circularMinuteDistance(minA: Int, minB: Int): Int {
            val diff = abs(minA - minB)
            return min(diff, 1440 - diff)
        }

        /**
         * Pure, unit-testable HabitPredictor pattern matcher:
         * finds all apps/commands executed [minOccurrences]+ times within ±[windowMinutes]
         * of [nowMillis]'s time-of-day.
         */
        fun computePredictionsForTime(
            events: List<HabitEventEntity>,
            nowMillis: Long,
            windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
            minOccurrences: Int = DEFAULT_MIN_OCCURRENCES
        ): List<PredictedHabitSuggestion> {
            if (events.isEmpty()) return emptyList()

            val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
            val nowTotalMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val grouped = events.groupBy { "${it.actionType}|${it.targetPackageOrCommand}" }
            val results = mutableListOf<PredictedHabitSuggestion>()

            for ((key, groupEvents) in grouped) {
                val matching = groupEvents.filter { ev ->
                    val evTotalMin = ev.hourOfDay * 60 + ev.minuteBucket
                    circularMinuteDistance(evTotalMin, nowTotalMin) <= windowMinutes
                }
                if (matching.size >= minOccurrences) {
                    val latest = matching.maxByOrNull { it.timestamp } ?: continue
                    val actionType = HabitActionType.fromStored(latest.actionType)
                    val label = latest.displayLabel.ifBlank { latest.targetPackageOrCommand }
                    val timeLabel = String.format(Locale.US, "%02d:%02d", latest.hourOfDay, latest.minuteBucket)
                    results.add(
                        PredictedHabitSuggestion(
                            id = key,
                            actionType = actionType,
                            target = latest.targetPackageOrCommand,
                            displayLabel = label,
                            matchCount = matching.size,
                            typicalTimeLabel = timeLabel,
                            promptHindi = "Samay ho gaya hai, kya main $label chalu kar doon?"
                        )
                    )
                }
            }

            return results.sortedByDescending { it.matchCount }
        }
    }
}
