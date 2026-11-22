package com.arif.goku.brain

import android.content.Context
import android.content.Intent
import com.arif.goku.accessibility.GokuAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class ZeroCopyResult(
    val success: Boolean,
    val targetAppLabel: String,
    val computedPayload: String,
    val spokenReplyHindi: String
)

enum class ZeroCopyTargetApp(val displayName: String, val candidatePackages: List<String>) {
    WHATSAPP("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b")),
    NOTES(
        "Notes",
        listOf(
            "com.google.android.keep",
            "com.samsung.android.app.notes",
            "com.miui.notes",
            "com.oneplus.note",
            "com.coloros.note"
        )
    ),
    GMAIL("Gmail", listOf("com.google.android.gm")),
    TELEGRAM("Telegram", listOf("org.telegram.messenger")),
    SHARE_CHOOSER("Target App", emptyList())
}

/**
 * Phase 5 Zero-Copy App Bridge.
 *
 * Executes single-command cross-app workflows without manual copy-paste:
 * 1) **Extract:** Captures current screen via Accessibility UI tree, falling back to Screen Vision
 *    JPEG when the screen is an image/PDF/custom view.
 * 2) **Compute / Format:** Uses `BrainRouter` (or fast deterministic local bill/number summing when
 *    offline) to compute totals, clean lists, or summarize screen data.
 * 3) **Inject Directly:** Sends the formatted payload straight into Notes/Keep, WhatsApp, Gmail,
 *    or Telegram via targeted `ACTION_SEND` / direct Accessibility field injection.
 */
