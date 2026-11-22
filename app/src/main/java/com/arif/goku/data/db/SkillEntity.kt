package com.arif.goku.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

enum class SkillMode(val label: String, val speedBonus: Int) {
    ADB_FAST("ADB_FAST", 15),
    INTENT("INTENT", 11),
    UI_TAP("UI_TAP", 4);

    companion object {
        fun fromStored(raw: String): SkillMode {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: UI_TAP
        }
    }
}

enum class SkillStepType {
    ADB_SHELL,
    INTENT_URI,
    OPEN_APP,
    CLICK_TEXT,
    TYPE_TEXT,
    TAP_XY,
    SCROLL,
    WAIT,
    SPEAK;

    companion object {
        fun fromString(raw: String): SkillStepType? {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

data class SkillStep(
    val type: SkillStepType,
    val target: String = "",
    val value: String = "",
    val x: Int? = null,
    val y: Int? = null,
    val delayMs: Long = 350L
)

/**
 * Pure JSON codec for serializing and safely parsing ordered Skill steps (`stepsJson`).
 * Tolerates markdown fences and partial LLM wrappers.
 */
object SkillStepCodec {

    fun toJson(steps: List<SkillStep>): String {
        val arr = JSONArray()
        steps.forEach { step ->
            val obj = JSONObject()
            obj.put("type", step.type.name)
            if (step.target.isNotBlank()) obj.put("target", step.target)
            if (step.value.isNotBlank()) obj.put("value", step.value)
            if (step.x != null) obj.put("x", step.x)
            if (step.y != null) obj.put("y", step.y)
            if (step.delayMs != 350L) obj.put("delayMs", step.delayMs)
            arr.put(obj)
        }
        return arr.toString()
    }

    fun parseSteps(rawJson: String): List<SkillStep> {
        val cleaned = extractJsonArrayString(rawJson) ?: return emptyList()
        return try {
            val arr = JSONArray(cleaned)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawType = obj.optString("type").ifBlank { obj.optString("action") }
                    val stepType = SkillStepType.fromString(rawType) ?: continue
                    val target = obj.optString("target", "").trim()
                    val value = obj.optString("value", "")
                        .ifBlank { obj.optString("text", "") }
                        .ifBlank { obj.optString("command", "") }
                        .ifBlank { obj.optString("uri", "") }
                        .trim()
                    val x = if (obj.has("x") && !obj.isNull("x")) obj.optInt("x") else null
                    val y = if (obj.has("y") && !obj.isNull("y")) obj.optInt("y") else null
                    val delayMs = obj.optLong("delayMs", 350L).coerceIn(0L, 15_000L)
                    add(
                        SkillStep(
                            type = stepType,
                            target = target,
                            value = value,
                            x = x,
                            y = y,
                            delayMs = delayMs
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun inferMode(steps: List<SkillStep>): SkillMode {
        if (steps.isEmpty()) return SkillMode.UI_TAP
        val hasUiStep = steps.any {
            it.type == SkillStepType.CLICK_TEXT ||
                it.type == SkillStepType.TYPE_TEXT ||
                it.type == SkillStepType.TAP_XY ||
                it.type == SkillStepType.SCROLL
        }
        if (hasUiStep) return SkillMode.UI_TAP
        val hasAdb = steps.any { it.type == SkillStepType.ADB_SHELL }
        if (hasAdb) return SkillMode.ADB_FAST
        val hasIntentOrOpen = steps.any {
            it.type == SkillStepType.INTENT_URI || it.type == SkillStepType.OPEN_APP
        }
        return if (hasIntentOrOpen) SkillMode.INTENT else SkillMode.ADB_FAST
    }

    private fun extractJsonArrayString(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val noFence = trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        // If wrapped inside an object with "steps": [...]
        if (noFence.startsWith("{")) {
            val obj = runCatching { JSONObject(noFence) }.getOrNull()
            val nested = obj?.optJSONArray("steps")
            if (nested != null) return nested.toString()
        }
        val start = noFence.indexOf('[')
        val end = noFence.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return noFence.substring(start, end + 1)
        }
        return null
    }
}

/**
 * Room Entity storing GokuAgent's evolving, self-healing, offline-executable Skills.
 */
@Entity(
    tableName = "skills",
    indices = [
        Index(value = ["name"]),
        Index(value = ["evolutionScore"])
    ]
)
data class SkillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val triggerPhrases: String,
    val targetPackage: String = "",
    val stepsJson: String,
    val mode: String = SkillMode.UI_TAP.name,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val avgDurationMs: Long = 0L,
    val evolutionScore: Int = 50,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun modeEnum(): SkillMode = SkillMode.fromStored(mode)

    fun parsedSteps(): List<SkillStep> = SkillStepCodec.parseSteps(stepsJson)

    fun triggerList(): List<String> = triggerPhrases
        .split(",", "\n", "|")
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY evolutionScore DESC, updatedAt DESC")
    fun observeAllSkills(): Flow<List<SkillEntity>>

    @Query("SELECT * FROM skills ORDER BY evolutionScore DESC, updatedAt DESC")
    suspend fun getAllSkillsSnapshot(): List<SkillEntity>

    @Query("SELECT * FROM skills WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SkillEntity?

    @Query("SELECT * FROM skills WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByNameExact(name: String): SkillEntity?

    @Query(
        "SELECT * FROM skills WHERE (mode = 'UI_TAP' AND evolutionScore < :maxScore) OR failCount > 0 " +
            "ORDER BY evolutionScore ASC, failCount DESC LIMIT :limit"
    )
    suspend fun getCandidatesForOptimization(maxScore: Int = 75, limit: Int = 8): List<SkillEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: SkillEntity): Long

    @Update
    suspend fun updateSkill(skill: SkillEntity)

    @Query("DELETE FROM skills WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM skills")
    suspend fun clearAll()
}
