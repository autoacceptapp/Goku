package com.arif.goku.system

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.arif.goku.adb.LocalAdbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class GhostSettingType(val displayName: String) {
    WIFI("Wi-Fi"),
    BLUETOOTH("Bluetooth"),
    BRIGHTNESS("Brightness"),
    DND("Do Not Disturb"),
    DARK_MODE("Dark Mode")
}

data class ProcessMetric(
    val processName: String,
    val memoryMb: Int,
    val isSafeToStop: Boolean
)

data class PhoneDoctorReport(
    val topProcesses: List<ProcessMetric>,
    val totalReclaimableCandidates: Int,
    val summaryText: String
)

data class FreezableAppInfo(
    val packageName: String,
    val label: String,
    val isFrozen: Boolean
)

/**
 * Phase 2 Local ADB Power Modules (`127.0.0.1:5555`):
 * - Ghost Mode: silent `settings put` / `svc` / `cmd` toggles without opening Settings UI.
 * - Auto-Repair Phone Doctor: inspects `dumpsys meminfo` / `top -n 1` and safely force-stops background bloat.
 * - App Freezer: freezes (`pm disable-user --user 0`) and unfreezes (`pm enable`) non-system apps.
 */
class SystemPowerTools(
    private val context: Context,
    private val adbManager: LocalAdbManager
) {
    private val appContext = context.applicationContext

    /**
     * Ghost Mode: Applies system setting silently over Local ADB without launching Settings UI.
     */
    suspend fun applyGhostSetting(
        setting: GhostSettingType,
        enabled: Boolean? = null,
        numericValue: Int? = null
    ): Result<String> {
        val command = when (setting) {
            GhostSettingType.WIFI -> {
                val on = enabled ?: true
                "svc wifi ${if (on) "enable" else "disable"}"
            }

            GhostSettingType.BLUETOOTH -> {
                val on = enabled ?: true
                "svc bluetooth ${if (on) "enable" else "disable"}"
            }

            GhostSettingType.BRIGHTNESS -> {
                val level = (numericValue ?: if (enabled == false) 45 else 180).coerceIn(10, 255)
                "settings put system screen_brightness_mode 0 && settings put system screen_brightness $level"
            }

            GhostSettingType.DND -> {
                val on = enabled ?: true
                "cmd notification set_dnd ${if (on) "priority" else "off"}"
            }

            GhostSettingType.DARK_MODE -> {
                val on = enabled ?: true
                "cmd uimode night ${if (on) "yes" else "no"}"
            }
        }

        return adbManager.executeShellCommand(command).map {
            val stateDesc = when (setting) {
                GhostSettingType.BRIGHTNESS -> "level ${(numericValue ?: 180).coerceIn(10, 255)}"
                else -> if (enabled == false) "OFF" else "ON"
            }
            "Ghost Mode: ${setting.displayName} silently set to $stateDesc."
        }
    }

    /**
     * Auto-Repair Phone Doctor: Parses `dumpsys meminfo --short` (and falls back to `top -b -n 1`)
     * to identify top RAM-consuming user processes.
     */
    suspend fun runPhoneDoctorScan(): Result<PhoneDoctorReport> = withContext(Dispatchers.IO) {
        val memOutput = adbManager.executeShellCommand("dumpsys meminfo --short")
            .getOrElse { err ->
                // Fallback to `top -b -n 1`
                val topRes = adbManager.executeShellCommand("top -b -n 1 -m 10")
                if (topRes.isFailure) return@withContext Result.failure(err)
                topRes.getOrDefault("")
            }

        val parsed = parseDumpsysMeminfo(memOutput, appContext.packageName)
        val stoppableCount = parsed.count { it.isSafeToStop }
        val summary = if (parsed.isEmpty()) {
            "Phone Doctor scan complete: No heavy background user apps found."
        } else {
            val top = parsed.first()
            "Phone Doctor found ${parsed.size} active processes (Top: ${top.processName} ~${top.memoryMb}MB, $stoppableCount safe to clean)."
        }

        Result.success(
            PhoneDoctorReport(
                topProcesses = parsed,
                totalReclaimableCandidates = stoppableCount,
                summaryText = summary
            )
        )
    }

    /**
     * Safely runs `am force-stop` on non-system background packages identified by Phone Doctor.
     */
    suspend fun cleanBackgroundProcesses(packages: List<String>): Result<String> = withContext(Dispatchers.IO) {
        val safeTargets = packages
            .map { it.substringBefore(":").trim() }
            .distinct()
            .filter { isSafeUserPackage(it, appContext.packageName) }

        if (safeTargets.isEmpty()) {
            return@withContext Result.success("Koi unsafe ya unnecessary background app force-stop karne ke liye nahi mila.")
        }

        var stoppedCount = 0
        for (pkg in safeTargets) {
            val res = adbManager.executeShellCommand("am force-stop $pkg")
            if (res.isSuccess) stoppedCount++
        }

        Result.success("Phone Doctor cleaned $stoppedCount background app(s) successfully.")
    }

    /**
     * Lists non-system user-installed apps along with their current frozen/enabled state.
     */
    suspend fun listThirdPartyApps(): List<FreezableAppInfo> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val disabledViaAdb = if (adbManager.isConnected) {
            adbManager.executeShellCommand("pm list packages -3 -d")
                .getOrDefault("")
                .lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } else {
            emptySet()
        }

        val adbPackages = if (adbManager.isConnected) {
            adbManager.executeShellCommand("pm list packages -3")
                .getOrDefault("")
                .lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { isSafeUserPackage(it, appContext.packageName) }
                .toList()
        } else {
            emptyList()
        }

        val discoveredPackages = if (adbPackages.isNotEmpty()) {
            adbPackages
        } else {
            // Fallback via PackageManager launcher intent query
            val launchIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            pm.queryIntentActivities(launchIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .filter { pkg ->
                    isSafeUserPackage(pkg, appContext.packageName) && !isSystemApp(pm, pkg)
                }
        }

        discoveredPackages.map { pkg ->
            val label = try {
                val info = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast('.')
            }
            val frozen = pkg in disabledViaAdb || try {
                !pm.getApplicationInfo(pkg, 0).enabled
            } catch (_: Exception) {
                false
            }
            FreezableAppInfo(packageName = pkg, label = label, isFrozen = frozen)
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * Freezes (`pm disable-user --user 0 <pkg>`) or unfreezes (`pm enable <pkg>`) a non-system app.
     */
    suspend fun setAppFrozen(packageName: String, freeze: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val pkg = packageName.trim()
        if (!isSafeUserPackage(pkg, appContext.packageName)) {
            return@withContext Result.failure(
                SecurityException("Refused to modify protected/system package '$pkg'.")
            )
        }
        val cmd = if (freeze) "pm disable-user --user 0 $pkg" else "pm enable $pkg"
        adbManager.executeShellCommand(cmd).map {
            if (freeze) "App frozen: $pkg" else "App unfrozen: $pkg"
        }
    }

    private fun isSystemApp(pm: PackageManager, pkg: String): Boolean {
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val MEMINFO_LINE_REGEX = Regex("^\\s*([\\d,]+)K:\\s+([a-zA-Z0-9._:]+)")

        private val PROTECTED_PREFIXES = listOf(
            "com.android.",
            "android",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.inputmethod",
            "com.google.android.tts",
            "com.google.android.googlequicksearchbox",
            "com.qualcomm.",
            "com.mediatek."
        )

        fun isSafeUserPackage(pkg: String, selfPackage: String): Boolean {
            if (!LocalAdbManager.PACKAGE_NAME_REGEX.matches(pkg)) return false
            if (pkg == selfPackage || pkg == "com.arif.goku") return false
            if (PROTECTED_PREFIXES.any { pkg == it || pkg.startsWith(it) }) return false
            return true
        }

        fun parseDumpsysMeminfo(rawOutput: String, selfPackage: String): List<ProcessMetric> {
            val metrics = mutableListOf<ProcessMetric>()
            var inPssSection = false

            rawOutput.lineSequence().forEach { line ->
                if (line.contains("Total PSS by process", ignoreCase = true)) {
                    inPssSection = true
                    return@forEach
                }
                if (inPssSection && line.contains("Total PSS by OOM", ignoreCase = true)) {
                    inPssSection = false
                    return@forEach
                }
                val match = MEMINFO_LINE_REGEX.find(line)
                if (match != null) {
                    val kb = match.groupValues[1].replace(",", "").toLongOrNull() ?: 0L
                    val proc = match.groupValues[2]
                    val basePkg = proc.substringBefore(":")
                    val mb = (kb / 1024L).toInt()
                    if (mb >= 15 && basePkg.contains(".")) {
                        metrics.add(
                            ProcessMetric(
                                processName = basePkg,
                                memoryMb = mb,
                                isSafeToStop = isSafeUserPackage(basePkg, selfPackage)
                            )
                        )
                    }
                }
            }
            return metrics
                .distinctBy { it.processName }
                .sortedByDescending { it.memoryMb }
                .take(12)
        }
    }
}
