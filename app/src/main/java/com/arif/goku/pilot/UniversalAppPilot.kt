package com.arif.goku.pilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.arif.goku.accessibility.GokuAccessibilityService
import com.arif.goku.action.ActionExecutor
import com.arif.goku.action.GokuAction
import com.arif.goku.action.GokuActionType
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.brain.BrainRouter
import com.arif.goku.safety.ScreenMistakeBlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

enum class PilotObservationMode(val badge: String) {
    UI_TREE("UI Tree"),
    ADB_UIAUTOMATOR("ADB UI Dump"),
    SCREEN_VISION("Screen Vision (JPEG)"),
    UNAVAILABLE("Unavailable")
}

data class PilotObservation(
    val mode: PilotObservationMode,
    val packageName: String,
    val uiSummaryText: String,
    val screenSignature: String,
    val screenshotJpegBytes: ByteArray? = null,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400
)

enum class PilotStepActionType {
    CLICK_TEXT,
    TAP_XY,
    TYPE_TEXT,
    SCROLL,
    BACK,
    HOME,
    OPEN_APP,
    WAIT,
    DONE
}

data class PilotStepDecision(
    val action: PilotStepActionType,
    val target: String = "",
    val text: String = "",
    val x: Int? = null,
    val y: Int? = null,
    val forward: Boolean = true,
    val reason: String = ""
) {
    fun fingerprint(): String = "${action.name}|${target.lowercase()}|${text.take(24)}|$x|$y|$forward"
}

data class PilotDispatchResult(
    val success: Boolean,
    val detail: String
)

data class PilotState(
    val isRunning: Boolean = false,
    val goal: String = "",
    val currentStep: Int = 0,
    val maxSteps: Int = UniversalAppPilot.DEFAULT_MAX_STEPS,
    val lastObservationMode: PilotObservationMode = PilotObservationMode.UI_TREE,
    val lastStatusHindi: String = "Universal App Pilot standby.",
    val stepLogs: List<String> = emptyList()
)

sealed class PilotRunOutcome {
    data class Completed(val stepsUsed: Int, val summaryHindi: String) : PilotRunOutcome()
    data class StoppedByUser(val stepsUsed: Int, val summaryHindi: String) : PilotRunOutcome()
    data class StepLimitReached(val maxSteps: Int, val summaryHindi: String) : PilotRunOutcome()
    data class LoopDetected(val stepsUsed: Int, val summaryHindi: String) : PilotRunOutcome()
    data class RiskBlocked(val stepsUsed: Int, val summaryHindi: String) : PilotRunOutcome()
    data class Failed(val stepsUsed: Int, val summaryHindi: String) : PilotRunOutcome()
}

interface PilotObserver {
    suspend fun observe(allowVisionFallback: Boolean): PilotObservation
}

interface PilotBrainDecider {
    suspend fun decideNextStep(
        goal: String,
        observation: PilotObservation,
        stepNumber: Int,
        maxSteps: Int,
        previousSteps: List<String>
    ): Result<PilotStepDecision>
}

interface PilotActionDispatcher {
    suspend fun dispatch(decision: PilotStepDecision): PilotDispatchResult
}

/**
 * Phase 5 Universal App Pilot with Screen Vision Fallback.
 *
 * Autonomous **Observe -> Think -> Act -> Verify** loop that can operate ANY Android app
 * without pre-saved skills:
 * 1) **Observe:** Accessibility UI tree -> Local ADB `uiautomator dump` -> Screen Vision JPEG
 *    fallback (`AccessibilityService.takeScreenshot()` on Android 11+ or ADB `screencap`).
 * 2) **Think:** Queries `BrainRouter` for the exact next JSON step (`CLICK_TEXT`, `TAP_XY`,
 *    `TYPE_TEXT`, `SCROLL`, `BACK`, `HOME`, `OPEN_APP`, `WAIT`, `DONE`).
 * 3) **Safety Cap & Guards:** Hard max step limit (12), repeated-action loop detector,
 *    high-risk click blocker check, and instant `"Goku stop"` cancellation.
 */
