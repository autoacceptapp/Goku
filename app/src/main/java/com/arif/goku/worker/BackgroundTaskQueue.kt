package com.arif.goku.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.brain.BrainRouter
import com.arif.goku.brain.GeminiChatClient
import com.arif.goku.brain.OfflineLlmEngine
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.dev.GokuDevManager
import com.arif.goku.overlay.OverlayBridge
import com.example.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class BackgroundTaskType(val label: String) {
    WEB_RESEARCH("Web Research"),
    GITHUB_BUILD("GitHub Build"),
    FILE_CLEANUP("File Cleanup"),
    PRICE_LOOKUP("Price Lookup")
}

enum class BackgroundTaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class BackgroundTaskItem(
    val id: String = UUID.randomUUID().toString(),
    val type: BackgroundTaskType,
    val query: String,
    val status: BackgroundTaskStatus = BackgroundTaskStatus.QUEUED,
    val progressPercent: Int = 0,
    val summaryHindi: String = "Queued…",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

interface BackgroundTaskExecutor {
    suspend fun execute(
        type: BackgroundTaskType,
        query: String,
        onProgress: (Int, String) -> Unit
    ): Result<String>
}

/**
 * AndroidX `WorkManager` `CoroutineWorker` backing silent background execution even when GokuAgent's
 * Activity is backgrounded.
 */
class GokuBackgroundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val typeName = inputData.getString(KEY_TASK_TYPE) ?: return Result.failure()
        val query = inputData.getString(KEY_TASK_QUERY).orEmpty()
        val type = BackgroundTaskType.entries.firstOrNull { it.name == typeName }
            ?: BackgroundTaskType.WEB_RESEARCH

        val securePrefs = SecurePrefs(applicationContext)
        val adbManager = LocalAdbManager(applicationContext, securePrefs)
        val devManager = GokuDevManager(applicationContext, securePrefs, adbManager)
        val db = GokuDatabase.getInstance(applicationContext)
        val memoryManager = MemoryManager(db.memoryDao(), db.chatHistoryDao())
        val geminiClient = GeminiChatClient(applicationContext, securePrefs)
        val offlineEngine = OfflineLlmEngine(applicationContext)
        val brainRouter = BrainRouter(geminiClient, offlineEngine, memoryManager)

        val executor = DefaultBackgroundTaskExecutor(applicationContext, brainRouter, devManager)
        val outcome = executor.execute(type, query) { _, _ -> }

        return outcome.fold(
            onSuccess = { summary ->
                Result.success(Data.Builder().putString(KEY_RESULT_SUMMARY, summary).build())
            },
            onFailure = { err ->
                Result.failure(
                    Data.Builder().putString(KEY_RESULT_SUMMARY, err.message ?: "Background task failed.").build()
                )
            }
        )
    }

    companion object {
        const val KEY_TASK_TYPE = "goku_bg_task_type"
        const val KEY_TASK_QUERY = "goku_bg_task_query"
        const val KEY_RESULT_SUMMARY = "goku_bg_result_summary"
    }
}

/**
 * Phase 5 Background Task Queue.
 *
 * Runs non-UI tasks (Web Research, GitHub Build Checks, File Cleanup, Price/Info Lookups) silently
 * in the background while the user uses other apps, mirrors live progress onto the Floating Orb
 * (`OverlayBridge.updateQueueProgress`), posts a concise completion notification, and emits a
 * completion event so Goku speaks a brief Hindi summary.
 */
