package com.arif.goku.action

import org.json.JSONObject

enum class GokuActionType {
    OPEN_APP,
    CLICK_TEXT,
    TYPE_TEXT,
    SCROLL,
    TAP,
    CALL,
    SEND_SMS,
    SEND_WHATSAPP,
    ADB_SHELL,
    TOGGLE_SETTING,
    READ_NOTIFICATIONS,
    READ_SCREEN,
    PHONE_DOCTOR,
    FREEZE_APP,
    UNFREEZE_APP,
    GLOBAL_NAV,
    FOCUS_MODE,
    TOGGLE_TORCH,
    ADJUST_VOLUME,
    SET_ALARM,
    DEV_GITHUB_BUILD,
    DEV_TERMUX_CMD
}

data class GokuAction(
    val type: GokuActionType,
    val target: String = "",
    val text: String = "",
    val phone: String = "",
    val setting: String = "",
    val enabled: Boolean? = null,
    val value: Int? = null,
    val x: Float? = null,
    val y: Float? = null,
    val forward: Boolean = true
) {
    fun requiresSafetyConfirmation(): Boolean = when (type) {
        GokuActionType.CALL,
        GokuActionType.SEND_SMS,
        GokuActionType.SEND_WHATSAPP,
        GokuActionType.ADB_SHELL,
        GokuActionType.FREEZE_APP,
        GokuActionType.DEV_TERMUX_CMD -> true
        else -> false
    }

    fun readableSummary(): String = when (type) {
        GokuActionType.OPEN_APP -> "Open app: $target"
        GokuActionType.CLICK_TEXT -> "Click \"$target\""
        GokuActionType.TYPE_TEXT -> "Type text into ${target.ifBlank { "focused field" }}"
        GokuActionType.SCROLL -> if (forward) "Scroll down" else "Scroll up"
        GokuActionType.TAP -> "Tap (${x?.toInt() ?: 0}, ${y?.toInt() ?: 0})"
        GokuActionType.CALL -> "Call ${phone.ifBlank { target }}"
        GokuActionType.SEND_SMS -> "Send SMS to ${phone.ifBlank { target }}: \"${text.take(60)}\""
        GokuActionType.SEND_WHATSAPP -> "Send WhatsApp to ${phone.ifBlank { target }}: \"${text.take(60)}\""
        GokuActionType.ADB_SHELL -> "Run ADB shell: $text"
        GokuActionType.TOGGLE_SETTING -> "Ghost toggle $setting (${enabled ?: value ?: ""})"
        GokuActionType.READ_NOTIFICATIONS -> "Read recent notifications"
        GokuActionType.READ_SCREEN -> "Inspect current screen"
        GokuActionType.PHONE_DOCTOR -> "Run Phone Doctor scan"
        GokuActionType.FREEZE_APP -> "Freeze app: $target"
        GokuActionType.UNFREEZE_APP -> "Unfreeze app: $target"
        GokuActionType.GLOBAL_NAV -> "Global nav: $target"
        GokuActionType.FOCUS_MODE -> if (enabled == false) "Turn Deep Focus Shield OFF" else "Turn Deep Focus Shield ON"
        GokuActionType.TOGGLE_TORCH -> if (enabled == false) "Turn Flashlight OFF" else "Turn Flashlight ON"
        GokuActionType.ADJUST_VOLUME -> "Adjust volume (${target.ifBlank { value?.toString() ?: "UP" }})"
        GokuActionType.SET_ALARM -> "Set alarm for ${target.ifBlank { "requested time" }}"
        GokuActionType.DEV_GITHUB_BUILD -> "Check GitHub Actions build (${target.ifBlank { "active repo" }})"
        GokuActionType.DEV_TERMUX_CMD -> "Run Termux command: $text"
    }
}

data class ParsedGeminiResponse(
    val visibleReply: String,
    val action: GokuAction?
)

/**
 * Extracts optional structured `<<<ACTION_JSON>>> ... <<<END_ACTION_JSON>>>` blocks from Gemini
 * replies while returning spotless Hindi/Hinglish text for the chat UI and TTS engine.
 */
object ActionJsonParser {

    private val ACTION_BLOCK_REGEX = Regex(
        "<<<ACTION_JSON>>>\\s*(\\{[\\s\\S]*?\\})\\s*<<<END_ACTION_JSON>>>",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val FENCED_JSON_REGEX = Regex(
        "```json\\s*(\\{[\\s\\S]*?\"action\"[\\s\\S]*?\\})\\s*```",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun parse(rawReply: String): ParsedGeminiResponse {
        val match = ACTION_BLOCK_REGEX.find(rawReply) ?: FENCED_JSON_REGEX.find(rawReply)
        if (match == null) {
            return ParsedGeminiResponse(visibleReply = rawReply.trim(), action = null)
        }

        val jsonPayload = match.groupValues[1].trim()
        val cleanedReply = rawReply.removeRange(match.range).trim()
            .ifBlank { "Theek hai, action tayyar kar raha hoon." }

        val parsedAction = parseJson(jsonPayload)
        return ParsedGeminiResponse(visibleReply = cleanedReply, action = parsedAction)
    }

    fun parseJson(jsonStr: String): GokuAction? {
        return try {
            val obj = JSONObject(jsonStr)
            val actionName = obj.optString("action").trim().uppercase()
            val type = GokuActionType.entries.firstOrNull { it.name == actionName } ?: return null

            val enabledOpt = if (obj.has("enabled") && !obj.isNull("enabled")) {
                obj.optBoolean("enabled")
            } else {
                null
            }
            val valueOpt = if (obj.has("value") && !obj.isNull("value")) {
                obj.optInt("value")
            } else {
                null
            }
            val xOpt = if (obj.has("x") && !obj.isNull("x")) obj.optDouble("x").toFloat() else null
            val yOpt = if (obj.has("y") && !obj.isNull("y")) obj.optDouble("y").toFloat() else null

            GokuAction(
                type = type,
                target = obj.optString("target", "").trim(),
                text = obj.optString("text", obj.optString("command", "")).trim(),
                phone = obj.optString("phone", "").trim(),
                setting = obj.optString("setting", "").trim(),
                enabled = enabledOpt,
                value = valueOpt,
                x = xOpt,
                y = yOpt,
                forward = obj.optBoolean("forward", true)
            )
        } catch (_: Exception) {
            null
        }
    }
}
