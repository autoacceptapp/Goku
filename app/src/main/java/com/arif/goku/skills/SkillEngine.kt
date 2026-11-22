package com.arif.goku.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.arif.goku.action.ActionExecutor
import com.arif.goku.action.GokuAction
import com.arif.goku.action.GokuActionType
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.brain.BrainRouter
import com.arif.goku.data.db.SkillDao
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.data.db.SkillMode
import com.arif.goku.data.db.SkillStep
import com.arif.goku.data.db.SkillStepCodec
import com.arif.goku.data.db.SkillStepType
import com.arif.goku.pilot.PilotRunOutcome
import com.arif.goku.pilot.UniversalAppPilot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

data class SkillEngineUiState(
    val isRunning: Boolean = false,
    val activeSkillName: String = "",
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val isHealing: Boolean = false,
    val statusHindi: String = "Skills Engine standby."
)

data class SkillExecutionReport(
    val skill: SkillEntity,
    val success: Boolean,
    val healed: Boolean = false,
    val newlyLearned: Boolean = false,
    val durationMs: Long = 0L,
    val summaryHindi: String,
    val failureReason: String = ""
)

/**
 * Phase 7 Evolving & Self-Healing Skills Engine.
 *
 * Capabilities:
 * - **Offline Execution:** Runs saved Room `SkillEntity` step pipelines (`ADB_SHELL`, `INTENT_URI`,
 *   `OPEN_APP`, `CLICK_TEXT`, `TYPE_TEXT`, `TAP_XY`, `SCROLL`, `WAIT`, `SPEAK`) at 0-cloud-cost.
 * - **Self-Learning:** When a workflow command has no saved Skill, asks `BrainRouter` (Gemini + Search)
 *   to synthesize a structured JSON Skill, executes it, and saves it to Room for offline reuse.
 * - **Self-Healing:** When a UI step breaks (e.g., app update renamed a button), falls back to
 *   `UniversalAppPilot` (with Screen Vision) to complete the goal, patches `stepsJson`, and saves the healed Skill.
 * - **Skill Evolution Score (0–100):** Updated after every run from reliability, speed, and execution mode.
 */
