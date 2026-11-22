package com.arif.goku.safety

import com.arif.goku.action.GokuAction
import com.arif.goku.action.GokuActionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class SensitiveActionType(val displayTitle: String) {
    PHONE_CALL("Phone Call"),
    SEND_MESSAGE("Send Message / WhatsApp"),
    SHELL_COMMAND("ADB / Shell Command"),
    APP_FREEZE("Freeze App"),
    SYSTEM_CONTROL("System Action")
}

enum class VoiceConfirmationDecision {
    CONFIRMED,
    CANCELLED,
    UNKNOWN
}

data class PendingSafetyAction(
    val id: String = UUID.randomUUID().toString(),
    val type: SensitiveActionType,
    val rawCommand: String,
    val summary: String,
    val hindiVoicePrompt: String,
    val riskNotice: String,
    val structuredAction: GokuAction? = null
)

data class SafetyState(
    val pendingAction: PendingSafetyAction? = null,
    val emergencyStopActive: Boolean = false,
    val lastEmergencyReason: String? = null,
    val emergencyTriggerCount: Int = 0
)

/**
 * Enforces GokuAgent safety boundaries across Phase 1 & Phase 2:
 * 1) Instant "Goku stop" emergency kill switch across voice, TTS, network, overlay, and actions.
 * 2) Mandatory user confirmation (dialog + Hindi voice prompt) before any sensitive action
 *    (calls, SMS, WhatsApp, ADB shell commands, or freezing apps).
 */
class SafetyGuard {

    private val _state = MutableStateFlow(SafetyState())
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    fun isEmergencyStopCommand(input: String): Boolean {
        val normalized = input.trim().lowercase()
            .replace(Regex("[.,!?]+"), " ")
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return false

        return EMERGENCY_STOP_PHRASES.any { phrase ->
            normalized == phrase || normalized.contains(phrase)
        }
    }

    fun parseVoiceConfirmation(input: String): VoiceConfirmationDecision {
        val normalized = input.trim().lowercase()
            .replace(Regex("[.,!?]+"), " ")
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return VoiceConfirmationDecision.UNKNOWN

        if (isEmergencyStopCommand(normalized) || CANCEL_PHRASES.any { normalized == it || normalized.contains(it) }) {
            return VoiceConfirmationDecision.CANCELLED
        }
        if (CONFIRM_PHRASES.any { normalized == it || normalized.contains(it) }) {
            return VoiceConfirmationDecision.CONFIRMED
        }
        return VoiceConfirmationDecision.UNKNOWN
    }

    /**
     * Wraps a structured [GokuAction] into a [PendingSafetyAction] when confirmation is required.
     */
    fun buildConfirmationForAction(action: GokuAction, originalCommand: String): PendingSafetyAction? {
        if (!action.requiresSafetyConfirmation()) return null
        return when (action.type) {
            GokuActionType.CALL -> PendingSafetyAction(
                type = SensitiveActionType.PHONE_CALL,
                rawCommand = originalCommand.ifBlank { action.readableSummary() },
                summary = action.readableSummary(),
                hindiVoicePrompt = "${action.phone.ifBlank { action.target }} ko phone call lagane ke liye Haan ya Naa boliye, ya Confirm dabaiye.",
                riskNotice = "Confirming will immediately place an outgoing call using Local ADB or Android Call API.",
                structuredAction = action
            )

            GokuActionType.SEND_SMS -> PendingSafetyAction(
                type = SensitiveActionType.SEND_MESSAGE,
                rawCommand = originalCommand.ifBlank { action.readableSummary() },
                summary = action.readableSummary(),
                hindiVoicePrompt = "${action.phone.ifBlank { action.target }} ko SMS bhejne ke liye Haan ya Naa boliye.",
                riskNotice = "Confirming will dispatch an SMS via your device SIM.",
                structuredAction = action
            )

            GokuActionType.SEND_WHATSAPP -> PendingSafetyAction(
                type = SensitiveActionType.SEND_MESSAGE,
                rawCommand = originalCommand.ifBlank { action.readableSummary() },
                summary = action.readableSummary(),
                hindiVoicePrompt = "${action.phone.ifBlank { action.target }} ko WhatsApp message bhejne ke liye Haan ya Naa boliye.",
                riskNotice = "Confirming will open WhatsApp with your message and tap Send if Accessibility is active.",
                structuredAction = action
            )

            GokuActionType.ADB_SHELL -> PendingSafetyAction(
                type = SensitiveActionType.SHELL_COMMAND,
                rawCommand = action.text.ifBlank { originalCommand },
                summary = action.readableSummary(),
                hindiVoicePrompt = "Rukiye! Yeh ADB shell command hai. Kya main ise 127.0.0.1:5555 par run karoon? Haan ya Naa boliye.",
                riskNotice = "ADB shell commands run with shell privileges over local ADB.",
                structuredAction = action
            )

            GokuActionType.FREEZE_APP -> PendingSafetyAction(
                type = SensitiveActionType.APP_FREEZE,
                rawCommand = originalCommand.ifBlank { action.readableSummary() },
                summary = action.readableSummary(),
                hindiVoicePrompt = "${action.target} app ko freeze karne ke liye Haan ya Naa boliye.",
                riskNotice = "Freezing disables the selected non-system app until you unfreeze it.",
                structuredAction = action
            )

            GokuActionType.DEV_TERMUX_CMD -> PendingSafetyAction(
                type = SensitiveActionType.SHELL_COMMAND,
                rawCommand = action.text.ifBlank { originalCommand },
                summary = action.readableSummary(),
                hindiVoicePrompt = "Rukiye! Termux mein command chalane se pehle confirm karein: ${action.text.take(60)}. Haan ya Naa boliye.",
                riskNotice = "Confirming will execute this command in Termux (or Local ADB fallback).",
                structuredAction = action
            )

            else -> null
        }
    }

