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
    entities = [MemoryEntity::class, ChatHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class GokuDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun chatHistoryDao(): ChatHistoryDao

    companion object {
        private const val DB_NAME = "goku_memory_vault.db"

        @Volatile
        private var INSTANCE: GokuDatabase? = null

        fun getInstance(context: Context): GokuDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GokuDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
