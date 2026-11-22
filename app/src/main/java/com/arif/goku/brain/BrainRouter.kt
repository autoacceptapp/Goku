package com.arif.goku.brain

import com.arif.goku.action.GokuAction
import com.arif.goku.action.GokuActionType
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.system.FocusShieldManager

enum class ActiveBrainTier(val badgeText: String) {
    ONLINE_GEMINI("ONLINE (Gemini)"),
    OFFLINE_LOCAL_AI("OFFLINE (Local AI)"),
    OFFLINE_REFLEX("OFFLINE (Reflex)")
}

sealed class BrainRouteResult {
    /** Tier 1 (0ms offline reflex): executes immediately without cloud or LLM latency. */
    data class FastReflexAction(
        val action: GokuAction,
        val introReplyHindi: String = ""
    ) : BrainRouteResult()

    /** Direct memory vault mutation ("Yaad rakhna..." / "Bhool jaao..."). */
    data class MemoryHandled(
        val replyHindi: String
    ) : BrainRouteResult()

    /** Tier 2 (Offline LiteRT-LM / MediaPipe LLM) or Tier 3 (Online Gemini) answer. */
    data class BrainResponse(
        val tier: ActiveBrainTier,
        val text: String,
        val spokenText: String,
        val action: GokuAction? = null,
        val injectedMemoryCount: Int = 0
    ) : BrainRouteResult()

    data class Error(
        val userMessage: String,
        val isCancelled: Boolean = false
    ) : BrainRouteResult()
}

/**
 * Phase 4 Hybrid 3-Tier Brain Router:
 * - **Tier 1 (0ms Fast Offline Reflex):** Instant regex/intent matcher for device controls
 *   (Torch, Wi-Fi, Bluetooth, Brightness, DND, Volume, Alarm, Open App, Global Nav,
 *   Read Notifications, Phone Doctor, App Freezer, Deep Focus Shield, GitHub Build, Termux).
 * - **Tier 2 (Offline Local LLM):** Uses `OfflineLlmEngine` (LiteRT-LM / MediaPipe Tasks GenAI)
 *   when offline or no API key is configured, enriched with Personal Memory Vault context.
 * - **Tier 3 (Online Gemini Brain):** Uses `GeminiChatClient` with Google Search Grounding,
 *   Live Device Context, Smart Context directives, and Personal Memory Vault context.
 */
