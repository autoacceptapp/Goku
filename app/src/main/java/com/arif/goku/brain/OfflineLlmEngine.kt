package com.arif.goku.brain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class OfflineLlmStage {
    NOT_INSTALLED,
    IMPORTING,
    DOWNLOADING,
    READY,
    ERROR
}

data class OfflineLlmState(
    val stage: OfflineLlmStage = OfflineLlmStage.NOT_INSTALLED,
    val modelFileName: String = "",
    val modelSizeMb: Float = 0f,
    val progressPercent: Int = 0,
    val isEngineLoaded: Boolean = false,
    val statusMessage: String = "No local .task / .litertlm model imported yet."
)

/**
 * Phase 4 Offline Local LLM Engine (`LiteRT-LM` / MediaPipe Tasks GenAI `LlmInference`).
 *
 * Manages quantized on-device models (e.g., Gemma `.task` / `.litertlm` / `.bin`) inside
 * `context.filesDir/llm_model` so the APK stays lightweight.
 * - Supports zero-permission SAF file import (`OpenDocument`) from local storage/Downloads.
 * - Supports optional Wi-Fi HTTPS model download with live progress & size validation.
 * - Lazily initializes `LlmInference` on IO thread and generates offline Hinglish/Hindi responses.
 */
class OfflineLlmEngine(context: Context) {

    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, MODEL_DIR_NAME)
    private val inferenceMutex = Mutex()
    private val activeDownload = AtomicReference<Call?>(null)

    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(inspectModelState())
    val state: StateFlow<OfflineLlmState> = _state.asStateFlow()

    fun isModelAvailable(): Boolean = findInstalledModelFile() != null

    fun refreshState(): OfflineLlmState {
        val current = inspectModelState()
        _state.value = current
        return current
    }

    /**
     * Imports a user-selected quantized `.task` / `.litertlm` / `.bin` model via SAF Uri
     * into `context.filesDir/llm_model`.
     */
    suspend fun importModelFromUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val rawName = resolveDisplayName(uri) ?: "local_gemma_model.task"
        val safeName = sanitizeModelFileName(rawName)
        if (!hasAllowedExtension(safeName)) {
            return@withContext Result.failure(
                IllegalArgumentException("Sirf .task, .litertlm, ya .bin quantized LLM model file select karein.")
            )
        }

        _state.update {
            it.copy(
                stage = OfflineLlmStage.IMPORTING,
                progressPercent = 10,
                statusMessage = "Importing $safeName into local vault…"
            )
        }

        try {
            closeEngine()
            clearExistingModels()
            modelDir.mkdirs()

            val targetFile = File(modelDir, safeName)
            val canonicalRoot = modelDir.canonicalPath + File.separator
            if (!targetFile.canonicalPath.startsWith(canonicalRoot)) {
                throw SecurityException("Blocked invalid model file path.")
            }

            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    if (copied < MIN_VALID_MODEL_BYTES) {
                        targetFile.delete()
                        throw IllegalArgumentException("Selected file bahut chhoti hai — valid quantized model nahi lag rahi.")
                    }
                }
            } ?: throw IllegalStateException("File open nahi ho paayi.")

            val refreshed = inspectModelState()
            _state.value = refreshed
            Result.success("Offline AI model '$safeName' (${String.format("%.1f", refreshed.modelSizeMb)} MB) ready!")
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    stage = OfflineLlmStage.ERROR,
                    progressPercent = 0,
                    statusMessage = e.message ?: "Model import fail hua."
                )
            }
            Result.failure(e)
        }
    }

    /**
     * Downloads a quantized model over Wi-Fi from a user-supplied HTTPS URL (e.g. GitHub/HuggingFace direct link).
     */
    suspend fun downloadModelOverWifi(rawUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedUrl = rawUrl.trim()
        if (!trimmedUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext Result.failure(
                IllegalArgumentException("Sirf secure https:// direct model link (.task / .litertlm) allow hai.")
            )
        }
        if (!isWifiConnected()) {
            return@withContext Result.failure(
                IllegalStateException("Bada LLM model download karne ke liye Wi-Fi connect karein (mobile data bachane ke liye).")
            )
        }

        val urlFileName = trimmedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "gemma_local.task" }
        val safeName = sanitizeModelFileName(
            if (hasAllowedExtension(urlFileName)) urlFileName else "$urlFileName.task"
        )
        val tempFile = File(appContext.cacheDir, "llm_download.tmp")

        try {
            _state.update {
                it.copy(
                    stage = OfflineLlmStage.DOWNLOADING,
                    progressPercent = 0,
                    statusMessage = "Downloading $safeName over Wi-Fi…"
                )
            }

            val req = Request.Builder()
                .url(trimmedUrl)
                .header("User-Agent", "GokuAgent-Android/4.0")
                .get()
                .build()
            val call = httpClient.newCall(req)
            activeDownload.set(call)

            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Model download failed (HTTP ${resp.code}).")
                }
                val body = resp.body ?: throw IllegalStateException("Empty download stream.")
                val totalBytes = body.contentLength().coerceAtLeast(-1L)
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            output.write(buf, 0, r)
                            copied += r
                            if (totalBytes > 0L) {
                                val pct = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 99)
                                if (pct != lastPct) {
                                    lastPct = pct
                                    _state.update {
                                        it.copy(
                                            progressPercent = pct,
                                            modelSizeMb = copied / (1024f * 1024f),
                                            statusMessage = "Downloading $safeName… $pct%"
                                        )
                                    }
                                }
                            }
                        }
                        if (copied < MIN_VALID_MODEL_BYTES) {
                            throw IllegalStateException("Downloaded file valid model nahi hai (size too small).")
                        }
                    }
                }
            }

            closeEngine()
            clearExistingModels()
            modelDir.mkdirs()
            val dest = File(modelDir, safeName)
            if (!tempFile.renameTo(dest)) {
                tempFile.copyTo(dest, overwrite = true)
            }

            val refreshed = inspectModelState()
            _state.value = refreshed
            Result.success("Offline AI model '$safeName' download aur install ho gaya!")
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    stage = OfflineLlmStage.ERROR,
                    progressPercent = 0,
                    statusMessage = e.message ?: "Model download error."
                )
            }
            Result.failure(e)
        } finally {
            activeDownload.set(null)
            tempFile.delete()
        }
    }

    fun cancelDownload() {
        activeDownload.getAndSet(null)?.cancel()
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        cancelDownload()
        closeEngine()
        clearExistingModels()
        _state.value = inspectModelState()
    }

    /**
     * Generates an offline Hinglish/Hindi response using the local MediaPipe/LiteRT-LM model.
     */
    suspend fun generateOfflineReply(
        userQuery: String,
        memoryBlock: String = "",
        styleDirective: String = ""
    ): Result<String> = withContext(Dispatchers.IO) {
        val modelFile = findInstalledModelFile()
            ?: return@withContext Result.failure(
                IllegalStateException("Offline AI model installed nahi hai. Settings -> Brain mein .task/.litertlm model import karein.")
            )

        inferenceMutex.withLock {
            try {
                val engine = ensureEngineLoaded(modelFile)
                val prompt = buildLocalPrompt(userQuery, memoryBlock, styleDirective)
                val rawResponse = engine.generateResponse(prompt).orEmpty().trim()
                if (rawResponse.isBlank()) {
                    Result.failure(IllegalStateException("Local model ne khaali jawab diya."))
                } else {
                    Result.success(GeminiChatClient.sanitizeForSpeech(rawResponse))
                }
            } catch (t: Throwable) {
                Result.failure(
                    IllegalStateException(
                        "Offline Local AI runtime error: ${t.message ?: t.javaClass.simpleName}"
                    )
                )
            }
        }
    }

    private fun ensureEngineLoaded(modelFile: File): LlmInference {
        val existing = llmInference
        if (existing != null && loadedModelPath == modelFile.absolutePath) {
            return existing
        }
        closeEngineInternal()

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(512)
            .build()

        val created = LlmInference.createFromOptions(appContext, options)
        llmInference = created
        loadedModelPath = modelFile.absolutePath
        _state.update { it.copy(isEngineLoaded = true) }
        return created
    }

    suspend fun closeEngine() = withContext(Dispatchers.IO) {
        inferenceMutex.withLock {
            closeEngineInternal()
        }
    }

    private fun closeEngineInternal() {
        try {
            llmInference?.close()
        } catch (_: Throwable) {
        }
        llmInference = null
        loadedModelPath = null
        _state.update { it.copy(isEngineLoaded = false) }
    }

    private fun findInstalledModelFile(): File? {
        if (!modelDir.exists()) return null
        return modelDir.listFiles()?.firstOrNull {
            it.isFile && hasAllowedExtension(it.name) && it.length() >= MIN_VALID_MODEL_BYTES
        }
    }

    private fun clearExistingModels() {
        try {
            modelDir.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    private fun inspectModelState(): OfflineLlmState {
        val file = findInstalledModelFile()
        return if (file != null) {
            val sizeMb = file.length() / (1024f * 1024f)
            OfflineLlmState(
                stage = OfflineLlmStage.READY,
                modelFileName = file.name,
                modelSizeMb = sizeMb,
                progressPercent = 100,
                isEngineLoaded = llmInference != null && loadedModelPath == file.absolutePath,
                statusMessage = "Local AI Ready: ${file.name} (${String.format("%.1f", sizeMb)} MB)"
            )
        } else {
            OfflineLlmState(
                stage = OfflineLlmStage.NOT_INSTALLED,
                statusMessage = "Offline Reflex active. Import a quantized Gemma .task / .litertlm file for Offline AI chat."
            )
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun buildLocalPrompt(
        userQuery: String,
        memoryBlock: String,
        styleDirective: String
    ): String = buildString {
        appendLine("You are Goku, a warm, concise Hinglish/Hindi offline Android assistant. Reply in 1-2 short natural sentences without markdown.")
        if (styleDirective.isNotBlank()) {
            appendLine("Style: $styleDirective")
        }
        if (memoryBlock.isNotBlank()) {
            appendLine(memoryBlock)
        }
        appendLine("User: $userQuery")
        append("Goku: ")
    }

    companion object {
        const val MODEL_DIR_NAME = "llm_model"
        private const val MIN_VALID_MODEL_BYTES = 1024L * 1024L // 1 MB floor
        private val ALLOWED_EXTENSIONS = listOf(".task", ".litertlm", ".bin")

        fun hasAllowedExtension(fileName: String): Boolean {
            val lower = fileName.lowercase()
            return ALLOWED_EXTENSIONS.any { lower.endsWith(it) }
        }

        fun sanitizeModelFileName(raw: String): String {
            return File(raw).name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(80)
        }
    }
}