class UniversalAppPilot(
    private val observer: PilotObserver,
    private val brainDecider: PilotBrainDecider,
    private val dispatcher: PilotActionDispatcher,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    private val stepDelayMs: Long = 950L
) {
    private val cancelled = AtomicBoolean(false)

    private val _state = MutableStateFlow(PilotState(maxSteps = maxSteps))
    val state: StateFlow<PilotState> = _state.asStateFlow()

    fun cancel(reason: String = "Goku Stop") {
        cancelled.set(true)
        _state.update {
            if (it.isRunning) {
                it.copy(
                    isRunning = false,
                    lastStatusHindi = "Pilot turant roka gaya ($reason)."
                )
            } else {
                it
            }
        }
    }

    suspend fun runGoal(
        goal: String,
        allowVisionFallback: Boolean = true,
        onStepUpdate: ((String) -> Unit)? = null
    ): PilotRunOutcome {
        val cleanedGoal = goal.trim()
        if (cleanedGoal.isEmpty()) {
            return PilotRunOutcome.Failed(0, "Pilot ke liye koi clear task ya goal boliye.")
        }

        cancelled.set(false)
        val logs = mutableListOf<String>()
        val recentFingerprints = mutableListOf<String>()

        _state.value = PilotState(
            isRunning = true,
            goal = cleanedGoal,
            currentStep = 0,
            maxSteps = maxSteps,
            lastStatusHindi = "Universal Pilot shuru: $cleanedGoal",
            stepLogs = emptyList()
        )

        var step = 0
        try {
            while (step < maxSteps) {
                if (cancelled.get()) {
                    return finishWith(
                        PilotRunOutcome.StoppedByUser(step, "Goku Stop dabane par Pilot ruk gaya.")
                    )
                }

                step++
                // 1. OBSERVE
                val observation = observer.observe(allowVisionFallback)
                if (cancelled.get()) {
                    return finishWith(
                        PilotRunOutcome.StoppedByUser(step, "Goku Stop dabane par Pilot ruk gaya.")
                    )
                }

                if (observation.mode == PilotObservationMode.UNAVAILABLE) {
                    return finishWith(
                        PilotRunOutcome.Failed(
                            step,
                            "Screen observe nahi ho paayi. Accessibility Service ya Local ADB ON karein."
                        )
                    )
                }

                _state.update {
                    it.copy(
                        currentStep = step,
                        lastObservationMode = observation.mode,
                        lastStatusHindi = "Step $step/$maxSteps (${observation.mode.badge}): Thinking…"
                    )
                }

                // 2. THINK
                val decisionRes = brainDecider.decideNextStep(
                    goal = cleanedGoal,
                    observation = observation,
                    stepNumber = step,
                    maxSteps = maxSteps,
                    previousSteps = logs.takeLast(6)
                )

                if (cancelled.get()) {
                    return finishWith(
                        PilotRunOutcome.StoppedByUser(step, "Goku Stop dabane par Pilot ruk gaya.")
                    )
                }

                val decision = decisionRes.getOrElse { err ->
                    return finishWith(
                        PilotRunOutcome.Failed(
                            step,
                            err.message ?: "Pilot next step decide nahi kar paya."
                        )
                    )
                }

                // 3. DONE CHECK
                if (decision.action == PilotStepActionType.DONE) {
                    val doneMsg = decision.reason.ifBlank { "Task complete ho gaya hai!" }
                    logs.add("Step $step [DONE]: $doneMsg")
                    _state.update { it.copy(stepLogs = logs.toList()) }
                    return finishWith(PilotRunOutcome.Completed(step, doneMsg))
                }

                // 4. LOOP DETECTION (3 identical action+screen fingerprints in a row)
                val combinedKey = "${decision.fingerprint()}::${observation.screenSignature}"
                recentFingerprints.add(combinedKey)
                val repeatedCount = recentFingerprints.takeLast(LOOP_REPEAT_THRESHOLD).count { it == combinedKey }
                if (repeatedCount >= LOOP_REPEAT_THRESHOLD) {
                    val msg = "Same step $LOOP_REPEAT_THRESHOLD baar repeat ho raha tha, loop rok diya hai."
                    logs.add("Step $step [LOOP ABORT]: ${decision.action.name}")
                    _state.update { it.copy(stepLogs = logs.toList()) }
                    return finishWith(PilotRunOutcome.LoopDetected(step, msg))
                }

                // 5. HIGH-RISK SAFETY CHECK (Never blindly click Delete/Uninstall/Pay in Pilot)
                if (decision.action == PilotStepActionType.CLICK_TEXT &&
                    ScreenMistakeBlocker.isHighRiskLabel(decision.target)
                ) {
                    val msg = "Ruko! Pilot ne risky button '${decision.target}' dekha aur suraksha ke liye ruk gaya."
                    logs.add("Step $step [RISK BLOCKED]: ${decision.target}")
                    _state.update { it.copy(stepLogs = logs.toList()) }
                    return finishWith(PilotRunOutcome.RiskBlocked(step, msg))
                }

                // 6. ACT
                val dispatchRes = dispatcher.dispatch(decision)
                val logEntry = "Step $step [${observation.mode.badge}] ${decision.action.name} " +
                    "${decision.target.ifBlank { decision.text }} -> ${if (dispatchRes.success) "OK" else "FAIL"} (${dispatchRes.detail})"
                logs.add(logEntry)
                _state.update {
                    it.copy(
                        stepLogs = logs.toList(),
                        lastStatusHindi = "Step $step/$maxSteps: ${decision.reason.ifBlank { decision.action.name }}"
                    )
                }
                onStepUpdate?.invoke(logEntry)

                // 7. VERIFY DELAY (allow UI transition before next observe)
                if (stepDelayMs > 0L) {
                    delay(stepDelayMs)
                }
            }

            return finishWith(
                PilotRunOutcome.StepLimitReached(
                    maxSteps,
                    "Safety cap ($maxSteps steps) poora ho gaya — task abhi tak finish nahi hua, Pilot rok diya hai."
                )
            )
        } finally {
            _state.update { it.copy(isRunning = false) }
        }
    }

    private fun finishWith(outcome: PilotRunOutcome): PilotRunOutcome {
        val summary = when (outcome) {
            is PilotRunOutcome.Completed -> outcome.summaryHindi
            is PilotRunOutcome.StoppedByUser -> outcome.summaryHindi
            is PilotRunOutcome.StepLimitReached -> outcome.summaryHindi
            is PilotRunOutcome.LoopDetected -> outcome.summaryHindi
            is PilotRunOutcome.RiskBlocked -> outcome.summaryHindi
            is PilotRunOutcome.Failed -> outcome.summaryHindi
        }
        _state.update {
            it.copy(
                isRunning = false,
                lastStatusHindi = summary
            )
        }
        return outcome
    }

    companion object {
        const val DEFAULT_MAX_STEPS = 12
        private const val LOOP_REPEAT_THRESHOLD = 3

        fun create(
            context: Context,
            adbManager: LocalAdbManager,
            brainRouter: BrainRouter,
            actionExecutor: ActionExecutor
        ): UniversalAppPilot {
            return UniversalAppPilot(
                observer = AndroidPilotObserver(context.applicationContext, adbManager),
                brainDecider = BrainRouterPilotDecider(brainRouter),
                dispatcher = ActionExecutorPilotDispatcher(actionExecutor)
            )
        }

        fun isPilotCommand(rawInput: String): Boolean {
            val lower = rawInput.trim().lowercase()
            return lower.startsWith("pilot ") ||
                lower.startsWith("goku pilot") ||
                lower.contains("auto pilot") ||
                lower.contains("autopilot") ||
                lower.contains("khud karo") ||
                lower.contains("automatically") && (lower.contains("open") || lower.contains("khol") || lower.contains("kar"))
        }

        fun extractPilotGoal(rawInput: String): String {
            return rawInput.trim()
                .replace(Regex("^(?:goku[,\\s]+)?(?:auto\\s*pilot|pilot)\\s*[:,-]?\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        fun parsePilotDecisionJson(rawReply: String): PilotStepDecision? {
            val jsonCandidate = extractFirstJsonObject(rawReply) ?: return null
            return try {
                val obj = JSONObject(jsonCandidate)
                val actionRaw = obj.optString("action", "").trim().uppercase()
                val action = PilotStepActionType.entries.firstOrNull { it.name == actionRaw }
                    ?: when (actionRaw) {
                        "TAP", "CLICK_XY" -> PilotStepActionType.TAP_XY
                        "CLICK" -> PilotStepActionType.CLICK_TEXT
                        "TYPE", "INPUT" -> PilotStepActionType.TYPE_TEXT
                        "FINISH", "COMPLETE" -> PilotStepActionType.DONE
                        else -> return null
                    }
                val x = if (obj.has("x") && !obj.isNull("x")) obj.optInt("x") else null
                val y = if (obj.has("y") && !obj.isNull("y")) obj.optInt("y") else null
                PilotStepDecision(
                    action = action,
                    target = obj.optString("target", "").trim(),
                    text = obj.optString("text", "").trim(),
                    x = x,
                    y = y,
                    forward = obj.optBoolean("forward", true),
                    reason = obj.optString("reason", obj.optString("summary", "")).trim()
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun extractFirstJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return raw.substring(start, end + 1)
        }
    }
}

/**
 * Real Android Observer:
 * 1) Accessibility UI Tree + node coordinates.
 * 2) Local ADB `uiautomator dump` if Accessibility tree is unavailable/sparse.
 * 3) Screen Vision JPEG fallback (`AccessibilityService.takeScreenshot()` on Android 11+ or ADB `screencap`).
 */
class AndroidPilotObserver(
    private val appContext: Context,
    private val adbManager: LocalAdbManager
) : PilotObserver {

    override suspend fun observe(allowVisionFallback: Boolean): PilotObservation = withContext(Dispatchers.IO) {
        val dm = appContext.resources.displayMetrics
        val width = dm.widthPixels.coerceAtLeast(360)
        val height = dm.heightPixels.coerceAtLeast(640)

        // 1. Primary: Accessibility UI Tree + Bounds
        val accessNodes = GokuAccessibilityService.readDetailedUiNodes(limit = 50)
        val accessSummary = GokuAccessibilityService.readScreenSummary()
        val pkg = accessSummary?.packageName.orEmpty()
            .ifBlank { GokuAccessibilityService.currentForegroundPackage.value }

        if (accessNodes.size >= 3) {
            val formatted = buildString {
                appendLine("App Package: ${pkg.ifBlank { "unknown" }}")
                accessNodes.forEach { n ->
                    val role = when {
                        n.isEditable -> "INPUT"
                        n.isClickable -> "BTN"
                        else -> "TXT"
                    }
                    val label = n.label.ifBlank { n.hint }
                    appendLine("[$role] \"$label\" @(${n.centerX},${n.centerY})")
                }
            }.trim()
            return@withContext PilotObservation(
                mode = PilotObservationMode.UI_TREE,
                packageName = pkg,
                uiSummaryText = formatted,
                screenSignature = "$pkg:${accessNodes.take(12).joinToString("|") { it.label }}".hashCode().toString(),
                screenWidth = width,
                screenHeight = height
            )
        }

        // 2. Secondary: Local ADB `uiautomator dump` XML node extraction
        if (adbManager.isConnected) {
            val dumpRes = adbManager.executeShellCommand(
                "uiautomator dump /sdcard/goku_ui.xml >/dev/null 2>&1 && cat /sdcard/goku_ui.xml"
            )
            val xml = dumpRes.getOrNull().orEmpty()
            val parsedAdbNodes = parseUiAutomatorXml(xml)
            if (parsedAdbNodes.size >= 3) {
                val formatted = parsedAdbNodes.take(45).joinToString("\n")
                return@withContext PilotObservation(
                    mode = PilotObservationMode.ADB_UIAUTOMATOR,
                    packageName = pkg,
                    uiSummaryText = formatted,
                    screenSignature = formatted.take(400).hashCode().toString(),
                    screenWidth = width,
                    screenHeight = height
                )
            }
        }

        // 3. Fallback: Screen Vision JPEG capture (Android 11+ Accessibility takeScreenshot or ADB screencap)
        if (allowVisionFallback) {
            val jpegBytes = GokuAccessibilityService.captureScreenJpegBytes(maxWidth = 720, quality = 65)
                ?: captureViaAdbScreencap(maxWidth = 720, quality = 65)
            if (jpegBytes != null && jpegBytes.isNotEmpty()) {
                return@withContext PilotObservation(
                    mode = PilotObservationMode.SCREEN_VISION,
                    packageName = pkg,
                    uiSummaryText = accessSummary?.toCompactPromptText().orEmpty()
                        .ifBlank { "UI nodes unreadable (WebView/CustomView/Game). Analyze attached screenshot." },
                    screenSignature = "vision:${jpegBytes.size}:${jpegBytes.take(64).hashCode()}",
                    screenshotJpegBytes = jpegBytes,
                    screenWidth = width,
                    screenHeight = height
                )
            }
        }

        // Fallback to whatever minimal UI summary exists
        if (accessSummary != null) {
            val compact = accessSummary.toCompactPromptText()
            return@withContext PilotObservation(
                mode = PilotObservationMode.UI_TREE,
                packageName = pkg,
                uiSummaryText = compact,
                screenSignature = compact.hashCode().toString(),
                screenWidth = width,
                screenHeight = height
            )
        }

        PilotObservation(
            mode = PilotObservationMode.UNAVAILABLE,
            packageName = "",
            uiSummaryText = "",
            screenSignature = "none",
            screenWidth = width,
            screenHeight = height
        )
    }

    private suspend fun captureViaAdbScreencap(maxWidth: Int, quality: Int): ByteArray? {
        if (!adbManager.isConnected) return null
        val res = adbManager.executeShellCommand(
            "screencap -p /sdcard/goku_screen.png && base64 -w 0 /sdcard/goku_screen.png && rm -f /sdcard/goku_screen.png"
        )
        val b64 = res.getOrNull()?.trim().orEmpty()
        if (b64.length < 200) return null
        return try {
            val pngBytes = Base64.decode(b64, Base64.DEFAULT)
            val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size) ?: return null
            val scaled = if (bmp.width > maxWidth && bmp.width > 0) {
                val scale = maxWidth.toFloat() / bmp.width.toFloat()
                Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                bmp
            }
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            baos.toByteArray()
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseUiAutomatorXml(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val results = mutableListOf<String>()
        val nodeRegex = Regex("<node[^>]+>")
        val textAttr = Regex("text=\"([^\"]+)\"")
        val descAttr = Regex("content-desc=\"([^\"]+)\"")
        val boundsAttr = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")

        for (match in nodeRegex.findAll(xml)) {
            val tag = match.value
            val label = textAttr.find(tag)?.groupValues?.get(1)?.trim().orEmpty()
                .ifBlank { descAttr.find(tag)?.groupValues?.get(1)?.trim().orEmpty() }
            if (label.isBlank()) continue
            val b = boundsAttr.find(tag)
            val coords = if (b != null) {
                val cx = (b.groupValues[1].toInt() + b.groupValues[3].toInt()) / 2
                val cy = (b.groupValues[2].toInt() + b.groupValues[4].toInt()) / 2
                "@($cx,$cy)"
            } else {
                ""
            }
            results.add("\"${label.take(60)}\" $coords")
            if (results.size >= 45) break
        }
        return results
    }
}

class BrainRouterPilotDecider(
    private val brainRouter: BrainRouter
) : PilotBrainDecider {

    override suspend fun decideNextStep(
        goal: String,
        observation: PilotObservation,
        stepNumber: Int,
        maxSteps: Int,
        previousSteps: List<String>
    ): Result<PilotStepDecision> {
        val prompt = buildString {
            appendLine("USER GOAL: $goal")
            appendLine("STEP: $stepNumber / $maxSteps")
            appendLine("SCREEN SIZE: ${observation.screenWidth}x${observation.screenHeight}")
            if (previousSteps.isNotEmpty()) {
                appendLine("PREVIOUS STEPS:")
                previousSteps.forEach { appendLine("- $it") }
            }
            appendLine("CURRENT SCREEN (${observation.mode.badge}):")
            appendLine(observation.uiSummaryText.take(2600))
            appendLine()
            append("Respond ONLY with one valid JSON object for the single next action.")
        }

        val replyRes = brainRouter.askMultimodalOrLocal(
            prompt = prompt,
            imageBytes = observation.screenshotJpegBytes,
            systemInstruction = PILOT_SYSTEM_INSTRUCTION,
            includeGoogleSearchTool = false
        )

        return replyRes.fold(
            onSuccess = { raw ->
                val parsed = UniversalAppPilot.parsePilotDecisionJson(raw)
                if (parsed != null) {
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException("Pilot AI se valid step JSON nahi mila."))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    companion object {
        private const val PILOT_SYSTEM_INSTRUCTION =
            "You are Goku Universal App Pilot controlling an Android phone step-by-step. " +
                "Return ONLY ONE raw JSON object with no markdown: " +
                "{\"action\":\"CLICK_TEXT|TAP_XY|TYPE_TEXT|SCROLL|BACK|HOME|OPEN_APP|WAIT|DONE\"," +
                "\"target\":\"button/app/field label\",\"text\":\"text to type\",\"x\":540,\"y\":1200," +
                "\"forward\":true,\"reason\":\"brief Hindi/Hinglish status\"}. " +
                "Use CLICK_TEXT when a button text is visible, TAP_XY with pixel coordinates when using screenshot vision, " +
                "and DONE with a clear Hindi summary once the user's goal is achieved."
    }
}

class ActionExecutorPilotDispatcher(
    private val actionExecutor: ActionExecutor
) : PilotActionDispatcher {

    override suspend fun dispatch(decision: PilotStepDecision): PilotDispatchResult {
        val gokuAction = when (decision.action) {
            PilotStepActionType.CLICK_TEXT -> GokuAction(GokuActionType.CLICK_TEXT, target = decision.target)
            PilotStepActionType.TAP_XY -> GokuAction(
                GokuActionType.TAP,
                x = decision.x?.toFloat() ?: 540f,
                y = decision.y?.toFloat() ?: 1100f
            )
            PilotStepActionType.TYPE_TEXT -> GokuAction(
                GokuActionType.TYPE_TEXT,
                target = decision.target,
                text = decision.text
            )
            PilotStepActionType.SCROLL -> GokuAction(GokuActionType.SCROLL, forward = decision.forward)
            PilotStepActionType.BACK -> GokuAction(GokuActionType.GLOBAL_NAV, target = "BACK")
            PilotStepActionType.HOME -> GokuAction(GokuActionType.GLOBAL_NAV, target = "HOME")
            PilotStepActionType.OPEN_APP -> GokuAction(GokuActionType.OPEN_APP, target = decision.target)
            PilotStepActionType.WAIT -> {
                delay(1200L)
                return PilotDispatchResult(true, "Waited 1.2s")
            }
            PilotStepActionType.DONE -> return PilotDispatchResult(true, "Done")
        }

        val res = actionExecutor.execute(gokuAction)
        return PilotDispatchResult(success = res.success, detail = res.logDetail)
    }
}
