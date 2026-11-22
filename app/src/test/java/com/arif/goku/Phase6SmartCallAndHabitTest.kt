package com.arif.goku

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.arif.goku.calls.CallAnswerDelegate
import com.arif.goku.calls.CallAudioRouter
import com.arif.goku.calls.CallSignalType
import com.arif.goku.calls.CallVoiceDelegate
import com.arif.goku.calls.CallerIdentity
import com.arif.goku.calls.CallerLookupDelegate
import com.arif.goku.calls.IncomingCallSignal
import com.arif.goku.calls.SmartCallAnswerMode
import com.arif.goku.calls.SmartCallCompletionEvent
import com.arif.goku.calls.SmartCallManager
import com.arif.goku.calls.SmartCallPhase
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.CallSummaryEntity
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.HabitActionType
import com.arif.goku.data.db.HabitEventEntity
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.habits.HabitPredictor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase6SmartCallAndHabitTest {

    private lateinit var context: Context
    private lateinit var db: GokuDatabase
    private lateinit var securePrefs: SecurePrefs
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GokuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        securePrefs = SecurePrefs(context)
        memoryManager = MemoryManager(db.memoryDao(), db.chatHistoryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomMigration1To2_preservesMemoriesAndCreatesPhase6Tables() = runBlocking {
        val dbFileName = "phase6_migration_test_${System.currentTimeMillis()}.db"
        val dbFile: File = context.getDatabasePath(dbFileName)
        dbFile.parentFile?.mkdirs()
        if (dbFile.exists()) dbFile.delete()

        // 1. Manually create exact v1 SQLite database and seed Memory + Chat rows
        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        try {
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `memories` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `category` TEXT NOT NULL,
                    `aliasKey` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            sqlite.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_memories_aliasKey` ON `memories` (`aliasKey`)"
            )
            sqlite.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chat_history` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `role` TEXT NOT NULL,
                    `message` TEXT NOT NULL,
                    `timestamp` INTEGER NOT NULL,
                    `isAlert` INTEGER NOT NULL
                )
                """.trimIndent()
            )
            sqlite.execSQL(
                "INSERT INTO `memories` (`category`, `aliasKey`, `content`, `updatedAt`) " +
                    "VALUES ('PERSON', 'Papa', 'Husenbhai - +919876543210', 1700000000000)"
            )
            sqlite.execSQL(
                "INSERT INTO `chat_history` (`role`, `message`, `timestamp`, `isAlert`) " +
                    "VALUES ('USER', 'Namaste Goku', 1700000001000, 0)"
            )
            sqlite.version = 1
        } finally {
            sqlite.close()
        }

        // 2. Open with Room v3 + MIGRATION_1_2 + MIGRATION_2_3 (Room strictly validates full schema & indices!)
        val migratedDb = Room.databaseBuilder(context, GokuDatabase::class.java, dbFileName)
            .addMigrations(GokuDatabase.MIGRATION_1_2, GokuDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()

        try {
            val memories = migratedDb.memoryDao().getAllMemoriesSnapshot()
            assertEquals(1, memories.size)
            assertEquals("Papa", memories.first().aliasKey)

            val history = migratedDb.chatHistoryDao().getRecentChatSnapshot(10)
            assertEquals(1, history.size)
            assertEquals("Namaste Goku", history.first().message)

            // 3. Verify newly migrated Phase 6 tables work cleanly
            val habitId = migratedDb.habitDao().insertHabitEvent(
                HabitEventEntity(
                    actionType = HabitActionType.APP_LAUNCH.name,
                    targetPackageOrCommand = "com.whatsapp",
                    displayLabel = "WhatsApp",
                    hourOfDay = 9,
                    minuteBucket = 15,
                    dayOfWeek = Calendar.MONDAY
                )
            )
            assertTrue(habitId > 0L)
            assertEquals(1, migratedDb.habitDao().getRecentEventsSnapshot(10).size)

            val callId = migratedDb.callSummaryDao().insertCallSummary(
                CallSummaryEntity(
                    callerName = "Papa",
                    callerNumber = "+919876543210",
                    transcript = "Ghar jaldi aa jana",
                    summaryHindi = "Papa ka message: Ghar jaldi aa jana",
                    isSpamOrUnknown = false,
                    autoAnswered = true,
                    answerMode = SmartCallAnswerMode.INCALL_SERVICE.name,
                    durationSeconds = 18
                )
            )
            assertTrue(callId > 0L)
            assertEquals(1, migratedDb.callSummaryDao().getRecentCallSummariesSnapshot(10).size)
        } finally {
            migratedDb.close()
            dbFile.delete()
        }
    }

    @Test
    fun smartCallCountdown_cancelsImmediatelyOnUserAnswerDeclineAndGokuStop() = runBlocking {
        securePrefs.setSmartCallEnabled(true)
        securePrefs.setSmartCallDelaySeconds(10)

        var answerInvoked = false
        var speakerInvoked = false
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val manager = SmartCallManager(
            context = context,
            securePrefs = securePrefs,
            callSummaryDao = db.callSummaryDao(),
            memoryManager = memoryManager,
            scope = testScope,
            answerDelegate = object : CallAnswerDelegate {
                override fun answerCall(isTestSimulation: Boolean): SmartCallAnswerMode {
                    answerInvoked = true
                    return SmartCallAnswerMode.TELECOM_FALLBACK
                }

                override fun endCallIfPossible(isTestSimulation: Boolean) = Unit
            },
            audioRouter = object : CallAudioRouter {
                override fun enableSpeakerphone(): Boolean {
                    speakerInvoked = true
                    return true
                }

                override fun restoreNormalAudio() = Unit
            },
            voiceDelegate = object : CallVoiceDelegate {
                override fun speakGreetingThenListen(greetingHindi: String, onReadyToListen: () -> Unit) = Unit
                override fun speakAlertHindi(messageHindi: String) = Unit
                override fun stopVoice() = Unit
            },
            callerLookup = object : CallerLookupDelegate {
                override suspend fun resolveCaller(phoneNumber: String): CallerIdentity {
                    return CallerIdentity("Papa", phoneNumber, isKnownContact = true, isSpamSuspected = false)
                }
            },
            tickIntervalMs = 25L,
            callerListenTimeoutMs = 500L
        )

        try {
            // Case A: User answers manually (OFFHOOK) -> countdown must cancel
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.RINGING, "+919876543210"))
            delay(40L)
            assertEquals(SmartCallPhase.RINGING_COUNTDOWN, manager.state.value.phase)
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.OFFHOOK, "+919876543210"))
            delay(150L)
            assertEquals(SmartCallPhase.IDLE, manager.state.value.phase)
            assertFalse(answerInvoked)
            assertFalse(speakerInvoked)

            // Case B: Caller hangs up or user declines (IDLE) -> countdown must cancel
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.RINGING, "+919876543210"))
            delay(40L)
            assertEquals(SmartCallPhase.RINGING_COUNTDOWN, manager.state.value.phase)
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.IDLE, "+919876543210"))
            delay(150L)
            assertEquals(SmartCallPhase.IDLE, manager.state.value.phase)
            assertFalse(answerInvoked)

            // Case C: "Goku stop" emergency kill switch -> countdown must cancel immediately
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.RINGING, "+919876543210"))
            delay(40L)
            assertEquals(SmartCallPhase.RINGING_COUNTDOWN, manager.state.value.phase)
            manager.cancelEmergencyStop("Goku Stop")
            delay(150L)
            assertEquals(SmartCallPhase.IDLE, manager.state.value.phase)
            assertFalse(answerInvoked)
        } finally {
            testScope.cancel()
        }
    }

    @Test
    fun smartCallAutoAnswer_routesSpeakerphoneSpeaksGreetingFlagsSpamAndSavesSummary() = runBlocking {
        securePrefs.setSmartCallEnabled(true)
        securePrefs.setSmartCallDelaySeconds(5)
        securePrefs.setSmartCallSpamAlertEnabled(true)
        securePrefs.setSmartCallOwnerName("Arif")

        var answeredMode: SmartCallAnswerMode? = null
        var speakerEnabled = false
        var speakerRestored = false
        var spokenGreeting = ""
        val capturedAlerts = mutableListOf<String>()
        val completions = mutableListOf<SmartCallCompletionEvent>()

        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val manager = SmartCallManager(
            context = context,
            securePrefs = securePrefs,
            callSummaryDao = db.callSummaryDao(),
            memoryManager = memoryManager,
            scope = testScope,
            answerDelegate = object : CallAnswerDelegate {
                override fun answerCall(isTestSimulation: Boolean): SmartCallAnswerMode {
                    return SmartCallAnswerMode.INCALL_SERVICE.also { answeredMode = it }
                }

                override fun endCallIfPossible(isTestSimulation: Boolean) = Unit
            },
            audioRouter = object : CallAudioRouter {
                override fun enableSpeakerphone(): Boolean {
                    speakerEnabled = true
                    return true
                }

                override fun restoreNormalAudio() {
                    speakerRestored = true
                }
            },
            voiceDelegate = object : CallVoiceDelegate {
                override fun speakGreetingThenListen(greetingHindi: String, onReadyToListen: () -> Unit) {
                    spokenGreeting = greetingHindi
                    onReadyToListen()
                }

                override fun speakAlertHindi(messageHindi: String) {
                    capturedAlerts.add(messageHindi)
                }

                override fun stopVoice() = Unit
            },
            callerLookup = object : CallerLookupDelegate {
                override suspend fun resolveCaller(phoneNumber: String): CallerIdentity {
                    val isTelemarketer = SmartCallManager.isSpamNumber(phoneNumber)
                    return CallerIdentity(
                        displayName = "Unknown Caller",
                        phoneNumber = phoneNumber,
                        isKnownContact = false,
                        isSpamSuspected = isTelemarketer,
                        spamReason = if (isTelemarketer) "Telemarketing prefix (140)" else ""
                    )
                }
            },
            tickIntervalMs = 15L,
            callerListenTimeoutMs = 5_000L,
            ioDispatcher = Dispatchers.Unconfined
        )

        val collectorJob = testScope.launch {
            manager.completions.collect { completions.add(it) }
        }
        val spamAlertJob = testScope.launch {
            manager.spamAlerts.collect { capturedAlerts.add(it) }
        }

        try {
            // Telemarketing 140-prefix unknown number rings and goes unanswered
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.RINGING, "1409998888"))
            delay(180L)

            assertEquals(SmartCallAnswerMode.INCALL_SERVICE, answeredMode)
            assertTrue(speakerEnabled)
            assertTrue(spokenGreeting.contains("Namaste"))
            assertTrue(spokenGreeting.contains("Arif"))
            assertEquals(SmartCallPhase.LISTENING_CALLER, manager.state.value.phase)
            assertTrue(manager.isListeningForCallerMessage())
            assertTrue(manager.state.value.isSpamOrUnknown)
            assertTrue(capturedAlerts.any { it.contains("spam", ignoreCase = true) })

            // Caller speaks a message, then call ends
            manager.onCallerTranscriptCaptured("Credit card offer ke liye call kiya tha")
            manager.onIncomingSignal(IncomingCallSignal(CallSignalType.IDLE, "1409998888"))

            val deadlineMs = System.currentTimeMillis() + 3_000L
            while (completions.isEmpty() && System.currentTimeMillis() < deadlineMs) {
                delay(25L)
            }

            assertTrue(speakerRestored)
            assertEquals(1, completions.size)

            val savedSummaries = db.callSummaryDao().getRecentCallSummariesSnapshot(10)
            assertEquals(1, savedSummaries.size)
            val saved = savedSummaries.first()
            assertEquals("1409998888", saved.callerNumber)
            assertTrue(saved.isSpamOrUnknown)
            assertTrue(saved.transcript.contains("Credit card offer"))

            // Also persisted into Personal Memory Vault so Goku remembers who called
            val memories = db.memoryDao().getAllMemoriesSnapshot()
            assertTrue(memories.any { it.content.contains("Credit card offer") })
        } finally {
            collectorJob.cancel()
            testScope.cancel()
        }
    }

    @Test
    fun habitPredictor_detectsTimeWindowPatternsHandlesMidnightWrapAndSnoozes() = runBlocking {
        securePrefs.setHabitPredictorEnabled(true)
        val predictor = HabitPredictor(context, db.habitDao(), securePrefs)

        // Seed 3 occurrences of YouTube around 08:00 (±15 min) -> SHOULD trigger
        val baseTs = 1_700_000_000_000L
        db.habitDao().insertHabitEvent(
            HabitEventEntity(
                actionType = HabitActionType.APP_LAUNCH.name,
                targetPackageOrCommand = "com.google.android.youtube",
                displayLabel = "YouTube",
                hourOfDay = 7,
                minuteBucket = 52,
                dayOfWeek = Calendar.MONDAY,
                timestamp = baseTs
            )
        )
        db.habitDao().insertHabitEvent(
            HabitEventEntity(
                actionType = HabitActionType.APP_LAUNCH.name,
                targetPackageOrCommand = "com.google.android.youtube",
                displayLabel = "YouTube",
                hourOfDay = 8,
                minuteBucket = 5,
                dayOfWeek = Calendar.TUESDAY,
                timestamp = baseTs + 86_400_000L
            )
        )
        db.habitDao().insertHabitEvent(
            HabitEventEntity(
                actionType = HabitActionType.APP_LAUNCH.name,
                targetPackageOrCommand = "com.google.android.youtube",
                displayLabel = "YouTube",
                hourOfDay = 8,
                minuteBucket = 14,
                dayOfWeek = Calendar.WEDNESDAY,
                timestamp = baseTs + 2 * 86_400_000L
            )
        )

        // Seed only 2 occurrences of Spotify around 08:00 -> below threshold (3), must NOT trigger
        repeat(2) { idx ->
            db.habitDao().insertHabitEvent(
                HabitEventEntity(
                    actionType = HabitActionType.APP_LAUNCH.name,
                    targetPackageOrCommand = "com.spotify.music",
                    displayLabel = "Spotify",
                    hourOfDay = 8,
                    minuteBucket = idx * 4,
                    dayOfWeek = Calendar.MONDAY + idx,
                    timestamp = baseTs + idx * 86_400_000L
                )
            )
        }

        // Seed 3 occurrences of night voice command at 23:55 -> tested at 00:05 (midnight wraparound!)
        repeat(3) { idx ->
            db.habitDao().insertHabitEvent(
                HabitEventEntity(
                    actionType = HabitActionType.VOICE_COMMAND.name,
                    targetPackageOrCommand = "alarm laga do subah 6 baje",
                    displayLabel = "Alarm laga do subah 6 baje",
                    hourOfDay = 23,
                    minuteBucket = 55,
                    dayOfWeek = Calendar.MONDAY + idx,
                    timestamp = baseTs + idx * 86_400_000L
                )
            )
        }

        // Test 08:02 AM evaluation
        val eightAm = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 2)
            set(Calendar.SECOND, 0)
        }.timeInMillis

        val morningHabit = predictor.evaluatePredictions(nowMillis = eightAm, publishToOverlay = false)
        assertNotNull(morningHabit)
        assertEquals("com.google.android.youtube", morningHabit?.target)
        assertEquals(3, morningHabit?.matchCount)
        assertEquals("Samay ho gaya hai, kya main YouTube chalu kar doon?", morningHabit?.promptHindi)
        assertEquals(1, predictor.allPredictedHabits.value.size)

        // Snooze / Ignore hides suggestion immediately within 4-hour window
        predictor.dismissSuggestion(morningHabit!!.id, nowMillis = eightAm)
        assertNull(predictor.activeSuggestion.value)
        assertNull(predictor.evaluatePredictions(nowMillis = eightAm + 30 * 60 * 1000L, publishToOverlay = false))

        // Test midnight wraparound at 00:05 AM (10 minutes from 23:55!)
        val midnight05 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
        }.timeInMillis

        val nightHabit = predictor.evaluatePredictions(nowMillis = midnight05, publishToOverlay = false)
        assertNotNull(nightHabit)
        assertEquals(HabitActionType.VOICE_COMMAND, nightHabit?.actionType)
        assertEquals("Samay ho gaya hai, kya main Alarm laga do subah 6 baje chalu kar doon?", nightHabit?.promptHindi)
    }
}
