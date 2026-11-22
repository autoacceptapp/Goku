package com.arif.goku.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.HabitEventEntity
import com.arif.goku.data.db.MemoryEntity
import com.arif.goku.data.db.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BrainBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val skills: List<SkillEntity>,
    val memories: List<MemoryEntity>,
    val habits: List<HabitEventEntity>
)

data class BackupOperationReport(
    val skillsCount: Int,
    val memoriesCount: Int,
    val habitsCount: Int,
    val detailHindi: String,
    val gistId: String = ""
)

/**
 * Phase 7 Encrypted Brain Backup & Restore Manager.
 *
 * - Exports all `SkillEntity`, `MemoryEntity`, and `HabitEventEntity` rows into an AES-256-GCM
 *   authenticated encrypted `.goku` JSON bundle (PBKDF2WithHmacSHA256 key derivation with random
 *   16-byte salt & 12-byte GCM nonce).
 * - Supports 1-tap **Export / Restore** via Android Storage Access Framework (`Uri` -> works directly
 *   with Google Drive, Downloads, Files app without broad storage permissions).
 * - Supports optional **Private GitHub Gist Sync** using the Dev Mode GitHub token.
 */
class BrainBackupManager(
    private val context: Context,
    private val db: GokuDatabase,
    private val securePrefs: SecurePrefs
) {
    private val appContext = context.applicationContext
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun buildEncryptedBundle(passphrase: String): Result<Pair<String, BrainBackupPayload>> =
        withContext(Dispatchers.IO) {
            if (passphrase.trim().length < 4) {
                return@withContext Result.failure(
                    IllegalArgumentException("Backup passphrase kam se kam 4 characters ka hona chahiye.")
                )
            }
            val payload = BrainBackupPayload(
                schemaVersion = 1,
                exportedAt = System.currentTimeMillis(),
                skills = db.skillDao().getAllSkillsSnapshot(),
                memories = db.memoryDao().getAllMemoriesSnapshot(),
                habits = db.habitDao().getRecentEventsSnapshot(limit = 500)
            )
            runCatching {
                val encrypted = encryptPayload(payload, passphrase.trim())
                encrypted to payload
            }
        }

    /**
     * Exports AES-256 encrypted `.goku` file to a Storage Access Framework `Uri` (Google Drive / Downloads).
     */
    suspend fun exportToUri(uri: Uri, passphrase: String): Result<BackupOperationReport> =
        withContext(Dispatchers.IO) {
            val (encryptedText, payload) = buildEncryptedBundle(passphrase).getOrElse {
                return@withContext Result.failure(it)
            }
            runCatching {
                val outputStream = appContext.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Selected file write nahi ho payi.")
                outputStream.use { os ->
                    os.write(encryptedText.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                val summary = "Exported ${payload.skills.size} skills, ${payload.memories.size} memories, ${payload.habits.size} habits (.goku AES-256)."
                securePrefs.saveLastBackupSummary(summary)
                BackupOperationReport(
                    skillsCount = payload.skills.size,
                    memoriesCount = payload.memories.size,
                    habitsCount = payload.habits.size,
                    detailHindi = "Encrypted Brain Backup (.goku) save ho gaya: ${payload.skills.size} skills, ${payload.memories.size} memories, ${payload.habits.size} habits."
                )
            }
        }

    /**
     * Decrypts and merges a `.goku` encrypted backup from a Storage Access Framework `Uri`.
     */
    suspend fun restoreFromUri(uri: Uri, passphrase: String): Result<BackupOperationReport> =
        withContext(Dispatchers.IO) {
            if (passphrase.trim().length < 4) {
                return@withContext Result.failure(
                    IllegalArgumentException("Restore ke liye sahi passphrase enter karein.")
                )
            }
            runCatching {
                val inputStream = appContext.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Selected .goku file open nahi ho payi.")
                val rawEnvelope = inputStream.use { it.bufferedReader(Charsets.UTF_8).readText() }
                val payload = decryptPayload(rawEnvelope, passphrase.trim())
                restorePayloadIntoRoom(payload)
            }
        }

    /**
     * Optional zero-cost cloud sync: uploads the AES-256 encrypted `.goku` file to a **private**
     * GitHub Gist using the user's Dev Mode GitHub token.
     */
    suspend fun syncToPrivateGitHubGist(passphrase: String): Result<BackupOperationReport> =
        withContext(Dispatchers.IO) {
            val token = securePrefs.getGithubToken()
            if (token.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Settings -> Dev Mode mein GitHub Token (gist scope) add karein.")
                )
            }
            val (encryptedText, payload) = buildEncryptedBundle(passphrase).getOrElse {
                return@withContext Result.failure(it)
            }

            runCatching {
                val existingGistId = securePrefs.getBackupGistId()
                val filesObj = JSONObject().apply {
                    put(GIST_FILENAME, JSONObject().put("content", encryptedText))
                }
                val bodyJson = JSONObject().apply {
                    put("description", "GokuAgent Encrypted Brain Backup (AES-256-GCM)")
                    put("public", false)
                    put("files", filesObj)
                }

                val url = if (existingGistId.isNotBlank()) {
                    "https://api.github.com/gists/$existingGistId"
                } else {
                    "https://api.github.com/gists"
                }
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $token")
                    .header("X-GitHub-Api-Version", "2022-11-28")

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val req = if (existingGistId.isNotBlank()) {
                    reqBuilder.patch(bodyJson.toString().toRequestBody(mediaType)).build()
                } else {
                    reqBuilder.post(bodyJson.toString().toRequestBody(mediaType)).build()
                }

                val returnedGistId = httpClient.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GitHub Gist sync error (HTTP ${resp.code}): ${respBody.take(120)}")
                    }
                    JSONObject(respBody).optString("id").ifBlank { existingGistId }
                }

                if (returnedGistId.isNotBlank()) {
                    securePrefs.saveBackupGistId(returnedGistId)
                }
                val summary = "Synced to Private GitHub Gist (${returnedGistId.take(8)}…): ${payload.skills.size} skills, ${payload.memories.size} memories."
                securePrefs.saveLastBackupSummary(summary)
                BackupOperationReport(
                    skillsCount = payload.skills.size,
                    memoriesCount = payload.memories.size,
                    habitsCount = payload.habits.size,
                    detailHindi = "Encrypted Brain private GitHub Gist par sync ho gaya (${payload.skills.size} skills, ${payload.memories.size} memories).",
                    gistId = returnedGistId
                )
            }
        }

    /**
     * Pulls and decrypts the `.goku` bundle from the saved Private GitHub Gist ID.
     */
    suspend fun restoreFromPrivateGitHubGist(passphrase: String): Result<BackupOperationReport> =
        withContext(Dispatchers.IO) {
            val token = securePrefs.getGithubToken()
            val gistId = securePrefs.getBackupGistId()
            if (token.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Settings -> Dev Mode mein GitHub Token add karein.")
                )
            }
            if (gistId.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Pehle GitHub Gist Sync chalayein ya Gist ID save karein.")
                )
            }
            if (passphrase.trim().length < 4) {
                return@withContext Result.failure(
                    IllegalArgumentException("Restore ke liye sahi passphrase enter karein.")
                )
            }

            runCatching {
                val req = Request.Builder()
                    .url("https://api.github.com/gists/$gistId")
                    .header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer $token")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .get()
                    .build()

                val rawEnvelope = httpClient.newCall(req).execute().use { resp ->
                    val respBody = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("GitHub Gist download failed (HTTP ${resp.code}).")
                    }
                    val files = JSONObject(respBody).optJSONObject("files")
                        ?: throw IllegalStateException("Gist mein koi files nahi mili.")
                    val fileObj = files.optJSONObject(GIST_FILENAME)
                        ?: throw IllegalStateException("Gist mein $GIST_FILENAME nahi mila.")
                    fileObj.optString("content").ifBlank {
                        throw IllegalStateException("Gist backup file empty hai.")
                    }
                }

                val payload = decryptPayload(rawEnvelope, passphrase.trim())
                restorePayloadIntoRoom(payload, gistId = gistId)
            }
        }

    private suspend fun restorePayloadIntoRoom(
        payload: BrainBackupPayload,
        gistId: String = ""
    ): BackupOperationReport {
        val skillDao = db.skillDao()
        val memoryDao = db.memoryDao()
        val habitDao = db.habitDao()

        var restoredSkills = 0
        for (skill in payload.skills) {
            val existing = skillDao.findByNameExact(skill.name)
            if (existing != null) {
                skillDao.updateSkill(skill.copy(id = existing.id))
            } else {
                skillDao.insertSkill(skill.copy(id = 0L))
            }
            restoredSkills++
        }

        var restoredMemories = 0
        for (mem in payload.memories) {
            val existing = memoryDao.findByAliasExact(mem.aliasKey)
            if (existing != null) {
                memoryDao.updateMemory(mem.copy(id = existing.id))
            } else {
                memoryDao.insertMemory(mem.copy(id = 0L))
            }
            restoredMemories++
        }

        var restoredHabits = 0
        for (habit in payload.habits) {
            habitDao.insertHabitEvent(habit.copy(id = 0L))
            restoredHabits++
        }

        val summary = "Restored $restoredSkills skills, $restoredMemories memories, $restoredHabits habits."
        securePrefs.saveLastBackupSummary(summary)

        return BackupOperationReport(
            skillsCount = restoredSkills,
            memoriesCount = restoredMemories,
            habitsCount = restoredHabits,
            detailHindi = "Brain Restore poora hua: $restoredSkills skills, $restoredMemories memories, aur $restoredHabits habits wapas aa gaye!",
            gistId = gistId
        )
    }

    companion object {
        const val FORMAT_TAG = "GOKU_BRAIN_V1"
        const val GIST_FILENAME = "goku_brain_backup.goku"
        private const val PBKDF2_ALGO = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12

        fun serializePayload(payload: BrainBackupPayload): String {
            val root = JSONObject()
            root.put("schemaVersion", payload.schemaVersion)
            root.put("exportedAt", payload.exportedAt)

            val skillsArr = JSONArray()
            payload.skills.forEach { s ->
                skillsArr.put(
                    JSONObject().apply {
                        put("name", s.name)
                        put("triggerPhrases", s.triggerPhrases)
                        put("targetPackage", s.targetPackage)
                        put("stepsJson", s.stepsJson)
                        put("mode", s.mode)
                        put("successCount", s.successCount)
                        put("failCount", s.failCount)
                        put("avgDurationMs", s.avgDurationMs)
                        put("evolutionScore", s.evolutionScore)
                        put("updatedAt", s.updatedAt)
                    }
                )
            }
            root.put("skills", skillsArr)

            val memArr = JSONArray()
            payload.memories.forEach { m ->
                memArr.put(
                    JSONObject().apply {
                        put("category", m.category)
                        put("aliasKey", m.aliasKey)
                        put("content", m.content)
                        put("updatedAt", m.updatedAt)
                    }
                )
            }
            root.put("memories", memArr)

            val habitArr = JSONArray()
            payload.habits.forEach { h ->
                habitArr.put(
                    JSONObject().apply {
                        put("actionType", h.actionType)
                        put("targetPackageOrCommand", h.targetPackageOrCommand)
                        put("displayLabel", h.displayLabel)
                        put("hourOfDay", h.hourOfDay)
                        put("minuteBucket", h.minuteBucket)
                        put("dayOfWeek", h.dayOfWeek)
                        put("timestamp", h.timestamp)
                    }
                )
            }
            root.put("habits", habitArr)

            return root.toString()
        }

        fun deserializePayload(json: String): BrainBackupPayload {
            val root = JSONObject(json)
            val schemaVersion = root.optInt("schemaVersion", 1)
            val exportedAt = root.optLong("exportedAt", System.currentTimeMillis())

            val skills = mutableListOf<SkillEntity>()
            val skillsArr = root.optJSONArray("skills") ?: JSONArray()
            for (i in 0 until skillsArr.length()) {
                val o = skillsArr.optJSONObject(i) ?: continue
                skills.add(
                    SkillEntity(
                        name = o.optString("name"),
                        triggerPhrases = o.optString("triggerPhrases"),
                        targetPackage = o.optString("targetPackage"),
                        stepsJson = o.optString("stepsJson"),
                        mode = o.optString("mode", "UI_TAP"),
                        successCount = o.optInt("successCount", 0),
                        failCount = o.optInt("failCount", 0),
                        avgDurationMs = o.optLong("avgDurationMs", 0L),
                        evolutionScore = o.optInt("evolutionScore", 50),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }

            val memories = mutableListOf<MemoryEntity>()
            val memArr = root.optJSONArray("memories") ?: JSONArray()
            for (i in 0 until memArr.length()) {
                val o = memArr.optJSONObject(i) ?: continue
                memories.add(
                    MemoryEntity(
                        category = o.optString("category", "FACT"),
                        aliasKey = o.optString("aliasKey"),
                        content = o.optString("content"),
                        updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                    )
                )
            }

            val habits = mutableListOf<HabitEventEntity>()
            val habitArr = root.optJSONArray("habits") ?: JSONArray()
            for (i in 0 until habitArr.length()) {
                val o = habitArr.optJSONObject(i) ?: continue
                habits.add(
                    HabitEventEntity(
                        actionType = o.optString("actionType", "APP_LAUNCH"),
                        targetPackageOrCommand = o.optString("targetPackageOrCommand"),
                        displayLabel = o.optString("displayLabel"),
                        hourOfDay = o.optInt("hourOfDay", 0),
                        minuteBucket = o.optInt("minuteBucket", 0),
                        dayOfWeek = o.optInt("dayOfWeek", 1),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }

            return BrainBackupPayload(
                schemaVersion = schemaVersion,
                exportedAt = exportedAt,
                skills = skills,
                memories = memories,
                habits = habits
            )
        }

        /**
         * Pure AES-256-GCM encryption with random salt + random IV and PBKDF2WithHmacSHA256.
         */
        fun encryptPayload(payload: BrainBackupPayload, passphrase: String): String {
            val plaintext = serializePayload(payload).toByteArray(Charsets.UTF_8)
            val random = SecureRandom()
            val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

            val key = deriveAesKey(passphrase, salt, PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            return JSONObject().apply {
                put("format", FORMAT_TAG)
                put("kdf", PBKDF2_ALGO)
                put("iter", PBKDF2_ITERATIONS)
                put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            }.toString(2)
        }

        /**
         * Pure AES-256-GCM decryption & authentication check. Throws if passphrase is wrong or file is tampered.
         */
        fun decryptPayload(encryptedEnvelopeJson: String, passphrase: String): BrainBackupPayload {
            val env = JSONObject(encryptedEnvelopeJson)
            val format = env.optString("format")
            if (format != FORMAT_TAG) {
                throw IllegalArgumentException("Unsupported backup format: '$format'. Expected $FORMAT_TAG.")
            }
            val iter = env.optInt("iter", PBKDF2_ITERATIONS).coerceIn(10_000, 500_000)
            val salt = Base64.decode(env.getString("salt"), Base64.NO_WRAP)
            val iv = Base64.decode(env.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(env.getString("ciphertext"), Base64.NO_WRAP)

            val key = deriveAesKey(passphrase, salt, iter)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintextBytes = cipher.doFinal(ciphertext)
            return deserializePayload(String(plaintextBytes, Charsets.UTF_8))
        }

        private fun deriveAesKey(passphrase: String, salt: ByteArray, iterations: Int): SecretKeySpec {
            val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, AES_KEY_BITS)
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGO)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        }
    }
}
