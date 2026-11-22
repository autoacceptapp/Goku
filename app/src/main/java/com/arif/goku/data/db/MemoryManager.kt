package com.arif.goku.data.db

import com.arif.goku.brain.ContextBrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class MemoryCommandOutcome(
    val handled: Boolean,
    val replyHindi: String,
    val affectedAlias: String = ""
)

/**
 * Repository & intelligence layer over GokuAgent's Room Personal Memory Vault (`GokuDatabase`).
 *
 * Responsibilities:
 * 1) Exposes reactive `Flow<List<MemoryEntity>>` for the Memory Vault Settings UI.
 * 2) Retrieves the most relevant memories for any user prompt and formats a clean prompt block.
 * 3) Handles explicit voice commands ("Yaad rakhna..." / "Bhool jaao...") and passively extracts
 *    personal facts, contact aliases, preferences, and routines from natural conversations.
 * 4) Resolves saved contact aliases (e.g., "Papa" -> "+91...") for offline/online calling & WhatsApp.
 * 5) Persists chat history across app restarts.
 */
class MemoryManager(
    private val memoryDao: MemoryDao,
    private val chatHistoryDao: ChatHistoryDao
) {
    val allMemories: Flow<List<MemoryEntity>> = memoryDao.observeAllMemories()

    suspend fun saveOrUpsertMemory(
        category: MemoryCategory,
        aliasKey: String,
        content: String
    ): MemoryEntity = withContext(Dispatchers.IO) {
        val cleanAlias = aliasKey.trim().take(60)
        val cleanContent = content.trim().take(320)
        val now = System.currentTimeMillis()
        val existing = memoryDao.findByAliasExact(cleanAlias)
        if (existing != null) {
            val updated = existing.copy(
                category = category.name,
                content = cleanContent,
                updatedAt = now
            )
            memoryDao.updateMemory(updated)
            updated
        } else {
            val created = MemoryEntity(
                category = category.name,
                aliasKey = cleanAlias,
                content = cleanContent,
                updatedAt = now
            )
            val newId = memoryDao.insertMemory(created)
            created.copy(id = newId)
        }
    }

    suspend fun updateMemory(memory: MemoryEntity) = withContext(Dispatchers.IO) {
        memoryDao.updateMemory(memory.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteMemoryById(id: Long) = withContext(Dispatchers.IO) {
        memoryDao.deleteById(id)
    }

    /**
     * Retrieves top relevant memories for the given user query by scoring alias match,
     * token overlap, and recency.
     */
    suspend fun retrieveRelevantMemories(query: String, limit: Int = 6): List<MemoryEntity> =
        withContext(Dispatchers.IO) {
            val all = memoryDao.getAllMemoriesSnapshot()
            if (all.isEmpty()) return@withContext emptyList()

            val lowerQuery = query.lowercase()
            val queryTokens = tokenize(lowerQuery)

            val scored = all.mapIndexed { idx, memory ->
                var score = 0
                val aliasLower = memory.aliasKey.lowercase()
                val contentLower = memory.content.lowercase()

                if (aliasLower.isNotBlank() && lowerQuery.contains(aliasLower)) {
                    score += 100
                }
                val memTokens = tokenize("$aliasLower $contentLower")
                val overlap = queryTokens.count { it in memTokens }
                score += overlap * 18

                // Always keep core PERSON & PREFERENCE memories lightly boosted
                if (memory.category == MemoryCategory.PERSON.name) score += 6
                if (memory.category == MemoryCategory.PREFERENCE.name) score += 4
                // Slight recency preference
                score += (all.size - idx).coerceAtLeast(0).coerceAtMost(10)

                memory to score
            }

            scored.sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        }

    suspend fun buildMemoryPromptBlock(query: String, limit: Int = 6): String {
        val relevant = retrieveRelevantMemories(query, limit)
        if (relevant.isEmpty()) return ""
        return buildString {
            appendLine("[PERSONAL MEMORY VAULT]")
            relevant.forEach { mem ->
                appendLine("- [${mem.category}] ${mem.aliasKey}: ${mem.content}")
            }
        }.trim()
    }

    /**
     * Looks up a saved PERSON memory (e.g., "Papa" -> "Husenbhai - +9198...") and extracts
     * a callable phone number for offline & online calls / WhatsApp.
     */
    suspend fun resolveSavedPersonContact(query: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val cleaned = query.trim().lowercase()
            if (cleaned.isEmpty()) return@withContext null
            val all = memoryDao.getAllMemoriesSnapshot()
            val match = all.firstOrNull { mem ->
                val alias = mem.aliasKey.lowercase()
                alias == cleaned || cleaned.contains(alias) || alias.contains(cleaned)
            } ?: return@withContext null

            val number = ContextBrain.extractPhoneNumber(match.content) ?: return@withContext null
            match.aliasKey to number
        }

    /**
     * Detects and executes explicit user memory commands:
     * - "Yaad rakhna Papa ka number 9876543210 hai"
     * - "Remember my bike number is GJ01AB1234"
     * - "Bhool jaao Papa ka purana number" / "Forget ..."
     */
    suspend fun processExplicitMemoryCommand(rawInput: String): MemoryCommandOutcome? =
        withContext(Dispatchers.IO) {
            val trimmed = rawInput.trim()
            val lower = trimmed.lowercase()

            // 1. Forget command ("Bhool jaao ..." / "Forget ...")
            val forgetMatch = FORGET_REGEX.find(trimmed)
            if (forgetMatch != null) {
                val target = forgetMatch.groupValues[1].trim().trimEnd('.', '!', '?')
                if (target.isBlank()) {
                    return@withContext MemoryCommandOutcome(
                        handled = true,
                        replyHindi = "Kya bhoolna hai? Kripya naam ya baat boliye."
                    )
                }
                val deleted = memoryDao.deleteMatching(target)
                val reply = if (deleted > 0) {
                    "Theek hai, maine \"$target\" se judi $deleted memory bhool di hai."
                } else {
                    "Memory Vault mein \"$target\" naam se kuch saved nahi mila."
                }
                return@withContext MemoryCommandOutcome(
                    handled = true,
                    replyHindi = reply,
                    affectedAlias = target
                )
            }

            // 2. Explicit Remember command ("Yaad rakhna ..." / "Remember ...")
            val rememberMatch = REMEMBER_REGEX.find(trimmed)
            if (rememberMatch != null) {
                val payload = rememberMatch.groupValues[1].trim()
                if (payload.isBlank()) {
                    return@withContext MemoryCommandOutcome(
                        handled = true,
                        replyHindi = "Kya yaad rakhna hai? Kripya poori baat boliye."
                    )
                }
                val (category, alias, content) = classifyAndExtract(payload)
                saveOrUpsertMemory(category, alias, content)
                return@withContext MemoryCommandOutcome(
                    handled = true,
                    replyHindi = "Done! Maine Memory Vault mein save kar liya hai ($alias: $content).",
                    affectedAlias = alias
                )
            }

            // 3. Query saved memory directly ("Meri memories dikhao" / "Kya yaad hai")
            if (lower.contains("kya kya yaad hai") || lower.contains("meri memory batao") ||
                lower.contains("list memories")
            ) {
                val list = memoryDao.getAllMemoriesSnapshot().take(5)
                val reply = if (list.isEmpty()) {
                    "Abhi Memory Vault khaali hai. Aap 'Yaad rakhna...' bolkar kuch bhi save kar sakte hain."
                } else {
                    "Mujhe yeh baatein yaad hain: " + list.joinToString("; ") { "${it.aliasKey} — ${it.content}" }
                }
                return@withContext MemoryCommandOutcome(handled = true, replyHindi = reply)
            }

            null
        }

    /**
     * Passively scans natural user messages and saves clear personal facts/contacts/preferences
     * without interrupting the conversation.
     */
    suspend fun autoExtractFromUserMessage(rawInput: String): MemoryEntity? =
        withContext(Dispatchers.IO) {
            val trimmed = rawInput.trim()
            if (trimmed.length < 6 || trimmed.length > 260) return@withContext null

            // Pattern A: "Mera naam Arif hai" / "My name is Arif"
            NAME_FACT_REGEX.find(trimmed)?.let { m ->
                val name = m.groupValues[1].trim().trimEnd('.', '!')
                if (name.length in 2..40) {
                    return@withContext saveOrUpsertMemory(MemoryCategory.FACT, "User Name", name)
                }
            }

            // Pattern B: "Papa ka number 9876543210 hai" / "Mom number is +91..."
            PERSON_NUMBER_REGEX.find(trimmed)?.let { m ->
                val alias = m.groupValues[1].trim().replaceFirstChar { it.uppercase() }
                val phone = ContextBrain.extractPhoneNumber(m.groupValues[2])
                if (alias.length in 2..30 && !phone.isNullOrBlank()) {
                    return@withContext saveOrUpsertMemory(MemoryCategory.PERSON, alias, phone)
                }
            }

            // Pattern C: "Mujhe chai pasand hai" / "I like dark mode"
            PREFERENCE_REGEX.find(trimmed)?.let { m ->
                val pref = m.groupValues[1].trim().trimEnd('.', '!')
                if (pref.length in 3..90) {
                    return@withContext saveOrUpsertMemory(
                        MemoryCategory.PREFERENCE,
                        "Likes ${pref.take(24)}",
                        trimmed
                    )
                }
            }

            // Pattern D: "Main roz subah 6 baje uthta hoon" / "My daily routine..."
            ROUTINE_REGEX.find(trimmed)?.let {
                return@withContext saveOrUpsertMemory(
                    MemoryCategory.ROUTINE,
                    "Daily Routine",
                    trimmed
                )
            }

            null
        }

    // --- Persistent Chat History ---

    suspend fun recordChatTurn(role: String, message: String, isAlert: Boolean = false) =
        withContext(Dispatchers.IO) {
            chatHistoryDao.insertMessage(
                ChatHistoryEntity(
                    role = role,
                    message = message,
                    timestamp = System.currentTimeMillis(),
                    isAlert = isAlert
                )
            )
            chatHistoryDao.trimToLast(120)
        }

    suspend fun loadRecentChatHistory(limit: Int = 60): List<ChatHistoryEntity> =
        withContext(Dispatchers.IO) {
            chatHistoryDao.getRecentChatSnapshot(limit).reversed()
        }

    suspend fun clearChatHistory() = withContext(Dispatchers.IO) {
        chatHistoryDao.clearAll()
    }

    private fun classifyAndExtract(payload: String): Triple<MemoryCategory, String, String> {
        val phone = ContextBrain.extractPhoneNumber(payload)
        val lower = payload.lowercase()

        // Check "<Alias> ka number <phone>" or "<Alias>: <content>"
        val colonIdx = payload.indexOf(':')
        if (colonIdx in 2..35) {
            val alias = payload.substring(0, colonIdx).trim().replaceFirstChar { it.uppercase() }
            val content = payload.substring(colonIdx + 1).trim()
            val cat = inferCategory(lower, phone != null)
            return Triple(cat, alias, content.ifBlank { payload })
        }

        val relationMatch = PERSON_ALIAS_PREFIX.find(payload)
        if (relationMatch != null) {
            val alias = relationMatch.groupValues[1].trim().replaceFirstChar { it.uppercase() }
            val cat = if (phone != null || lower.contains("bhai") || lower.contains("papa") ||
                lower.contains("mom") || lower.contains("mummy") || lower.contains("dost") ||
                lower.contains("number")
            ) {
                MemoryCategory.PERSON
            } else {
                inferCategory(lower, false)
            }
            return Triple(cat, alias, payload)
        }

        val category = inferCategory(lower, phone != null)
        val shortAlias = payload.split(Regex("\\s+")).take(3).joinToString(" ")
            .replaceFirstChar { it.uppercase() }
        return Triple(category, shortAlias, payload)
    }

    private fun inferCategory(lower: String, hasPhone: Boolean): MemoryCategory {
        return when {
            hasPhone || lower.contains("number") || lower.contains("contact") ||
                lower.contains("papa") || lower.contains("mummy") || lower.contains("mom") ||
                lower.contains("boss") -> MemoryCategory.PERSON
            lower.contains("pasand") || lower.contains("favorite") || lower.contains("prefer") ||
                lower.contains("like") -> MemoryCategory.PREFERENCE
            lower.contains("roz") || lower.contains("daily") || lower.contains("subah") ||
                lower.contains("routine") || lower.contains("every day") -> MemoryCategory.ROUTINE
            else -> MemoryCategory.FACT
        }
    }

    private fun tokenize(text: String): Set<String> {
        return text.split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .toSet()
    }

    companion object {
        private val REMEMBER_REGEX = Regex(
            "(?:yaad\\s+rakhna|yaad\\s+rakho|remember\\s+that|remember|याद\\s+रखना|याद\\s+रखो)\\s*(?:ki|that)?\\s*[:,-]?\\s*(.+)",
            RegexOption.IGNORE_CASE
        )
        private val FORGET_REGEX = Regex(
            "(?:bhool\\s+jaao|bhool\\s+jao|bhul\\s+jao|forget\\s+about|forget|भूल\\s+जाओ|भूल\\s+जा)\\s*[:,-]?\\s*(.+)",
            RegexOption.IGNORE_CASE
        )
        private val NAME_FACT_REGEX = Regex(
            "(?:mera\\s+naam|my\\s+name\\s+is)\\s+([\\p{L}\\p{M}\\s]{2,35}?)(?:\\s+hai\\b|$|[.,!])",
            RegexOption.IGNORE_CASE
        )
        private val PERSON_NUMBER_REGEX = Regex(
            "([\\p{L}\\p{M}]{2,24})\\s+(?:ka\\s+number|ki\\s+number|number\\s+is|phone\\s+is)\\s+(.+)",
            RegexOption.IGNORE_CASE
        )
        private val PERSON_ALIAS_PREFIX = Regex(
            "^([\\p{L}\\p{M}]{2,24})\\s+(?:ka|ki|ke|is|-)\\s+",
            RegexOption.IGNORE_CASE
        )
        private val PREFERENCE_REGEX = Regex(
            "(?:mujhe|i\\s+like|i\\s+prefer|my\\s+favorite\\s+is)\\s+(.+?)(?:\\s+pasand\\s+hai|$|[.,!])",
            RegexOption.IGNORE_CASE
        )
        private val ROUTINE_REGEX = Regex(
            "(?:main\\s+roz|har\\s+roz|daily\\s+routine|every\\s+morning|every\\s+day\\s+at)",
            RegexOption.IGNORE_CASE
        )
        private val STOP_WORDS = setOf(
            "hai", "ko", "ka", "ki", "ke", "se", "me", "mein", "par", "aur", "kya", "goku",
            "the", "is", "to", "in", "for", "of", "and", "please", "karo", "kar"
        )
    }
}
