package com.arif.goku.voice

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

enum class KwsDownloadStage {
    NOT_DOWNLOADED,
    DOWNLOADING,
    EXTRACTING,
    READY,
    ERROR
}

data class KwsModelState(
    val stage: KwsDownloadStage = KwsDownloadStage.NOT_DOWNLOADED,
    val progressPercent: Int = 0,
    val downloadedMb: Float = 0f,
    val totalMb: Float = 5.4f,
    val modelDirPath: String = "",
    val hasOnnxFiles: Boolean = false,
    val hasSherpaNativeBinding: Boolean = false,
    val statusMessage: String = "Offline KWS model not downloaded yet (VAD fallback active)."
)

/**
 * Manages one-time download, safe Tar-Slip-protected extraction, and local lifecycle of the
 * official Sherpa-ONNX Keyword Spotting model (~5.4 MB) into `context.filesDir/kws_model`.
 *
 * Keeps the Git repository lightweight while configuring `keywords.txt` for "HEY GOKU" and
 * "GOKU STOP".
 */
class KwsModelManager(context: Context) {

    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, KWS_DIR_NAME)
    private val activeCall = AtomicReference<Call?>(null)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _state = MutableStateFlow(inspectCurrentState())
    val state: StateFlow<KwsModelState> = _state.asStateFlow()

    fun refreshStatus(): KwsModelState {
        val current = inspectCurrentState()
        _state.value = current
        return current
    }

    fun getModelDirectory(): File = modelDir

    fun getEncoderFile(): File? = modelDir.listFiles()?.firstOrNull {
        it.isFile && it.name.startsWith("encoder") && it.name.endsWith(".onnx")
    }

    fun getDecoderFile(): File? = modelDir.listFiles()?.firstOrNull {
        it.isFile && it.name.startsWith("decoder") && it.name.endsWith(".onnx")
    }

    fun getJoinerFile(): File? = modelDir.listFiles()?.firstOrNull {
        it.isFile && it.name.startsWith("joiner") && it.name.endsWith(".onnx")
    }

    fun getTokensFile(): File? = File(modelDir, "tokens.txt").takeIf { it.isFile }

    fun getKeywordsFile(): File? = File(modelDir, "keywords.txt").takeIf { it.isFile }

    /**
     * Opens a live Sherpa-ONNX KeywordSpotter + OnlineStream session backed by the downloaded
     * Zipformer GigaSpeech ONNX weights in `filesDir/kws_model` and the bundled official
     * `sherpa-onnx.aar` (loaded dynamically so local/offline builds compile even when `app/libs/`
     * is empty).
     *
     * Returns `null` if ONNX files are missing, the AAR is not bundled, or native initialization
     * fails, allowing [WakeWordService] to fall back cleanly to VAD + offline SpeechRecognizer.
     */
    fun createKeywordSpotterSession(): SherpaKwsSession? {
        if (!isSherpaOnnxClassAvailable()) return null
        val encoder = getEncoderFile() ?: return null
        val decoder = getDecoderFile() ?: return null
        val joiner = getJoinerFile() ?: return null
        val tokens = getTokensFile() ?: return null
        val keywordsFile = getKeywordsFile() ?: run {
            try {
                writeKeywordsConfig(modelDir)
                getKeywordsFile()
            } catch (_: Exception) {
                null
            }
        } ?: return null

        return SherpaKwsSession.create(
            encoderPath = encoder.absolutePath,
            decoderPath = decoder.absolutePath,
            joinerPath = joiner.absolutePath,
            tokensPath = tokens.absolutePath,
            keywordsPath = keywordsFile.absolutePath
        )
    }

    fun cancelDownload() {
        activeCall.getAndSet(null)?.cancel()
    }

    suspend fun downloadAndInstallModel(): Result<String> = withContext(Dispatchers.IO) {
        if (_state.value.stage == KwsDownloadStage.DOWNLOADING ||
            _state.value.stage == KwsDownloadStage.EXTRACTING
        ) {
            return@withContext Result.failure(IllegalStateException("Download already in progress."))
        }

        val tempArchive = File(appContext.cacheDir, "sherpa_kws_temp.tar.bz2")
        try {
            _state.update {
                it.copy(
                    stage = KwsDownloadStage.DOWNLOADING,
                    progressPercent = 0,
                    downloadedMb = 0f,
                    statusMessage = "Downloading official Sherpa-ONNX KWS model (~5.4 MB)…"
                )
            }

            val request = Request.Builder()
                .url(OFFICIAL_KWS_MODEL_URL)
                .header("User-Agent", "GokuAgent-Android/3.0")
                .get()
                .build()

            val call = httpClient.newCall(request)
            activeCall.set(call)

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("GitHub release download failed (HTTP ${response.code}).")
                }
                val body = response.body ?: throw IllegalStateException("Empty model download response.")
                val contentLength = body.contentLength().coerceAtLeast(-1L)
                val totalMb = if (contentLength > 0L) contentLength / (1024f * 1024f) else 5.4f

                body.byteStream().use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied > MAX_ARCHIVE_BYTES) {
                                throw SecurityException("Downloaded archive exceeded safety size cap.")
                            }
                            val pct = if (contentLength > 0L) {
                                ((copied * 100L) / contentLength).toInt().coerceIn(0, 99)
                            } else {
                                ((copied * 100L) / (5_600_000L)).toInt().coerceIn(0, 95)
                            }
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.update {
                                    it.copy(
                                        progressPercent = pct,
                                        downloadedMb = copied / (1024f * 1024f),
                                        totalMb = totalMb,
                                        statusMessage = "Downloading Sherpa-ONNX KWS… $pct%"
                                    )
                                }
                            }
                        }
                    }
                }
            }

            _state.update {
                it.copy(
                    stage = KwsDownloadStage.EXTRACTING,
                    progressPercent = 99,
                    statusMessage = "Extracting ONNX model files securely…"
                )
            }

            extractTarBz2Safely(tempArchive, modelDir)
            writeKeywordsConfig(modelDir)

            val refreshed = inspectCurrentState()
            if (!refreshed.hasOnnxFiles) {
                throw IllegalStateException("Extracted archive did not contain expected ONNX files.")
            }

            _state.value = refreshed
            Result.success("Sherpa-ONNX KWS model ready! Keywords configured: HEY GOKU & GOKU STOP.")
        } catch (e: Exception) {
            val message = e.message ?: "Model download failed. Internet check karein."
            _state.update {
                it.copy(
                    stage = KwsDownloadStage.ERROR,
                    statusMessage = message
                )
            }
            Result.failure(e)
        } finally {
            activeCall.set(null)
            tempArchive.delete()
        }
    }

    fun deleteModel() {
        cancelDownload()
        try {
            modelDir.deleteRecursively()
        } catch (_: Exception) {
        }
        _state.value = inspectCurrentState()
    }

    private fun extractTarBz2Safely(archiveFile: File, targetDir: File) {
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        val canonicalTarget = targetDir.canonicalPath + File.separator
        var totalExtractedBytes = 0L

        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tais ->
                        while (true) {
                            val entry = tais.nextEntry ?: break
                            if (entry.isDirectory || entry.isSymbolicLink || entry.isLink) continue

                            // Flatten archive root directory into kws_model/<filename>
                            val safeName = File(entry.name).name
                            if (safeName.isBlank() || safeName.startsWith(".") || safeName.contains("..")) {
                                continue
                            }
                            if (!ALLOWED_MODEL_EXTENSIONS.any { safeName.endsWith(it, ignoreCase = true) }) {
                                continue
                            }

                            val outFile = File(targetDir, safeName)
                            if (!outFile.canonicalPath.startsWith(canonicalTarget)) {
                                throw SecurityException("Blocked unsafe tar path entry: ${entry.name}")
                            }

                            FileOutputStream(outFile).use { fos ->
                                val buf = ByteArray(16 * 1024)
                                while (true) {
                                    val count = tais.read(buf)
                                    if (count < 0) break
                                    totalExtractedBytes += count
                                    if (totalExtractedBytes > MAX_EXTRACTED_BYTES) {
                                        throw SecurityException("Extracted model exceeded safety size limit.")
                                    }
                                    fos.write(buf, 0, count)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeKeywordsConfig(targetDir: File) {
        val keywordsFile = File(targetDir, "keywords.txt")
        // GigaSpeech BPE token format for Sherpa-ONNX KWS + human-readable labels
        val content = """
            ▁HEY ▁GO KU :1.2 #0.25 @HEY_GOKU
            ▁GO KU ▁STOP :1.4 #0.25 @GOKU_STOP
        """.trimIndent() + "\n"
        keywordsFile.writeText(content)
    }

    private fun inspectCurrentState(): KwsModelState {
        val encoder = getEncoderFile()
        val decoder = getDecoderFile()
        val joiner = getJoinerFile()
        val tokens = getTokensFile()
        val ready = encoder != null && decoder != null && joiner != null && tokens != null
        val sherpaAvailable = isSherpaOnnxClassAvailable()

        val msg = when {
            ready && sherpaAvailable ->
                "Sherpa-ONNX KWS Engine Ready (HEY GOKU & GOKU STOP offline)"
            ready ->
                "Sherpa-ONNX ONNX weights downloaded & ready (Hybrid AudioRecord VAD + Offline KWS mode)"
            else ->
                "Model not downloaded yet — using lightweight AudioRecord VAD + Offline Speech fallback."
        }

        return KwsModelState(
            stage = if (ready) KwsDownloadStage.READY else KwsDownloadStage.NOT_DOWNLOADED,
            progressPercent = if (ready) 100 else 0,
            downloadedMb = if (ready) calculateDirSizeMb(modelDir) else 0f,
            modelDirPath = modelDir.absolutePath,
            hasOnnxFiles = ready,
            hasSherpaNativeBinding = sherpaAvailable,
            statusMessage = msg
        )
    }

    private fun calculateDirSizeMb(dir: File): Float {
        if (!dir.exists()) return 0f
        val bytes = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
        return bytes / (1024f * 1024f)
    }

    companion object {
        const val KWS_DIR_NAME = "kws_model"
        const val OFFICIAL_KWS_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
        private const val MAX_ARCHIVE_BYTES = 18L * 1024L * 1024L
        private const val MAX_EXTRACTED_BYTES = 35L * 1024L * 1024L
        private val ALLOWED_MODEL_EXTENSIONS = listOf(".onnx", ".txt")

        fun isSherpaOnnxClassAvailable(): Boolean {
            return try {
                Class.forName(
                    "com.k2fsa.sherpa.onnx.KeywordSpotter",
                    false,
                    KwsModelManager::class.java.classLoader
                )
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * Runtime-bound wrapper around `com.k2fsa.sherpa.onnx.KeywordSpotter` and `OnlineStream`.
 *
 * Pre-resolves all JNI wrapper `Method` handles once on creation so the 16 kHz real-time PCM loop
 * incurs zero reflective lookup overhead, while keeping the app buildable even when `app/libs/`
 * has no `.aar` present locally.
 */
class SherpaKwsSession private constructor(
    private val spotter: Any,
    private val stream: Any,
    private val acceptWaveformMethod: java.lang.reflect.Method,
    private val acceptWaveformSamplesFirst: Boolean,
    private val isReadyMethod: java.lang.reflect.Method,
    private val decodeMethod: java.lang.reflect.Method,
    private val getResultMethod: java.lang.reflect.Method,
    private val getKeywordMethod: java.lang.reflect.Method,
    private val resetMethod: java.lang.reflect.Method,
    private val streamReleaseMethod: java.lang.reflect.Method,
    private val spotterReleaseMethod: java.lang.reflect.Method
) {
    fun acceptWaveformAndDetect(samples: FloatArray, sampleRate: Int): String {
        if (acceptWaveformSamplesFirst) {
            acceptWaveformMethod.invoke(stream, samples, sampleRate)
        } else {
            acceptWaveformMethod.invoke(stream, sampleRate, samples)
        }
        while (isReadyMethod.invoke(spotter, stream) == true) {
            decodeMethod.invoke(spotter, stream)
        }
        val result = getResultMethod.invoke(spotter, stream) ?: return ""
        return (getKeywordMethod.invoke(result) as? String)?.trim().orEmpty()
    }

    fun reset() {
        try {
            resetMethod.invoke(spotter, stream)
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            streamReleaseMethod.invoke(stream)
        } catch (_: Throwable) {
        }
        try {
            spotterReleaseMethod.invoke(spotter)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val PKG = "com.k2fsa.sherpa.onnx"

        fun create(
            encoderPath: String,
            decoderPath: String,
            joinerPath: String,
            tokensPath: String,
            keywordsPath: String
        ): SherpaKwsSession? {
            var spotterInstance: Any? = null
            var streamInstance: Any? = null
            return try {
                val cl = SherpaKwsSession::class.java.classLoader
                val featureConfigCls = Class.forName("$PKG.FeatureConfig", true, cl)
                val transducerCls = Class.forName("$PKG.OnlineTransducerModelConfig", true, cl)
                val modelConfigCls = Class.forName("$PKG.OnlineModelConfig", true, cl)
                val kwsConfigCls = Class.forName("$PKG.KeywordSpotterConfig", true, cl)
                val spotterCls = Class.forName("$PKG.KeywordSpotter", true, cl)
                val streamCls = Class.forName("$PKG.OnlineStream", true, cl)
                val resultCls = Class.forName("$PKG.KeywordSpotterResult", true, cl)

                val featConfig = featureConfigCls.getDeclaredConstructor().newInstance()
                setRequired(featConfig, "setSampleRate", 16000, Int::class.javaPrimitiveType!!)
                setRequired(featConfig, "setFeatureDim", 80, Int::class.javaPrimitiveType!!)

                val transducerConfig = transducerCls.getDeclaredConstructor().newInstance()
                setRequired(transducerConfig, "setEncoder", encoderPath, String::class.java)
                setRequired(transducerConfig, "setDecoder", decoderPath, String::class.java)
                setRequired(transducerConfig, "setJoiner", joinerPath, String::class.java)

                val modelConfig = modelConfigCls.getDeclaredConstructor().newInstance()
                setRequired(modelConfig, "setTransducer", transducerConfig, transducerCls)
                setRequired(modelConfig, "setTokens", tokensPath, String::class.java)
                setOptional(modelConfig, "setNumThreads", 2, Int::class.javaPrimitiveType!!)
                setOptional(modelConfig, "setDebug", false, Boolean::class.javaPrimitiveType!!)
                setOptional(modelConfig, "setProvider", "cpu", String::class.java)

                val kwsConfig = kwsConfigCls.getDeclaredConstructor().newInstance()
                setRequired(kwsConfig, "setFeatConfig", featConfig, featureConfigCls)
                setRequired(kwsConfig, "setModelConfig", modelConfig, modelConfigCls)
                setOptional(kwsConfig, "setMaxActivePaths", 4, Int::class.javaPrimitiveType!!)
                setRequired(kwsConfig, "setKeywordsFile", keywordsPath, String::class.java)
                setOptional(kwsConfig, "setKeywordsScore", 1.5f, Float::class.javaPrimitiveType!!)
                setOptional(kwsConfig, "setKeywordsThreshold", 0.25f, Float::class.javaPrimitiveType!!)
                setOptional(kwsConfig, "setNumTrailingBlanks", 2, Int::class.javaPrimitiveType!!)

                val ctor = spotterCls.getConstructor(AssetManager::class.java, kwsConfigCls)
                spotterInstance = ctor.newInstance(null, kwsConfig)

                streamInstance = try {
                    spotterCls.getMethod("createStream", String::class.java).invoke(spotterInstance, "")
                } catch (_: NoSuchMethodException) {
                    spotterCls.getMethod("createStream").invoke(spotterInstance)
                } ?: return null

                val (acceptMethod, samplesFirst) = try {
                    streamCls.getMethod(
                        "acceptWaveform",
                        FloatArray::class.java,
                        Int::class.javaPrimitiveType!!
                    ) to true
                } catch (_: NoSuchMethodException) {
                    streamCls.getMethod(
                        "acceptWaveform",
                        Int::class.javaPrimitiveType!!,
                        FloatArray::class.java
                    ) to false
                }

                val isReadyMethod = spotterCls.getMethod("isReady", streamCls)
                val decodeMethod = spotterCls.getMethod("decode", streamCls)
                val getResultMethod = spotterCls.getMethod("getResult", streamCls)
                val getKeywordMethod = resultCls.getMethod("getKeyword")
                val resetMethod = spotterCls.getMethod("reset", streamCls)
                val streamReleaseMethod = streamCls.getMethod("release")
                val spotterReleaseMethod = spotterCls.getMethod("release")

                SherpaKwsSession(
                    spotter = spotterInstance,
                    stream = streamInstance,
                    acceptWaveformMethod = acceptMethod,
                    acceptWaveformSamplesFirst = samplesFirst,
                    isReadyMethod = isReadyMethod,
                    decodeMethod = decodeMethod,
                    getResultMethod = getResultMethod,
                    getKeywordMethod = getKeywordMethod,
                    resetMethod = resetMethod,
                    streamReleaseMethod = streamReleaseMethod,
                    spotterReleaseMethod = spotterReleaseMethod
                )
            } catch (_: Throwable) {
                try {
                    streamInstance?.let { it.javaClass.getMethod("release").invoke(it) }
                } catch (_: Throwable) {
                }
                try {
                    spotterInstance?.let { it.javaClass.getMethod("release").invoke(it) }
                } catch (_: Throwable) {
                }
                null
            }
        }

        private fun setRequired(target: Any, methodName: String, value: Any, paramType: Class<*>) {
            target.javaClass.getMethod(methodName, paramType).invoke(target, value)
        }

        private fun setOptional(target: Any, methodName: String, value: Any, paramType: Class<*>) {
            try {
                target.javaClass.getMethod(methodName, paramType).invoke(target, value)
            } catch (_: NoSuchMethodException) {
            }
        }
    }
}