class BrainRouter(
    private val geminiClient: GeminiChatClient,
    private val offlineLlmEngine: OfflineLlmEngine,
    private val memoryManager: MemoryManager
) {
    fun resolveActiveTier(): ActiveBrainTier {
        return resolveTier(
            isOnlineWithKey = geminiClient.checkBrainStatus() == BrainConnectionStatus.ONLINE,
            hasLocalLlmModel = offlineLlmEngine.isModelAvailable()
        )
    }

    suspend fun route(
        userInput: String,
        liveContext: LiveContextSnapshot?,
        smartContextDirective: String = ""
    ): BrainRouteResult {
        val cleaned = userInput.trim()
        if (cleaned.isEmpty()) {
            return BrainRouteResult.Error("Kuch boliye ya type kijiye, main sun raha hoon.")
        }

        // 0. Explicit Personal Memory Vault commands ("Yaad rakhna..." / "Bhool jaao...")
        val memoryOutcome = memoryManager.processExplicitMemoryCommand(cleaned)
        if (memoryOutcome != null && memoryOutcome.handled) {
            return BrainRouteResult.MemoryHandled(memoryOutcome.replyHindi)
        }

        // Passively learn personal facts/contacts/preferences in background
        memoryManager.autoExtractFromUserMessage(cleaned)

        // 1. Tier 1 — Fast Offline Reflex (0ms, zero internet required)
        val reflexAction = matchFastOfflineReflex(cleaned)
        if (reflexAction != null) {
            return BrainRouteResult.FastReflexAction(action = reflexAction)
        }

        // Retrieve top relevant personal memories for both Offline Local AI and Online Gemini
        val relevantMemories = memoryManager.retrieveRelevantMemories(cleaned, limit = 6)
        val memoryBlock = memoryManager.buildMemoryPromptBlock(cleaned, limit = 6)

        // 2. Decide Tier 3 (Online Gemini) vs Tier 2 (Offline Local LLM)
        val activeTier = resolveActiveTier()
        if (activeTier == ActiveBrainTier.ONLINE_GEMINI) {
            return when (
                val gemRes = geminiClient.sendMessage(
                    userMessage = cleaned,
                    liveContext = liveContext,
                    smartContextDirective = smartContextDirective,
                    memoryBlock = memoryBlock
                )
            ) {
                is GeminiReplyResult.Success -> BrainRouteResult.BrainResponse(
                    tier = ActiveBrainTier.ONLINE_GEMINI,
                    text = gemRes.text,
                    spokenText = gemRes.spokenText,
                    action = gemRes.action,
                    injectedMemoryCount = relevantMemories.size
                )

                is GeminiReplyResult.Error -> {
                    if (gemRes.isCancelled) {
                        BrainRouteResult.Error(gemRes.userMessage, isCancelled = true)
                    } else if (offlineLlmEngine.isModelAvailable()) {
                        // Seamless fallback to Offline Local AI if cloud call fails mid-flight
                        runLocalOfflineLlm(cleaned, memoryBlock, smartContextDirective, relevantMemories.size)
                    } else {
                        BrainRouteResult.Error(gemRes.userMessage, isCancelled = false)
                    }
                }
            }
        }

        // 3. Tier 2 — Offline Local LLM (LiteRT-LM / MediaPipe)
        if (activeTier == ActiveBrainTier.OFFLINE_LOCAL_AI) {
            return runLocalOfflineLlm(cleaned, memoryBlock, smartContextDirective, relevantMemories.size)
        }

        // 4. Offline Reflex-only fallback when offline and no local .task/.litertlm model imported
        val hint = if (geminiClient.checkBrainStatus() == BrainConnectionStatus.NO_API_KEY) {
            "Offline Reflex mode active hai (Torch, Wi-Fi, Alarm, Open App, Call, Kaam Mode ready). Chat ke liye Settings -> Brain mein Gemini API Key ya Offline .task/.litertlm model add karein."
        } else {
            "Internet offline hai aur Local AI model import nahi hua hai. Offline Reflex commands (Torch, Wi-Fi, Volume, Alarm, Open App, Call) abhi bhi kaam karenge."
        }
        return BrainRouteResult.Error(hint)
    }

    private suspend fun runLocalOfflineLlm(
        userQuery: String,
        memoryBlock: String,
        styleDirective: String,
        memoryCount: Int
    ): BrainRouteResult {
        val localRes = offlineLlmEngine.generateOfflineReply(
            userQuery = userQuery,
            memoryBlock = memoryBlock,
            styleDirective = styleDirective
        )
        return localRes.fold(
            onSuccess = { reply ->
                BrainRouteResult.BrainResponse(
                    tier = ActiveBrainTier.OFFLINE_LOCAL_AI,
                    text = reply,
                    spokenText = reply,
                    action = null,
                    injectedMemoryCount = memoryCount
                )
            },
            onFailure = { err ->
                BrainRouteResult.Error(err.message ?: "Offline Local AI jawab nahi de paya.")
            }
        )
    }

    /**
     * Phase 5 helper for Universal App Pilot, Screen Co-Pilot, Zero-Copy Bridge, and Background
     * Research: uses Gemini Vision/Text when online (with optional JPEG bytes & Google Search),
     * or falls back to Offline Local AI when offline for text-only tasks.
     */
    suspend fun askMultimodalOrLocal(
        prompt: String,
        imageBytes: ByteArray? = null,
        systemInstruction: String,
        includeGoogleSearchTool: Boolean = false
    ): Result<String> {
        val tier = resolveActiveTier()
        if (tier == ActiveBrainTier.ONLINE_GEMINI) {
            return when (
                val res = geminiClient.sendVisionPrompt(
                    prompt = prompt,
                    imageBytes = imageBytes,
                    systemInstruction = systemInstruction,
                    includeGoogleSearchTool = includeGoogleSearchTool
                )
            ) {
                is GeminiReplyResult.Success -> Result.success(res.text)
                is GeminiReplyResult.Error -> {
                    if (!res.isCancelled && imageBytes == null && offlineLlmEngine.isModelAvailable()) {
                        offlineLlmEngine.generateOfflineReply(userQuery = prompt, styleDirective = systemInstruction)
                    } else {
                        Result.failure(IllegalStateException(res.userMessage))
                    }
                }
            }
        }

        if (tier == ActiveBrainTier.OFFLINE_LOCAL_AI && imageBytes == null) {
            return offlineLlmEngine.generateOfflineReply(userQuery = prompt, styleDirective = systemInstruction)
        }

        return Result.failure(
            IllegalStateException(
                if (imageBytes != null) {
                    "Screen Vision ke liye internet aur Gemini API Key zaroori hai."
                } else {
                    "Internet offline hai aur Local AI model installed nahi hai."
                }
            )
        )
    }

    companion object {
        fun resolveTier(isOnlineWithKey: Boolean, hasLocalLlmModel: Boolean): ActiveBrainTier {
            return when {
                isOnlineWithKey -> ActiveBrainTier.ONLINE_GEMINI
                hasLocalLlmModel -> ActiveBrainTier.OFFLINE_LOCAL_AI
                else -> ActiveBrainTier.OFFLINE_REFLEX
            }
        }

        /**
         * Pure 0ms Tier-1 Offline Reflex matcher.
         * Covers Torch, Wi-Fi, Bluetooth, DND, Dark Mode, Volume, Alarm, Open App,
         * Global Nav, Read Notifications, Phone Doctor, App Freezer, Focus Shield,
         * GitHub Build Check, and Termux Command.
         */
        fun matchFastOfflineReflex(rawInput: String): GokuAction? {
            val trimmed = rawInput.trim()
            val lower = trimmed.lowercase()
            if (lower.isEmpty()) return null

            // Deep Focus Shield ("Goku, kaam mode ON" / "Focus mode OFF")
            if (FocusShieldManager.isFocusOnCommand(trimmed)) {
                return GokuAction(GokuActionType.FOCUS_MODE, enabled = true)
            }
            if (FocusShieldManager.isFocusOffCommand(trimmed)) {
                return GokuAction(GokuActionType.FOCUS_MODE, enabled = false)
            }

            // Goku Dev Mode — GitHub Actions Build Check
            if (lower.contains("github build") || lower.contains("build check") ||
                lower.contains("workflow status") || lower.contains("actions build") ||
                lower.contains("गिटहब बिल्ड")
            ) {
                val repoMatch = Regex("([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)").find(trimmed)?.groupValues?.get(1)
                    ?: when {
                        lower.contains("order") -> "Order-master"
                        lower.contains("goku") -> "goku_agent"
                        else -> ""
                    }
                return GokuAction(GokuActionType.DEV_GITHUB_BUILD, target = repoMatch)
            }

            // Goku Dev Mode — Termux Command (will be gated by SafetyGuard)
            TERMUX_CMD_REGEX.find(trimmed)?.let { m ->
                val cmd = m.groupValues[1].ifBlank { m.groupValues.getOrNull(2).orEmpty() }
                    .replace(Regex("\\s+(?:chalao|chala\\s+do|run\\s+karo|चलाओ)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (cmd.isNotBlank()) {
                    return GokuAction(GokuActionType.DEV_TERMUX_CMD, text = cmd)
                }
            }

            // Torch / Flashlight
            if (lower.contains("torch") || lower.contains("flashlight") || lower.contains("flash light") ||
                lower.contains("टॉर्च") || lower.contains("फ्लैशलाइट")
            ) {
                val turnOff = isOffIntent(lower)
                return GokuAction(GokuActionType.TOGGLE_TORCH, enabled = !turnOff)
            }

            // Wi-Fi Ghost Toggle
            if (lower.contains("wifi") || lower.contains("wi-fi") || lower.contains("वाईफाई")) {
                if (isOnIntent(lower) || isOffIntent(lower)) {
                    return GokuAction(GokuActionType.TOGGLE_SETTING, setting = "wifi", enabled = !isOffIntent(lower))
                }
            }

            // Bluetooth Ghost Toggle
            if (lower.contains("bluetooth") || lower.contains("ब्लूटूथ")) {
                if (isOnIntent(lower) || isOffIntent(lower)) {
                    return GokuAction(GokuActionType.TOGGLE_SETTING, setting = "bluetooth", enabled = !isOffIntent(lower))
                }
            }

            // DND Ghost Toggle
            if (lower.contains("dnd") || lower.contains("do not disturb")) {
                if (isOnIntent(lower) || isOffIntent(lower)) {
                    return GokuAction(GokuActionType.TOGGLE_SETTING, setting = "dnd", enabled = !isOffIntent(lower))
                }
            }

            // Volume Control
            if (lower.contains("volume") || lower.contains("awaaz") || lower.contains("awaz") || lower.contains("वॉल्यूम")) {
                val pct = Regex("(\\d{1,3})\\s*%?").find(lower)?.groupValues?.get(1)?.toIntOrNull()
                return when {
                    lower.contains("mute") || lower.contains("silent") || lower.contains("band") ->
                        GokuAction(GokuActionType.ADJUST_VOLUME, target = "MUTE")
                    lower.contains("full") || lower.contains("max") ->
                        GokuAction(GokuActionType.ADJUST_VOLUME, target = "MAX")
                    pct != null && pct in 0..100 ->
                        GokuAction(GokuActionType.ADJUST_VOLUME, target = "SET", value = pct)
                    lower.contains("down") || lower.contains("kam") || lower.contains("low") || lower.contains("कम") ->
                        GokuAction(GokuActionType.ADJUST_VOLUME, target = "DOWN")
                    lower.contains("up") || lower.contains("badha") || lower.contains("high") || lower.contains("ज्यादा") ->
                        GokuAction(GokuActionType.ADJUST_VOLUME, target = "UP")
                    else -> null
                }
            }

            // Alarm ("6:30 ka alarm set karo" / "set alarm for 7 am")
            if (lower.contains("alarm") || lower.contains("अलार्म")) {
                val parsedTime = parseAlarmTime(lower)
                if (parsedTime != null) {
                    return GokuAction(GokuActionType.SET_ALARM, target = parsedTime, text = "Goku Alarm")
                }
            }

            // Phone Doctor
            if (lower.contains("phone doctor") || lower.contains("ram scan") || lower.contains("phone scan") ||
                lower.contains("फ़ोन डॉक्टर")
            ) {
                return GokuAction(GokuActionType.PHONE_DOCTOR)
            }

            // App Freezer / Unfreezer
            FREEZE_APP_REGEX.find(trimmed)?.let { m ->
                val target = m.groupValues[1].trim()
                if (target.isNotBlank()) {
                    val isUnfreeze = lower.contains("unfreeze") || lower.contains("un-freeze")
                    return GokuAction(
                        type = if (isUnfreeze) GokuActionType.UNFREEZE_APP else GokuActionType.FREEZE_APP,
                        target = target
                    )
                }
            }

            // Global Nav & Notifications
            if (lower == "go home" || lower.contains("home screen") || lower.contains("home jao") || lower.contains("होम जाओ")) {
                return GokuAction(GokuActionType.GLOBAL_NAV, target = "HOME")
            }
            if (lower == "go back" || lower.contains("back jao") || lower.contains("peeche jao") || lower.contains("बैक जाओ")) {
                return GokuAction(GokuActionType.GLOBAL_NAV, target = "BACK")
            }
            if (lower.contains("open notifications") || lower.contains("notification kholo") || lower.contains("notifications kholo")) {
                return GokuAction(GokuActionType.GLOBAL_NAV, target = "NOTIFICATIONS")
            }
            if (lower.contains("read notification") || lower.contains("notifications padho") ||
                lower.contains("notification batao") || lower.contains("नोटिफिकेशन पढ़ो")
            ) {
                return GokuAction(GokuActionType.READ_NOTIFICATIONS)
            }

            // Open App ("YouTube kholo" / "open Instagram")
            OPEN_APP_PREFIX_REGEX.find(trimmed)?.let { m ->
                val app = m.groupValues[1].trim().trimEnd('.', '!')
                if (app.length in 2..35 && !app.contains("notification", ignoreCase = true)) {
                    return GokuAction(GokuActionType.OPEN_APP, target = app)
                }
            }
            OPEN_APP_SUFFIX_REGEX.find(trimmed)?.let { m ->
                val app = m.groupValues[1].trim()
                    .replace(Regex("(?i)^(goku|please|hey)\\s+"), "")
                    .trim()
                if (app.length in 2..35 && !app.contains("notification", ignoreCase = true)) {
                    return GokuAction(GokuActionType.OPEN_APP, target = app)
                }
            }

            return null
        }

        private fun isOffIntent(lower: String): Boolean =
            lower.contains(" off") || lower.endsWith("off") || lower.contains("band") ||
                lower.contains("disable") || lower.contains("बंद") || lower.contains("ऑफ")

        private fun isOnIntent(lower: String): Boolean =
            lower.contains(" on") || lower.endsWith("on") || lower.contains("chalu") ||
                lower.contains("enable") || lower.contains("चालू") || lower.contains("ऑन")

        private fun parseAlarmTime(lower: String): String? {
            val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|baje|बजे)?").find(lower) ?: return null
            var hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val meridiem = match.groupValues[3].lowercase()
            if (hour !in 0..23 || minute !in 0..59) return null
            if (meridiem == "pm" && hour in 1..11) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0
            return String.format("%02d:%02d", hour, minute)
        }

        private val TERMUX_CMD_REGEX = Regex(
            "(?:termux\\s+(?:mein|me|run|command)\\s*(?:command\\s*)?(?:chalao|run\\s*karo)?\\s*[:,-]?\\s*(.+)|run\\s+in\\s+termux\\s*[:,-]?\\s*(.+))",
            RegexOption.IGNORE_CASE
        )

        private val FREEZE_APP_REGEX = Regex(
            "(?:unfreeze|freeze)\\s+(?:app\\s+)?([a-zA-Z0-9 ._\\-]{2,40})",
            RegexOption.IGNORE_CASE
        )

        private val OPEN_APP_PREFIX_REGEX = Regex(
            "^(?:goku[,\\s]+)?(?:open|launch|start)\\s+([a-zA-Z0-9 ._\\-]{2,35})$",
            RegexOption.IGNORE_CASE
        )

        private val OPEN_APP_SUFFIX_REGEX = Regex(
            "^([a-zA-Z0-9 ._\\-]{2,35})\\s+(?:app\\s+)?(?:kholo|khol\\s+do|open\\s+karo|खोलो)$",
            RegexOption.IGNORE_CASE
        )
    }
}
