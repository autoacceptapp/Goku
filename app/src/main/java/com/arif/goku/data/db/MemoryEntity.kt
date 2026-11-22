package com.arif.goku.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MemoryCategory(val label: String) {
    PERSON("Person"),
    PREFERENCE("Preference"),
    FACT("Fact"),
    ROUTINE("Routine");

    companion object {
        fun fromStored(raw: String): MemoryCategory {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: FACT
        }
    }
}

/**
 * Room Entity storing personal facts, contact aliases, preferences, and daily routines
 * in GokuAgent's local Personal Memory Vault.
 */
@Entity(
    tableName = "memories",
    indices = [Index(value = ["aliasKey"], unique = false)]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val category: String = MemoryCategory.FACT.name,
    val aliasKey: String,
    val content: String,
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun categoryEnum(): MemoryCategory = MemoryCategory.fromStored(category)
}

/**
 * Room Entity storing persistent chat history across app restarts.
 */
@Entity(tableName = "chat_history")
data class ChatHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val role: String, // "USER", "GOKU", "SYSTEM"
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isAlert: Boolean = false
)
