package com.arif.goku.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

enum class HabitActionType(val label: String) {
    APP_LAUNCH("App Launch"),
    VOICE_COMMAND("Voice Command");

    companion object {
        fun fromStored(raw: String): HabitActionType {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: VOICE_COMMAND
        }
    }
}

/**
 * Room Entity passively recording app launches (via AccessibilityService) and repeated voice
 * commands along with their time-of-day buckets for HabitPredictor pattern discovery.
 */
@Entity(
    tableName = "habit_events",
    indices = [Index(value = ["hourOfDay", "targetPackageOrCommand"])]
)
data class HabitEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val actionType: String, // HabitActionType.name
    val targetPackageOrCommand: String,
    val displayLabel: String,
    val hourOfDay: Int,      // 0..23
    val minuteBucket: Int,   // 0..59 (exact minute for ±20 min window matching)
    val dayOfWeek: Int,      // Calendar.SUNDAY..Calendar.SATURDAY (1..7)
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Room Entity storing Smart Call auto-answered transcripts, spam flags, and post-call Hindi summaries.
 */
@Entity(
    tableName = "call_summaries",
    indices = [Index(value = ["timestamp"])]
)
data class CallSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val callerName: String,
    val callerNumber: String,
    val transcript: String,
    val summaryHindi: String,
    val isSpamOrUnknown: Boolean = false,
    val autoAnswered: Boolean = true,
    val answerMode: String = "TELECOM_FALLBACK",
    val durationSeconds: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface HabitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHabitEvent(event: HabitEventEntity): Long

    @Query("SELECT * FROM habit_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentEvents(limit: Int = 100): Flow<List<HabitEventEntity>>

    @Query("SELECT * FROM habit_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEventsSnapshot(limit: Int = 500): List<HabitEventEntity>

    @Query(
        "SELECT * FROM habit_events WHERE targetPackageOrCommand = :target ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun getLatestEventForTarget(target: String): HabitEventEntity?

    @Query("DELETE FROM habit_events WHERE timestamp < :olderThanMs")
    suspend fun pruneOlderThan(olderThanMs: Long)

    @Query("DELETE FROM habit_events")
    suspend fun clearAll()
}

@Dao
interface CallSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallSummary(summary: CallSummaryEntity): Long

    @Query("SELECT * FROM call_summaries ORDER BY timestamp DESC LIMIT :limit")
    fun observeCallSummaries(limit: Int = 30): Flow<List<CallSummaryEntity>>

    @Query("SELECT * FROM call_summaries ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentCallSummariesSnapshot(limit: Int = 30): List<CallSummaryEntity>

    @Query("DELETE FROM call_summaries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM call_summaries")
    suspend fun clearAll()
}
