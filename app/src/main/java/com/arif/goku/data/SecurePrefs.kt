package com.arif.goku.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GokuUserSettings(
    val hasApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val geminiModel: String = SecurePrefs.DEFAULT_GEMINI_MODEL,
    val continuousListeningEnabled: Boolean = true,
    val adbPairPort: String = "",
    val adbConnectPort: String = "",
    val adbTargetHost: String = SecurePrefs.DEFAULT_ADB_HOST,
    val adbTargetPort: Int = SecurePrefs.DEFAULT_ADB_PORT,
    val speechRate: Float = 1.0f,
    // Phase 3 settings
    val wakeWordEnabled: Boolean = false,
    val shortFastReplyMode: Boolean = false,
    val focusShieldEnabled: Boolean = false,
    val focusUseAdbSuspend: Boolean = true,
    val focusDistractionPackages: Set<String> = SecurePrefs.DEFAULT_DISTRACTION_PACKAGES,
    // Phase 4 settings
    val googleSearchGroundingEnabled: Boolean = true,
    val offlineLlmModelUrl: String = "",
    val githubRepos: List<String> = SecurePrefs.DEFAULT_GITHUB_REPOS,
    val activeGithubRepo: String = SecurePrefs.DEFAULT_GITHUB_REPOS.first(),
    val hasGithubToken: Boolean = false,
    val maskedGithubToken: String = "",
    // Phase 5 settings
    val universalPilotEnabled: Boolean = true,
    val screenVisionFallbackEnabled: Boolean = true,
    val mistakeBlockerEnabled: Boolean = true,
    val zeroCopyBridgeEnabled: Boolean = true
)

/**
 * Stores Gemini API key and GokuAgent user preferences using AndroidX EncryptedSharedPreferences
 * backed by Android Keystore AES256-GCM.
 */
