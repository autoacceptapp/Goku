package com.arif.goku.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.arif.goku.action.ActionJsonParser
import com.arif.goku.action.GokuAction
import com.arif.goku.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class BrainConnectionStatus {
    ONLINE,
    NO_API_KEY,
    OFFLINE
}

sealed class GeminiReplyResult {
    data class Success(
        val text: String,
        val spokenText: String,
        val action: GokuAction? = null
    ) : GeminiReplyResult()

    data class Error(
        val userMessage: String,
        val isCancelled: Boolean = false
    ) : GeminiReplyResult()
}

private data class ConversationTurn(
    val role: String, // "user" or "model"
    val text: String
)

/**
 * Online Gemini REST API client for GokuAgent (Phase 2 Dual-Engine Action Ready).
 *
 * Replies in natural, friendly Hinglish/Hindi suitable for Hindi Text-to-Speech, and can
 * optionally emit a structured `<<<ACTION_JSON>>>` command block for the Dual-Engine ActionExecutor.
 * API key is sent strictly via the `x-goog-api-key` HTTP header (never in URL or logs).
 */
class GeminiChatClient(
    private val context: Context,
    private val securePrefs: SecurePrefs
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val activeCall = AtomicReference<Call?>(null)
    private val historyLock = Any()
    private val history = ArrayDeque<ConversationTurn>()

    fun checkBrainStatus(): BrainConnectionStatus {
        val apiKey = securePrefs.getGeminiApiKey()
        if (apiKey.isBlank()) return BrainConnectionStatus.NO_API_KEY
        return if (isNetworkAvailable()) {
            BrainConnectionStatus.ONLINE
        } else {
            BrainConnectionStatus.OFFLINE
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun cancelActiveRequest() {
        activeCall.getAndSet(null)?.cancel()
    }

    fun clearHistory() {
        synchronized(historyLock) {
            history.clear()
        }
    }

    suspend fun sendMessage(
        userMessage: String,
        liveContext: LiveContextSnapshot? = null,
        smartContextDirective: String = "",
        memoryBlock: String = "",
        enableGoogleSearchGrounding: Boolean = securePrefs.isGoogleSearchGroundingEnabled()
    ): GeminiReplyResult = withContext(Dispatchers.IO) {
        val cleanedInput = userMessage.trim()
        if (cleanedInput.isEmpty()) {
            return@withContext GeminiReplyResult.Error("Kuch boliye ya type kijiye, main sun raha hoon.")
        }

        val apiKey = securePrefs.getGeminiApiKey()
        if (apiKey.isBlank()) {
            return@withContext GeminiReplyResult.Error(
                "Gemini API Key set nahi hai. Upar Settings icon dabakar apni API Key save karein."
            )
        }

        if (!isNetworkAvailable()) {
            return@withContext GeminiReplyResult.Error(
                "Internet connection offline hai. Network check karke dobara try karein."
            )
        }

        val model = securePrefs.getGeminiModel()
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        val promptWithContext = buildString {
            if (memoryBlock.isNotBlank()) {
                appendLine(memoryBlock)
                appendLine()
            }
            if (liveContext != null) {
                appendLine(liveContext.toPromptBlock())
                appendLine()
            }
            append("User: ")
            append(cleanedInput)
        }

        val firstAttempt = executeGeminiCall(
            endpoint = endpoint,
            apiKey = apiKey,
            payload = buildRequestJson(
                userPrompt = promptWithContext,
                smartContextDirective = smartContextDirective,
                includeGoogleSearchTool = enableGoogleSearchGrounding
            ),
            cleanedInput = cleanedInput
        )

        // If Google Search tool isn't supported on the chosen model/tier (HTTP 400), seamlessly retry without tool
        if (enableGoogleSearchGrounding && firstAttempt is GeminiReplyResult.Error &&
            !firstAttempt.isCancelled && firstAttempt.userMessage.contains("(400)")
        ) {
            return@withContext executeGeminiCall(
                endpoint = endpoint,
                apiKey = apiKey,
                payload = buildRequestJson(
                    userPrompt = promptWithContext,
                    smartContextDirective = smartContextDirective,
                    includeGoogleSearchTool = false
                ),
                cleanedInput = cleanedInput
            )
        }

        firstAttempt
    }

    /**
     * Phase 5 Stateless Multimodal (Text + Optional JPEG/WebP Vision) Gemini API call.
     * Used by Universal App Pilot (Screen Vision fallback), Screen Co-Pilot, Zero-Copy Bridge,
     * and Background Task Queue research without mutating conversational chat history.
     */
    suspend fun sendVisionPrompt(
        prompt: String,
        imageBytes: ByteArray? = null,
        mimeType: String = "image/jpeg",
        systemInstruction: String = SYSTEM_PROMPT_PHASE4,
        includeGoogleSearchTool: Boolean = false,
        maxOutputTokens: Int = 520
    ): GeminiReplyResult = withContext(Dispatchers.IO) {
        val apiKey = securePrefs.getGeminiApiKey()
        if (apiKey.isBlank()) {
            return@withContext GeminiReplyResult.Error(
                "Gemini API Key set nahi hai. Settings -> Brain mein apni API Key save karein."
            )
        }
        if (!isNetworkAvailable()) {
            return@withContext GeminiReplyResult.Error(
                "Internet offline hai. Screen Vision ya Web Research ke liye network chahiye."
            )
        }

        val model = securePrefs.getGeminiModel()
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"

        val partsArray = JSONArray().apply {
            put(JSONObject().put("text", prompt))
            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().apply {
                            put("mime_type", mimeType)
                            put("data", base64Data)
                        }
                    )
                )
            }
        }

        fun buildStatelessPayload(withSearch: Boolean): JSONObject = JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", partsArray)
                    }
                )
            )
            if (withSearch) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.35)
                    put("maxOutputTokens", maxOutputTokens)
                }
            )
        }

        val first = executeStatelessCall(endpoint, apiKey, buildStatelessPayload(includeGoogleSearchTool))
        if (includeGoogleSearchTool && first is GeminiReplyResult.Error &&
            !first.isCancelled && first.userMessage.contains("(400)")
        ) {
            return@withContext executeStatelessCall(endpoint, apiKey, buildStatelessPayload(false))
        }
        first
    }

    private fun executeStatelessCall(
        endpoint: String,
        apiKey: String,
        payload: JSONObject
    ): GeminiReplyResult {
        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val call = httpClient.newCall(request)
        activeCall.set(call)

        return try {
            call.execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return GeminiReplyResult.Error(mapHttpError(response.code, bodyString))
                }
                val rawReply = extractReplyText(bodyString)
                if (rawReply.isNullOrBlank()) {
                    return GeminiReplyResult.Error("Gemini Vision se clear response nahi mila.")
                }
                GeminiReplyResult.Success(
                    text = rawReply.trim(),
                    spokenText = sanitizeForSpeech(rawReply),
                    action = null
                )
            }
        } catch (_: IOException) {
            if (call.isCanceled()) {
                GeminiReplyResult.Error("Request stopped.", isCancelled = true)
            } else {
                GeminiReplyResult.Error("Network disconnect ho gaya.")
            }
        } catch (_: Exception) {
            GeminiReplyResult.Error("Vision request mein error aaya.")
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun executeGeminiCall(
        endpoint: String,
        apiKey: String,
        payload: JSONObject,
        cleanedInput: String
    ): GeminiReplyResult {
        val requestBody = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        cancelActiveRequest()
        val call = httpClient.newCall(request)
        activeCall.set(call)

        return try {
            call.execute().use { response ->
                val bodyString = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    return GeminiReplyResult.Error(mapHttpError(response.code, bodyString))
                }

                val rawReply = extractReplyText(bodyString)
                if (rawReply.isNullOrBlank()) {
                    return GeminiReplyResult.Error(
                        "Goku ko abhi clear jawab nahi mila. Kripya dobara try karein."
                    )
                }

                val parsed = ActionJsonParser.parse(rawReply)
                recordTurn(cleanedInput, parsed.visibleReply)

                GeminiReplyResult.Success(
                    text = parsed.visibleReply,
                    spokenText = sanitizeForSpeech(parsed.visibleReply),
                    action = parsed.action
                )
            }
        } catch (_: IOException) {
            if (call.isCanceled()) {
                GeminiReplyResult.Error("Request stopped.", isCancelled = true)
            } else {
                GeminiReplyResult.Error(
                    "Network slow ya disconnect ho gaya hai. Thodi der baad dobara koshish karein."
                )
            }
        } catch (_: Exception) {
            GeminiReplyResult.Error("Kuch technical dikkat aayi. Kripya dobara try karein.")
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private fun buildRequestJson(
        userPrompt: String,
        smartContextDirective: String,
        includeGoogleSearchTool: Boolean
    ): JSONObject {
        val contentsArray = JSONArray()

        synchronized(historyLock) {
            history.forEach { turn ->
                contentsArray.put(
                    JSONObject().apply {
                        put("role", turn.role)
                        put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                    }
                )
            }
        }

        contentsArray.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }
        )

        val fullSystemPrompt = if (smartContextDirective.isNotBlank()) {
            "$SYSTEM_PROMPT_PHASE4\n\nACTIVE CONTEXT RULES: $smartContextDirective"
        } else {
            SYSTEM_PROMPT_PHASE4
        }

        return JSONObject().apply {
            put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", fullSystemPrompt))
                )
            )
            put("contents", contentsArray)
            if (includeGoogleSearchTool) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0.65)
                    put("maxOutputTokens", 450)
                }
            )
        }
    }

    private fun extractReplyText(rawJson: String): String? {
        if (rawJson.isBlank()) return null
        return try {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (partText.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append(partText.trim())
                }
            }
            builder.toString().trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun mapHttpError(code: Int, rawBody: String): String {
        val serverMsg = try {
            JSONObject(rawBody).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return when (code) {
            400 -> if (serverMsg.contains("API key", ignoreCase = true)) {
                "API Key invalid lag rahi hai. Settings mein apni Gemini API Key check karein."
            } else {
                "Request samajh nahi aayi (400). Kripya dobara try karein."
            }
            401, 403 -> "Gemini API Key authorize nahi hui ($code). Settings mein sahi key daalein."
            404 -> "Selected Gemini model abhi available nahi hai. Settings mein model change karein."
            429 -> "Free-tier rate limit poori ho gayi hai (429). 30 second baad dobara try karein."
            in 500..599 -> "Gemini server abhi busy hai ($code). Thodi der baad try karein."
            else -> "Gemini se connect nahi ho paya (HTTP $code)."
        }
    }

    private fun recordTurn(userText: String, modelText: String) {
        synchronized(historyLock) {
            history.addLast(ConversationTurn("user", userText))
            history.addLast(ConversationTurn("model", modelText))
            while (history.size > MAX_HISTORY_TURNS) {
                history.removeFirst()
            }
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_HISTORY_TURNS = 16

        private const val SYSTEM_PROMPT_PHASE4 =
            "Tum Goku ho — ek smart, friendly, loyal Jarvis-style personal AI phone agent. " +
                "Hamesha natural, warm Hinglish ya aasaan Hindi mein chhote aur clear jawab do " +
                "(1 se 3 sentences) taaki Hindi TTS voice mein mast lage. Markdown symbols (*, #, `) mat use karo. " +
                "Agar [PERSONAL MEMORY VAULT] diya ho, to use yaad rakhkar personalized jawab do. " +
                "Agar user koi phone action karne ko kahe, to apni Hindi reply ke BILKUL AAKHIR mein ek structured JSON block is exact format mein add karo:\n" +
                "<<<ACTION_JSON>>>\n" +
                "{\"action\":\"ACTION_NAME\",\"target\":\"...\",\"text\":\"...\",\"phone\":\"...\",\"setting\":\"...\",\"enabled\":true,\"value\":180,\"forward\":true}\n" +
                "<<<END_ACTION_JSON>>>\n" +
                "Allowed ACTION_NAME values: OPEN_APP (target=app name), CLICK_TEXT (target=button text), " +
                "TYPE_TEXT (target=field hint optional, text=text), SCROLL (forward=true/false), " +
                "CALL (phone=number or contact name or 'clipboard'), SEND_SMS (phone=..., text=...), " +
                "SEND_WHATSAPP (phone=number/contact/'clipboard', text=...), ADB_SHELL (text=shell command), " +
                "TOGGLE_SETTING (setting='wifi'|'bluetooth'|'brightness'|'dnd'|'dark_mode', enabled=true/false, value=10..255), " +
                "TOGGLE_TORCH (enabled=true/false), ADJUST_VOLUME (target='UP'|'DOWN'|'MUTE'|'MAX', value=0..100 optional), " +
                "SET_ALARM (target='HH:mm', text='label'), DEV_GITHUB_BUILD (target=optional repo), DEV_TERMUX_CMD (text=shell command), " +
                "READ_NOTIFICATIONS, READ_SCREEN, PHONE_DOCTOR, FREEZE_APP (target=app/pkg), UNFREEZE_APP (target=app/pkg), " +
                "GLOBAL_NAV (target='HOME'|'BACK'|'NOTIFICATIONS'), FOCUS_MODE (enabled=true/false for Kaam/Focus Mode).\n" +
                "Agar user normal sawal pooche (koi phone action nahi), to JSON block bilkul mat bhejo."

        fun sanitizeForSpeech(text: String): String {
            return text
                .replace(Regex("[*_`#~]+"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