class SkillEngine(
    private val context: Context,
    private val skillDao: SkillDao,
    private val actionExecutor: ActionExecutor,
    private val adbManager: LocalAdbManager,
    private val brainRouter: BrainRouter,
    private val universalPilot: UniversalAppPilot,
    private val onSpeakStep: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val cancelled = AtomicBoolean(false)

    val allSkills: Flow<List<SkillEntity>> = skillDao.observeAllSkills()

    private val _state = MutableStateFlow(SkillEngineUiState())
    val state: StateFlow<SkillEngineUiState> = _state.asStateFlow()

    fun cancel(reason: String = "Goku Stop") {
        cancelled.set(true)
        universalPilot.cancel(reason)
        _state.update {
            it.copy(
                isRunning = false,
                isHealing = false,
                statusHindi = "Skill execution ruk gaya ($reason)."
            )
        }
    }

    /**
     * Finds the best matching offline Skill for a user command based on trigger phrases & name overlap.
     */
    suspend fun findMatchingSkill(userCommand: String): SkillEntity? {
        val skills = skillDao.getAllSkillsSnapshot()
        return matchBestSkill(userCommand, skills)
    }

    /**
     * Executes an existing Skill, updating its `evolutionScore` and triggering automatic
     * Self-Healing via `UniversalAppPilot` + Screen Vision if a UI step fails.
     */
    suspend fun runSkill(
        skill: SkillEntity,
        allowSelfHeal: Boolean = true,
        allowVisionFallback: Boolean = true
    ): SkillExecutionReport = withContext(Dispatchers.IO) {
        cancelled.set(false)
        val steps = skill.parsedSteps()
        if (steps.isEmpty()) {
            return@withContext SkillExecutionReport(
                skill = skill,
                success = false,
                summaryHindi = "'${skill.name}' mein koi valid steps nahi mile.",
                failureReason = "Empty stepsJson"
            )
        }

        val startMs = System.currentTimeMillis()
        _state.value = SkillEngineUiState(
            isRunning = true,
            activeSkillName = skill.name,
            currentStepIndex = 0,
            totalSteps = steps.size,
            isHealing = false,
            statusHindi = "'${skill.name}' (${skill.mode}) chala raha hoon…"
        )

        var failedIndex = -1
        var failureDetail = ""

        for ((index, step) in steps.withIndex()) {
            if (cancelled.get()) {
                _state.update { it.copy(isRunning = false, statusHindi = "Skill cancelled.") }
                return@withContext SkillExecutionReport(
                    skill = skill,
                    success = false,
                    durationMs = System.currentTimeMillis() - startMs,
                    summaryHindi = "'${skill.name}' beech mein rok diya gaya.",
                    failureReason = "Cancelled by user"
                )
            }

            _state.update {
                it.copy(
                    currentStepIndex = index + 1,
                    statusHindi = "${skill.name}: Step ${index + 1}/${steps.size} (${step.type.name})"
                )
            }

            val stepOutcome = executeSingleStep(step)
            if (!stepOutcome.first) {
                failedIndex = index
                failureDetail = stepOutcome.second
                break
            }

            if (step.delayMs > 0L && index < steps.lastIndex) {
                delay(step.delayMs.coerceAtMost(4_000L))
            }
        }

        // Fast path: all steps succeeded cleanly!
        if (failedIndex == -1) {
            val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(120L)
            val updated = recordRunStats(skill = skill, succeeded = true, runDurationMs = elapsed)
            skillDao.updateSkill(updated)
            _state.update {
                it.copy(
                    isRunning = false,
                    statusHindi = "'${updated.name}' poora hua (${elapsed}ms • Score ${updated.evolutionScore}/100)."
                )
            }
            return@withContext SkillExecutionReport(
                skill = updated,
                success = true,
                durationMs = elapsed,
                summaryHindi = "'${updated.name}' skill poora ho gaya (${updated.mode}, Score ${updated.evolutionScore}/100)."
            )
        }

        // Failure path: attempt Self-Healing via UniversalAppPilot + Screen Vision fallback
        if (allowSelfHeal && !cancelled.get()) {
            _state.update {
                it.copy(
                    isHealing = true,
                    statusHindi = "Step ${failedIndex + 1} fail hua — Self-Healing (Universal Pilot + Vision) shuru…"
                )
            }

            val healGoal = buildString {
                append("Complete the skill '${skill.name}' (triggers: ${skill.triggerPhrases}). ")
                append("Step ${failedIndex + 1} failed: ${steps[failedIndex].type.name} '${steps[failedIndex].target}'. ")
                if (skill.targetPackage.isNotBlank()) {
                    append("Target app package: ${skill.targetPackage}. ")
                }
                append("Find the updated UI element and finish the remaining steps cleanly.")
            }

            val pilotOutcome = universalPilot.runGoal(
                goal = healGoal,
                allowVisionFallback = allowVisionFallback
            )

            if (pilotOutcome is PilotRunOutcome.Completed) {
                val healedTail = convertPilotLogsToSteps(universalPilot.state.value.stepLogs)
                val patchedSteps = if (healedTail.isNotEmpty()) {
                    (steps.take(failedIndex) + healedTail).take(14)
                } else {
                    steps
                }
                val newMode = SkillStepCodec.inferMode(patchedSteps)
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(250L)
                val patchedSkill = recordRunStats(
                    skill = skill.copy(
                        stepsJson = SkillStepCodec.toJson(patchedSteps),
                        mode = newMode.name,
                        updatedAt = System.currentTimeMillis()
                    ),
                    succeeded = true,
                    runDurationMs = elapsed
                )
                skillDao.updateSkill(patchedSkill)

                _state.update {
                    it.copy(
                        isRunning = false,
                        isHealing = false,
                        statusHindi = "'${patchedSkill.name}' Self-Heal ho gaya! Score ${patchedSkill.evolutionScore}/100."
                    )
                }
                return@withContext SkillExecutionReport(
                    skill = patchedSkill,
                    success = true,
                    healed = true,
                    durationMs = elapsed,
                    summaryHindi = "App UI badal gaya tha, par Goku ne '${patchedSkill.name}' skill ko Self-Heal karke patch kar diya! (Score ${patchedSkill.evolutionScore}/100)."
                )
            }
        }

        // Unrecovered failure: record fail count & drop evolution score
        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(150L)
        val failedUpdated = recordRunStats(skill = skill, succeeded = false, runDurationMs = elapsed)
        skillDao.updateSkill(failedUpdated)
        _state.update {
            it.copy(
                isRunning = false,
                isHealing = false,
                statusHindi = "'${skill.name}' fail hua: $failureDetail"
            )
        }
        return@withContext SkillExecutionReport(
            skill = failedUpdated,
            success = false,
            durationMs = elapsed,
            summaryHindi = "'${skill.name}' skill step ${failedIndex + 1} par fail hua: $failureDetail",
            failureReason = failureDetail
        )
    }

    /**
     * Self-Learning: asks `BrainRouter` (Gemini + Google Search) to synthesize a reusable JSON Skill
     * for a user workflow command, optionally executes it immediately, and saves it to Room DB
     * so future runs execute completely offline.
     */
    suspend fun synthesizeAndSaveSkill(
        userGoal: String,
        executeImmediately: Boolean = true
    ): Result<SkillExecutionReport> = withContext(Dispatchers.IO) {
        val cleanedGoal = userGoal.trim()
        if (cleanedGoal.length < 3) {
            return@withContext Result.failure(IllegalArgumentException("Skill goal bohot chhota hai."))
        }

        _state.update {
            it.copy(
                isRunning = true,
                activeSkillName = cleanedGoal.take(28),
                statusHindi = "Naya Skill synthesize kar raha hoon (BrainRouter)…"
            )
        }

        val prompt = """
            Create a reusable Android automation Skill for GokuAgent for this user request:
            "$cleanedGoal"

            Prefer fastest execution mode in this priority:
            1) ADB_FAST (using ADB_SHELL commands for `127.0.0.1:5555`, e.g., `am start ...`, `settings put ...`, `input keyevent ...`)
            2) INTENT (using INTENT_URI deep links or OPEN_APP)
            3) UI_TAP (OPEN_APP + CLICK_TEXT / TYPE_TEXT / SCROLL / TAP_XY)

            Allowed step types ONLY:
            ADB_SHELL, INTENT_URI, OPEN_APP, CLICK_TEXT, TYPE_TEXT, TAP_XY, SCROLL, WAIT, SPEAK

            Respond with STRICT JSON ONLY (no markdown, no explanation):
            {
              "name": "Short Skill Title",
              "triggerPhrases": "phrase 1, phrase 2, hinglish phrase 3",
              "targetPackage": "com.example.pkg or empty string",
              "mode": "ADB_FAST|INTENT|UI_TAP",
              "steps": [
                {"type":"INTENT_URI","target":"https://...","value":"","delayMs":400}
              ]
            }
        """.trimIndent()

        val brainRes = brainRouter.askMultimodalOrLocal(
            prompt = prompt,
            imageBytes = null,
            systemInstruction = "You are GokuAgent's Android Skill Architect. Output strictly valid JSON matching the requested schema.",
            includeGoogleSearchTool = true
        )

        val rawJson = brainRes.getOrElse { err ->
            _state.update { it.copy(isRunning = false, statusHindi = "Skill synthesis fail hua.") }
            return@withContext Result.failure(err)
        }

        val draft = parseSynthesizedSkillJson(rawJson, fallbackGoal = cleanedGoal)
            ?: run {
                _state.update { it.copy(isRunning = false, statusHindi = "Valid skill JSON nahi mila.") }
                return@withContext Result.failure(IllegalStateException("Brain se valid Skill JSON parse nahi ho paya."))
            }

        val existing = skillDao.findByNameExact(draft.name)
        val savedId = if (existing != null) {
            val merged = existing.copy(
                triggerPhrases = mergeTriggerPhrases(existing.triggerPhrases, draft.triggerPhrases),
                targetPackage = draft.targetPackage.ifBlank { existing.targetPackage },
                stepsJson = draft.stepsJson,
                mode = draft.mode,
                updatedAt = System.currentTimeMillis()
            )
            skillDao.updateSkill(merged)
            merged.id
        } else {
            skillDao.insertSkill(draft)
        }

        val persisted = skillDao.getById(savedId) ?: draft.copy(id = savedId)

        if (!executeImmediately) {
            _state.update {
                it.copy(
                    isRunning = false,
                    statusHindi = "Naya Skill '${persisted.name}' (${persisted.mode}) offline use ke liye save ho gaya!"
                )
            }
            return@withContext Result.success(
                SkillExecutionReport(
                    skill = persisted,
                    success = true,
                    newlyLearned = true,
                    summaryHindi = "Naya Skill '${persisted.name}' (${persisted.mode}) seekh kar save kar liya hai. Ab yeh offline bhi chalega!"
                )
            )
        }

        val runReport = runSkill(persisted, allowSelfHeal = true)
        Result.success(runReport.copy(newlyLearned = true))
    }

    suspend fun saveOrUpdateManualSkill(skill: SkillEntity): SkillEntity = withContext(Dispatchers.IO) {
        val steps = skill.parsedSteps()
        val mode = if (steps.isNotEmpty()) SkillStepCodec.inferMode(steps).name else skill.mode
        val score = computeEvolutionScore(
            successCount = skill.successCount,
            failCount = skill.failCount,
            avgDurationMs = skill.avgDurationMs,
            mode = SkillMode.fromStored(mode)
        )
        val normalized = skill.copy(
            name = skill.name.trim().ifBlank { "Custom Skill" },
            triggerPhrases = skill.triggerPhrases.trim().ifBlank { skill.name.trim() },
            mode = mode,
            evolutionScore = score,
            updatedAt = System.currentTimeMillis()
        )
        if (normalized.id > 0L) {
            skillDao.updateSkill(normalized)
            normalized
        } else {
            val id = skillDao.insertSkill(normalized)
            normalized.copy(id = id)
        }
    }

    suspend fun deleteSkill(id: Long) = withContext(Dispatchers.IO) {
        skillDao.deleteById(id)
    }

    private suspend fun executeSingleStep(step: SkillStep): Pair<Boolean, String> {
        return when (step.type) {
            SkillStepType.ADB_SHELL -> {
                val cmd = step.value.ifBlank { step.target }.trim()
                if (cmd.isEmpty()) return false to "Empty ADB command"
                val res = adbManager.executeShellCommand(cmd)
                if (res.isSuccess) true to (res.getOrNull().orEmpty().take(120))
                else false to (res.exceptionOrNull()?.message ?: "ADB command failed")
            }

            SkillStepType.INTENT_URI -> {
                val uriStr = step.value.ifBlank { step.target }.trim()
                if (uriStr.isEmpty()) return false to "Empty Intent URI"
                executeIntentUri(uriStr)
            }

            SkillStepType.OPEN_APP -> {
                val target = step.target.ifBlank { step.value }.trim()
                val res = actionExecutor.execute(GokuAction(GokuActionType.OPEN_APP, target = target))
                res.success to res.logDetail
            }

            SkillStepType.CLICK_TEXT -> {
                val target = step.target.ifBlank { step.value }.trim()
                val res = actionExecutor.execute(GokuAction(GokuActionType.CLICK_TEXT, target = target))
                res.success to res.logDetail
            }

            SkillStepType.TYPE_TEXT -> {
                val res = actionExecutor.execute(
                    GokuAction(
                        type = GokuActionType.TYPE_TEXT,
                        target = step.target,
                        text = step.value
                    )
                )
                res.success to res.logDetail
            }

            SkillStepType.TAP_XY -> {
                val x = step.x?.toFloat() ?: return false to "Missing X coordinate"
                val y = step.y?.toFloat() ?: return false to "Missing Y coordinate"
                val res = actionExecutor.execute(GokuAction(GokuActionType.TAP, x = x, y = y))
                res.success to res.logDetail
            }

            SkillStepType.SCROLL -> {
                val forward = !step.value.equals("up", ignoreCase = true) &&
                    !step.target.equals("up", ignoreCase = true)
                val res = actionExecutor.execute(GokuAction(GokuActionType.SCROLL, forward = forward))
                res.success to res.logDetail
            }

            SkillStepType.WAIT -> {
                val waitMs = step.value.toLongOrNull() ?: step.delayMs.coerceIn(200L, 10_000L)
                delay(waitMs.coerceIn(100L, 10_000L))
                true to "Waited ${waitMs}ms"
            }

            SkillStepType.SPEAK -> {
                val msg = step.value.ifBlank { step.target }.trim()
                if (msg.isNotBlank()) onSpeakStep(msg)
                true to "Spoke message"
            }
        }
    }

    private fun executeIntentUri(uriStr: String): Pair<Boolean, String> {
        return try {
            val intent = if (uriStr.startsWith("android.settings.") || uriStr.startsWith("android.intent.action.")) {
                Intent(uriStr)
            } else if (uriStr.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(uriStr, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(uriStr))
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            true to "Launched intent: $uriStr"
        } catch (e: Exception) {
            false to "Intent launch failed ($uriStr): ${e.message}"
        }
    }

    companion object {
        /**
         * Pure, deterministic Skill Evolution Score calculator (0..100).
         *
         * Combines:
         * - Reliability (0..60 pts): Bayesian-smoothed success ratio weighted by experience.
         * - Speed (0..25 pts): <= 800ms gets full 25 pts; smoothly decreases up to 15,000ms.
         * - Mode Bonus (0..15 pts): ADB_FAST (+15), INTENT (+11), UI_TAP (+4).
         */
        fun computeEvolutionScore(
            successCount: Int,
            failCount: Int,
            avgDurationMs: Long,
            mode: SkillMode
        ): Int {
            val s = successCount.coerceAtLeast(0)
            val f = failCount.coerceAtLeast(0)
            val total = s + f

            val reliabilityRatio = if (total == 0) {
                0.65
            } else {
                (s + 1.0) / (total + 2.0)
            }
            val volumeBoost = (total.coerceAtMost(10) / 10.0) * 6.0
            val reliabilityPts = (reliabilityRatio * 54.0) + volumeBoost

            val effectiveMs = if (avgDurationMs <= 0L) {
                when (mode) {
                    SkillMode.ADB_FAST -> 450L
                    SkillMode.INTENT -> 800L
                    SkillMode.UI_TAP -> 2800L
                }
            } else {
                avgDurationMs
            }

            val speedPts = when {
                effectiveMs <= 800L -> 25.0
                effectiveMs >= 15_000L -> 3.0
                else -> {
                    val fraction = (effectiveMs - 800L).toDouble() / (15_000L - 800L).toDouble()
                    25.0 - (fraction * 22.0)
                }
            }

            val modePts = mode.speedBonus.toDouble()
            return (reliabilityPts + speedPts + modePts).roundToInt().coerceIn(0, 100)
        }

        fun recordRunStats(
            skill: SkillEntity,
            succeeded: Boolean,
            runDurationMs: Long
        ): SkillEntity {
            val newSuccess = if (succeeded) skill.successCount + 1 else skill.successCount
            val newFail = if (succeeded) skill.failCount else skill.failCount + 1
            val prevRuns = (skill.successCount + skill.failCount).coerceAtLeast(0)
            val newAvgMs = if (prevRuns == 0 || skill.avgDurationMs <= 0L) {
                runDurationMs.coerceAtLeast(1L)
            } else {
                ((skill.avgDurationMs * prevRuns) + runDurationMs) / (prevRuns + 1)
            }
            val mode = skill.modeEnum()
            val newScore = computeEvolutionScore(
                successCount = newSuccess,
                failCount = newFail,
                avgDurationMs = newAvgMs,
                mode = mode
            )
            return skill.copy(
                successCount = newSuccess,
                failCount = newFail,
                avgDurationMs = newAvgMs,
                evolutionScore = newScore,
                updatedAt = System.currentTimeMillis()
            )
        }

        /**
         * Matches a user voice/text query against saved Skills using exact/substring trigger phrase
         * matching + token overlap scoring.
         */
        fun matchBestSkill(userCommand: String, skills: List<SkillEntity>): SkillEntity? {
            if (skills.isEmpty()) return null
            val normalizedQuery = normalizeText(userCommand)
            if (normalizedQuery.length < 3) return null
            val queryTokens = normalizedQuery.split(" ").filter { it.length >= 2 }.toSet()

            var bestSkill: SkillEntity? = null
            var bestScore = 0.0

            for (skill in skills) {
                val phrases = (skill.triggerList() + skill.name)
                    .map { normalizeText(it) }
                    .filter { it.length >= 3 }

                var skillScore = 0.0
                for (phrase in phrases) {
                    if (normalizedQuery == phrase) {
                        skillScore = maxOf(skillScore, 1.0)
                    } else if (normalizedQuery.contains(phrase) && phrase.length >= 4) {
                        skillScore = maxOf(skillScore, 0.88)
                    } else if (phrase.contains(normalizedQuery) && normalizedQuery.length >= 5) {
                        skillScore = maxOf(skillScore, 0.80)
                    } else {
                        val phraseTokens = phrase.split(" ").filter { it.length >= 2 }.toSet()
                        if (phraseTokens.isNotEmpty() && queryTokens.isNotEmpty()) {
                            val overlap = phraseTokens.intersect(queryTokens).size.toDouble()
                            val jaccard = overlap / phraseTokens.union(queryTokens).size.toDouble()
                            val recall = overlap / phraseTokens.size.toDouble()
                            val combined = (jaccard * 0.5) + (recall * 0.5)
                            if (overlap >= 2 && combined >= 0.62) {
                                skillScore = maxOf(skillScore, combined)
                            }
                        }
                    }
                }

                if (skillScore > bestScore && skillScore >= 0.65) {
                    bestScore = skillScore
                    bestSkill = skill
                }
            }

            return bestSkill
        }

        fun parseSynthesizedSkillJson(raw: String, fallbackGoal: String): SkillEntity? {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return null

            return try {
                val obj = JSONObject(cleaned.substring(start, end + 1))
                val stepsArr = obj.optJSONArray("steps") ?: return null
                val steps = SkillStepCodec.parseSteps(stepsArr.toString())
                if (steps.isEmpty()) return null

                val name = obj.optString("name").trim().ifBlank { fallbackGoal.take(36) }
                val triggers = obj.optString("triggerPhrases").trim().ifBlank { fallbackGoal }
                val targetPkg = obj.optString("targetPackage").trim()
                val rawMode = obj.optString("mode").trim()
                val mode = if (rawMode.isNotBlank()) {
                    SkillMode.fromStored(rawMode)
                } else {
                    SkillStepCodec.inferMode(steps)
                }
                val initialScore = computeEvolutionScore(
                    successCount = 0,
                    failCount = 0,
                    avgDurationMs = 0L,
                    mode = mode
                )

                SkillEntity(
                    name = name,
                    triggerPhrases = triggers,
                    targetPackage = targetPkg,
                    stepsJson = SkillStepCodec.toJson(steps),
                    mode = mode.name,
                    successCount = 0,
                    failCount = 0,
                    avgDurationMs = 0L,
                    evolutionScore = initialScore,
                    updatedAt = System.currentTimeMillis()
                )
            } catch (_: Exception) {
                null
            }
        }

        fun convertPilotLogsToSteps(stepLogs: List<String>): List<SkillStep> {
            return buildList {
                for (log in stepLogs) {
                    val lower = log.lowercase(Locale.US)
                    when {
                        lower.contains("click_text") -> {
                            val target = log.substringAfter("'", "").substringBefore("'", "").trim()
                            if (target.isNotBlank()) add(SkillStep(SkillStepType.CLICK_TEXT, target = target))
                        }
                        lower.contains("type_text") -> {
                            val value = log.substringAfter("'", "").substringBefore("'", "").trim()
                            if (value.isNotBlank()) add(SkillStep(SkillStepType.TYPE_TEXT, value = value))
                        }
                        lower.contains("open_app") -> {
                            val target = log.substringAfter("'", "").substringBefore("'", "").trim()
                            if (target.isNotBlank()) add(SkillStep(SkillStepType.OPEN_APP, target = target))
                        }
                        lower.contains("tap_xy") -> {
                            val match = Regex("\\((\\d+)\\s*,\\s*(\\d+)\\)").find(log)
                            if (match != null) {
                                add(
                                    SkillStep(
                                        type = SkillStepType.TAP_XY,
                                        x = match.groupValues[1].toIntOrNull(),
                                        y = match.groupValues[2].toIntOrNull()
                                    )
                                )
                            }
                        }
                        lower.contains("scroll") -> {
                            val dir = if (lower.contains("up")) "up" else "down"
                            add(SkillStep(SkillStepType.SCROLL, value = dir))
                        }
                    }
                }
            }
        }

        fun mergeTriggerPhrases(existing: String, incoming: String): String {
            return (existing.split(",", "\n") + incoming.split(",", "\n"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale.US) }
                .take(8)
                .joinToString(", ")
        }

        private fun normalizeText(raw: String): String {
            return raw.lowercase(Locale.US)
                .replace(Regex("^(?:hey\\s+)?goku[,\\s]+"), "")
                .replace(Regex("[^a-z0-9\\s\\u0900-\\u097F]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }
}