class ZeroCopyBridge(
    context: Context,
    private val brainRouter: BrainRouter
) {
    private val appContext = context.applicationContext

    suspend fun executeWorkflow(
        userCommand: String,
        allowVisionFallback: Boolean = true
    ): Result<ZeroCopyResult> = withContext(Dispatchers.IO) {
        val targetApp = detectTargetApp(userCommand)

        // 1. EXTRACT current screen data (UI tree -> Screen Vision JPEG fallback)
        val summary = GokuAccessibilityService.readScreenSummary()
        val rawTexts = summary?.visibleTexts.orEmpty() + summary?.clickableItems.orEmpty()
        val uiText = summary?.toCompactPromptText().orEmpty()
        val needsVision = rawTexts.size < 3

        val jpegBytes = if (needsVision && allowVisionFallback) {
            GokuAccessibilityService.captureScreenJpegBytes(maxWidth = 720, quality = 68)
        } else {
            null
        }

        if (uiText.isBlank() && jpegBytes == null) {
            return@withContext Result.failure(
                IllegalStateException("Screen data extract nahi ho paya. GokuAgent Accessibility Service ON karein.")
            )
        }

        // 2. COMPUTE / FORMAT via BrainRouter (or offline bill-total calculator)
        val prompt = buildString {
            appendLine("Cross-app Zero-Copy task: $userCommand")
            if (uiText.isNotBlank()) {
                appendLine("Extracted Screen Content:")
                appendLine(uiText.take(2400))
            }
            append(
                "Produce ONLY the clean final text ready to paste/send into ${targetApp.displayName} " +
                    "(e.g. itemized numbers + Grand Total if bill/numbers requested, or clean concise notes). No markdown symbols."
            )
        }

        val computedRes = brainRouter.askMultimodalOrLocal(
            prompt = prompt,
            imageBytes = jpegBytes,
            systemInstruction = "You are Goku Zero-Copy Bridge. Extract numbers/data accurately, calculate exact totals if asked, and output clean plain text ready for Notes or WhatsApp.",
            includeGoogleSearchTool = false
        )

        val payload = computedRes.getOrElse {
            // Deterministic offline fallback when summing bills/numbers or copying visible text
            if (isMathOrBillIntent(userCommand)) {
                extractLocalBillSummary(rawTexts)
                    ?: rawTexts.take(15).joinToString("\n")
            } else {
                rawTexts.take(15).joinToString("\n")
            }
        }.trim()

        if (payload.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Screen par forward karne layak koi text ya numbers nahi mile.")
            )
        }

        // 3. INJECT DIRECTLY into target app (no manual copy-paste!)
        val injected = injectIntoTargetApp(targetApp, payload)
        if (!injected) {
            return@withContext Result.failure(
                IllegalStateException("${targetApp.displayName} launch nahi ho paya.")
            )
        }

        val hindiReply = "Done! Zero-Copy Bridge ne data ready karke seedha ${targetApp.displayName} mein daal diya hai."
        Result.success(
            ZeroCopyResult(
                success = true,
                targetAppLabel = targetApp.displayName,
                computedPayload = payload,
                spokenReplyHindi = hindiReply
            )
        )
    }

    private suspend fun injectIntoTargetApp(target: ZeroCopyTargetApp, payload: String): Boolean {
        val installedPkg = target.candidatePackages.firstOrNull { isPackageInstalled(it) }

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, payload)
            putExtra(Intent.EXTRA_SUBJECT, "GokuAgent Zero-Copy Note")
            if (installedPkg != null) {
                setPackage(installedPkg)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            withContext(Dispatchers.Main) {
                if (installedPkg != null) {
                    appContext.startActivity(sendIntent)
                } else {
                    val chooser = Intent.createChooser(sendIntent, "Send via Goku Zero-Copy Bridge").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(chooser)
                }
            }
            // If Notes/Keep opens a direct editable composer without prefill, attempt Accessibility injection
            if (target == ZeroCopyTargetApp.NOTES && installedPkg != null &&
                GokuAccessibilityService.refreshActiveStatus(appContext)
            ) {
                delay(1100L)
                val currentNodes = GokuAccessibilityService.readDetailedUiNodes(limit = 20)
                val emptyEditable = currentNodes.firstOrNull { it.isEditable && it.label.isBlank() }
                if (emptyEditable != null) {
                    GokuAccessibilityService.typeIntoFocusedOrMatchingField(null, payload)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            appContext.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val AMOUNT_REGEX = Regex("(?:₹|rs\\.?|inr)?\\s*(?<!\\d)(\\d+(?:,\\d{2,3})*(?:\\.\\d{1,2})?)(?!\\d)", RegexOption.IGNORE_CASE)

        fun isZeroCopyCommand(rawInput: String): Boolean {
            val lower = rawInput.trim().lowercase()
            val mentionsScreen = lower.contains("screen") || lower.contains("bill") ||
                lower.contains("numbers") || lower.contains("is page") || lower.contains("स्क्रीन")
            val mentionsTransfer = lower.contains("notes") || lower.contains("keep") ||
                lower.contains("whatsapp") || lower.contains("gmail") || lower.contains("telegram") ||
                lower.contains("daal do") || lower.contains("save karo") || lower.contains("bhej do") ||
                lower.contains("total karke")
            return mentionsScreen && mentionsTransfer
        }

        fun detectTargetApp(rawInput: String): ZeroCopyTargetApp {
            val lower = rawInput.lowercase()
            return when {
                lower.contains("whatsapp") || lower.contains("व्हाट्सएप") -> ZeroCopyTargetApp.WHATSAPP
                lower.contains("note") || lower.contains("keep") || lower.contains("नोट्स") -> ZeroCopyTargetApp.NOTES
                lower.contains("gmail") || lower.contains("email") || lower.contains("mail") -> ZeroCopyTargetApp.GMAIL
                lower.contains("telegram") -> ZeroCopyTargetApp.TELEGRAM
                else -> ZeroCopyTargetApp.SHARE_CHOOSER
            }
        }

        fun isMathOrBillIntent(rawInput: String): Boolean {
            val lower = rawInput.lowercase()
            return lower.contains("total") || lower.contains("sum") || lower.contains("bill") ||
                lower.contains("hisab") || lower.contains("jod") || lower.contains("calculate")
        }

        /**
         * Pure, offline-capable number/bill extractor and totalizer for Zero-Copy Bridge.
         */
        fun extractLocalBillSummary(visibleTexts: List<String>): String? {
            val numbers = mutableListOf<Double>()
            for (line in visibleTexts) {
                // Skip obvious timestamps (e.g. 12:45) and 10-digit phone numbers
                if (line.contains(":") && line.length <= 8) continue
                for (match in AMOUNT_REGEX.findAll(line)) {
                    val rawNum = match.groupValues[1].replace(",", "")
                    if (rawNum.length >= 9 && !rawNum.contains(".")) continue // likely phone/order ID
                    val parsed = rawNum.toDoubleOrNull() ?: continue
                    if (parsed > 0.0 && parsed < 10_000_000.0) {
                        numbers.add(parsed)
                    }
                }
            }
            if (numbers.isEmpty()) return null

            val total = numbers.sum()
            val formattedItems = numbers.joinToString(" + ") { formatAmount(it) }
            val formattedTotal = formatAmount(total)
            return "Bill / Numbers Summary (${numbers.size} items):\n$formattedItems\nGrand Total: Rs. $formattedTotal"
        }

        private fun formatAmount(value: Double): String {
            return if (value % 1.0 == 0.0) {
                String.format(Locale.US, "%.0f", value)
            } else {
                String.format(Locale.US, "%.2f", value)
            }
        }
    }
}