    /**
     * Direct natural-language sensitive action detector (works even before/without Gemini JSON).
     */
    fun inspectForSensitiveAction(userText: String): PendingSafetyAction? {
        val trimmed = userText.trim()
        val lower = trimmed.lowercase()
        if (trimmed.isEmpty()) return null

        return when {
            SHELL_PATTERNS.any { it.containsMatchIn(lower) } -> {
                val extractedCmd = trimmed
                    .replace(Regex("(?i)^(run|execute)?\\s*adb\\s+shell\\s*"), "")
                    .trim()
                PendingSafetyAction(
                    type = SensitiveActionType.SHELL_COMMAND,
                    rawCommand = trimmed,
                    summary = "ADB / Shell command execution requested",
                    hindiVoicePrompt = "Rukiye! Yeh shell ya ADB command hai. Kya main aage badhoon? Haan ya Naa boliye, ya screen par Confirm dabaiye.",
                    riskNotice = "Shell commands execute directly via Local ADB 127.0.0.1:5555 after your confirmation.",
                    structuredAction = GokuAction(
                        type = GokuActionType.ADB_SHELL,
                        text = extractedCmd.ifBlank { trimmed }
                    )
                )
            }

            CALL_PATTERNS.any { it.containsMatchIn(lower) } -> {
                val target = extractCallTarget(trimmed)
                PendingSafetyAction(
                    type = SensitiveActionType.PHONE_CALL,
                    rawCommand = trimmed,
                    summary = "Outgoing phone call to ${target.ifBlank { "requested contact" }}",
                    hindiVoicePrompt = "Yeh phone call action hai. Kya aap sach mein call karna chahte hain? Haan ya Naa boliye.",
                    riskNotice = "Confirming will place a live phone call.",
                    structuredAction = GokuAction(
                        type = GokuActionType.CALL,
                        phone = target,
                        target = target
                    )
                )
            }

            WHATSAPP_PATTERNS.any { it.containsMatchIn(lower) } -> {
                val target = extractMessageTarget(trimmed)
                val msg = extractMessageBody(trimmed)
                PendingSafetyAction(
                    type = SensitiveActionType.SEND_MESSAGE,
                    rawCommand = trimmed,
                    summary = "WhatsApp message to ${target.ifBlank { "clipboard/contact" }}",
                    hindiVoicePrompt = "WhatsApp message bhejne ke liye Haan ya Naa boliye.",
                    riskNotice = "Confirming will open WhatsApp and send the message.",
                    structuredAction = GokuAction(
                        type = GokuActionType.SEND_WHATSAPP,
                        phone = target,
                        target = target,
                        text = msg
                    )
                )
            }

            SMS_PATTERNS.any { it.containsMatchIn(lower) } -> {
                val target = extractMessageTarget(trimmed)
                val msg = extractMessageBody(trimmed)
                PendingSafetyAction(
                    type = SensitiveActionType.SEND_MESSAGE,
                    rawCommand = trimmed,
                    summary = "Outgoing SMS to ${target.ifBlank { "requested contact" }}",
                    hindiVoicePrompt = "SMS bhejne ke liye Haan ya Naa boliye.",
                    riskNotice = "Confirming will send an SMS from your device.",
                    structuredAction = GokuAction(
                        type = GokuActionType.SEND_SMS,
                        phone = target,
                        target = target,
                        text = msg
                    )
                )
            }

            else -> null
        }
    }

