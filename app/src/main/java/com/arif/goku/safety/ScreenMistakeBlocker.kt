package com.arif.goku.safety

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.arif.goku.accessibility.AccessibilityScreenSignal
import com.arif.goku.accessibility.GokuAccessibilityService
import com.arif.goku.brain.BrainRouter
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.overlay.GokuOverlayService
import com.arif.goku.overlay.MistakeOverlayAlert
import com.arif.goku.overlay.OverlayBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

enum class MistakeRiskCategory(val title: String) {
    DESTRUCTIVE_ACTION("Destructive Action"),
    PHISHING_OR_APK("Suspicious APK / Phishing"),
    PAYMENT_RISK("High-Risk Payment / Transfer")
}

data class MistakeEvaluation(
    val category: MistakeRiskCategory,
    val signature: String,
    val title: String,
    val reasonHindi: String,
    val packageName: String
)

data class BlockedMistakeRecord(
    val title: String,
    val packageName: String,
    val decision: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Phase 5 Live Screen Co-Pilot & Mistake Blocker.
 *
 * 1. **Real-Time Mistake Blocker:** Evaluates live `GokuAccessibilityService` events for high-risk
 *    actions (Delete, Uninstall, Clear All, Format, unknown APK/phishing prompts, high-risk UPI
 *    payments) and immediately shows a blocking warning overlay via `GokuOverlayService` with
 *    **Rok Do (Cancel)** and **Allow Once** options + Hindi voice alert.
 * 2. **Screen Co-Pilot:** On voice command (*"Goku, yeh kya likha hai?"* or *"Is form mein meri
 *    details bhar do"*), explains English screens/errors in simple Hinglish (with Screen Vision
 *    fallback) or auto-fills form fields from saved `MemoryManager` facts.
 */
class ScreenMistakeBlocker(
    private val context: Context,
    private val securePrefs: SecurePrefs
) {
    private val appContext = context.applicationContext
    private val allowOnceUntilMs = ConcurrentHashMap<String, Long>()
    private val recentAlertCooldownMs = ConcurrentHashMap<String, Long>()

    private val _recentRecords = MutableStateFlow<List<BlockedMistakeRecord>>(emptyList())
    val recentRecords: StateFlow<List<BlockedMistakeRecord>> = _recentRecords.asStateFlow()

    private val _blockedCount = MutableStateFlow(0)
    val blockedCount: StateFlow<Int> = _blockedCount.asStateFlow()

    /**
     * Evaluates a live Accessibility event; if high-risk and not exempted via "Allow Once",
     * displays the blocking warning overlay and returns the evaluation for Hindi TTS alerting.
     */
    fun onAccessibilitySignal(signal: AccessibilityScreenSignal): MistakeEvaluation? {
        if (!securePrefs.isMistakeBlockerEnabled()) return null
        val eval = evaluateSignal(signal, selfPackage = appContext.packageName) ?: return null

        val now = System.currentTimeMillis()
        val allowedUntil = allowOnceUntilMs[eval.signature] ?: 0L
        if (now < allowedUntil) return null

        val lastWarned = recentAlertCooldownMs[eval.signature] ?: 0L
        if (now - lastWarned < ALERT_COOLDOWN_MS) return null
        recentAlertCooldownMs[eval.signature] = now

        // Ensure overlay service is running if permission is granted so warning appears immediately
        if (!OverlayBridge.isOverlayRunning.value) {
            GokuOverlayService.start(appContext)
        }
        OverlayBridge.showMistakeWarning(
            MistakeOverlayAlert(
                signature = eval.signature,
                title = eval.title,
                reasonHindi = eval.reasonHindi,
                targetPackage = eval.packageName
            )
        )
        return eval
    }

    /**
     * Handles the user's tap on **Rok Do (Cancel)** or **Allow Once** from the Mistake Blocker overlay.
     */
    fun handleUserDecision(signature: String, allowOnce: Boolean): String {
        val pkg = signature.substringBefore('|')
        val label = signature.substringAfter('|', "Risky Action")
        return if (allowOnce) {
            allowOnceUntilMs[signature] = System.currentTimeMillis() + ALLOW_ONCE_WINDOW_MS
            recordDecision(label, pkg, "ALLOWED ONCE (60s)")
            "Theek hai, ek baar ke liye allow kar diya hai."
        } else {
            GokuAccessibilityService.performBackAction()
            _blockedCount.update { it + 1 }
            recordDecision(label, pkg, "BLOCKED (ROK DIYA)")
            "Surakshit! Risky step rok diya gaya hai aur pichhli screen par aa gaye hain."
        }
    }

    fun grantAllowOnce(signature: String, durationMs: Long = ALLOW_ONCE_WINDOW_MS) {
        allowOnceUntilMs[signature] = System.currentTimeMillis() + durationMs
    }

    fun isExempted(signature: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = allowOnceUntilMs[signature] ?: return false
        return nowMs < until
    }

    private fun recordDecision(title: String, pkg: String, decision: String) {
        _recentRecords.update { existing ->
            (listOf(BlockedMistakeRecord(title = title, packageName = pkg, decision = decision)) + existing)
                .take(15)
        }
    }

    companion object {
        private const val ALLOW_ONCE_WINDOW_MS = 60_000L
        private const val ALERT_COOLDOWN_MS = 8_000L

        private val DESTRUCTIVE_KEYWORDS = listOf(
            "delete", "uninstall", "clear all", "clear data", "clear storage",
            "format", "factory reset", "erase all", "permanently delete",
            "delete account", "remove all", "हटाएं", "डिलीट", "अनइंस्टॉल"
        )

        private val PHISHING_OR_APK_KEYWORDS = listOf(
            "install unknown", "unknown app", "kyc suspended", "account blocked",
            "claim prize", "lottery winner", "enter atm pin", "share otp",
            "bit.ly/", "tinyurl.com/", "apk install", ".apk"
        )

        private val PAYMENT_KEYWORDS = listOf(
            "pay now", "proceed to pay", "send money", "confirm payment",
            "confirm transfer", "transfer now", "pay ₹", "pay rs"
        )

        private val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller"
        )

        fun isHighRiskLabel(label: String): Boolean {
            val lower = label.trim().lowercase()
            if (lower.isEmpty()) return false
            return DESTRUCTIVE_KEYWORDS.any { lower.contains(it) } ||
                PAYMENT_KEYWORDS.any { lower.contains(it) }
        }

        /**
         * Pure, unit-testable Mistake Blocker rule evaluator.
         */
        fun evaluateSignal(
            signal: AccessibilityScreenSignal,
            selfPackage: String = "com.arif.goku"
        ): MistakeEvaluation? {
            val pkg = signal.packageName.trim()
            if (pkg.isEmpty() || pkg == selfPackage || pkg == "com.android.systemui") return null

            val clickedLower = signal.clickedOrFocusedText.lowercase()
            val fullScreenLower = (signal.visibleTexts + signal.clickableItems + signal.clickedOrFocusedText)
                .joinToString(" ")
                .lowercase()

            // 1. Suspicious APK install or Phishing / Scam screen
            val isInstaller = pkg in INSTALLER_PACKAGES &&
                (fullScreenLower.contains("install") || fullScreenLower.contains("unknown"))
            val phishingHit = PHISHING_OR_APK_KEYWORDS.firstOrNull { fullScreenLower.contains(it) }
            if (isInstaller || phishingHit != null) {
                val trigger = if (isInstaller) "Unknown APK Install" else phishingHit.orEmpty()
                return MistakeEvaluation(
                    category = MistakeRiskCategory.PHISHING_OR_APK,
                    signature = "$pkg|PHISHING|$trigger",
                    title = "Suspicious APK / Link Alert",
                    reasonHindi = "Ruko! Yeh unknown APK install ya suspicious phishing link lag raha hai ($trigger). Pehle verify karein, warna Rok Do dabayein.",
                    packageName = pkg
                )
            }

            // 2. Destructive Action clicked OR destructive confirmation dialog opened
            val isClick = signal.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
            val isWindowChange = signal.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val destructiveClickHit = if (isClick) {
                DESTRUCTIVE_KEYWORDS.firstOrNull { clickedLower.contains(it) }
            } else {
                null
            }
            val destructiveDialogHit = if (isWindowChange &&
                (fullScreenLower.contains("cancel") || fullScreenLower.contains("confirm") || fullScreenLower.contains("are you sure"))
            ) {
                DESTRUCTIVE_KEYWORDS.firstOrNull { fullScreenLower.contains(it) }
            } else {
                null
            }

            val destructiveHit = destructiveClickHit ?: destructiveDialogHit
            if (destructiveHit != null) {
                return MistakeEvaluation(
                    category = MistakeRiskCategory.DESTRUCTIVE_ACTION,
                    signature = "$pkg|DESTRUCTIVE|$destructiveHit",
                    title = "Destructive Action Detected",
                    reasonHindi = "Ruko! Yeh galat ya risky step lag raha hai ('$destructiveHit'). Data ya app delete ho sakta hai — aage badhna hai ya Rok Do?",
                    packageName = pkg
                )
            }

            // 3. High-Risk Payment / Money Transfer confirmation
            val paymentHit = if (isClick) {
                PAYMENT_KEYWORDS.firstOrNull { clickedLower.contains(it) }
            } else if (isWindowChange && fullScreenLower.contains("upi")) {
                PAYMENT_KEYWORDS.firstOrNull { fullScreenLower.contains(it) }
            } else {
                null
            }
            if (paymentHit != null) {
                return MistakeEvaluation(
                    category = MistakeRiskCategory.PAYMENT_RISK,
                    signature = "$pkg|PAYMENT|$paymentHit",
                    title = "Payment Verification",
                    reasonHindi = "Ruko! Yeh payment ya paisa bhejne ka step lag raha hai ('$paymentHit'). Recipient aur amount dhyan se check karke hi Allow Once dabayein.",
                    packageName = pkg
                )
            }

            return null
        }
    }
}

