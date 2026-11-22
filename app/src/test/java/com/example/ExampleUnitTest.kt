package com.example

import com.arif.goku.action.ActionJsonParser
import com.arif.goku.action.GokuActionType
import com.arif.goku.adb.AdbCrypto
import com.arif.goku.brain.ContextBrain
import com.arif.goku.brain.GeminiChatClient
import com.arif.goku.data.SecurePrefs
import com.arif.goku.safety.SafetyGuard
import com.arif.goku.safety.SensitiveActionType
import com.arif.goku.safety.VoiceConfirmationDecision
import com.arif.goku.system.FocusShieldManager
import com.arif.goku.system.FocusShieldRuntime
import com.arif.goku.system.SmartContextManager
import com.arif.goku.system.SystemPowerTools
import com.arif.goku.voice.WakePhraseMatch
import com.arif.goku.voice.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleUnitTest {

    private val safetyGuard = SafetyGuard()

    @Test
    fun emergencyStopDetectsEnglishAndHindiPhrases() {
        assertTrue(safetyGuard.isEmergencyStopCommand("Goku stop"))
        assertTrue(safetyGuard.isEmergencyStopCommand("please goku stop!"))
        assertTrue(safetyGuard.isEmergencyStopCommand("गोकू स्टॉप"))
        assertTrue(safetyGuard.isEmergencyStopCommand("गोकू रुको"))
        assertFalse(safetyGuard.isEmergencyStopCommand("Goku aaj ka mausam kaisa hai"))
    }

    @Test
    fun sensitiveActionsRequireConfirmation() {
        val callAction = safetyGuard.inspectForSensitiveAction("Mom ko call lagao")
        assertNotNull(callAction)
        assertEquals(SensitiveActionType.PHONE_CALL, callAction?.type)
        assertEquals(GokuActionType.CALL, callAction?.structuredAction?.type)

        val waAction = safetyGuard.inspectForSensitiveAction("copied number par whatsapp karo ki hello")
        assertNotNull(waAction)
        assertEquals(GokuActionType.SEND_WHATSAPP, waAction?.structuredAction?.type)
        assertEquals("clipboard", waAction?.structuredAction?.phone)

        val shellAction = safetyGuard.inspectForSensitiveAction("adb shell pm list packages")
        assertNotNull(shellAction)
        assertEquals(SensitiveActionType.SHELL_COMMAND, shellAction?.type)

        assertNull(safetyGuard.inspectForSensitiveAction("Aaj India ka match kab hai?"))
    }

    @Test
    fun voiceConfirmationParsesHindiAndEnglish() {
        assertEquals(VoiceConfirmationDecision.CONFIRMED, safetyGuard.parseVoiceConfirmation("Haan kar do"))
        assertEquals(VoiceConfirmationDecision.CONFIRMED, safetyGuard.parseVoiceConfirmation("हां"))
        assertEquals(VoiceConfirmationDecision.CANCELLED, safetyGuard.parseVoiceConfirmation("Nahi mat karo"))
        assertEquals(VoiceConfirmationDecision.CANCELLED, safetyGuard.parseVoiceConfirmation("Goku stop"))
    }

    @Test
    fun actionJsonParserExtractsStructuredActionAndCleansSpeech() {
        val raw = """
            Theek hai, Wi-Fi silent mode mein OFF kar raha hoon.
            <<<ACTION_JSON>>>
            {"action":"TOGGLE_SETTING","setting":"wifi","enabled":false}
            <<<END_ACTION_JSON>>>
        """.trimIndent()

        val parsed = ActionJsonParser.parse(raw)
        assertEquals("Theek hai, Wi-Fi silent mode mein OFF kar raha hoon.", parsed.visibleReply)
        assertNotNull(parsed.action)
        assertEquals(GokuActionType.TOGGLE_SETTING, parsed.action?.type)
        assertEquals("wifi", parsed.action?.setting)
        assertEquals(false, parsed.action?.enabled)
    }

    @Test
    fun contextBrainExtractsAndNormalizesIndianPhoneNumbers() {
        assertEquals("+919876543210", ContextBrain.extractPhoneNumber("Call me at 9876543210 asap"))
        assertEquals("+919876543210", ContextBrain.extractPhoneNumber("Number: +91 98765-43210"))
        assertNull(ContextBrain.extractPhoneNumber("No phone number here"))
    }

    @Test
    fun systemPowerToolsProtectsCorePackagesAndParsesMeminfo() {
        assertFalse(SystemPowerTools.isSafeUserPackage("com.arif.goku", "com.arif.goku"))
        assertFalse(SystemPowerTools.isSafeUserPackage("com.android.systemui", "com.arif.goku"))
        assertFalse(SystemPowerTools.isSafeUserPackage("bad;rm -rf /", "com.arif.goku"))
        assertTrue(SystemPowerTools.isSafeUserPackage("com.instagram.android", "com.arif.goku"))

        val sampleMem = """
            Total PSS by process:
              215,040K: com.instagram.android (pid 1402)
              140,288K: com.android.systemui (pid 902)
               92,160K: com.arif.goku (pid 2201)
            Total PSS by OOM adjustment:
        """.trimIndent()

        val parsed = SystemPowerTools.parseDumpsysMeminfo(sampleMem, "com.arif.goku")
        assertEquals(3, parsed.size)
        assertEquals("com.instagram.android", parsed[0].processName)
        assertTrue(parsed[0].isSafeToStop)
        assertFalse(parsed[1].isSafeToStop)
        assertFalse(parsed[2].isSafeToStop)
    }

    @Test
    fun adbCryptoEncodes524ByteAospRsaPublicKeyAndSelfSignedCert() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        val encoded = AdbCrypto.encodeAospRsaPublicKey(pair.public as RSAPublicKey)
        assertEquals(524, encoded.size)

        val cert = AdbCrypto.generateSelfSignedCert(pair)
        assertEquals("X.509", cert.type)
        assertTrue(cert.subjectX500Principal.name.contains("GokuAgentADB"))
    }

    @Test
    fun sanitizeForSpeechStripsMarkdown() {
        val cleaned = GeminiChatClient.sanitizeForSpeech("**Namaste!** Main `Goku` hoon #ready")
        assertEquals("Namaste! Main Goku hoon ready", cleaned)
    }

    @Test
    fun maskApiKeyHidesMiddleCharacters() {
        assertEquals("AIza••••••••7890", SecurePrefs.maskApiKey("AIzaSyDummyKeyExample7890"))
        assertEquals("", SecurePrefs.maskApiKey(""))
    }

    @Test
    fun wakeWordMatcherDetectsHeyGokuAndGokuStopInEnglishAndHindi() {
        assertEquals(WakePhraseMatch.HEY_GOKU, WakeWordMatcher.matchPhrase("Hey Goku!"))
        assertEquals(WakePhraseMatch.HEY_GOKU, WakeWordMatcher.matchPhrase("हे गोकू"))
        assertEquals(WakePhraseMatch.GOKU_STOP, WakeWordMatcher.matchPhrase("Goku stop right now"))
        assertEquals(WakePhraseMatch.GOKU_STOP, WakeWordMatcher.matchPhrase("गोकू स्टॉप"))
        assertNull(WakeWordMatcher.matchPhrase("Aaj mausam kaisa hai"))
    }

    @Test
    fun smartContextEvaluatesNightDndLowBatteryAndShortFastMode() {
        val nightProfile = SmartContextManager.evaluateProfile(
            hourOfDay = 23,
            batteryPercent = 72,
            isCharging = false,
            isDndActive = false,
            shortFastReply = false
        )
        assertTrue(nightProfile.isNightQuietMode)
        assertTrue(nightProfile.dimVisualsForNight)
        assertEquals(0.42f, nightProfile.recommendedTtsVolume, 0.01f)
        assertTrue(nightProfile.toGeminiStyleDirective().contains("NIGHT/DND"))

        val lowBattProfile = SmartContextManager.evaluateProfile(
            hourOfDay = 14,
            batteryPercent = 12,
            isCharging = false,
            isDndActive = false,
            shortFastReply = true
        )
        assertTrue(lowBattProfile.isLowBatteryMode)
        assertTrue(lowBattProfile.wakeDutyCyclePowerSave)
        assertTrue(lowBattProfile.isShortFastReplyMode)
        assertTrue(lowBattProfile.toGeminiStyleDirective().contains("ULTRA-SHORT"))
        assertTrue(lowBattProfile.toGeminiStyleDirective().contains("LOW BATTERY"))
    }

    @Test
    fun focusShieldDetectsKaamModeVoiceCommandsAndFiltersNotifications() {
        assertTrue(FocusShieldManager.isFocusOnCommand("Goku, kaam mode ON"))
        assertTrue(FocusShieldManager.isFocusOnCommand("Focus mode ON kar do"))
        assertTrue(FocusShieldManager.isFocusOffCommand("Focus mode OFF"))
        assertTrue(FocusShieldManager.isFocusOffCommand("kaam mode band karo"))

        FocusShieldRuntime.sync(active = true, packages = setOf("com.instagram.android"))
        assertTrue(FocusShieldRuntime.shouldMutePackage("com.instagram.android", isCallOrImportant = false))
        assertFalse(FocusShieldRuntime.shouldMutePackage("com.instagram.android", isCallOrImportant = true))
        assertFalse(FocusShieldRuntime.shouldMutePackage("com.google.android.dialer", isCallOrImportant = true))
        FocusShieldRuntime.sync(active = false, packages = emptySet())
        assertFalse(FocusShieldRuntime.shouldMutePackage("com.instagram.android", isCallOrImportant = false))
    }

    @Test
    fun actionJsonParserHandlesFocusModeAction() {
        val raw = """
            Kaam mode chalu kar raha hoon!
            <<<ACTION_JSON>>>
            {"action":"FOCUS_MODE","enabled":true}
            <<<END_ACTION_JSON>>>
        """.trimIndent()
        val parsed = ActionJsonParser.parse(raw)
        assertEquals(GokuActionType.FOCUS_MODE, parsed.action?.type)
        assertEquals(true, parsed.action?.enabled)
    }
}
