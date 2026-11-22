package com.arif.goku

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import com.arif.goku.accessibility.AccessibilityScreenSignal
import com.arif.goku.brain.ZeroCopyBridge
import com.arif.goku.brain.ZeroCopyTargetApp
import com.arif.goku.data.SecurePrefs
import com.arif.goku.overlay.OverlayBridge
import com.arif.goku.pilot.PilotActionDispatcher
import com.arif.goku.pilot.PilotBrainDecider
import com.arif.goku.pilot.PilotDispatchResult
import com.arif.goku.pilot.PilotObservation
import com.arif.goku.pilot.PilotObservationMode
import com.arif.goku.pilot.PilotObserver
import com.arif.goku.pilot.PilotRunOutcome
import com.arif.goku.pilot.PilotStepActionType
import com.arif.goku.pilot.PilotStepDecision
import com.arif.goku.pilot.UniversalAppPilot
import com.arif.goku.safety.MistakeRiskCategory
import com.arif.goku.safety.ScreenCoPilot
import com.arif.goku.safety.ScreenMistakeBlocker
import com.arif.goku.worker.BackgroundTaskExecutor
import com.arif.goku.worker.BackgroundTaskQueue
import com.arif.goku.worker.BackgroundTaskStatus
import com.arif.goku.worker.BackgroundTaskType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class Phase5PilotBlockerQueueTest {

    private lateinit var context: Context
    private lateinit var securePrefs: SecurePrefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        securePrefs = SecurePrefs(context)
    }

    // 1. Live Mistake Blocker Rules Tests
    @Test
    fun mistakeBlockerDetectsDestructiveUnknownApkPhishingAndPayments() {
        // Destructive button click
        val deleteClick = ScreenMistakeBlocker.evaluateSignal(
            AccessibilityScreenSignal(
                packageName = "com.android.settings",
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                clickedOrFocusedText = "Clear All Data",
                visibleTexts = listOf("Storage", "Clear All Data"),
                clickableItems = listOf("Clear All Data")
            )
        )
        assertNotNull(deleteClick)
        assertEquals(MistakeRiskCategory.DESTRUCTIVE_ACTION, deleteClick?.category)

        // Destructive confirmation dialog window change
        val formatDialog = ScreenMistakeBlocker.evaluateSignal(
            AccessibilityScreenSignal(
                packageName = "com.android.settings",
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                clickedOrFocusedText = "",
                visibleTexts = listOf("Are you sure you want to Format SD Card?", "Cancel", "Format"),
                clickableItems = listOf("Cancel", "Format")
            )
        )
        assertNotNull(formatDialog)
        assertEquals(MistakeRiskCategory.DESTRUCTIVE_ACTION, formatDialog?.category)

        // Unknown APK installer package
        val apkInstall = ScreenMistakeBlocker.evaluateSignal(
            AccessibilityScreenSignal(
                packageName = "com.google.android.packageinstaller",
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                clickedOrFocusedText = "",
                visibleTexts = listOf("Do you want to install this unknown app?"),
                clickableItems = listOf("Cancel", "Install")
            )
        )
        assertNotNull(apkInstall)
        assertEquals(MistakeRiskCategory.PHISHING_OR_APK, apkInstall?.category)

        // Phishing prompt
        val phishing = ScreenMistakeBlocker.evaluateSignal(
            AccessibilityScreenSignal(
                packageName = "com.whatsapp",
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                clickedOrFocusedText = "",
                visibleTexts = listOf("Urgent: Your KYC suspended! Click bit.ly/verify"),
                clickableItems = emptyList()
            )
        )
        assertNotNull(phishing)
        assertEquals(MistakeRiskCategory.PHISHING_OR_APK, phishing?.category)

        // High-risk UPI payment button click
        val upiPay = ScreenMistakeBlocker.evaluateSignal(
            AccessibilityScreenSignal(
                packageName = "com.phonepe.app",
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                clickedOrFocusedText = "Pay Now ₹5000",
                visibleTexts = listOf("UPI Transfer", "Pay Now ₹5000"),
                clickableItems = listOf("Pay Now ₹5000")
            )
        )
        assertNotNull(upiPay)
        assertEquals(MistakeRiskCategory.PAYMENT_RISK, upiPay?.category)

        // Self package and SystemUI must always be ignored
        val selfSignal = ScreenMistakeBlocker.evaluateSignal(
            AccessibilityScreenSignal(
                packageName = "com.arif.goku",
                eventType = AccessibilityEvent.TYPE_VIEW_CLICKED,
                clickedOrFocusedText = "Delete Memory",
                visibleTexts = listOf("Delete Memory"),
                clickableItems = listOf("Delete Memory")
            ),
            selfPackage = "com.arif.goku"
        )
        assertNull(selfSignal)
    }

    @Test
    fun mistakeBlockerAllowOnceExemptionWindowWorks() {
        val blocker = ScreenMistakeBlocker(context, securePrefs)
        val sig = "com.android.settings|DESTRUCTIVE|delete"
        assertFalse(blocker.isExempted(sig))

        blocker.grantAllowOnce(sig, durationMs = 60_000L)
        assertTrue(blocker.isExempted(sig))
        assertFalse(blocker.isExempted(sig, nowMs = System.currentTimeMillis() + 65_000L))
    }

    // 2. Universal App Pilot Tests (Step Cap, Loop Detection, Risk Guard, Vision Fallback, Cancellation)
    @Test
    fun universalPilotEnforcesStepLimitCap() = runBlocking {
        var stepCounter = 0
        val observer = object : PilotObserver {
            override suspend fun observe(allowVisionFallback: Boolean): PilotObservation {
                stepCounter++
                return PilotObservation(
                    mode = PilotObservationMode.UI_TREE,
                    packageName = "com.demo.app",
                    uiSummaryText = "Screen state $stepCounter",
                    screenSignature = "sig_$stepCounter"
                )
            }
        }
        val decider = object : PilotBrainDecider {
            override suspend fun decideNextStep(
                goal: String,
                observation: PilotObservation,
                stepNumber: Int,
                maxSteps: Int,
                previousSteps: List<String>
            ): Result<PilotStepDecision> {
                return Result.success(
                    PilotStepDecision(
                        action = PilotStepActionType.SCROLL,
                        forward = true,
                        reason = "Neeche scroll kar raha hoon"
                    )
                )
            }
        }
        val dispatcher = object : PilotActionDispatcher {
            override suspend fun dispatch(decision: PilotStepDecision): PilotDispatchResult {
                return PilotDispatchResult(true, "Scrolled")
            }
        }

        val pilot = UniversalAppPilot(
            observer = observer,
            brainDecider = decider,
            dispatcher = dispatcher,
            maxSteps = 4,
            stepDelayMs = 1L
        )

        val outcome = pilot.runGoal("Find hidden setting")
        assertTrue(outcome is PilotRunOutcome.StepLimitReached)
        assertEquals(4, (outcome as PilotRunOutcome.StepLimitReached).maxSteps)
        assertFalse(pilot.state.value.isRunning)
    }

    @Test
    fun universalPilotDetectsRepeatedActionLoop() = runBlocking {
        val observer = object : PilotObserver {
            override suspend fun observe(allowVisionFallback: Boolean): PilotObservation {
                return PilotObservation(
                    mode = PilotObservationMode.UI_TREE,
                    packageName = "com.demo.stuck",
                    uiSummaryText = "Same stuck dialog",
                    screenSignature = "stuck_sig_1"
                )
            }
        }
        val decider = object : PilotBrainDecider {
            override suspend fun decideNextStep(
                goal: String,
                observation: PilotObservation,
                stepNumber: Int,
                maxSteps: Int,
                previousSteps: List<String>
            ): Result<PilotStepDecision> {
                return Result.success(
                    PilotStepDecision(
                        action = PilotStepActionType.CLICK_TEXT,
                        target = "Next",
                        reason = "Next button click kar raha hoon"
                    )
                )
            }
        }
        val dispatcher = object : PilotActionDispatcher {
            override suspend fun dispatch(decision: PilotStepDecision): PilotDispatchResult {
                return PilotDispatchResult(true, "Clicked Next")
            }
        }

        val pilot = UniversalAppPilot(
            observer = observer,
            brainDecider = decider,
            dispatcher = dispatcher,
            maxSteps = 10,
            stepDelayMs = 1L
        )

        val outcome = pilot.runGoal("Press Next")
        assertTrue(outcome is PilotRunOutcome.LoopDetected)
        assertEquals(3, (outcome as PilotRunOutcome.LoopDetected).stepsUsed)
    }

    @Test
    fun universalPilotBlocksDestructiveHighRiskClick() = runBlocking {
        val observer = object : PilotObserver {
            override suspend fun observe(allowVisionFallback: Boolean): PilotObservation {
                return PilotObservation(
                    mode = PilotObservationMode.UI_TREE,
                    packageName = "com.demo.settings",
                    uiSummaryText = "[BTN] Delete Account",
                    screenSignature = "risk_sig"
                )
            }
        }
        val decider = object : PilotBrainDecider {
            override suspend fun decideNextStep(
                goal: String,
                observation: PilotObservation,
                stepNumber: Int,
                maxSteps: Int,
                previousSteps: List<String>
            ): Result<PilotStepDecision> {
                return Result.success(
                    PilotStepDecision(
                        action = PilotStepActionType.CLICK_TEXT,
                        target = "Delete Account",
                        reason = "Deleting account"
                    )
                )
            }
        }
        var dispatchCalled = false
        val dispatcher = object : PilotActionDispatcher {
            override suspend fun dispatch(decision: PilotStepDecision): PilotDispatchResult {
                dispatchCalled = true
                return PilotDispatchResult(true, "Should not run")
            }
        }

        val pilot = UniversalAppPilot(
            observer = observer,
            brainDecider = decider,
            dispatcher = dispatcher,
            maxSteps = 6,
            stepDelayMs = 1L
        )

        val outcome = pilot.runGoal("Clean up account")
        assertTrue(outcome is PilotRunOutcome.RiskBlocked)
        assertFalse(dispatchCalled)
    }

    @Test
    fun universalPilotHandlesScreenVisionFallbackAndInstantCancellation() = runBlocking {
        val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val observer = object : PilotObserver {
            override suspend fun observe(allowVisionFallback: Boolean): PilotObservation {
                return PilotObservation(
                    mode = PilotObservationMode.SCREEN_VISION,
                    packageName = "com.custom.webview",
                    uiSummaryText = "Screen Vision JPEG captured",
                    screenSignature = "vision_1",
                    screenshotJpegBytes = if (allowVisionFallback) fakeJpeg else null
                )
            }
        }
        val decider = object : PilotBrainDecider {
            override suspend fun decideNextStep(
                goal: String,
                observation: PilotObservation,
                stepNumber: Int,
                maxSteps: Int,
                previousSteps: List<String>
            ): Result<PilotStepDecision> {
                assertNotNull(observation.screenshotJpegBytes)
                return if (stepNumber == 1) {
                    Result.success(
                        PilotStepDecision(
                            action = PilotStepActionType.TAP_XY,
                            x = 540,
                            y = 1200,
                            reason = "Vision tap on button"
                        )
                    )
                } else {
                    Result.success(
                        PilotStepDecision(
                            action = PilotStepActionType.DONE,
                            reason = "Kaam poora ho gaya!"
                        )
                    )
                }
            }
        }
        val dispatcher = object : PilotActionDispatcher {
            override suspend fun dispatch(decision: PilotStepDecision): PilotDispatchResult {
                return PilotDispatchResult(true, "Dispatched ${decision.action}")
            }
        }

        val pilot = UniversalAppPilot(
            observer = observer,
            brainDecider = decider,
            dispatcher = dispatcher,
            maxSteps = 6,
            stepDelayMs = 1L
        )

        val completed = pilot.runGoal("Click dark mode toggle", allowVisionFallback = true)
        assertTrue(completed is PilotRunOutcome.Completed)
        assertEquals(2, (completed as PilotRunOutcome.Completed).stepsUsed)
        assertEquals(PilotObservationMode.SCREEN_VISION, pilot.state.value.lastObservationMode)

        // Instant Goku Stop cancellation mid-run
        val slowDecider = object : PilotBrainDecider {
            override suspend fun decideNextStep(
                goal: String,
                observation: PilotObservation,
                stepNumber: Int,
                maxSteps: Int,
                previousSteps: List<String>
            ): Result<PilotStepDecision> {
                delay(40L)
                return Result.success(PilotStepDecision(PilotStepActionType.WAIT, reason = "Waiting"))
            }
        }
        val cancellablePilot = UniversalAppPilot(
            observer = observer,
            brainDecider = slowDecider,
            dispatcher = dispatcher,
            maxSteps = 10,
            stepDelayMs = 30L
        )
        val cancelJob = launch {
            delay(15L)
            cancellablePilot.cancel("Goku Stop voice kill switch")
        }
        val stopped = cancellablePilot.runGoal("Keep waiting")
        cancelJob.join()
        assertTrue(stopped is PilotRunOutcome.StoppedByUser)

        // Verify JSON parser & command detection
        assertTrue(UniversalAppPilot.isPilotCommand("Goku pilot: Settings mein Dark Mode on karo"))
        assertEquals(
            "Settings mein Dark Mode on karo",
            UniversalAppPilot.extractPilotGoal("Goku pilot: Settings mein Dark Mode on karo")
        )
        val parsed = UniversalAppPilot.parsePilotDecisionJson(
            """```json {"action":"TAP_XY","x":420,"y":980,"reason":"Tap Play"} ```"""
        )
        assertNotNull(parsed)
        assertEquals(PilotStepActionType.TAP_XY, parsed?.action)
        assertEquals(420, parsed?.x)
        assertEquals(980, parsed?.y)
    }

    // 3. Zero-Copy Bridge & Screen Co-Pilot Tests
    @Test
    fun zeroCopyBridgeExtractsBillTotalsAndRoutesTargetApps() {
        assertTrue(ZeroCopyBridge.isZeroCopyCommand("Screen par jo bill hai uska total karke Notes mein daal do"))
        assertEquals(ZeroCopyTargetApp.NOTES, ZeroCopyBridge.detectTargetApp("total karke Notes mein save karo"))
        assertEquals(ZeroCopyTargetApp.WHATSAPP, ZeroCopyBridge.detectTargetApp("screen numbers WhatsApp par bhej do"))
        assertEquals(ZeroCopyTargetApp.GMAIL, ZeroCopyBridge.detectTargetApp("screen details Gmail mein daal do"))

        val billLines = listOf(
            "12:45", // time should be ignored
            "Groceries ₹450",
            "Milk Rs. 120.50",
            "Delivery 49.50",
            "Call 9876543210" // 10-digit phone number should be ignored
        )
        val summary = ZeroCopyBridge.extractLocalBillSummary(billLines)
        assertNotNull(summary)
        assertTrue("Expected Grand Total Rs. 620, got: $summary", summary!!.contains("Grand Total: Rs. 620"))
        assertFalse("Phone number must be excluded, got: $summary", summary.contains("9876543"))

        // Screen Co-Pilot matching from Memory Vault
        assertTrue(ScreenCoPilot.isExplainScreenCommand("Goku, yeh kya likha hai?"))
        assertTrue(ScreenCoPilot.isAutoFillFormCommand("Is form mein meri details bhar do"))
        val memories = listOf(
            "User Name" to "Arif Khan",
            "My Phone" to "+91 9876500000",
            "Email" to "arif@example.com"
        )
        assertEquals("Arif Khan", ScreenCoPilot.matchMemoryForField("full name", memories))
        assertEquals("+91 9876500000", ScreenCoPilot.matchMemoryForField("mobile number", memories))
        assertEquals("arif@example.com", ScreenCoPilot.matchMemoryForField("email address", memories))
    }

    // 4. Background Task Queue Tests
    @Test
    fun backgroundTaskQueueExecutesEmitsCompletionAndSupportsCancelAndClear() = runBlocking {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fakeExecutor = object : BackgroundTaskExecutor {
            override suspend fun execute(
                type: BackgroundTaskType,
                query: String,
                onProgress: (Int, String) -> Unit
            ): Result<String> {
                onProgress(50, "Checking $query…")
                delay(20L)
                return Result.success("Result ready for $query")
            }
        }
        val queue = BackgroundTaskQueue(
            context = context,
            executor = fakeExecutor,
            scheduleWorkManager = false,
            scope = testScope
        )

        val parsedCmd = BackgroundTaskQueue.parseBackgroundQueueCommand(
            "Background mein Pixel 9a ka price check karo"
        )
        assertNotNull(parsedCmd)
        assertEquals(BackgroundTaskType.PRICE_LOOKUP, parsedCmd?.first)

        val completionDeferred = launch(Dispatchers.Unconfined) {
            val done = withTimeout(3_000L) { queue.completions.first() }
            assertEquals(BackgroundTaskStatus.COMPLETED, done.status)
            assertEquals("Result ready for Pixel 9a", done.summaryHindi)
        }

        val item = queue.enqueue(BackgroundTaskType.PRICE_LOOKUP, "Pixel 9a")
        completionDeferred.join()

        val updated = queue.tasks.value.first { it.id == item.id }
        assertEquals(BackgroundTaskStatus.COMPLETED, updated.status)
        assertEquals(100, updated.progressPercent)
        assertTrue(OverlayBridge.queueProgressLabel.value.isBlank())

        // Test clearFinished removes completed tasks
        queue.clearFinished()
        assertTrue(queue.tasks.value.isEmpty())

        // Test cancelTask cancels a slow active task
        val slowQueue = BackgroundTaskQueue(
            context = context,
            executor = object : BackgroundTaskExecutor {
                override suspend fun execute(
                    type: BackgroundTaskType,
                    query: String,
                    onProgress: (Int, String) -> Unit
                ): Result<String> {
                    delay(5_000L)
                    return Result.success("Late")
                }
            },
            scheduleWorkManager = false,
            scope = testScope
        )
        val slowItem = slowQueue.enqueue(BackgroundTaskType.WEB_RESEARCH, "Long research")
        delay(15L)
        slowQueue.cancelTask(slowItem.id)
        val cancelled = slowQueue.tasks.value.first { it.id == slowItem.id }
        assertEquals(BackgroundTaskStatus.CANCELLED, cancelled.status)
    }
}