class SecurePrefs(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = createEncryptedPrefs(appContext)

    private val _settingsFlow = MutableStateFlow(readSnapshot())
    val settingsFlow: StateFlow<GokuUserSettings> = _settingsFlow.asStateFlow()

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            buildEncryptedPrefs(context)
        } catch (_: Exception) {
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            try {
                buildEncryptedPrefs(context)
            } catch (_: Exception) {
                context.getSharedPreferences(PREFS_FILE_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getGeminiApiKey(): String = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty().trim()

    fun saveGeminiApiKey(rawKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, rawKey.trim()).apply()
        refreshSnapshot()
    }

    fun clearGeminiApiKey() {
        prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
        refreshSnapshot()
    }

    fun getGeminiModel(): String =
        prefs.getString(KEY_GEMINI_MODEL, DEFAULT_GEMINI_MODEL).orEmpty()
            .ifBlank { DEFAULT_GEMINI_MODEL }

    fun saveGeminiModel(model: String) {
        val valid = if (model in SUPPORTED_MODELS) model else DEFAULT_GEMINI_MODEL
        prefs.edit().putString(KEY_GEMINI_MODEL, valid).apply()
        refreshSnapshot()
    }

    fun isContinuousListeningEnabled(): Boolean =
        prefs.getBoolean(KEY_CONTINUOUS_LISTENING, true)

    fun setContinuousListeningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTINUOUS_LISTENING, enabled).apply()
        refreshSnapshot()
    }

    fun saveAdbConfig(pairPort: String, connectPort: String) {
        prefs.edit()
            .putString(KEY_ADB_PAIR_PORT, pairPort.trim())
            .putString(KEY_ADB_CONNECT_PORT, connectPort.trim())
            .apply()
        refreshSnapshot()
    }

    fun getAdbPairPort(): String = prefs.getString(KEY_ADB_PAIR_PORT, "").orEmpty()

    fun getAdbConnectPort(): String = prefs.getString(KEY_ADB_CONNECT_PORT, "").orEmpty()

    fun getSpeechRate(): Float = prefs.getFloat(KEY_SPEECH_RATE, 1.0f).coerceIn(0.6f, 1.6f)

    fun saveSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_SPEECH_RATE, rate.coerceIn(0.6f, 1.6f)).apply()
        refreshSnapshot()
    }

    // --- Phase 3 Preferences ---

    fun isWakeWordEnabled(): Boolean = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)

    fun setWakeWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, enabled).apply()
        refreshSnapshot()
    }

    fun isShortFastReplyMode(): Boolean = prefs.getBoolean(KEY_SHORT_FAST_REPLY, false)

    fun setShortFastReplyMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHORT_FAST_REPLY, enabled).apply()
        refreshSnapshot()
    }

    fun isFocusShieldEnabled(): Boolean = prefs.getBoolean(KEY_FOCUS_SHIELD_ENABLED, false)

    fun setFocusShieldEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOCUS_SHIELD_ENABLED, enabled).apply()
        refreshSnapshot()
    }

    fun isFocusUseAdbSuspend(): Boolean = prefs.getBoolean(KEY_FOCUS_USE_ADB_SUSPEND, true)

    fun setFocusUseAdbSuspend(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOCUS_USE_ADB_SUSPEND, enabled).apply()
        refreshSnapshot()
    }

    fun getFocusDistractionPackages(): Set<String> {
        val stored = prefs.getStringSet(KEY_FOCUS_PACKAGES, null)
        return if (stored == null) DEFAULT_DISTRACTION_PACKAGES else stored.toSet()
    }

    fun setFocusDistractionPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_FOCUS_PACKAGES, packages.toSet()).apply()
        refreshSnapshot()
    }

    // --- Phase 4 Preferences (Hybrid Brain & Goku Dev Mode) ---

    fun isGoogleSearchGroundingEnabled(): Boolean =
        prefs.getBoolean(KEY_GOOGLE_SEARCH_GROUNDING, true)

    fun setGoogleSearchGroundingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GOOGLE_SEARCH_GROUNDING, enabled).apply()
        refreshSnapshot()
    }

    fun getOfflineLlmModelUrl(): String =
        prefs.getString(KEY_OFFLINE_LLM_URL, "").orEmpty().trim()

    fun saveOfflineLlmModelUrl(url: String) {
        prefs.edit().putString(KEY_OFFLINE_LLM_URL, url.trim()).apply()
        refreshSnapshot()
    }

    fun getGithubRepos(): List<String> {
        val raw = prefs.getString(KEY_GITHUB_REPOS, null)
        if (raw.isNullOrBlank()) return DEFAULT_GITHUB_REPOS
        return raw.split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { DEFAULT_GITHUB_REPOS }
    }

    fun saveGithubRepos(repos: List<String>) {
        val cleaned = repos.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val finalList = cleaned.ifEmpty { DEFAULT_GITHUB_REPOS }
        val active = getActiveGithubRepo()
        val nextActive = if (active in finalList) active else finalList.first()
        prefs.edit()
            .putString(KEY_GITHUB_REPOS, finalList.joinToString("\n"))
            .putString(KEY_ACTIVE_GITHUB_REPO, nextActive)
            .apply()
        refreshSnapshot()
    }

    fun getActiveGithubRepo(): String {
        val repos = getGithubRepos()
        val stored = prefs.getString(KEY_ACTIVE_GITHUB_REPO, null)?.trim().orEmpty()
        return if (stored in repos) stored else repos.first()
    }

    fun setActiveGithubRepo(repo: String) {
        val trimmed = repo.trim()
        if (trimmed.isEmpty()) return
        val current = getGithubRepos().toMutableList()
        if (trimmed !in current) current.add(0, trimmed)
        prefs.edit()
            .putString(KEY_GITHUB_REPOS, current.joinToString("\n"))
            .putString(KEY_ACTIVE_GITHUB_REPO, trimmed)
            .apply()
        refreshSnapshot()
    }

    fun getGithubToken(): String = prefs.getString(KEY_GITHUB_TOKEN, "").orEmpty().trim()

    fun saveGithubToken(token: String) {
        prefs.edit().putString(KEY_GITHUB_TOKEN, token.trim()).apply()
        refreshSnapshot()
    }

    fun clearGithubToken() {
        prefs.edit().remove(KEY_GITHUB_TOKEN).apply()
        refreshSnapshot()
    }

    // --- Phase 5 Preferences (Universal Pilot, Vision Fallback, Mistake Blocker, Zero-Copy Bridge) ---

    fun isUniversalPilotEnabled(): Boolean = prefs.getBoolean(KEY_UNIVERSAL_PILOT, true)

    fun setUniversalPilotEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UNIVERSAL_PILOT, enabled).apply()
        refreshSnapshot()
    }

    fun isScreenVisionFallbackEnabled(): Boolean = prefs.getBoolean(KEY_SCREEN_VISION_FALLBACK, true)

    fun setScreenVisionFallbackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_VISION_FALLBACK, enabled).apply()
        refreshSnapshot()
    }

    fun isMistakeBlockerEnabled(): Boolean = prefs.getBoolean(KEY_MISTAKE_BLOCKER, true)

    fun setMistakeBlockerEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MISTAKE_BLOCKER, enabled).apply()
        refreshSnapshot()
    }

    fun isZeroCopyBridgeEnabled(): Boolean = prefs.getBoolean(KEY_ZERO_COPY_BRIDGE, true)

    fun setZeroCopyBridgeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ZERO_COPY_BRIDGE, enabled).apply()
        refreshSnapshot()
    }

    private fun refreshSnapshot() {
        _settingsFlow.value = readSnapshot()
    }

    private fun readSnapshot(): GokuUserSettings {
        val key = getGeminiApiKey()
        val ghToken = getGithubToken()
        return GokuUserSettings(
            hasApiKey = key.isNotEmpty(),
            maskedApiKey = maskApiKey(key),
            geminiModel = getGeminiModel(),
            continuousListeningEnabled = isContinuousListeningEnabled(),
            adbPairPort = getAdbPairPort(),
            adbConnectPort = getAdbConnectPort(),
            adbTargetHost = DEFAULT_ADB_HOST,
            adbTargetPort = DEFAULT_ADB_PORT,
            speechRate = getSpeechRate(),
            wakeWordEnabled = isWakeWordEnabled(),
            shortFastReplyMode = isShortFastReplyMode(),
            focusShieldEnabled = isFocusShieldEnabled(),
            focusUseAdbSuspend = isFocusUseAdbSuspend(),
            focusDistractionPackages = getFocusDistractionPackages(),
            googleSearchGroundingEnabled = isGoogleSearchGroundingEnabled(),
            offlineLlmModelUrl = getOfflineLlmModelUrl(),
            githubRepos = getGithubRepos(),
            activeGithubRepo = getActiveGithubRepo(),
            hasGithubToken = ghToken.isNotEmpty(),
            maskedGithubToken = maskApiKey(ghToken),
            universalPilotEnabled = isUniversalPilotEnabled(),
            screenVisionFallbackEnabled = isScreenVisionFallbackEnabled(),
            mistakeBlockerEnabled = isMistakeBlockerEnabled(),
            zeroCopyBridgeEnabled = isZeroCopyBridgeEnabled()
        )
    }

    companion object {
        private const val PREFS_FILE_NAME = "goku_agent_secure_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_CONTINUOUS_LISTENING = "continuous_listening_enabled"
        private const val KEY_ADB_PAIR_PORT = "adb_pair_port"
        private const val KEY_ADB_CONNECT_PORT = "adb_connect_port"
        private const val KEY_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_24x7_enabled"
        private const val KEY_SHORT_FAST_REPLY = "short_fast_reply_mode"
        private const val KEY_FOCUS_SHIELD_ENABLED = "focus_shield_enabled"
        private const val KEY_FOCUS_USE_ADB_SUSPEND = "focus_use_adb_suspend"
        private const val KEY_FOCUS_PACKAGES = "focus_distraction_packages"
        private const val KEY_GOOGLE_SEARCH_GROUNDING = "google_search_grounding_enabled"
        private const val KEY_OFFLINE_LLM_URL = "offline_llm_model_url"
        private const val KEY_GITHUB_REPOS = "dev_github_repos"
        private const val KEY_ACTIVE_GITHUB_REPO = "dev_active_github_repo"
        private const val KEY_GITHUB_TOKEN = "dev_github_token"
        private const val KEY_UNIVERSAL_PILOT = "phase5_universal_pilot_enabled"
        private const val KEY_SCREEN_VISION_FALLBACK = "phase5_screen_vision_fallback_enabled"
        private const val KEY_MISTAKE_BLOCKER = "phase5_mistake_blocker_enabled"
        private const val KEY_ZERO_COPY_BRIDGE = "phase5_zero_copy_bridge_enabled"

        val DEFAULT_GITHUB_REPOS = listOf(
            "autoacceptapp/goku_agent",
            "autoacceptapp/Order-master"
        )

        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        const val DEFAULT_ADB_HOST = "127.0.0.1"
        const val DEFAULT_ADB_PORT = 5555

        val SUPPORTED_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.0-flash"
        )

        val DEFAULT_DISTRACTION_PACKAGES = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.facebook.katana",
            "com.twitter.android",
            "com.snapchat.android",
            "com.amazon.mShop.android.shopping",
            "com.flipkart.android",
            "com.meesho.supply",
            "com.pubg.imobile",
            "com.dts.freefiremax"
        )

        fun maskApiKey(key: String): String {
            if (key.isBlank()) return ""
            return if (key.length <= 8) {
                "••••${key.takeLast(2)}"
            } else {
                "${key.take(4)}••••••••${key.takeLast(4)}"
            }
        }
    }
}