/**
 * Phase 5 Live Screen Co-Pilot:
 * - Explains English error screens / pages in simple Hinglish (UI tree + Screen Vision JPEG fallback).
 * - Auto-fills visible form fields using saved `MemoryManager` facts.
 */
class ScreenCoPilot(
    private val brainRouter: BrainRouter,
    private val memoryManager: MemoryManager
) {
    suspend fun explainCurrentScreen(
        userQuestion: String,
        allowVisionFallback: Boolean = true
    ): Result<String> {
        val summary = GokuAccessibilityService.readScreenSummary()
        val uiText = summary?.toCompactPromptText().orEmpty()
        val needsVision = uiText.length < 40 || summary?.visibleTexts.orEmpty().size < 2

        val jpegBytes = if (needsVision && allowVisionFallback) {
            GokuAccessibilityService.captureScreenJpegBytes(maxWidth = 720, quality = 68)
        } else {
            null
        }

        if (uiText.isBlank() && jpegBytes == null) {
            return Result.failure(
                IllegalStateException("Screen read karne ke liye GokuAgent Accessibility Service ON karein.")
            )
        }

        val prompt = buildString {
            appendLine("User question: $userQuestion")
            if (uiText.isNotBlank()) {
                appendLine("Active Screen UI Text:")
                appendLine(uiText.take(2200))
            }
            append("Explain clearly in simple, warm Hinglish (2-3 short sentences, no markdown) what is on screen and what the user should do next.")
        }

        return brainRouter.askMultimodalOrLocal(
            prompt = prompt,
            imageBytes = jpegBytes,
            systemInstruction = "Tum Goku Screen Co-Pilot ho. English errors, forms, ya pages ko aasaan aur friendly Hinglish mein samjhao (no markdown).",
            includeGoogleSearchTool = false
        )
    }

    suspend fun autoFillCurrentForm(): Result<String> {
        val nodes = GokuAccessibilityService.readDetailedUiNodes(limit = 50)
        val editableNodes = nodes.filter { it.isEditable }
        if (editableNodes.isEmpty()) {
            return Result.failure(
                IllegalStateException("Screen par koi editable form field nahi mila. Pehle form page kholein aur Accessibility ON rakhein.")
            )
        }

        val memories = memoryManager.retrieveRelevantMemories("name phone email address city pincode upi details", limit = 15)
        if (memories.isEmpty()) {
            return Result.failure(
                IllegalStateException("Memory Vault mein abhi aapki details saved nahi hain. Settings -> Memory mein Name, Phone, Email ya Address save karein.")
            )
        }

        var filledCount = 0
        val filledLabels = mutableListOf<String>()

        for (field in editableNodes) {
            val fieldDescriptor = "${field.hint} ${field.label}".trim().lowercase()
            val matchedValue = matchMemoryForField(fieldDescriptor, memories.map { it.aliasKey to it.content })
            if (!matchedValue.isNullOrBlank()) {
                val query = field.hint.ifBlank { field.label }
                val ok = GokuAccessibilityService.typeIntoFocusedOrMatchingField(
                    query = query.ifBlank { null },
                    text = matchedValue
                )
                if (ok) {
                    filledCount++
                    filledLabels.add(query.ifBlank { "Field $filledCount" })
                }
            }
        }

        return if (filledCount > 0) {
            Result.success("Done! Maine $filledCount form field(s) auto-fill kar diye hain: ${filledLabels.joinToString(", ")}.")
        } else {
            Result.failure(
                IllegalStateException("Form fields ke liye matching Memory Vault entry nahi mili (Name, Phone, Email, Address save karein).")
            )
        }
    }

    companion object {
        fun isExplainScreenCommand(rawInput: String): Boolean {
            val lower = rawInput.trim().lowercase()
            return lower.contains("yeh kya likha") ||
                lower.contains("ye kya likha") ||
                lower.contains("screen samjha") ||
                lower.contains("screen explain") ||
                lower.contains("what is on screen") ||
                lower.contains("error samjha") ||
                lower.contains("is page ka matlab") ||
                lower.contains("यह क्या लिखा")
        }

        fun isAutoFillFormCommand(rawInput: String): Boolean {
            val lower = rawInput.trim().lowercase()
            return lower.contains("form bhar") ||
                lower.contains("details bhar") ||
                lower.contains("auto fill") ||
                lower.contains("autofill") ||
                lower.contains("fill form") ||
                lower.contains("फॉर्म भर")
        }

        fun matchMemoryForField(fieldLower: String, memories: List<Pair<String, String>>): String? {
            if (fieldLower.isBlank()) return null
            val fieldKeys = when {
                fieldLower.contains("name") || fieldLower.contains("naam") -> listOf("user name", "name", "naam")
                fieldLower.contains("email") || fieldLower.contains("mail") -> listOf("email", "mail")
                fieldLower.contains("phone") || fieldLower.contains("mobile") || fieldLower.contains("number") ->
                    listOf("my phone", "my number", "phone", "mobile")
                fieldLower.contains("address") || fieldLower.contains("pata") -> listOf("address", "home", "pata")
                fieldLower.contains("city") || fieldLower.contains("shehar") -> listOf("city", "shehar")
                fieldLower.contains("pin") || fieldLower.contains("zip") -> listOf("pincode", "pin", "zip")
                fieldLower.contains("upi") -> listOf("upi")
                else -> listOf(fieldLower)
            }

            for (key in fieldKeys) {
                val hit = memories.firstOrNull { (alias, _) ->
                    alias.lowercase().contains(key) || key.contains(alias.lowercase())
                }
                if (hit != null && hit.second.isNotBlank()) {
                    return hit.second.trim()
                }
            }
            return null
        }
    }
}
