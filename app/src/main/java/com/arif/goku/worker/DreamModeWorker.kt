package com.arif.goku.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arif.goku.brain.BrainRouter
import com.arif.goku.brain.GeminiChatClient
import com.arif.goku.brain.OfflineLlmEngine
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.HabitActionType
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.data.db.SkillMode
import com.arif.goku.data.db.SkillStep
import com.arif.goku.data.db.SkillStepCodec
import com.arif.goku.data.db.SkillStepType
import com.arif.goku.skills.SkillEngine
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class DreamModeSummary(
    val optimizedSkillsCount: Int,
    val prebuiltHabitSkillsCount: Int,
    val morningSummaryHindi: String
)

/**
 * Phase 7 Dream Mode Overnight Self-Optimization Worker (`WorkManager`).
 *
 * Scheduled under strict battery/data-friendly constraints:
 * - `setRequiresCharging(true)`
 * - `NetworkType.UNMETERED` (Wi-Fi)
 *
 * Overnight responsibilities:
 * 1) Finds failed or low-`evolutionScore` `UI_TAP` skills and asks `BrainRouter` to upgrade them
 *    into faster `INTENT` or `ADB_FAST` versions.
 * 2) Scans `HabitDao` for frequent un-skilled commands/apps (3+ occurrences) and pre-builds offline
 *    Skills for them.
 * 3) Saves a Morning Dream Summary for Arif in `SecurePrefs` + posts a gentle morning notification.
 */
class DreamModeWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val securePrefs = SecurePrefs(applicationContext)
        val db = GokuDatabase.getInstance(applicationContext)
        val memoryManager = MemoryManager(db.memoryDao(), db.chatHistoryDao())
        val geminiClient = GeminiChatClient(applicationContext, securePrefs)
        val offlineLlm = OfflineLlmEngine(applicationContext)
        val brainRouter = BrainRouter(geminiClient, offlineLlm, memoryManager)

        try {
            val summary = executeDreamCycle(
                db = db,
                brainRouter = brainRouter
            )
            securePrefs.saveLastDreamReport(summary.morningSummaryHindi)
            postMorningDreamNotification(applicationContext, summary.morningSummaryHindi)
            Result.success()
        } catch (e: Exception) {
            securePrefs.saveLastDreamReport("Dream Mode partial run: ${e.message ?: "unknown error"}")
            Result.retry()
        } finally {
            runCatching { offlineLlm.closeEngine() }
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "goku_dream_mode_overnight"
        const val UNIQUE_IMMEDIATE_WORK_NAME = "goku_dream_mode_immediate"
        private const val DREAM_CHANNEL_ID = "goku_dream_mode_channel"

        /**
         * Core dream-cycle algorithm extracted so both `DreamModeWorker` and unit tests can run it cleanly.
         */
        suspend fun executeDreamCycle(
            db: GokuDatabase,
            brainRouter: BrainRouter
        ): DreamModeSummary {
            val skillDao = db.skillDao()
            val habitDao = db.habitDao()

            var optimizedCount = 0
            var prebuiltCount = 0

            // 1. Optimize failed or low-evolutionScore UI_TAP skills into INTENT / ADB_FAST
            val candidates = skillDao.getCandidatesForOptimization(maxScore = 75, limit = 5)
            for (skill in candidates) {
                val upgraded = optimizeSingleSkill(skill, brainRouter)
                if (upgraded != null) {
                    skillDao.updateSkill(upgraded)
                    optimizedCount++
                }
            }

            // 2. Pre-build Skills for frequent un-skilled HabitPredictor commands/apps (3+ occurrences)
            val allSkills = skillDao.getAllSkillsSnapshot()
            val recentHabits = habitDao.getRecentEventsSnapshot(limit = 400)
            val frequentHabits = recentHabits
                .groupBy { "${it.actionType}|${it.targetPackageOrCommand}" }
                .values
                .filter { it.size >= 3 }
                .sortedByDescending { it.size }
                .take(4)

            for (group in frequentHabits) {
                val sample = group.first()
                val alreadySkilled = SkillEngine.matchBestSkill(sample.displayLabel, allSkills) != null ||
                    allSkills.any {
                        it.targetPackage.equals(sample.targetPackageOrCommand, ignoreCase = true) ||
                            it.name.equals(sample.displayLabel, ignoreCase = true)
                    }
                if (alreadySkilled) continue

                val newSkill = buildSkillForHabit(sample.actionType, sample.targetPackageOrCommand, sample.displayLabel, brainRouter)
                if (newSkill != null) {
                    skillDao.insertSkill(newSkill)
                    prebuiltCount++
                }
            }

            val morningHindi = buildString {
                append("Good Morning! Dream Mode complete: ")
                append("$optimizedCount skills optimize kiye gaye")
                append(" aur $prebuiltCount frequent habits ke liye naye offline skills tayyar kiye gaye.")
            }

            return DreamModeSummary(
                optimizedSkillsCount = optimizedCount,
                prebuiltHabitSkillsCount = prebuiltCount,
                morningSummaryHindi = morningHindi
            )
        }

        private suspend fun optimizeSingleSkill(
            skill: SkillEntity,
            brainRouter: BrainRouter
        ): SkillEntity? {
            val prompt = """
                Optimize this Android GokuAgent Skill for maximum speed and reliability.
                Current Skill:
                Name: ${skill.name}
                Triggers: ${skill.triggerPhrases}
                Package: ${skill.targetPackage}
                Mode: ${skill.mode} (Success=${skill.successCount}, Fail=${skill.failCount}, Score=${skill.evolutionScore})
                Current stepsJson: ${skill.stepsJson}

                Upgrade slow UI_TAP steps into direct `INTENT_URI` deep links or `ADB_SHELL` commands wherever possible.
                Return STRICT JSON ONLY:
                {"name":"${skill.name}","triggerPhrases":"${skill.triggerPhrases}","targetPackage":"${skill.targetPackage}","mode":"ADB_FAST|INTENT|UI_TAP","steps":[...]}
            """.trimIndent()

            val raw = brainRouter.askMultimodalOrLocal(
                prompt = prompt,
                imageBytes = null,
                systemInstruction = "You are GokuAgent's Dream Mode Skill Optimizer. Output strict JSON only.",
                includeGoogleSearchTool = false
            ).getOrNull() ?: return null

            val candidate = SkillEngine.parseSynthesizedSkillJson(raw, skill.name) ?: return null
            val upgradedMode = candidate.modeEnum()
            val shouldApply = upgradedMode.speedBonus > skill.modeEnum().speedBonus ||
                candidate.stepsJson != skill.stepsJson

            if (!shouldApply) return null

            val refreshedScore = SkillEngine.computeEvolutionScore(
                successCount = skill.successCount,
                failCount = 0, // Reset stale failures after Dream Mode upgrade
                avgDurationMs = skill.avgDurationMs,
                mode = upgradedMode
            )
            return skill.copy(
                stepsJson = candidate.stepsJson,
                mode = upgradedMode.name,
                failCount = 0,
                evolutionScore = refreshedScore,
                updatedAt = System.currentTimeMillis()
            )
        }

        private suspend fun buildSkillForHabit(
            actionTypeRaw: String,
            target: String,
            displayLabel: String,
            brainRouter: BrainRouter
        ): SkillEntity? {
            val actionType = HabitActionType.fromStored(actionTypeRaw)
            if (actionType == HabitActionType.APP_LAUNCH) {
                val steps = listOf(SkillStep(SkillStepType.OPEN_APP, target = target, delayMs = 300L))
                return SkillEntity(
                    name = "Open $displayLabel",
                    triggerPhrases = "open $displayLabel, $displayLabel kholo, $displayLabel chalu karo",
                    targetPackage = target,
                    stepsJson = SkillStepCodec.toJson(steps),
                    mode = SkillMode.INTENT.name,
                    evolutionScore = SkillEngine.computeEvolutionScore(1, 0, 500L, SkillMode.INTENT),
                    updatedAt = System.currentTimeMillis()
                )
            }

            // Voice command habit -> ask BrainRouter to synthesize a reusable skill
            val prompt = """
                Pre-build a reusable Android Skill for this frequent user habit command:
                "$displayLabel"
                Output STRICT JSON ONLY:
                {"name":"Short Name","triggerPhrases":"$displayLabel","targetPackage":"","mode":"ADB_FAST|INTENT|UI_TAP","steps":[...]}
            """.trimIndent()
            val raw = brainRouter.askMultimodalOrLocal(
                prompt = prompt,
                imageBytes = null,
                systemInstruction = "Output strict JSON only for GokuAgent Skill.",
                includeGoogleSearchTool = false
            ).getOrNull() ?: return null

            return SkillEngine.parseSynthesizedSkillJson(raw, displayLabel)
        }

        private fun postMorningDreamNotification(context: Context, summaryHindi: String) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
                val channel = NotificationChannel(
                    DREAM_CHANNEL_ID,
                    "GokuAgent Dream Mode",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Morning self-optimization reports from GokuAgent Dream Mode."
                }
                nm.createNotificationChannel(channel)

                val notif = NotificationCompat.Builder(context, DREAM_CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Goku Dream Mode — Morning Report")
                    .setContentText(summaryHindi)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(summaryHindi))
                    .setAutoCancel(true)
                    .build()
                nm.notify(7007, notif)
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * Helper scheduling overnight Charging + Unmetered Wi-Fi Dream Mode via AndroidX WorkManager.
 */
object DreamModeScheduler {
    fun buildOvernightConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
    }

    fun scheduleOvernight(context: Context) {
        val request = PeriodicWorkRequestBuilder<DreamModeWorker>(24, TimeUnit.HOURS)
            .setConstraints(buildOvernightConstraints())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            DreamModeWorker.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelOvernight(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(DreamModeWorker.UNIQUE_PERIODIC_WORK_NAME)
    }

    fun triggerImmediateDream(context: Context) {
        val request = OneTimeWorkRequestBuilder<DreamModeWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            DreamModeWorker.UNIQUE_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
