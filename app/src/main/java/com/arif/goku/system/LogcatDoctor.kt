package com.arif.goku.system

import android.content.Context
import com.arif.goku.accessibility.GokuAccessibilityService
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.brain.BrainRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class DiagnosisCategory(val badge: String) {
    ADB_DISCONNECTED("ADB Disconnected"),
    MISSING_PERMISSION("Missing Permission"),
    ACCESSIBILITY_OFF("Accessibility Off"),
    UI_CHANGED("UI Element Changed"),
    NETWORK_OR_API("Network / API Issue"),
    CRASH_OR_EXCEPTION("Runtime Exception"),
    HEALTHY("No Critical Errors")
}

data class LogcatDiagnosisReport(
    val category: DiagnosisCategory,
    val rootCauseHindi: String,
    val suggestedFixHindi: String,
    val autoRecoveryAttempted: Boolean,
    val autoRecoverySucceeded: Boolean,
    val rawErrorSnippets: List<String>,
    val source: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Phase 7 `logcat` Self-Diagnosis Doctor.
 *
 * Tracks consecutive failures for Skills & internal actions. On repeated failures (or manual trigger),
 * reads the last 150 lines of `logcat` via `LocalAdbManager` (`logcat -d -t 150`, with app-local
 * fallback if ADB is disconnected), diagnoses the root cause offline + via `BrainRouter`, attempts
 * safe auto-recovery (`pm grant`, ADB re-probe, Accessibility check), and explains the fix in Hindi.
 */
class LogcatDoctor(
    private val context: Context,
    private val adbManager: LocalAdbManager,
    private val brainRouter: BrainRouter
) {
    private val appContext = context.applicationContext
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    private val _latestReport = MutableStateFlow<LogcatDiagnosisReport?>(null)
    val latestReport: StateFlow<LogcatDiagnosisReport?> = _latestReport.asStateFlow()

    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()

    fun recordSuccess(actionKey: String) {
        consecutiveFailures.remove(actionKey.lowercase(Locale.US))
    }

    /**
     * Increments failure count for [actionKey]. Returns true when repeated failure threshold (2+)
     * is reached and `logcat` self-diagnosis should run automatically.
     */
    fun recordFailureAndShouldDiagnose(actionKey: String, threshold: Int = 2): Boolean {
        val key = actionKey.lowercase(Locale.US)
        val count = (consecutiveFailures[key] ?: 0) + 1
        consecutiveFailures[key] = count
        return count >= threshold
    }

    suspend fun runSelfDiagnosis(
        contextLabel: String = "GokuAgent System Check",
        lastErrorDetail: String = ""
    ): LogcatDiagnosisReport = withContext(Dispatchers.IO) {
        _isDiagnosing.value = true
        try {
            val (logs, source) = captureRecentLogcat()
            val errorLines = extractRelevantErrorLines(logs, lastErrorDetail)
            val heuristic = classifyDeterministically(
                errorLines = errorLines,
                lastErrorDetail = lastErrorDetail,
                isAdbConnected = adbManager.isConnected,
                isAccessibilityActive = GokuAccessibilityService.refreshActiveStatus(appContext)
            )

            // Attempt safe auto-recovery where applicable
            var attempted = false
            var recovered = false
            when (heuristic.category) {
                DiagnosisCategory.ADB_DISCONNECTED -> {
                    attempted = true
                    recovered = adbManager.probeLocalTcpip5555()
                }
                DiagnosisCategory.MISSING_PERMISSION -> {
                    if (adbManager.isConnected) {
                        attempted = true
                        recovered = adbManager.autoGrantSelfPermissions()
                    }
                }
                DiagnosisCategory.ACCESSIBILITY_OFF -> {
                    attempted = true
                    recovered = GokuAccessibilityService.refreshActiveStatus(appContext)
                }
                else -> Unit
            }

            // Optionally enrich diagnosis via BrainRouter if online
            val brainHindi = if (errorLines.isNotEmpty() || lastErrorDetail.isNotBlank()) {
                askBrainForConciseDiagnosis(contextLabel, lastErrorDetail, errorLines)
            } else {
                null
            }

            val finalReport = heuristic.copy(
                rootCauseHindi = brainHindi?.first ?: heuristic.rootCauseHindi,
                suggestedFixHindi = brainHindi?.second ?: heuristic.suggestedFixHindi,
                autoRecoveryAttempted = attempted,
                autoRecoverySucceeded = recovered,
                rawErrorSnippets = errorLines.take(8),
                source = source,
                timestampMs = System.currentTimeMillis()
            )
            _latestReport.value = finalReport
            finalReport
        } finally {
            _isDiagnosing.value = false
        }
    }

    private suspend fun captureRecentLogcat(): Pair<String, String> {
        if (adbManager.isConnected) {
            val adbLog = adbManager.executeShellCommand("logcat -d -t 150")
            if (adbLog.isSuccess) {
                val output = adbLog.getOrNull().orEmpty()
                if (output.isNotBlank()) {
                    return output to "Local ADB (logcat -d -t 150)"
                }
            }
        }
        // Safe fallback: read app-scoped process logcat when Local ADB is disconnected
        val localOutput = runCatching {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "150"))
            val text = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.destroy()
            text
        }.getOrDefault("")
        return localOutput to "App Process Logcat (ADB Disconnected)"
    }

    private suspend fun askBrainForConciseDiagnosis(
        contextLabel: String,
        lastError: String,
        errorLines: List<String>
    ): Pair<String, String>? {
        val snippet = errorLines.take(10).joinToString("\n")
        val prompt = """
            Diagnose this GokuAgent Android failure in simple friendly Hinglish (max 2 short lines).
            Context: $contextLabel
            Last Error: $lastError
            Recent logcat lines:
            $snippet

            Reply in EXACTLY two lines:
            CAUSE: <1 short Hinglish sentence explaining root cause>
            FIX: <1 short Hinglish sentence explaining exact fix>
        """.trimIndent()

        val reply = brainRouter.askMultimodalOrLocal(
            prompt = prompt,
            imageBytes = null,
            systemInstruction = "You are GokuAgent's Android logcat diagnostic engineer. Keep explanations concise and actionable in Hinglish.",
            includeGoogleSearchTool = false
        ).getOrNull() ?: return null

        val cause = reply.lines().firstOrNull { it.trim().startsWith("CAUSE:", ignoreCase = true) }
            ?.substringAfter(":")?.trim().orEmpty()
        val fix = reply.lines().firstOrNull { it.trim().startsWith("FIX:", ignoreCase = true) }
            ?.substringAfter(":")?.trim().orEmpty()

        return if (cause.isNotBlank() && fix.isNotBlank()) cause to fix else null
    }

    companion object {
        fun extractRelevantErrorLines(rawLogcat: String, lastErrorDetail: String): List<String> {
            val combined = buildList {
                if (lastErrorDetail.isNotBlank()) add("LAST_ERROR: $lastErrorDetail")
                rawLogcat.lineSequence()
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotBlank() && (
                            line.contains(" E ") ||
                                line.contains("E/") ||
                                line.contains("AndroidRuntime") ||
                                line.contains("Exception") ||
                                line.contains("Permission Denial", ignoreCase = true) ||
                                line.contains("SecurityException", ignoreCase = true) ||
                                line.contains("ActivityNotFoundException", ignoreCase = true) ||
                                line.contains("com.arif.goku", ignoreCase = true)
                            )
                    }
                    .take(18)
                    .forEach { add(it.take(180)) }
            }
            return combined.distinct()
        }

        fun classifyDeterministically(
            errorLines: List<String>,
            lastErrorDetail: String,
            isAdbConnected: Boolean,
            isAccessibilityActive: Boolean
        ): LogcatDiagnosisReport {
            val corpus = (errorLines + lastErrorDetail).joinToString("\n").lowercase(Locale.US)
            return when {
                corpus.contains("permission denial") ||
                    corpus.contains("securityexception") ||
                    corpus.contains("missing permission") -> LogcatDiagnosisReport(
                    category = DiagnosisCategory.MISSING_PERMISSION,
                    rootCauseHindi = "Android permission missing ya revoke ho gayi hai.",
                    suggestedFixHindi = "Settings -> Control ya Local ADB se required permission grant karein.",
                    autoRecoveryAttempted = false,
                    autoRecoverySucceeded = false,
                    rawErrorSnippets = errorLines,
                    source = "Heuristic"
                )

                !isAdbConnected && (corpus.contains("adb") || corpus.contains("127.0.0.1") || corpus.contains("refused")) ->
                    LogcatDiagnosisReport(
                        category = DiagnosisCategory.ADB_DISCONNECTED,
                        rootCauseHindi = "Local ADB (127.0.0.1:5555) disconnect ho gaya hai.",
                        suggestedFixHindi = "Wireless Debugging ON karke Settings -> Local ADB mein Probe / Reconnect dabayein.",
                        autoRecoveryAttempted = false,
                        autoRecoverySucceeded = false,
                        rawErrorSnippets = errorLines,
                        source = "Heuristic"
                    )

                !isAccessibilityActive && (corpus.contains("accessibility") || corpus.contains("click") || corpus.contains("node")) ->
                    LogcatDiagnosisReport(
                        category = DiagnosisCategory.ACCESSIBILITY_OFF,
                        rootCauseHindi = "Goku Accessibility Service band hai, isliye UI button nahi mil raha.",
                        suggestedFixHindi = "Settings -> Control -> Enable Accessibility Service ko ON karein.",
                        autoRecoveryAttempted = false,
                        autoRecoverySucceeded = false,
                        rawErrorSnippets = errorLines,
                        source = "Heuristic"
                    )

                corpus.contains("not found") || corpus.contains("activitynotfound") || corpus.contains("ui") ->
                    LogcatDiagnosisReport(
                        category = DiagnosisCategory.UI_CHANGED,
                        rootCauseHindi = "Target app ka UI ya button text badal gaya hai.",
                        suggestedFixHindi = "Skill dobara chalayein — Goku Universal Pilot + Screen Vision se auto Self-Heal kar dega.",
                        autoRecoveryAttempted = false,
                        autoRecoverySucceeded = false,
                        rawErrorSnippets = errorLines,
                        source = "Heuristic"
                    )

                corpus.contains("unknownhost") || corpus.contains("timeout") || corpus.contains("401") || corpus.contains("429") ->
                    LogcatDiagnosisReport(
                        category = DiagnosisCategory.NETWORK_OR_API,
                        rootCauseHindi = "Internet slow hai ya Gemini API key / quota issue hai.",
                        suggestedFixHindi = "Network check karein ya Offline Skills / Local AI model use karein.",
                        autoRecoveryAttempted = false,
                        autoRecoverySucceeded = false,
                        rawErrorSnippets = errorLines,
                        source = "Heuristic"
                    )

                errorLines.isNotEmpty() -> LogcatDiagnosisReport(
                    category = DiagnosisCategory.CRASH_OR_EXCEPTION,
                    rootCauseHindi = "Recent logcat mein runtime error mila: ${errorLines.first().take(90)}",
                    suggestedFixHindi = "Skill steps check karein ya Dream Mode se optimize karein.",
                    autoRecoveryAttempted = false,
                    autoRecoverySucceeded = false,
                    rawErrorSnippets = errorLines,
                    source = "Heuristic"
                )

                else -> LogcatDiagnosisReport(
                    category = DiagnosisCategory.HEALTHY,
                    rootCauseHindi = "Logcat clean hai — koi critical crash ya permission error nahi mila.",
                    suggestedFixHindi = "Sab systems healthy hain.",
                    autoRecoveryAttempted = false,
                    autoRecoverySucceeded = false,
                    rawErrorSnippets = emptyList(),
                    source = "Heuristic"
                )
            }
        }
    }
}