class BackgroundTaskQueue(
    private val context: Context,
    private val executor: BackgroundTaskExecutor,
    private val scheduleWorkManager: Boolean = true,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context.applicationContext
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<BackgroundTaskItem>>(emptyList())
    val tasks: StateFlow<List<BackgroundTaskItem>> = _tasks.asStateFlow()

    private val _completions = MutableSharedFlow<BackgroundTaskItem>(extraBufferCapacity = 12)
    val completions: SharedFlow<BackgroundTaskItem> = _completions.asSharedFlow()

    fun enqueue(type: BackgroundTaskType, rawQuery: String): BackgroundTaskItem {
        val query = rawQuery.trim().ifBlank { type.label }
        val item = BackgroundTaskItem(
            type = type,
            query = query,
            status = BackgroundTaskStatus.QUEUED,
            progressPercent = 10,
            summaryHindi = "Background mein shuru ho raha hai…"
        )
        _tasks.update { (listOf(item) + it).take(25) }
        syncFloatingOrbBadge()

        if (scheduleWorkManager) {
            try {
                val workReq = OneTimeWorkRequestBuilder<GokuBackgroundWorker>()
                    .setInputData(
                        Data.Builder()
                            .putString(GokuBackgroundWorker.KEY_TASK_TYPE, type.name)
                            .putString(GokuBackgroundWorker.KEY_TASK_QUERY, query)
                            .build()
                    )
                    .addTag(item.id)
                    .build()
                WorkManager.getInstance(appContext).enqueue(workReq)
            } catch (_: Throwable) {
                // Safe fallback when WorkManager isn't initialized (e.g. unit test environment)
            }
        }

        val job = scope.launch {
            updateTask(item.id) {
                it.copy(
                    status = BackgroundTaskStatus.RUNNING,
                    progressPercent = 25,
                    summaryHindi = "${type.label} chal raha hai…"
                )
            }

            val result = try {
                executor.execute(type, query) { pct, statusText ->
                    updateTask(item.id) {
                        it.copy(
                            progressPercent = pct.coerceIn(0, 99),
                            summaryHindi = statusText
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }

            val completedItem = result.fold(
                onSuccess = { summary ->
                    updateTask(item.id) {
                        it.copy(
                            status = BackgroundTaskStatus.COMPLETED,
                            progressPercent = 100,
                            summaryHindi = summary
                        )
                    }
                },
                onFailure = { err ->
                    updateTask(item.id) {
                        it.copy(
                            status = BackgroundTaskStatus.FAILED,
                            progressPercent = 0,
                            summaryHindi = err.message ?: "Background task fail ho gaya."
                        )
                    }
                }
            )

            activeJobs.remove(item.id)
            syncFloatingOrbBadge()
            if (completedItem != null) {
                postCompletionNotification(completedItem)
                _completions.tryEmit(completedItem)
            }
        }

        activeJobs[item.id] = job
        return item
    }

    fun cancelTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        if (scheduleWorkManager) {
            try {
                WorkManager.getInstance(appContext).cancelAllWorkByTag(taskId)
            } catch (_: Throwable) {
            }
        }
        updateTask(taskId) {
            if (it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING) {
                it.copy(
                    status = BackgroundTaskStatus.CANCELLED,
                    progressPercent = 0,
                    summaryHindi = "Task cancel kar diya gaya."
                )
            } else {
                it
            }
        }
        syncFloatingOrbBadge()
    }

    fun cancelAllActive() {
        val ids = activeJobs.keys.toList()
        ids.forEach { cancelTask(it) }
    }

    fun clearFinished() {
        _tasks.update { list ->
            list.filter {
                it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING
            }
        }
        syncFloatingOrbBadge()
    }

    private fun updateTask(id: String, transform: (BackgroundTaskItem) -> BackgroundTaskItem): BackgroundTaskItem? {
        var updatedItem: BackgroundTaskItem? = null
        _tasks.update { list ->
            list.map { item ->
                if (item.id == id) {
                    val next = transform(item).copy(updatedAtMs = System.currentTimeMillis())
                    updatedItem = next
                    next
                } else {
                    item
                }
            }
        }
        syncFloatingOrbBadge()
        return updatedItem
    }

    private fun syncFloatingOrbBadge() {
        val active = _tasks.value.filter {
            it.status == BackgroundTaskStatus.QUEUED || it.status == BackgroundTaskStatus.RUNNING
        }
        if (active.isEmpty()) {
            OverlayBridge.updateQueueProgress("")
        } else {
            val top = active.first()
            OverlayBridge.updateQueueProgress("Q:${active.size} • ${top.progressPercent}%")
        }
    }

    private fun postCompletionNotification(item: BackgroundTaskItem) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GokuAgent Background Tasks",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)
            val notif = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Goku Queue: ${item.type.label}")
                .setContentText(item.summaryHindi.take(160))
                .setStyle(NotificationCompat.BigTextStyle().bigText(item.summaryHindi))
                .setAutoCancel(true)
                .build()
            nm.notify(item.id.hashCode(), notif)
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "goku_bg_task_queue"

        fun create(
            context: Context,
            brainRouter: BrainRouter,
            devManager: GokuDevManager,
            scope: CoroutineScope
        ): BackgroundTaskQueue {
            return BackgroundTaskQueue(
                context = context.applicationContext,
                executor = DefaultBackgroundTaskExecutor(context.applicationContext, brainRouter, devManager),
                scheduleWorkManager = true,
                scope = scope
            )
        }

        fun parseBackgroundQueueCommand(rawInput: String): Pair<BackgroundTaskType, String>? {
            val trimmed = rawInput.trim()
            val lower = trimmed.lowercase()
            if (!lower.contains("background") && !lower.contains("queue") &&
                !lower.contains("peeche") && !lower.contains("silent")
            ) {
                return null
            }
            val type = when {
                lower.contains("github") || lower.contains("build") -> BackgroundTaskType.GITHUB_BUILD
                lower.contains("clean") || lower.contains("cache") || lower.contains("file") -> BackgroundTaskType.FILE_CLEANUP
                lower.contains("price") || lower.contains("rate") || lower.contains("daam") -> BackgroundTaskType.PRICE_LOOKUP
                else -> BackgroundTaskType.WEB_RESEARCH
            }
            val cleanedQuery = trimmed
                .replace(Regex("(?i)(?:goku[,\\s]+)?(?:background\\s+(?:mein|me|task)?|in\\s+background|queue\\s+(?:karo|mein))"), "")
                .trim()
                .ifBlank { trimmed }
            return type to cleanedQuery
        }
    }
}

class DefaultBackgroundTaskExecutor(
    private val appContext: Context,
    private val brainRouter: BrainRouter,
    private val devManager: GokuDevManager
) : BackgroundTaskExecutor {

    override suspend fun execute(
        type: BackgroundTaskType,
        query: String,
        onProgress: (Int, String) -> Unit
    ): Result<String> {
        return when (type) {
            BackgroundTaskType.GITHUB_BUILD -> {
                onProgress(40, "GitHub Actions API check ho raha hai…")
                val repoOverride = Regex("([a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)").find(query)?.groupValues?.get(1)
                val res = devManager.checkLatestGitHubBuild(repoOverride)
                onProgress(90, "Report tayyar hai…")
                res.map { it.hindiSummary }
            }

            BackgroundTaskType.FILE_CLEANUP -> {
                onProgress(35, "Temporary cache files scan ho rahi hain…")
                val cleaned = cleanAppCacheDirectory()
                onProgress(90, "Cleanup poora ho gaya…")
                Result.success(
                    "Background File Cleanup complete: ${cleaned.first} temporary files (${String.format("%.1f", cleaned.second)} KB) saaf kar di gayi hain."
                )
            }

            BackgroundTaskType.PRICE_LOOKUP -> {
                onProgress(45, "Live price aur info search ho rahi hai…")
                val res = brainRouter.askMultimodalOrLocal(
                    prompt = "Find current Indian market price / best deal info for: $query. Give a crisp 2-sentence Hinglish summary without markdown.",
                    imageBytes = null,
                    systemInstruction = "Tum Goku Background Researcher ho. Live price aur short comparison Hinglish mein 2-3 lines mein do (no markdown).",
                    includeGoogleSearchTool = true
                )
                onProgress(90, "Summary tayyar hai…")
                res
            }

            BackgroundTaskType.WEB_RESEARCH -> {
                onProgress(45, "Web research chal rahi hai…")
                val res = brainRouter.askMultimodalOrLocal(
                    prompt = "Research and summarize concisely in 2-3 friendly Hinglish sentences (no markdown): $query",
                    imageBytes = null,
                    systemInstruction = "Tum Goku Background Researcher ho. Accurate aur crisp Hinglish summary do (no markdown).",
                    includeGoogleSearchTool = true
                )
                onProgress(90, "Research complete…")
                res
            }
        }
    }

    private fun cleanAppCacheDirectory(): Pair<Int, Float> {
        var count = 0
        var bytes = 0L
        val cacheRoot = appContext.cacheDir
        if (cacheRoot != null && cacheRoot.exists()) {
            cacheRoot.walkBottomUp().forEach { f ->
                if (f != cacheRoot && f.isFile && (f.name.endsWith(".tmp") || f.name.contains("goku_"))) {
                    val len = f.length()
                    if (f.delete()) {
                        count++
                        bytes += len
                    }
                }
            }
        }
        return count to (bytes / 1024f)
    }
}
