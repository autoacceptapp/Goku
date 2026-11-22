package com.arif.goku

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.arif.goku.action.GokuActionType
import com.arif.goku.brain.ActiveBrainTier
import com.arif.goku.brain.BrainRouteResult
import com.arif.goku.brain.BrainRouter
import com.arif.goku.brain.GeminiChatClient
import com.arif.goku.brain.OfflineLlmEngine
import com.arif.goku.data.SecurePrefs
import com.arif.goku.data.db.GokuDatabase
import com.arif.goku.data.db.MemoryCategory
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.dev.GokuDevManager
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase4RoomAndRouterTest {

    private lateinit var context: Context
    private lateinit var db: GokuDatabase
    private lateinit var memoryManager: MemoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, GokuDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryManager = MemoryManager(db.memoryDao(), db.chatHistoryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roomMemoryVault_crudAndChatHistoryPersistCorrectly() = runBlocking {
        val saved = memoryManager.saveOrUpsertMemory(
            category = MemoryCategory.PERSON,
            aliasKey = "Papa",
            content = "Husenbhai - +919876543210"
        )
        assertTrue(saved.id > 0L)

        // Upsert with same alias updates instead of duplicating
        memoryManager.saveOrUpsertMemory(
            category = MemoryCategory.PERSON,
            aliasKey = "papa",
            content = "Husenbhai - +919999888877"
        )
        val relevant = memoryManager.retrieveRelevantMemories("Papa ko call karo", limit = 5)
        assertEquals(1, relevant.size)
        assertTrue(relevant.first().content.contains("+919999888877"))

        // Resolve saved contact alias
        val resolved = memoryManager.resolveSavedPersonContact("Papa")
        assertNotNull(resolved)
        assertEquals("+919999888877", resolved?.second)

        // Chat history persistence & ordering
        memoryManager.recordChatTurn("USER", "Namaste Goku")
        memoryManager.recordChatTurn("GOKU", "Namaste! Kaise madad karoon?")
        val history = memoryManager.loadRecentChatHistory(10)
        assertEquals(2, history.size)
        assertEquals("USER", history[0].role)
        assertEquals("GOKU", history[1].role)

        memoryManager.clearChatHistory()
        assertTrue(memoryManager.loadRecentChatHistory(10).isEmpty())
    }

    @Test
    fun memoryManager_explicitCommandsAndAutoExtractionWork() = runBlocking {
        // Explicit "Yaad rakhna..."
        val remember = memoryManager.processExplicitMemoryCommand(
            "Yaad rakhna Papa ka number +919812345678 hai"
        )
        assertNotNull(remember)
        assertTrue(remember!!.handled)

        // Auto-extract user name & preference
        val extractedName = memoryManager.autoExtractFromUserMessage("Mera naam Arif hai")
        assertNotNull(extractedName)
        assertEquals("Arif", extractedName?.content)

        // Memory prompt injection ranks matching alias first
        val promptBlock = memoryManager.buildMemoryPromptBlock("Papa ko message bhejo", limit = 4)
        assertTrue(promptBlock.contains("[PERSONAL MEMORY VAULT]"))
        assertTrue(promptBlock.contains("Papa"))
        assertTrue(promptBlock.contains("+919812345678"))

        // Explicit "Bhool jaao..."
        val forgot = memoryManager.processExplicitMemoryCommand("Bhool jaao Papa")
        assertNotNull(forgot)
        assertTrue(forgot!!.handled)
        assertNull(memoryManager.resolveSavedPersonContact("Papa"))
    }

    @Test
    fun brainRouter_resolvesTiersAndFastOfflineReflexes() {
        assertEquals(
            ActiveBrainTier.ONLINE_GEMINI,
            BrainRouter.resolveTier(isOnlineWithKey = true, hasLocalLlmModel = true)
        )
        assertEquals(
            ActiveBrainTier.OFFLINE_LOCAL_AI,
            BrainRouter.resolveTier(isOnlineWithKey = false, hasLocalLlmModel = true)
        )
        assertEquals(
            ActiveBrainTier.OFFLINE_REFLEX,
            BrainRouter.resolveTier(isOnlineWithKey = false, hasLocalLlmModel = false)
        )

        val torchOn = BrainRouter.matchFastOfflineReflex("Goku torch on karo")
        assertEquals(GokuActionType.TOGGLE_TORCH, torchOn?.type)
        assertEquals(true, torchOn?.enabled)

        val torchOff = BrainRouter.matchFastOfflineReflex("Flashlight band karo")
        assertEquals(GokuActionType.TOGGLE_TORCH, torchOff?.type)
        assertEquals(false, torchOff?.enabled)

        val wifiOff = BrainRouter.matchFastOfflineReflex("Wi-Fi off karo")
        assertEquals(GokuActionType.TOGGLE_SETTING, wifiOff?.type)
        assertEquals("wifi", wifiOff?.setting)
        assertEquals(false, wifiOff?.enabled)

        val volumePct = BrainRouter.matchFastOfflineReflex("Volume 70% set karo")
        assertEquals(GokuActionType.ADJUST_VOLUME, volumePct?.type)
        assertEquals("SET", volumePct?.target)
        assertEquals(70, volumePct?.value)

        val alarm = BrainRouter.matchFastOfflineReflex("6:30 am ka alarm set karo")
        assertEquals(GokuActionType.SET_ALARM, alarm?.type)
        assertEquals("06:30", alarm?.target)

        val openApp = BrainRouter.matchFastOfflineReflex("YouTube kholo")
        assertEquals(GokuActionType.OPEN_APP, openApp?.type)
        assertEquals("YouTube", openApp?.target)

        val githubBuild = BrainRouter.matchFastOfflineReflex("Goku, Order-master ka GitHub build check karo")
        assertEquals(GokuActionType.DEV_GITHUB_BUILD, githubBuild?.type)
        assertEquals("Order-master", githubBuild?.target)

        val termux1 = BrainRouter.matchFastOfflineReflex("Termux mein git status chalao")
        assertEquals(GokuActionType.DEV_TERMUX_CMD, termux1?.type)
        assertEquals("git status", termux1?.text)

        val termux2 = BrainRouter.matchFastOfflineReflex("run in termux: ls -la")
        assertEquals(GokuActionType.DEV_TERMUX_CMD, termux2?.type)
        assertEquals("ls -la", termux2?.text)
    }

    @Test
    fun brainRouter_endToEndOfflineMemoryAndReflexRouting() = runBlocking {
        val prefs = SecurePrefs(context).apply { clearGeminiApiKey() }
        val gemini = GeminiChatClient(context, prefs)
        val offlineEngine = OfflineLlmEngine(context)
        val router = BrainRouter(gemini, offlineEngine, memoryManager)

        // 1. Explicit Memory command executes locally
        val memRes = router.route("Yaad rakhna Wifi: Goku5G", liveContext = null)
        assertTrue(memRes is BrainRouteResult.MemoryHandled)

        // 2. Fast Offline Reflex executes at 0ms even without API key / network
        val reflexRes = router.route("Torch on karo", liveContext = null)
        assertTrue(reflexRes is BrainRouteResult.FastReflexAction)
        assertEquals(GokuActionType.TOGGLE_TORCH, (reflexRes as BrainRouteResult.FastReflexAction).action.type)

        // 3. Free-form query with no key & no local model returns helpful Offline Reflex hint
        val fallbackRes = router.route("Aaj ka mausam kaisa hai?", liveContext = null)
        assertTrue(fallbackRes is BrainRouteResult.Error)
        assertTrue((fallbackRes as BrainRouteResult.Error).userMessage.contains("Offline Reflex"))
    }

    @Test
    fun gokuDevManager_validatesRepoSlugsAndParsesGitHubRuns() {
        assertTrue(GokuDevManager.isValidRepoSlug("autoacceptapp/goku_agent"))
        assertTrue(GokuDevManager.isValidRepoSlug("autoacceptapp/Order-master"))
        assertFalse(GokuDevManager.isValidRepoSlug("invalid repo"))
        assertFalse(GokuDevManager.isValidRepoSlug("../secret/repo"))

        val passJson = """
            {
              "workflow_runs": [
                {
                  "id": 123456,
                  "name": "Build GokuAgent APK",
                  "status": "completed",
                  "conclusion": "success",
                  "run_number": 42,
                  "html_url": "https://github.com/autoacceptapp/goku_agent/actions/runs/123456",
                  "head_commit": { "message": "Phase 4 Hybrid Brain & Memory Vault\nDetails..." }
                }
              ]
            }
        """.trimIndent()

        val report = GokuDevManager.parseLatestRunJson("autoacceptapp/goku_agent", passJson)
        assertNotNull(report)
        assertTrue(report!!.isSuccess)
        assertEquals(42, report.runNumber)
        assertEquals("Phase 4 Hybrid Brain & Memory Vault", report.commitMessage)
        assertTrue(report.hindiSummary.contains("PASS"))
    }
}
