package com.arif.goku.skills

import android.view.accessibility.AccessibilityEvent
import com.arif.goku.accessibility.AccessibilityScreenSignal
import com.arif.goku.brain.BrainRouter
import com.arif.goku.data.db.SkillDao
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.data.db.SkillMode
import com.arif.goku.data.db.SkillStep
import com.arif.goku.data.db.SkillStepCodec
import com.arif.goku.data.db.SkillStepType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

enum class RawTraceAction {
    OPEN_APP,
    CLICK,
    TYPE_TEXT,
    SCROLL,
    BACK
}

data class RecordedTraceEvent(
    val action: RawTraceAction,
    val packageName: String,
    val label: String = "",
    val viewId: String = "",
    val typedText: String = "",
    val x: Int? = null,
    val y: Int? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

data class TeachSessionState(
    val isRecording: Boolean = false,
    val isSynthesizing: Boolean = false,
    val hintName: String = "",
    val recordedEventCount: Int = 0,
    val lastCapturedAction: String = "",
    val statusHindi: String = "Teach-by-Example standby."
)

data class CleanedTraceResult(
    val primaryPackage: String,
    val cleanedEvents: List<RecordedTraceEvent>,
    val removedWasteCount: Int,
    val upgradedSteps: List<SkillStep>,
    val inferredMode: SkillMode,
    val optimizationNote: String
)

/**
 * Phase 7 Smart Teach-by-Example Recorder & Trace Optimizer.
 *
 * 1) Records live `GokuAccessibilityService` events (app switches, clicks, view IDs, typed text, scrolls)
 *    when triggered via voice (*"Goku, main jo kar raha hoon woh seekh lo"* -> *"Seekh liya"*)
 *    or the Floating Overlay **TEACH** button.
 * 2) Strips wasted/mistake steps deterministically (accidental taps reversed by Back, duplicate clicks,
 *    intermediate keystrokes, redundant scrolls, Goku's own UI events).
 * 3) Upgrades slow UI sequences into direct Android Intents/Deep Links or Local ADB commands wherever
 *    possible — plus optionally refines with `BrainRouter` so Goku runs faster than the user!
 */
class TeachByExampleRecorder(
    private val selfPackageName: String,
    private val skillDao: SkillDao,
    private val brainRouter: BrainRouter
) {
    private val rawTraceLock = Any()
    private val rawTrace = mutableListOf<RecordedTraceEvent>()
    private var lastForegroundPkg: String = ""

    private val _state = MutableStateFlow(TeachSessionState())
    val state: StateFlow<TeachSessionState> = _state.asStateFlow()

    fun startRecording(hintName: String = "") {
        synchronized(rawTraceLock) {
            rawTrace.clear()
            lastForegroundPkg = ""
        }
        _state.value = TeachSessionState(
            isRecording = true,
            isSynthesizing = false,
            hintName = hintName.trim(),
            recordedEventCount = 0,
            lastCapturedAction = "Waiting for your taps…",
            statusHindi = "Teach Mode ON! Aap apna kaam kijiye, main dekh kar seekh raha hoon. Poora hone par 'Seekh liya' bolein ya DONE dabayein."
        )
    }

    fun cancelRecording(reason: String = "Cancelled") {
        synchronized(rawTraceLock) {
            rawTrace.clear()
            lastForegroundPkg = ""
        }
        _state.value = TeachSessionState(
            isRecording = false,
            isSynthesizing = false,
            statusHindi = "Teach Mode band kiya gaya ($reason)."
        )
    }

    /**
     * Ingests a live `AccessibilityScreenSignal` while Teach Mode is active.
     */
    fun onAccessibilitySignal(signal: AccessibilityScreenSignal) {
        if (!_state.value.isRecording) return
        val pkg = signal.packageName.trim()
        if (pkg.isEmpty() || isIgnoredPackage(pkg, selfPackageName)) return

        val captured: RecordedTraceEvent? = when (signal.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != lastForegroundPkg) {
                    lastForegroundPkg = pkg
                    RecordedTraceEvent(
                        action = RawTraceAction.OPEN_APP,
                        packageName = pkg,
                        label = pkg.substringAfterLast('.'),
                        timestampMs = signal.timestampMs
                    )
                } else {
                    null
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = signal.clickedOrFocusedText.trim()
                val isBackButton = label.equals("Back", ignoreCase = true) ||
                    label.equals("Navigate up", ignoreCase = true) ||
                    signal.viewId.contains("back", ignoreCase = true)
                RecordedTraceEvent(
                    action = if (isBackButton) RawTraceAction.BACK else RawTraceAction.CLICK,
                    packageName = pkg,
                    label = label,
                    viewId = signal.viewId,
                    x = signal.centerX,
                    y = signal.centerY,
                    timestampMs = signal.timestampMs
                )
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = signal.typedText.trim()
                if (text.isNotEmpty()) {
                    RecordedTraceEvent(
                        action = RawTraceAction.TYPE_TEXT,
                        packageName = pkg,
                        label = signal.clickedOrFocusedText.trim(),
                        viewId = signal.viewId,
                        typedText = text,
                        timestampMs = signal.timestampMs
                    )
                } else {
                    null
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                RecordedTraceEvent(
                    action = RawTraceAction.SCROLL,
                    packageName = pkg,
                    label = "Scroll",
                    timestampMs = signal.timestampMs
                )
            }

            else -> null
        }

        if (captured != null) {
            val newSize = synchronized(rawTraceLock) {
                rawTrace.add(captured)
                rawTrace.size
            }
            val summaryLabel = when (captured.action) {
                RawTraceAction.OPEN_APP -> "Opened ${captured.packageName.substringAfterLast('.')}"
                RawTraceAction.CLICK -> "Clicked '${captured.label.ifBlank { captured.viewId.substringAfterLast('/') }}'"
                RawTraceAction.TYPE_TEXT -> "Typed '${captured.typedText.take(24)}'"
                RawTraceAction.SCROLL -> "Scrolled"
                RawTraceAction.BACK -> "Pressed Back"
            }
            _state.update {
                it.copy(
                    recordedEventCount = newSize,
                    lastCapturedAction = summaryLabel,
                    statusHindi = "Recording ($newSize steps): $summaryLabel"
                )
            }
        }
    }

    /**
     * Stops recording, strips wasted/mistake actions, upgrades to fast Intents / ADB where possible,
     * refines with `BrainRouter` if online, and persists the learned `SkillEntity` to Room DB.
     */
    suspend fun finishAndSaveSkill(
        optionalCustomName: String = ""
    ): Result<Pair<SkillEntity, CleanedTraceResult>> = withContext(Dispatchers.IO) {
        val snapshot = synchronized(rawTraceLock) {
            val copy = rawTrace.toList()
            rawTrace.clear()
            lastForegroundPkg = ""
            copy
        }
        val hintName = optionalCustomName.trim().ifBlank { _state.value.hintName }

        if (snapshot.isEmpty()) {
            _state.value = TeachSessionState(
                isRecording = false,
                isSynthesizing = false,
                statusHindi = "Koi bhi tap ya app action record nahi hua. Pehle Accessibility ON karein aur steps dikhayein."
            )
            return@withContext Result.failure(IllegalStateException("No events recorded during Teach Mode."))
        }

        _state.update {
            it.copy(
                isRecording = false,
                isSynthesizing = true,
                statusHindi = "${snapshot.size} raw steps clean aur optimize kar raha hoon…"
            )
        }

        val cleaned = optimizeTrace(snapshot, selfPackageName = selfPackageName)

        // Try BrainRouter optimization (extract clean Hinglish name, trigger phrases, and even faster ADB/Intent steps)
        val brainCandidate = tryOptimizeWithBrain(cleaned, hintName)

        val finalSkillDraft = brainCandidate ?: buildDeterministicSkill(cleaned, hintName)
        val savedId = skillDao.insertSkill(finalSkillDraft)
        val persisted = skillDao.getById(savedId) ?: finalSkillDraft.copy(id = savedId)

        _state.value = TeachSessionState(
            isRecording = false,
            isSynthesizing = false,
            statusHindi = "Seekh liya! '${persisted.name}' (${persisted.mode}) save ho gaya — ${cleaned.removedWasteCount} waste steps hata diye gaye."
        )

        Result.success(persisted to cleaned)
    }

    private suspend fun tryOptimizeWithBrain(
        cleaned: CleanedTraceResult,
        hintName: String
    ): SkillEntity? {
        val traceLines = cleaned.cleanedEvents.mapIndexed { idx, ev ->
            "${idx + 1}. [${ev.action.name}] pkg=${ev.packageName} label='${ev.label}' viewId='${ev.viewId}' typed='${ev.typedText}' xy=(${ev.x},${ev.y})"
        }.joinToString("\n")

        val prompt = """
            The user just demonstrated a workflow on their Android phone in Teach-by-Example mode.
            Optional user hint: "${hintName.ifBlank { "(none)" }}"
            Cleaned trace (${cleaned.removedWasteCount} mistake/redundant steps already stripped):
            $traceLines

            Your job:
            1. Infer the true goal/intent and create a short clear name + natural English/Hinglish trigger phrases.
            2. Upgrade slow UI steps into direct Android `INTENT_URI` deep links or `ADB_SHELL` commands (`127.0.0.1:5555`) wherever faster & reliable!
            3. Keep only essential steps. Allowed step types: ADB_SHELL, INTENT_URI, OPEN_APP, CLICK_TEXT, TYPE_TEXT, TAP_XY, SCROLL, WAIT, SPEAK.

            Output STRICT JSON ONLY:
            {
              "name": "Skill Name",
              "triggerPhrases": "trigger 1, trigger 2",
              "targetPackage": "${cleaned.primaryPackage}",
              "mode": "ADB_FAST|INTENT|UI_TAP",
              "steps": [...]
            }
        """.trimIndent()

        val raw = brainRouter.askMultimodalOrLocal(
            prompt = prompt,
            imageBytes = null,
            systemInstruction = "You are GokuAgent's Smart Teach-by-Example Optimizer. Output strict JSON only.",
            includeGoogleSearchTool = false
        ).getOrNull() ?: return null

        val parsed = SkillEngine.parseSynthesizedSkillJson(
            raw = raw,
            fallbackGoal = hintName.ifBlank { "Taught Skill (${cleaned.primaryPackage.substringAfterLast('.')})" }
        ) ?: return null

        // If our deterministic fast-path already produced INTENT/ADB_FAST and Brain regressed to UI_TAP, keep the faster upgrade!
        val brainMode = parsed.modeEnum()
        return if (cleaned.inferredMode != SkillMode.UI_TAP && brainMode == SkillMode.UI_TAP) {
            parsed.copy(
                stepsJson = SkillStepCodec.toJson(cleaned.upgradedSteps),
                mode = cleaned.inferredMode.name,
                evolutionScore = SkillEngine.computeEvolutionScore(0, 0, 0L, cleaned.inferredMode)
            )
        } else {
            parsed
        }
    }

    private fun buildDeterministicSkill(cleaned: CleanedTraceResult, hintName: String): SkillEntity {
        val appShort = cleaned.primaryPackage.substringAfterLast('.')
            .replaceFirstChar { it.uppercase() }
            .ifBlank { "Custom Workflow" }
        val name = hintName.ifBlank { "$appShort Quick Skill" }
        val triggers = listOfNotNull(
            hintName.takeIf { it.isNotBlank() },
            name,
            "$appShort workflow"
        ).distinct().joinToString(", ")

        return SkillEntity(
            name = name,
            triggerPhrases = triggers,
            targetPackage = cleaned.primaryPackage,
            stepsJson = SkillStepCodec.toJson(cleaned.upgradedSteps),
            mode = cleaned.inferredMode.name,
            successCount = 0,
            failCount = 0,
            avgDurationMs = 0L,
            evolutionScore = SkillEngine.computeEvolutionScore(0, 0, 0L, cleaned.inferredMode),
            updatedAt = System.currentTimeMillis()
        )
    }

    companion object {
        private val IGNORED_PACKAGES = listOf(
            "systemui",
            "launcher",
            "inputmethod",
            "keyboard",
            "gboard"
        )

        fun isIgnoredPackage(pkg: String, selfPackage: String): Boolean {
            val lower = pkg.lowercase(Locale.US)
            if (lower == selfPackage.lowercase(Locale.US) || lower == "android") return true
            return IGNORED_PACKAGES.any { lower.contains(it) }
        }

        /**
         * Pure, unit-testable Smart Teach-by-Example pipeline:
         * 1) Strips wasted/mistake steps:
         *    - Ignored/launcher/Goku events
         *    - Accidental tap immediately followed by BACK (cancels out both!)
         *    - Consecutive duplicate clicks on the same label/viewId within 1.5s
         *    - Intermediate typing keystrokes on the same field (keeps only final typed text!)
         *    - Redundant back-to-back scrolls (collapses bursts of 3+ scrolls to at most 1)
         * 2) Upgrades slow UI traces into fast `INTENT_URI` or `ADB_SHELL` steps wherever possible.
         */
        fun optimizeTrace(
            rawEvents: List<RecordedTraceEvent>,
            selfPackageName: String = "com.arif.goku"
        ): CleanedTraceResult {
            val filtered = rawEvents.filterNot { isIgnoredPackage(it.packageName, selfPackageName) }
            val stack = mutableListOf<RecordedTraceEvent>()

            for (ev in filtered) {
                when (ev.action) {
                    RawTraceAction.BACK -> {
                        // If the previous event was a recent CLICK or OPEN_APP mistake, cancel it out!
                        val prev = stack.lastOrNull()
                        if (prev != null &&
                            (prev.action == RawTraceAction.CLICK || prev.action == RawTraceAction.OPEN_APP) &&
                            (ev.timestampMs - prev.timestampMs) <= 8_000L
                        ) {
                            stack.removeAt(stack.lastIndex)
                        } else {
                            // Drop stray leading/trailing back presses
                            if (stack.isNotEmpty() && stack.last().action != RawTraceAction.BACK) {
                                stack.add(ev)
                            }
                        }
                    }

                    RawTraceAction.TYPE_TEXT -> {
                        // Collapse incremental typing keystrokes ("y", "yo", "you", "youtube") into final text
                        val prev = stack.lastOrNull()
                        if (prev != null &&
                            prev.action == RawTraceAction.TYPE_TEXT &&
                            prev.packageName == ev.packageName &&
                            (prev.viewId == ev.viewId || prev.label == ev.label)
                        ) {
                            stack[stack.lastIndex] = ev
                        } else {
                            stack.add(ev)
                        }
                    }

                    RawTraceAction.CLICK -> {
                        // Deduplicate accidental double-taps on same target within 1.5 seconds
                        val prev = stack.lastOrNull()
                        val isDuplicateTap = prev != null &&
                            prev.action == RawTraceAction.CLICK &&
                            prev.packageName == ev.packageName &&
                            prev.label.equals(ev.label, ignoreCase = true) &&
                            prev.viewId == ev.viewId &&
                            (ev.timestampMs - prev.timestampMs) <= 1_500L
                        if (!isDuplicateTap) {
                            stack.add(ev)
                        }
                    }

                    RawTraceAction.SCROLL -> {
                        // Collapse consecutive redundant scrolls into one
                        if (stack.lastOrNull()?.action != RawTraceAction.SCROLL) {
                            stack.add(ev)
                        }
                    }

                    RawTraceAction.OPEN_APP -> {
                        val prev = stack.lastOrNull()
                        if (prev?.action == RawTraceAction.OPEN_APP && prev.packageName == ev.packageName) {
                            // skip duplicate window event
                        } else {
                            stack.add(ev)
                        }
                    }
                }
            }

            // Strip trailing stray BACK or SCROLL events
            while (stack.isNotEmpty() &&
                (stack.last().action == RawTraceAction.BACK || stack.last().action == RawTraceAction.SCROLL)
            ) {
                stack.removeAt(stack.lastIndex)
            }

            val removedWaste = (rawEvents.size - stack.size).coerceAtLeast(0)
            val primaryPkg = stack.firstOrNull { it.packageName.isNotBlank() }?.packageName.orEmpty()

            // Deterministic upgrade from slow UI steps -> direct Android Intents / ADB_SHELL
            val (upgradedSteps, note) = upgradeTraceDeterministically(primaryPkg, stack)
            val inferredMode = SkillStepCodec.inferMode(upgradedSteps)

            return CleanedTraceResult(
                primaryPackage = primaryPkg,
                cleanedEvents = stack,
                removedWasteCount = removedWaste,
                upgradedSteps = upgradedSteps,
                inferredMode = inferredMode,
                optimizationNote = note
            )
        }

        private fun upgradeTraceDeterministically(
            primaryPkg: String,
            events: List<RecordedTraceEvent>
        ): Pair<List<SkillStep>, String> {
            val lowerPkg = primaryPkg.lowercase(Locale.US)
            val typed = events.lastOrNull { it.action == RawTraceAction.TYPE_TEXT }?.typedText.orEmpty()
            val clickedLabels = events.filter { it.action == RawTraceAction.CLICK }.map { it.label.lowercase(Locale.US) }

            // Upgrade 1: Android Settings UI clicks -> instant direct Settings Intent or ADB_FAST
            if (lowerPkg.contains("settings")) {
                when {
                    clickedLabels.any { it.contains("wi-fi") || it.contains("wifi") } -> {
                        return listOf(
                            SkillStep(SkillStepType.INTENT_URI, target = "android.settings.WIFI_SETTINGS")
                        ) to "Upgraded slow Settings UI clicks -> direct WIFI_SETTINGS Intent"
                    }
                    clickedLabels.any { it.contains("bluetooth") } -> {
                        return listOf(
                            SkillStep(SkillStepType.INTENT_URI, target = "android.settings.BLUETOOTH_SETTINGS")
                        ) to "Upgraded Settings UI clicks -> direct BLUETOOTH_SETTINGS Intent"
                    }
                    clickedLabels.any { it.contains("battery") } -> {
                        return listOf(
                            SkillStep(SkillStepType.INTENT_URI, target = "android.intent.action.POWER_USAGE_SUMMARY")
                        ) to "Upgraded Settings UI clicks -> direct Battery Intent"
                    }
                    clickedLabels.any { it.contains("developer") || it.contains("wireless debugging") } -> {
                        return listOf(
                            SkillStep(SkillStepType.INTENT_URI, target = "android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
                        ) to "Upgraded Settings UI clicks -> direct Developer Options Intent"
                    }
                    clickedLabels.any { it.contains("dark") } -> {
                        return listOf(
                            SkillStep(SkillStepType.ADB_SHELL, value = "cmd uimode night yes")
                        ) to "Upgraded Settings UI clicks -> instant ADB_FAST dark mode command"
                    }
                }
            }

            // Upgrade 2: YouTube search UI clicks + typing -> direct YouTube Deep Link Intent!
            if (lowerPkg.contains("youtube") && typed.isNotBlank()) {
                val encoded = URLEncoder.encode(typed, "UTF-8")
                return listOf(
                    SkillStep(
                        type = SkillStepType.INTENT_URI,
                        target = "https://www.youtube.com/results?search_query=$encoded"
                    )
                ) to "Upgraded YouTube UI search -> instant INTENT_URI deep link"
            }

            // Upgrade 3: Google Maps search -> direct geo: Intent!
            if (lowerPkg.contains("apps.maps") && typed.isNotBlank()) {
                val encoded = URLEncoder.encode(typed, "UTF-8")
                return listOf(
                    SkillStep(
                        type = SkillStepType.INTENT_URI,
                        target = "geo:0,0?q=$encoded"
                    )
                ) to "Upgraded Google Maps UI search -> instant geo: Intent"
            }

            // Upgrade 4: Default clean UI steps from sanitized trace
            val steps = buildList {
                for (ev in events) {
                    when (ev.action) {
                        RawTraceAction.OPEN_APP -> add(
                            SkillStep(SkillStepType.OPEN_APP, target = ev.packageName, delayMs = 700L)
                        )
                        RawTraceAction.CLICK -> {
                            if (ev.label.isNotBlank()) {
                                add(SkillStep(SkillStepType.CLICK_TEXT, target = ev.label, delayMs = 450L))
                            } else if (ev.x != null && ev.y != null) {
                                add(SkillStep(SkillStepType.TAP_XY, x = ev.x, y = ev.y, delayMs = 450L))
                            }
                        }
                        RawTraceAction.TYPE_TEXT -> add(
                            SkillStep(SkillStepType.TYPE_TEXT, target = ev.label, value = ev.typedText, delayMs = 400L)
                        )
                        RawTraceAction.SCROLL -> add(
                            SkillStep(SkillStepType.SCROLL, value = "down", delayMs = 350L)
                        )
                        RawTraceAction.BACK -> {
                            // Already stripped accidental backs; only add if genuinely needed
                        }
                    }
                }
            }

            return steps to "Cleaned ${events.size} essential steps (waste stripped)"
        }
    }
}
