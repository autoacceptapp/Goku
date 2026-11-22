package com.arif.goku

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import com.arif.goku.backup.BrainBackupManager
import com.arif.goku.backup.BrainBackupPayload
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.HabitActionType
import com.arif.goku.data.db.HabitEventEntity
import com.arif.goku.data.db.MemoryCategory
import com.arif.goku.data.db.MemoryEntity
import com.arif.goku.data.db.SkillEntity
import com.arif.goku.data.db.SkillMode
import com.arif.goku.data.db.SkillStep
import com.arif.goku.data.db.SkillStepCodec
import com.arif.goku.data.db.SkillStepType
import com.arif.goku.skills.RawTraceAction
import com.arif.goku.skills.RecordedTraceEvent
import com.arif.goku.skills.SkillEngine
import com.arif.goku.skills.TeachByExampleRecorder
import com.arif.goku.system.DiagnosisCategory
import com.arif.goku.system.LogcatDoctor
import com.arif.goku.worker.DreamModeScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase7SkillsDreamBackupTest {

    @Test
    fun skillStepCodec_parsesAllStepTypesAndMarkdownWrappedJson() {
        val rawLlmOutput = """
            ```json
            {
              "name": "Morning Tech Brief",
              "triggerPhrases": "morning news, subah ki news, tech brief",
              "targetPackage": "com.google.android.youtube",
              "mode": "INTENT",
              "steps": [
                {"type":"ADB_SHELL","value":"settings put system screen_brightness 180","delayMs":200},
                {"type":"INTENT_URI","target":"https://www.youtube.com/results?search_query=tech+news"},
                {"type":"WAIT","value":"1200"},
                {"type":"CLICK_TEXT","target":"Play"},
                {"type":"TYPE_TEXT","target":"Search","value":"Hindi tech"},
                {"type":"TAP_XY","x":540,"y":1200},
                {"type":"SCROLL","value":"down"},
                {"type":"OPEN_APP","target":"com.whatsapp"},
                {"type":"SPEAK","value":"Morning brief ready hai!"}
              ]
            }
            ```
        """.trimIndent()

        val parsed = SkillEngine.parseSynthesizedSkillJson(rawLlmOutput, fallbackGoal = "fallback")
        assertNotNull(parsed)
        assertEquals("Morning Tech Brief", parsed!!.name)
        assertEquals("com.google.android.youtube", parsed.targetPackage)
        assertEquals(SkillMode.INTENT, parsed.modeEnum())

        val steps = parsed.parsedSteps()
        assertEquals(9, steps.size)
        assertEquals(SkillStepType.ADB_SHELL, steps[0].type)
        assertEquals(SkillStepType.INTENT_URI, steps[1].type)
        assertEquals(SkillStepType.WAIT, steps[2].type)
        assertEquals(SkillStepType.CLICK_TEXT, steps[3].type)
        assertEquals(SkillStepType.TYPE_TEXT, steps[4].type)
        assertEquals(540, steps[5].x)
        assertEquals(1200, steps[5].y)
        assertEquals(SkillStepType.SCROLL, steps[6].type)
        assertEquals(SkillStepType.OPEN_APP, steps[7].type)
        assertEquals(SkillStepType.SPEAK, steps[8].type)

        // Round-trip re-serialization preserves steps
        val reEncoded = SkillStepCodec.toJson(steps)
        assertEquals(steps.size, SkillStepCodec.parseSteps(reEncoded).size)
    }

    @Test
    fun evolutionScore_rewardsSpeedReliabilityAndFastExecutionModes() {
        val adbFastScore = SkillEngine.computeEvolutionScore(
            successCount = 10,
            failCount = 0,
            avgDurationMs = 400L,
            mode = SkillMode.ADB_FAST
        )
        val intentScore = SkillEngine.computeEvolutionScore(
            successCount = 10,
            failCount = 0,
            avgDurationMs = 700L,
            mode = SkillMode.INTENT
        )
        val slowUiScore = SkillEngine.computeEvolutionScore(
            successCount = 3,
            failCount = 4,
            avgDurationMs = 9_500L,
            mode = SkillMode.UI_TAP
        )

        assertTrue("ADB_FAST ($adbFastScore) should beat INTENT ($intentScore)", adbFastScore > intentScore)
        assertTrue("INTENT ($intentScore) should beat unreliable slow UI_TAP ($slowUiScore)", intentScore > slowUiScore)
        assertTrue(adbFastScore in 90..100)
        assertTrue(slowUiScore in 0..55)

        // Verify recordRunStats updates averages, counters, and score monotonically
        val baseSkill = SkillEntity(
            id = 1L,
            name = "Wi-Fi Fast Toggle",
            triggerPhrases = "wifi fast, net toggle",
            stepsJson = SkillStepCodec.toJson(listOf(SkillStep(SkillStepType.ADB_SHELL, value = "svc wifi enable"))),
            mode = SkillMode.ADB_FAST.name
        )
        val afterSuccess = SkillEngine.recordRunStats(baseSkill, succeeded = true, runDurationMs = 350L)
        assertEquals(1, afterSuccess.successCount)
        assertEquals(0, afterSuccess.failCount)
        assertEquals(350L, afterSuccess.avgDurationMs)

        val afterFail = SkillEngine.recordRunStats(afterSuccess, succeeded = false, runDurationMs = 6_000L)
        assertEquals(1, afterFail.failCount)
        assertTrue(afterFail.evolutionScore < afterSuccess.evolutionScore)

        // Trigger matching finds skill offline from natural Hinglish command
        val matched = SkillEngine.matchBestSkill("Hey Goku, wifi fast chalao", listOf(afterSuccess))
        assertNotNull(matched)
        assertEquals("Wi-Fi Fast Toggle", matched?.name)
    }

    @Test
    fun teachByExample_stripsWastedStepsAndUpgradesToFastIntent() {
        val t0 = 1_700_000_000_000L
        val rawTrace = listOf(
            // Goku's own UI event -> must be stripped
            RecordedTraceEvent(RawTraceAction.CLICK, "com.arif.goku", label = "TEACH", timestampMs = t0),
            // User opens YouTube
            RecordedTraceEvent(RawTraceAction.OPEN_APP, "com.google.android.youtube", label = "youtube", timestampMs = t0 + 400),
            // User accidentally taps "Shorts" and immediately presses Back -> both must cancel out!
            RecordedTraceEvent(RawTraceAction.CLICK, "com.google.android.youtube", label = "Shorts", timestampMs = t0 + 1000),
            RecordedTraceEvent(RawTraceAction.BACK, "com.google.android.youtube", label = "Back", timestampMs = t0 + 2200),
            // User double-taps Search accidentally within 500ms -> deduplicated to 1
            RecordedTraceEvent(RawTraceAction.CLICK, "com.google.android.youtube", label = "Search", viewId = "id/search", timestampMs = t0 + 3000),
            RecordedTraceEvent(RawTraceAction.CLICK, "com.google.android.youtube", label = "Search", viewId = "id/search", timestampMs = t0 + 3400),
            // Incremental typing keystrokes -> collapsed to final text "lofi beats"
            RecordedTraceEvent(RawTraceAction.TYPE_TEXT, "com.google.android.youtube", viewId = "id/search_edit", typedText = "lo", timestampMs = t0 + 4000),
            RecordedTraceEvent(RawTraceAction.TYPE_TEXT, "com.google.android.youtube", viewId = "id/search_edit", typedText = "lofi", timestampMs = t0 + 4300),
            RecordedTraceEvent(RawTraceAction.TYPE_TEXT, "com.google.android.youtube", viewId = "id/search_edit", typedText = "lofi beats", timestampMs = t0 + 4700),
            // Redundant trailing scrolls -> stripped
            RecordedTraceEvent(RawTraceAction.SCROLL, "com.google.android.youtube", timestampMs = t0 + 5200),
            RecordedTraceEvent(RawTraceAction.SCROLL, "com.google.android.youtube", timestampMs = t0 + 5500)
        )

        val result = TeachByExampleRecorder.optimizeTrace(rawTrace, selfPackageName = "com.arif.goku")

        assertTrue("Expected wasted steps removed, got ${result.removedWasteCount}", result.removedWasteCount >= 7)
        assertFalse(result.cleanedEvents.any { it.label == "Shorts" })
        assertEquals(1, result.cleanedEvents.count { it.action == RawTraceAction.TYPE_TEXT })
        assertEquals("lofi beats", result.cleanedEvents.first { it.action == RawTraceAction.TYPE_TEXT }.typedText)

        // Slow YouTube search trace upgraded automatically into instant INTENT_URI deep link!
        assertEquals(SkillMode.INTENT, result.inferredMode)
        assertEquals(1, result.upgradedSteps.size)
        assertEquals(SkillStepType.INTENT_URI, result.upgradedSteps.first().type)
        assertTrue(result.upgradedSteps.first().target.contains("youtube.com/results?search_query=lofi+beats"))
    }

    @Test
    fun encryptedBrainBackup_aes256RoundTripAndWrongPassphraseRejection() {
        val payload = BrainBackupPayload(
            schemaVersion = 1,
            exportedAt = 1_700_000_555_000L,
            skills = listOf(
                SkillEntity(
                    name = "Open Wi-Fi Settings",
                    triggerPhrases = "wifi settings, net settings",
                    targetPackage = "com.android.settings",
                    stepsJson = SkillStepCodec.toJson(listOf(SkillStep(SkillStepType.INTENT_URI, target = "android.settings.WIFI_SETTINGS"))),
                    mode = SkillMode.INTENT.name,
                    successCount = 7,
                    failCount = 1,
                    avgDurationMs = 520L,
                    evolutionScore = 88
                )
            ),
            memories = listOf(
                MemoryEntity(
                    category = MemoryCategory.PERSON.name,
                    aliasKey = "Papa",
                    content = "Husenbhai - +919876543210"
                )
            ),
            habits = listOf(
                HabitEventEntity(
                    actionType = HabitActionType.APP_LAUNCH.name,
                    targetPackageOrCommand = "com.whatsapp",
                    displayLabel = "WhatsApp",
                    hourOfDay = 9,
                    minuteBucket = 0,
                    dayOfWeek = Calendar.MONDAY
                )
            )
        )

        val passphrase = "GokuStrongPass#2026"
        val encrypted1 = BrainBackupManager.encryptPayload(payload, passphrase)
        val encrypted2 = BrainBackupManager.encryptPayload(payload, passphrase)

        // Ensure AES-256-GCM envelope hides plaintext and uses unique random salt + IV each time
        assertTrue(encrypted1.contains(BrainBackupManager.FORMAT_TAG))
        assertFalse(encrypted1.contains("Husenbhai"))
        assertFalse(encrypted1.contains("WIFI_SETTINGS"))
        assertNotEquals(encrypted1, encrypted2)

        // Decrypt with correct passphrase -> exact data fidelity
        val restored = BrainBackupManager.decryptPayload(encrypted1, passphrase)
        assertEquals(1, restored.skills.size)
        assertEquals("Open Wi-Fi Settings", restored.skills.first().name)
        assertEquals(88, restored.skills.first().evolutionScore)
        assertEquals(1, restored.memories.size)
        assertEquals("Papa", restored.memories.first().aliasKey)
        assertEquals(1, restored.habits.size)
        assertEquals("com.whatsapp", restored.habits.first().targetPackageOrCommand)

        // Wrong passphrase must fail GCM authentication cleanly
        var rejected = false
        try {
            BrainBackupManager.decryptPayload(encrypted1, "WrongPassphrase!")
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Decryption with wrong passphrase must fail GCM tag verification", rejected)
    }

    @Test
    fun roomMigration2To3_logcatDoctorAndDreamConstraintsWorkCorrectly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFileName = "phase7_migration_2_3_${System.currentTimeMillis()}.db"
        val dbFile: File = context.getDatabasePath(dbFileName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        // 1. Seed a real v2 SQLite DB (memories, chat_history, habit_events, call_summaries)
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqlite.execSQL(
                "CREATE TABLE `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `category` TEXT NOT NULL, `aliasKey` TEXT NOT NULL, `content` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
            )
            sqlite.execSQL("CREATE INDEX `index_memories_aliasKey` ON `memories` (`aliasKey`)")
            sqlite.execSQL(
                "CREATE TABLE `chat_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `role` TEXT NOT NULL, `message` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isAlert` INTEGER NOT NULL)"
            )
            sqlite.execSQL(
                "CREATE TABLE `habit_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `actionType` TEXT NOT NULL, `targetPackageOrCommand` TEXT NOT NULL, `displayLabel` TEXT NOT NULL, `hourOfDay` INTEGER NOT NULL, `minuteBucket` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)"
            )
            sqlite.execSQL(
                "CREATE INDEX `index_habit_events_hourOfDay_targetPackageOrCommand` ON `habit_events` (`hourOfDay`, `targetPackageOrCommand`)"
            )
            sqlite.execSQL(
                "CREATE TABLE `call_summaries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `callerName` TEXT NOT NULL, `callerNumber` TEXT NOT NULL, `transcript` TEXT NOT NULL, `summaryHindi` TEXT NOT NULL, `isSpamOrUnknown` INTEGER NOT NULL, `autoAnswered` INTEGER NOT NULL, `answerMode` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL)"
            )
            sqlite.execSQL("CREATE INDEX `index_call_summaries_timestamp` ON `call_summaries` (`timestamp`)")
            sqlite.execSQL(
                "INSERT INTO `memories` (`category`,`aliasKey`,`content`,`updatedAt`) VALUES ('FACT','City','Mumbai',1700000000000)"
            )
            sqlite.version = 2
        } finally {
            sqlite.close()
        }

        // 2. Migrate v2 -> v3 with MIGRATION_2_3
        val db = Room.databaseBuilder(context, GokuDatabase::class.java, dbFileName)
            .addMigrations(GokuDatabase.MIGRATION_1_2, GokuDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            assertEquals(1, db.memoryDao().getAllMemoriesSnapshot().size)
            val skillId = db.skillDao().insertSkill(
                SkillEntity(
                    name = "Night Dark Mode",
                    triggerPhrases = "dark mode on, raat mode",
                    stepsJson = SkillStepCodec.toJson(listOf(SkillStep(SkillStepType.ADB_SHELL, value = "cmd uimode night yes"))),
                    mode = SkillMode.ADB_FAST.name,
                    evolutionScore = 92
                )
            )
            assertTrue(skillId > 0L)
            assertEquals(1, db.skillDao().getAllSkillsSnapshot().size)
        } finally {
            db.close()
            dbFile.delete()
        }

        // 3. LogcatDoctor heuristic diagnosis verification
        val adbReport = LogcatDoctor.classifyDeterministically(
            errorLines = listOf("E/LocalAdb: Connection refused 127.0.0.1:5555"),
            lastErrorDetail = "ADB command failed",
            isAdbConnected = false,
            isAccessibilityActive = true
        )
        assertEquals(DiagnosisCategory.ADB_DISCONNECTED, adbReport.category)

        val permReport = LogcatDoctor.classifyDeterministically(
            errorLines = listOf("java.lang.SecurityException: Permission Denial: WRITE_SECURE_SETTINGS"),
            lastErrorDetail = "SecurityException",
            isAdbConnected = true,
            isAccessibilityActive = true
        )
        assertEquals(DiagnosisCategory.MISSING_PERMISSION, permReport.category)

        // 4. Dream Mode WorkManager constraints verification (Charging + Unmetered Wi-Fi)
        val constraints = DreamModeScheduler.buildOvernightConstraints()
        assertTrue(constraints.requiresCharging())
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
    }
}