    fun requestConfirmation(action: PendingSafetyAction) {
        _state.value = _state.value.copy(
            pendingAction = action,
            emergencyStopActive = false
        )
    }

    fun resolvePendingAction(): PendingSafetyAction? {
        val current = _state.value.pendingAction
        _state.value = _state.value.copy(pendingAction = null)
        return current
    }

    fun dismissPendingAction() {
        _state.value = _state.value.copy(pendingAction = null)
    }

    fun triggerEmergencyStop(reason: String = "Emergency kill switch activated (Goku stop)") {
        val current = _state.value
        _state.value = current.copy(
            pendingAction = null,
            emergencyStopActive = true,
            lastEmergencyReason = reason,
            emergencyTriggerCount = current.emergencyTriggerCount + 1
        )
    }

    fun clearEmergencyFlag() {
        if (_state.value.emergencyStopActive) {
            _state.value = _state.value.copy(emergencyStopActive = false)
        }
    }

    private fun extractCallTarget(text: String): String {
        val lower = text.lowercase()
        if (lower.contains("copied") || lower.contains("clipboard")) return "clipboard"
        return text
            .replace(Regex("(?i)\\b(please|goku|ko|par|pe|call|phone|dial|laga|lagao|kar|karo|to|my)\\b"), " ")
            .replace(Regex("[कॉलफोनलगाओकरो]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractMessageTarget(text: String): String {
        val lower = text.lowercase()
        if (lower.contains("copied") || lower.contains("clipboard")) return "clipboard"
        val beforeKeyword = text.split(Regex("(?i)\\b(whatsapp|sms|message|msg)\\b"), limit = 2).firstOrNull().orEmpty()
        return beforeKeyword
            .replace(Regex("(?i)\\b(please|goku|ko|par|pe|send|bhej|bhejo|to|a)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractMessageBody(text: String): String {
        val afterKeyword = text.split(Regex("(?i)\\b(ki|saying|bolna|likho|:)\\b"), limit = 2)
        return if (afterKeyword.size > 1) afterKeyword[1].trim() else "Hi"
    }

    companion object {
        private val EMERGENCY_STOP_PHRASES = listOf(
            "goku stop",
            "stop goku",
            "goku ruko",
            "ruko goku",
            "goku band karo",
            "गोकू स्टॉप",
            "गोकू रुको",
            "गोकू बंद करो",
            "स्टॉप गोकू",
            "रुको गोकू"
        )

        private val CONFIRM_PHRASES = listOf(
            "haan",
            "ha ",
            "han",
            "yes",
            "confirm",
            "kar do",
            "kardo",
            "theek hai",
            "thik hai",
            "ok kar do",
            "हां",
            "हाँ",
            "कर दो",
            "ठीक है"
        )

        private val CANCEL_PHRASES = listOf(
            "nahi",
            "nahin",
            "no",
            "cancel",
            "mat karo",
            "chhod do",
            "ruko",
            "stop",
            "नहीं",
            "मत करो",
            "कैंसल"
        )

        private val SHELL_PATTERNS = listOf(
            Regex("\\badb\\b"),
            Regex("\\bshell\\b"),
            Regex("\\btermux\\b"),
            Regex("\\bchmod\\b"),
            Regex("\\brm\\s+-rf\\b"),
            Regex("\\breboot\\b"),
            Regex("\\btcpip\\b"),
            Regex("\\bcommand\\s+chalao\\b"),
            Regex("\\bshell\\s+command\\b")
        )

        private val CALL_PATTERNS = listOf(
            Regex("\\bcall\\s+(kar|karo|laga|lagao|to|my)\\b"),
            Regex("\\bphone\\s+(laga|lagao|karo)\\b"),
            Regex("\\bdial\\s+\\d"),
            Regex("कॉल\\s*(करो|लगाओ)"),
            Regex("फोन\\s*(लगाओ|करो)")
        )

        private val WHATSAPP_PATTERNS = listOf(
            Regex("\\bwhatsapp\\s+(kar|karo|bhej|bhejo|message|msg|send)\\b"),
            Regex("\\b(send|bhej|bhejo)\\s+(a\\s+)?whatsapp\\b"),
            Regex("व्हाट्सएप\\s*(भेजो|करो)")
        )

        private val SMS_PATTERNS = listOf(
            Regex("\\b(send|bhej|bhejo)\\s+(a\\s+)?(sms|message|msg)\\b"),
            Regex("\\b(sms|message)\\s+(bhej|bhejo|karo|send)\\b"),
            Regex("मैसेज\\s*(भेजो|करो)")
        )
    }
}
