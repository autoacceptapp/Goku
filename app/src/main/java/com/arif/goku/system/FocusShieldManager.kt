package com.arif.goku.system

import android.content.Context
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class FocusShieldState(
    val isActive: Boolean = false,
    val blockedPackages: Set<String> = SecurePrefs.DEFAULT_DISTRACTION_PACKAGES,
    val useAdbSuspend: Boolean = true,
    val suspendedViaAdbCount: Int = 0,
    val mutedNotificationsCount: Int = 0,
    val lastStatusNote: String = "Deep Focus Shield OFF"
)

/**
 * Shared runtime policy bus read by GokuNotificationListener to silently dismiss distraction
 * notifications while allowing phone calls and important messages through.
 */
object FocusShieldRuntime {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _blockedPackages = MutableStateFlow(SecurePrefs.DEFAULT_DISTRACTION_PACKAGES)
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _mutedCount = MutableStateFlow(0)
    val mutedCount: StateFlow<Int> = _mutedCount.asStateFlow()

    fun sync(active: Boolean, packages: Set<String>) {
        _isActive.value = active
        _blockedPackages.value = packages
    }

    fun shouldMutePackage(packageName: String, isCallOrImportant: Boolean): Boolean {
        if (!_isActive.value) return false
        if (isCallOrImportant) return false
        return packageName in _blockedPackages.value ||
            DISTRACTION_KEYWORDS.any { packageName.contains(it, ignoreCase = true) }
    }

    fun recordMutedNotification() {
        _mutedCount.update { it + 1 }
    }

    fun resetMutedCounter() {
        _mutedCount.value = 0
    }

    private val DISTRACTION_KEYWORDS = listOf(
        "instagram",
        "facebook",
        "snapchat",
        "twitter",
        "tiktok",
        "youtube",
        "flipkart",
        "meesho",
        "myntra",
        "pubg",
        "freefire",
        "ludo",
        "dream11",
        "rummy"
    )
}

/**
 * Phase 3 Deep Focus Shield ("Goku, kaam mode ON" / "Focus mode OFF").
 *
 * When enabled:
 * 1) Instructs GokuNotificationListener to auto-dismiss distraction notifications (social media,
 *    games, shopping) while keeping calls & important alerts untouched.
 * 2) When Local ADB (127.0.0.1:5555) is connected and enabled, temporarily suspends selected
 *    non-system distraction apps (`pm suspend` + `cmd appops set <pkg> POST_NOTIFICATION ignore`)
 *    and cleanly restores them (`pm unsuspend`) when Focus Mode turns OFF.
 */
class FocusShieldManager(
    private val context: Context,
    private val securePrefs: SecurePrefs,
    private val adbManager: LocalAdbManager
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(
        FocusShieldState(
            isActive = securePrefs.isFocusShieldEnabled(),
            blockedPackages = securePrefs.getFocusDistractionPackages(),
            useAdbSuspend = securePrefs.isFocusUseAdbSuspend(),
            mutedNotificationsCount = FocusShieldRuntime.mutedCount.value
        )
    )
    val state: StateFlow<FocusShieldState> = _state.asStateFlow()

    init {
        FocusShieldRuntime.sync(_state.value.isActive, _state.value.blockedPackages)
    }

    suspend fun setFocusMode(enable: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val selected = securePrefs.getFocusDistractionPackages()
            .filter { SystemPowerTools.isSafeUserPackage(it, appContext.packageName) }
            .toSet()
        val useAdb = securePrefs.isFocusUseAdbSuspend()

        securePrefs.setFocusShieldEnabled(enable)
        if (enable) FocusShieldRuntime.resetMutedCounter()
        FocusShieldRuntime.sync(enable, selected)

        var adbChangedCount = 0
        if (useAdb && adbManager.isConnected && selected.isNotEmpty()) {
            for (pkg in selected) {
                if (enable) {
                    val suspendRes = adbManager.executeShellCommand("pm suspend $pkg")
                    adbManager.executeShellCommand("cmd appops set $pkg POST_NOTIFICATION ignore")
                    if (suspendRes.isSuccess) adbChangedCount++
                } else {
                    val unsuspendRes = adbManager.executeShellCommand("pm unsuspend $pkg")
                    adbManager.executeShellCommand("cmd appops set $pkg POST_NOTIFICATION allow")
                    if (unsuspendRes.isSuccess) adbChangedCount++
                }
            }
        }

        val message = if (enable) {
            if (adbChangedCount > 0) {
                "Kaam Mode ON! Distraction notifications muted aur $adbChangedCount apps temporarily suspended. Calls aate rahenge."
            } else {
                "Kaam Mode ON! Social media, games aur shopping notifications auto-mute kar diye gaye hain. Calls aate rahenge."
            }
        } else {
            if (adbChangedCount > 0) {
                "Focus Mode OFF! $adbChangedCount suspended apps restore kar diye gaye hain aur notifications normal hain."
            } else {
                "Focus Mode OFF! Sab notifications aur apps normal mode mein aa gaye hain."
            }
        }

        _state.update {
            it.copy(
                isActive = enable,
                blockedPackages = selected,
                useAdbSuspend = useAdb,
                suspendedViaAdbCount = if (enable) adbChangedCount else 0,
                mutedNotificationsCount = FocusShieldRuntime.mutedCount.value,
                lastStatusNote = message
            )
        }
        Result.success(message)
    }

    fun toggleAppInFocusList(packageName: String) {
        if (!SystemPowerTools.isSafeUserPackage(packageName, appContext.packageName)) return
        val current = securePrefs.getFocusDistractionPackages().toMutableSet()
        if (packageName in current) current.remove(packageName) else current.add(packageName)
        securePrefs.setFocusDistractionPackages(current)
        FocusShieldRuntime.sync(_state.value.isActive, current)
        _state.update { it.copy(blockedPackages = current) }
    }

    fun setUseAdbSuspend(enabled: Boolean) {
        securePrefs.setFocusUseAdbSuspend(enabled)
        _state.update { it.copy(useAdbSuspend = enabled) }
    }

    fun refreshMutedCount() {
        _state.update { it.copy(mutedNotificationsCount = FocusShieldRuntime.mutedCount.value) }
    }

    companion object {
        fun isFocusOnCommand(input: String): Boolean {
            val lower = input.trim().lowercase()
            return lower.contains("kaam mode on") ||
                lower.contains("kam mode on") ||
                lower.contains("focus mode on") ||
                lower.contains("focus shield on") ||
                lower.contains("kaam mode chalu") ||
                lower.contains("काम मोड ऑन") ||
                lower.contains("फोकस मोड ऑन")
        }

        fun isFocusOffCommand(input: String): Boolean {
            val lower = input.trim().lowercase()
            return lower.contains("kaam mode off") ||
                lower.contains("kam mode off") ||
                lower.contains("focus mode off") ||
                lower.contains("focus shield off") ||
                lower.contains("kaam mode band") ||
                lower.contains("focus mode band") ||
                lower.contains("काम मोड ऑफ") ||
                lower.contains("फोकस मोड ऑफ")
        }
    }
}
