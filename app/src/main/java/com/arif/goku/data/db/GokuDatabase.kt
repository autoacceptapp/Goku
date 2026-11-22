package com.arif.goku.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun observeAllMemories(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    suspend fun getAllMemoriesSnapshot(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE LOWER(aliasKey) = LOWER(:aliasKey) LIMIT 1")
    suspend fun findByAliasExact(aliasKey: String): MemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM memories WHERE LOWER(aliasKey) LIKE '%' || LOWER(:query) || '%' OR LOWER(content) LIKE '%' || LOWER(:query) || '%'")
    suspend fun deleteMatching(query: String): Int
}

@Dao
interface ChatHistoryDao {
    @Query("SELECT * FROM chat_history ORDER BY timestamp ASC, id ASC")
    fun observeChatHistory(): Flow<List<ChatHistoryEntity>>

    @Query("SELECT * FROM chat_history ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentChatSnapshot(limit: Int = 60): List<ChatHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(entry: ChatHistoryEntity): Long

    @Query("DELETE FROM chat_history")
    suspend fun clearAll()

    @Query(
        "DELETE FROM chat_history WHERE id NOT IN " +
            "(SELECT id FROM chat_history ORDER BY timestamp DESC, id DESC LIMIT :keepCount)"
    )
    suspend fun trimToLast(keepCount: Int = 120)
}

@Database(
    entities = [
        MemoryEntity::class,
        ChatHistoryEntity::class,
        HabitEventEntity::class,
        CallSummaryEntity::class,
        SkillEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GokuDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun chatHistoryDao(): ChatHistoryDao
    abstract fun habitDao(): HabitDao
    abstract fun callSummaryDao(): CallSummaryDao
    abstract fun skillDao(): SkillDao

    companion object {
        private const val DB_NAME = "goku_memory_vault.db"

        /**
         * Preserves all Phase 4 Personal Memory Vault & Chat History rows while adding
         * Phase 6 `habit_events` and `call_summaries` tables.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `habit_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `targetPackageOrCommand` TEXT NOT NULL,
                        `displayLabel` TEXT NOT NULL,
                        `hourOfDay` INTEGER NOT NULL,
                        `minuteBucket` INTEGER NOT NULL,
                        `dayOfWeek` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_habit_events_hourOfDay_targetPackageOrCommand` " +
                        "ON `habit_events` (`hourOfDay`, `targetPackageOrCommand`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `call_summaries` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `callerName` TEXT NOT NULL,
                        `callerNumber` TEXT NOT NULL,
                        `transcript` TEXT NOT NULL,
                        `summaryHindi` TEXT NOT NULL,
                        `isSpamOrUnknown` INTEGER NOT NULL,
                        `autoAnswered` INTEGER NOT NULL,
                        `answerMode` TEXT NOT NULL,
                        `durationSeconds` INTEGER NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_call_summaries_timestamp` " +
                        "ON `call_summaries` (`timestamp`)"
                )
            }
        }

        /**
         * Phase 7 non-destructive migration preserving all memories, chat history, habits,
         * and call summaries while creating the evolving `skills` table.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skills` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `triggerPhrases` TEXT NOT NULL,
                        `targetPackage` TEXT NOT NULL,
                        `stepsJson` TEXT NOT NULL,
                        `mode` TEXT NOT NULL,
                        `successCount` INTEGER NOT NULL,
                        `failCount` INTEGER NOT NULL,
                        `avgDurationMs` INTEGER NOT NULL,
                        `evolutionScore` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_skills_name` ON `skills` (`name`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_skills_evolutionScore` ON `skills` (`evolutionScore`)"
                )
            }
        }

        @Volatile
        private var INSTANCE: GokuDatabase? = null

        fun getInstance(context: Context): GokuDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GokuDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
