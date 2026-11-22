package com.arif.goku.action

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.arif.goku.accessibility.GokuAccessibilityService
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.brain.ContextBrain
import com.arif.goku.data.db.MemoryManager
import com.arif.goku.dev.GokuDevManager
import com.arif.goku.notifications.GokuNotificationListener
import com.arif.goku.system.FocusShieldManager
import com.arif.goku.system.GhostSettingType
import com.arif.goku.system.SystemPowerTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLEncoder

enum class ExecutionEngine(val badge: String) {
    LOCAL_ADB("ADB 127.0.0.1:5555"),
    ACCESSIBILITY("Accessibility"),
    ANDROID_INTENT("Android API"),
    NONE("Unavailable")
}

data class ActionExecutionResult(
    val success: Boolean,
    val engineUsed: ExecutionEngine,
    val spokenReplyHindi: String,
    val logDetail: String
)

/**
 * Unified Dual-Engine ActionExecutor for GokuAgent (Phase 2).
 *
 * Routing priority:
 * 1) Local ADB (`127.0.0.1:5555`) first for speed, reliability, and silent execution.
 * 2) Automatic fallback to `GokuAccessibilityService` / Android Intents when ADB is disconnected.
 */
class ActionExecutor(
    private val context: Context,
    private val adbManager: LocalAdbManager,
    private val contextBrain: ContextBrain,
    private val powerTools: SystemPowerTools,
    private val focusShieldManager: FocusShieldManager? = null,
    private val memoryManager: MemoryManager? = null,
    private val devManager: GokuDevManager? = null
) {
    private val appContext = context.applicationContext

    suspend fun execute(action: GokuAction): ActionExecutionResult = withContext(Dispatchers.IO) {
        when (action.type) {
            GokuActionType.OPEN_APP -> executeOpenApp(action.target)
            GokuActionType.CLICK_TEXT -> executeClickText(action.target)
            GokuActionType.TYPE_TEXT -> executeTypeText(action.target, action.text)
            GokuActionType.SCROLL -> executeScroll(action.forward)
            GokuActionType.TAP -> executeTap(action.x ?: 540f, action.y ?: 1100f)
            GokuActionType.CALL -> executePhoneCall(action.phone.ifBlank { action.target })
            GokuActionType.SEND_SMS -> executeSendSms(action.phone.ifBlank { action.target }, action.text)
            GokuActionType.SEND_WHATSAPP -> executeSendWhatsApp(action.phone.ifBlank { action.target }, action.text)
            GokuActionType.ADB_SHELL -> executeAdbShell(action.text.ifBlank { action.target })
            GokuActionType.TOGGLE_SETTING -> executeGhostToggle(action.setting, action.enabled, action.value)
            GokuActionType.READ_NOTIFICATIONS -> executeReadNotifications()
            GokuActionType.READ_SCREEN -> executeReadScreen()
            GokuActionType.PHONE_DOCTOR -> executePhoneDoctor()
            GokuActionType.FREEZE_APP -> executeFreezeToggle(action.target, freeze = true)
            GokuActionType.UNFREEZE_APP -> executeFreezeToggle(action.target, freeze = false)
            GokuActionType.GLOBAL_NAV -> executeGlobalNav(action.target)
            GokuActionType.FOCUS_MODE -> executeFocusMode(action.enabled ?: true)
            GokuActionType.TOGGLE_TORCH -> executeToggleTorch(action.enabled ?: true)
            GokuActionType.ADJUST_VOLUME -> executeAdjustVolume(action.target, action.value)
            GokuActionType.SET_ALARM -> executeSetAlarm(action.target, action.text)
            GokuActionType.DEV_GITHUB_BUILD -> executeCheckGitHubBuild(action.target)
            GokuActionType.DEV_TERMUX_CMD -> executeTermuxCommand(action.text.ifBlank { action.target })
        }
    }

    private fun executeToggleTorch(enable: Boolean): ActionExecutionResult {
        return try {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ActionExecutionResult(false, ExecutionEngine.NONE, "Camera flash service available nahi hai.", "No CameraManager")
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val facingBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                hasFlash && facingBack
            } ?: cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionExecutionResult(false, ExecutionEngine.NONE, "Is device par flashlight nahi mili.", "No flash camera")

            cm.setTorchMode(cameraId, enable)
            val msg = if (enable) "Flashlight ON kar di hai." else "Flashlight OFF kar di hai."
            ActionExecutionResult(true, ExecutionEngine.ANDROID_INTENT, msg, "Torch=$enable (cam $cameraId)")
        } catch (e: Exception) {
            ActionExecutionResult(
                false,
                ExecutionEngine.NONE,
                "Flashlight toggle nahi ho payi.",
                e.message ?: "Torch error"
            )
        }
    }

    private fun executeAdjustVolume(directionRaw: String, percent: Int?): ActionExecutionResult {
        return try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return ActionExecutionResult(false, ExecutionEngine.NONE, "Audio service unavailable hai.", "No AudioManager")
            val dir = directionRaw.uppercase()
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

            val reply = when {
                percent != null -> {
                    val targetIdx = ((percent.coerceIn(0, 100) / 100f) * maxVol).toInt()
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, targetIdx, AudioManager.FLAG_SHOW_UI)
                    "Volume $percent percent set kar diya hai."
                }
                dir.contains("MUTE") || dir.contains("SILENT") -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    "Media volume mute kar diya hai."
                }
                dir.contains("MAX") || dir.contains("FULL") -> {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, AudioManager.FLAG_SHOW_UI)
                    "Volume full kar diya hai."
                }
                dir.contains("DOWN") || dir.contains("LOW") || dir.contains("KAM") -> {
                    repeat(2) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    }
                    "Volume kam kar diya hai."
                }
                else -> {
                    repeat(2) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    }
                    "Volume badha diya hai."
                }
            }
            ActionExecutionResult(true, ExecutionEngine.ANDROID_INTENT, reply, "Volume adjusted: $dir ($percent)")
        } catch (e: Exception) {
            ActionExecutionResult(false, ExecutionEngine.NONE, "Volume change nahi ho paya.", e.message ?: "Volume error")
        }
    }

    private suspend fun executeSetAlarm(timeSpec: String, label: String): ActionExecutionResult {
        val parts = timeSpec.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 7
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Goku Alarm" })
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) { appContext.startActivity(intent) }
            val formatted = String.format("%02d:%02d", hour, minute)
            ActionExecutionResult(
                true,
                ExecutionEngine.ANDROID_INTENT,
                "$formatted ka alarm set kar diya hai.",
                "Alarm intent launched ($formatted)"
            )
        } catch (e: Exception) {
            ActionExecutionResult(false, ExecutionEngine.NONE, "Alarm app launch nahi ho paya.", e.message ?: "Alarm error")
        }
    }

    private suspend fun executeCheckGitHubBuild(repoTarget: String): ActionExecutionResult {
        val dev = devManager ?: return ActionExecutionResult(
            false,
            ExecutionEngine.NONE,
            "Dev Mode manager available nahi hai.",
            "GokuDevManager null"
        )
        val res = dev.checkLatestGitHubBuild(repoTarget.ifBlank { null })
        return res.fold(
            onSuccess = { report ->
                ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.ANDROID_INTENT,
                    spokenReplyHindi = report.hindiSummary,
                    logDetail = "GitHub [${report.repo}] #${report.runNumber} ${report.status}/${report.conclusion}: ${report.commitMessage}"
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = err.message ?: "GitHub build check fail ho gaya.",
                    logDetail = err.message ?: "GitHub API error"
                )
            }
        )
    }

    private suspend fun executeTermuxCommand(command: String): ActionExecutionResult {
        val dev = devManager ?: return ActionExecutionResult(
            false,
            ExecutionEngine.NONE,
            "Dev Mode manager available nahi hai.",
            "GokuDevManager null"
        )
        val res = dev.executeTermuxCommand(command)
        return res.fold(
            onSuccess = { msg ->
                ActionExecutionResult(
                    success = true,
                    engineUsed = if (adbManager.isConnected) ExecutionEngine.LOCAL_ADB else ExecutionEngine.ANDROID_INTENT,
                    spokenReplyHindi = "Termux command run kar diya hai.",
                    logDetail = msg
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = err.message ?: "Termux command fail ho gaya.",
                    logDetail = err.message ?: "Termux error"
                )
            }
        )
    }

    private suspend fun executeFocusMode(enable: Boolean): ActionExecutionResult {
        val manager = focusShieldManager ?: return ActionExecutionResult(
            success = false,
            engineUsed = ExecutionEngine.NONE,
            spokenReplyHindi = "Deep Focus Shield manager unavailable hai.",
            logDetail = "FocusShieldManager not attached"
        )
        val res = manager.setFocusMode(enable)
        return res.fold(
            onSuccess = { msg ->
                ActionExecutionResult(
                    success = true,
                    engineUsed = if (adbManager.isConnected) ExecutionEngine.LOCAL_ADB else ExecutionEngine.ANDROID_INTENT,
                    spokenReplyHindi = msg,
                    logDetail = msg
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = "Focus Mode toggle fail hua: ${err.message ?: "unknown error"}",
                    logDetail = err.message ?: "FocusShield failure"
                )
            }
        )
    }

    private suspend fun executeOpenApp(appQuery: String): ActionExecutionResult {
        val pkg = resolvePackageName(appQuery)
            ?: return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Mujhe '$appQuery' app phone mein nahi mila.",
                logDetail = "App not found: $appQuery"
            )

        // Engine 1: Local ADB fast launch
        if (adbManager.isConnected && LocalAdbManager.PACKAGE_NAME_REGEX.matches(pkg)) {
            val adbRes = adbManager.executeShellCommand("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
            if (adbRes.isSuccess) {
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = "Theek hai, $appQuery khol diya hai.",
                    logDetail = "Launched $pkg via Local ADB"
                )
            }
        }

        // Engine 2: Android Launcher Intent fallback
        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launchIntent != null) {
            withContext(Dispatchers.Main) { appContext.startActivity(launchIntent) }
            ActionExecutionResult(
                success = true,
                engineUsed = ExecutionEngine.ANDROID_INTENT,
                spokenReplyHindi = "Theek hai, $appQuery khol diya hai.",
                logDetail = "Launched $pkg via Intent fallback"
            )
        } else {
            ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "$appQuery launch nahi ho paya.",
                logDetail = "No launch intent for $pkg"
            )
        }
    }

    private fun executeClickText(query: String): ActionExecutionResult {
        if (!GokuAccessibilityService.refreshActiveStatus(appContext)) {
            return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Screen par click karne ke liye Accessibility Service ON karein.",
                logDetail = "Accessibility inactive for clickNodeByText"
            )
        }
        val clicked = GokuAccessibilityService.clickNodeByText(query)
        return ActionExecutionResult(
            success = clicked,
            engineUsed = ExecutionEngine.ACCESSIBILITY,
            spokenReplyHindi = if (clicked) "'$query' par tap kar diya hai." else "Screen par '$query' nahi mila.",
            logDetail = if (clicked) "Clicked '$query'" else "Node not found: $query"
        )
    }

    private suspend fun executeTypeText(fieldQuery: String, textToType: String): ActionExecutionResult {
        if (textToType.isBlank()) {
            return ActionExecutionResult(false, ExecutionEngine.NONE, "Type karne ke liye text khaali hai.", "Empty text")
        }

        // Try Accessibility first when a specific field label is provided
        if (GokuAccessibilityService.refreshActiveStatus(appContext)) {
            val typed = GokuAccessibilityService.typeIntoFocusedOrMatchingField(
                query = fieldQuery.ifBlank { null },
                text = textToType
            )
            if (typed) {
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.ACCESSIBILITY,
                    spokenReplyHindi = "Text type kar diya hai.",
                    logDetail = "Typed via AccessibilityNodeInfo"
                )
            }
        }

        // Fallback/Dual-engine: Local ADB `input text` (strictly ASCII-safe escaped)
        if (adbManager.isConnected) {
            val safeEscaped = textToType
                .replace(Regex("[^a-zA-Z0-9@._+\\-?!, ]"), "")
                .replace(" ", "%s")
            if (safeEscaped.isNotBlank()) {
                val res = adbManager.executeShellCommand("input text $safeEscaped")
                if (res.isSuccess) {
                    return ActionExecutionResult(
                        success = true,
                        engineUsed = ExecutionEngine.LOCAL_ADB,
                        spokenReplyHindi = "Text type kar diya hai.",
                        logDetail = "Typed via Local ADB input text"
                    )
                }
            }
        }

        return ActionExecutionResult(
            success = false,
            engineUsed = ExecutionEngine.NONE,
            spokenReplyHindi = "Koi active input box nahi mila. Pehle field par tap karein ya Accessibility ON karein.",
            logDetail = "Type failed on both engines"
        )
    }

    private suspend fun executeScroll(forward: Boolean): ActionExecutionResult {
        if (adbManager.isConnected) {
            val dm = appContext.resources.displayMetrics
            val x = (dm.widthPixels * 0.5f).toInt()
            val y1 = if (forward) (dm.heightPixels * 0.75f).toInt() else (dm.heightPixels * 0.25f).toInt()
            val y2 = if (forward) (dm.heightPixels * 0.25f).toInt() else (dm.heightPixels * 0.75f).toInt()
            val res = adbManager.executeShellCommand("input swipe $x $y1 $x $y2 280")
            if (res.isSuccess) {
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = if (forward) "Neeche scroll kar diya." else "Upar scroll kar diya.",
                    logDetail = "Scrolled via Local ADB"
                )
            }
        }

        if (GokuAccessibilityService.refreshActiveStatus(appContext)) {
            val ok = GokuAccessibilityService.scroll(forward)
            return ActionExecutionResult(
                success = ok,
                engineUsed = ExecutionEngine.ACCESSIBILITY,
                spokenReplyHindi = if (ok) {
                    if (forward) "Neeche scroll kar diya." else "Upar scroll kar diya."
                } else {
                    "Scroll nahi ho paya."
                },
                logDetail = "Scrolled via Accessibility ($ok)"
            )
        }

        return ActionExecutionResult(
            success = false,
            engineUsed = ExecutionEngine.NONE,
            spokenReplyHindi = "Scroll karne ke liye Local ADB ya Accessibility Service ON karein.",
            logDetail = "No engine available for scroll"
        )
    }

    private suspend fun executeTap(x: Float, y: Float): ActionExecutionResult {
        val ix = x.toInt().coerceAtLeast(0)
        val iy = y.toInt().coerceAtLeast(0)
        if (adbManager.isConnected) {
            val res = adbManager.executeShellCommand("input tap $ix $iy")
            if (res.isSuccess) {
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = "Screen par tap kar diya.",
                    logDetail = "Tapped ($ix, $iy) via Local ADB"
                )
            }
        }
        if (GokuAccessibilityService.refreshActiveStatus(appContext)) {
            val ok = GokuAccessibilityService.tapAt(x, y)
            return ActionExecutionResult(
                success = ok,
                engineUsed = ExecutionEngine.ACCESSIBILITY,
                spokenReplyHindi = if (ok) "Screen par tap kar diya." else "Tap fail ho gaya.",
                logDetail = "Tapped ($ix, $iy) via Accessibility"
            )
        }
        return ActionExecutionResult(
            success = false,
            engineUsed = ExecutionEngine.NONE,
            spokenReplyHindi = "Tap karne ke liye ADB ya Accessibility ON karein.",
            logDetail = "No engine for tap"
        )
    }

    /**
     * Dispatches a SafetyGuard-confirmed outgoing phone call.
     * Supports raw numbers, "copied number" (Clipboard), or Contact names (`READ_CONTACTS`).
     */
    private suspend fun executePhoneCall(targetRaw: String): ActionExecutionResult {
        val resolved = resolvePhoneTarget(targetRaw)
            ?: return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "'$targetRaw' ka valid phone number nahi mila. Contacts permission check karein ya number boliye.",
                logDetail = "Unresolved call target: $targetRaw"
            )

        val (displayName, phoneNumber) = resolved
        val hasCallPerm = ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        // Engine 1: Local ADB `am start -a android.intent.action.CALL` when connected
        if (adbManager.isConnected && SAFE_PHONE_REGEX.matches(phoneNumber)) {
            val adbRes = adbManager.executeShellCommand(
                "am start -a android.intent.action.CALL -d tel:$phoneNumber"
            )
            if (adbRes.isSuccess) {
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = "$displayName ko call laga raha hoon.",
                    logDetail = "Called $phoneNumber via Local ADB"
                )
            }
        }

        // Engine 2: Direct Android CALL_PHONE Intent (graceful ACTION_DIAL fallback if permission missing)
        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$phoneNumber")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            withContext(Dispatchers.Main) { appContext.startActivity(intent) }
            ActionExecutionResult(
                success = true,
                engineUsed = ExecutionEngine.ANDROID_INTENT,
                spokenReplyHindi = if (hasCallPerm) {
                    "$displayName ko call laga raha hoon."
                } else {
                    "Dialer khol diya hai — direct call ke liye Settings -> Control mein Call permission grant karein."
                },
                logDetail = "Started $action for $phoneNumber"
            )
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Call start nahi ho paya.",
                logDetail = e.message ?: "Call intent failed"
            )
        }
    }

    /**
     * Dispatches a SafetyGuard-confirmed SMS using `SmsManager`.
     */
    private suspend fun executeSendSms(targetRaw: String, messageBody: String): ActionExecutionResult {
        val resolved = resolvePhoneTarget(targetRaw)
            ?: return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "'$targetRaw' ka valid mobile number nahi mila.",
                logDetail = "Unresolved SMS target: $targetRaw"
            )

        val body = messageBody.trim()
        if (body.isEmpty()) {
            return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Message khaali hai, bhejne ke liye text boliye.",
                logDetail = "Empty SMS body"
            )
        }

        val (displayName, phoneNumber) = resolved
        val hasSmsPerm = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

        if (!hasSmsPerm) {
            // Graceful SMS compose fallback
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phoneNumber")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            withContext(Dispatchers.Main) { appContext.startActivity(smsIntent) }
            return ActionExecutionResult(
                success = true,
                engineUsed = ExecutionEngine.ANDROID_INTENT,
                spokenReplyHindi = "SMS permission off hai, isliye Messages app mein draft khol diya hai.",
                logDetail = "Opened SMS draft for $phoneNumber"
            )
        }

        return try {
            @Suppress("DEPRECATION")
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appContext.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(phoneNumber, null, body, null, null)
            }
            ActionExecutionResult(
                success = true,
                engineUsed = ExecutionEngine.ANDROID_INTENT,
                spokenReplyHindi = "$displayName ko SMS bhej diya hai.",
                logDetail = "SMS sent to $phoneNumber"
            )
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "SMS bhejne mein error aaya. SIM network check karein.",
                logDetail = e.message ?: "SmsManager error"
            )
        }
    }

    /**
     * Opens WhatsApp chat via `wa.me` deep link with pre-filled text, then uses Accessibility
     * to automatically tap the WhatsApp "Send" button when active.
     */
    private suspend fun executeSendWhatsApp(targetRaw: String, messageBody: String): ActionExecutionResult {
        val resolved = resolvePhoneTarget(targetRaw)
            ?: return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "WhatsApp ke liye number nahi mila. Clipboard par number copy karein ya contact ka naam boliye.",
                logDetail = "Unresolved WhatsApp target: $targetRaw"
            )

        val (displayName, phoneNumber) = resolved
        val waDigits = phoneNumber.filter(Char::isDigit)
        val encodedText = URLEncoder.encode(messageBody.ifBlank { "Hi" }, "UTF-8")
        val uri = Uri.parse("https://wa.me/$waDigits?text=$encodedText")

        val waIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            withContext(Dispatchers.Main) { appContext.startActivity(waIntent) }

            // Wait briefly for WhatsApp chat screen to open, then auto-tap Send via Accessibility
            if (GokuAccessibilityService.refreshActiveStatus(appContext)) {
                delay(1800L)
                val tapped = GokuAccessibilityService.clickNodeByText("Send") ||
                    GokuAccessibilityService.clickNodeByText("भेजें")
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.ACCESSIBILITY,
                    spokenReplyHindi = if (tapped) {
                        "$displayName ko WhatsApp message bhej diya hai."
                    } else {
                        "$displayName ka WhatsApp chat khol diya hai — Send button tap kar dijiye."
                    },
                    logDetail = "WhatsApp opened for $waDigits (autoSend=$tapped)"
                )
            }

            ActionExecutionResult(
                success = true,
                engineUsed = ExecutionEngine.ANDROID_INTENT,
                spokenReplyHindi = "$displayName ka WhatsApp chat message ke saath khol diya hai.",
                logDetail = "WhatsApp intent launched for $waDigits"
            )
        } catch (e: Exception) {
            ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "WhatsApp open nahi ho paya. Check karein ki WhatsApp installed hai.",
                logDetail = e.message ?: "WhatsApp launch error"
            )
        }
    }

    private suspend fun executeAdbShell(command: String): ActionExecutionResult {
        val result = adbManager.executeShellCommand(command)
        return result.fold(
            onSuccess = { output ->
                val preview = output.ifBlank { "(completed with no output)" }.take(220)
                ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = "ADB command chal gaya hai. Output transcript mein dekh sakte hain.",
                    logDetail = "ADB $ $command\n$preview"
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = "ADB shell run nahi hua: ${err.message ?: "Local ADB disconnected."}",
                    logDetail = err.message ?: "ADB failure"
                )
            }
        )
    }

    private suspend fun executeGhostToggle(
        settingRaw: String,
        enabled: Boolean?,
        numericValue: Int?
    ): ActionExecutionResult {
        val lower = settingRaw.lowercase()
        val type = when {
            lower.contains("wifi") || lower.contains("wi-fi") -> GhostSettingType.WIFI
            lower.contains("blue") || lower.contains("bt") -> GhostSettingType.BLUETOOTH
            lower.contains("bright") -> GhostSettingType.BRIGHTNESS
            lower.contains("dnd") || lower.contains("disturb") -> GhostSettingType.DND
            lower.contains("dark") || lower.contains("night") -> GhostSettingType.DARK_MODE
            else -> null
        } ?: return ActionExecutionResult(
            success = false,
            engineUsed = ExecutionEngine.NONE,
            spokenReplyHindi = "Yeh setting abhi Ghost Mode mein supported nahi hai.",
            logDetail = "Unsupported Ghost setting: $settingRaw"
        )

        val res = powerTools.applyGhostSetting(type, enabled, numericValue)
        return res.fold(
            onSuccess = { msg ->
                ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = msg,
                    logDetail = msg
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = "Ghost Mode ke liye Local ADB 127.0.0.1:5555 connected hona zaroori hai.",
                    logDetail = err.message ?: "Ghost Mode ADB required"
                )
            }
        )
    }

    private fun executeReadNotifications(): ActionExecutionResult {
        if (!GokuNotificationListener.refreshListenerStatus(appContext)) {
            return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Notification Access abhi OFF hai. Settings -> Control tab mein Notification Listener enable karein.",
                logDetail = "NotificationListener inactive"
            )
        }
        val digest = GokuNotificationListener.buildHindiDigest(limit = 5)
        return ActionExecutionResult(
            success = true,
            engineUsed = ExecutionEngine.ANDROID_INTENT,
            spokenReplyHindi = digest,
            logDetail = digest
        )
    }

    private fun executeReadScreen(): ActionExecutionResult {
        val summary = GokuAccessibilityService.readScreenSummary()
            ?: return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Screen read karne ke liye Accessibility Service ON karein.",
                logDetail = "Accessibility inactive"
            )
        val compact = summary.toCompactPromptText()
        return ActionExecutionResult(
            success = true,
            engineUsed = ExecutionEngine.ACCESSIBILITY,
            spokenReplyHindi = "Abhi screen par ${summary.packageName.substringAfterLast('.')} khula hai aur ${summary.clickableItems.size} buttons dikh rahe hain.",
            logDetail = compact
        )
    }

    private suspend fun executePhoneDoctor(): ActionExecutionResult {
        val reportRes = powerTools.runPhoneDoctorScan()
        return reportRes.fold(
            onSuccess = { report ->
                ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = report.summaryText,
                    logDetail = report.summaryText
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = "Phone Doctor scan ke liye Local ADB 127.0.0.1:5555 connect karein.",
                    logDetail = err.message ?: "Phone Doctor requires ADB"
                )
            }
        )
    }

    private suspend fun executeFreezeToggle(appQuery: String, freeze: Boolean): ActionExecutionResult {
        val pkg = if (LocalAdbManager.PACKAGE_NAME_REGEX.matches(appQuery) && appQuery.contains(".")) {
            appQuery
        } else {
            resolvePackageName(appQuery)
        } ?: return ActionExecutionResult(
            success = false,
            engineUsed = ExecutionEngine.NONE,
            spokenReplyHindi = "'$appQuery' app nahi mila.",
            logDetail = "App package not found: $appQuery"
        )

        val res = powerTools.setAppFrozen(pkg, freeze)
        return res.fold(
            onSuccess = { msg ->
                ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = if (freeze) "$appQuery ko freeze kar diya hai." else "$appQuery ko unfreeze kar diya hai.",
                    logDetail = msg
                )
            },
            onFailure = { err ->
                ActionExecutionResult(
                    success = false,
                    engineUsed = ExecutionEngine.NONE,
                    spokenReplyHindi = "App freeze/unfreeze fail hua: ${err.message ?: "Local ADB check karein."}",
                    logDetail = err.message ?: "Freeze error"
                )
            }
        )
    }

    private suspend fun executeGlobalNav(navTarget: String): ActionExecutionResult {
        val upper = navTarget.uppercase()
        if (adbManager.isConnected) {
            val keyEvent = when {
                upper.contains("HOME") -> "input keyevent 3"
                upper.contains("BACK") -> "input keyevent 4"
                upper.contains("NOTIF") -> "cmd statusbar expand-notifications"
                else -> null
            }
            if (keyEvent != null && adbManager.executeShellCommand(keyEvent).isSuccess) {
                return ActionExecutionResult(
                    success = true,
                    engineUsed = ExecutionEngine.LOCAL_ADB,
                    spokenReplyHindi = "Done!",
                    logDetail = "Global nav $upper via Local ADB"
                )
            }
        }

        if (!GokuAccessibilityService.refreshActiveStatus(appContext)) {
            return ActionExecutionResult(
                success = false,
                engineUsed = ExecutionEngine.NONE,
                spokenReplyHindi = "Navigation ke liye Local ADB ya Accessibility Service ON karein.",
                logDetail = "No engine for global nav"
            )
        }

        val ok = when {
            upper.contains("HOME") -> GokuAccessibilityService.performHomeAction()
            upper.contains("BACK") -> GokuAccessibilityService.performBackAction()
            upper.contains("NOTIF") -> GokuAccessibilityService.performNotificationsAction()
            else -> false
        }
        return ActionExecutionResult(
            success = ok,
            engineUsed = ExecutionEngine.ACCESSIBILITY,
            spokenReplyHindi = if (ok) "Done!" else "Navigation execute nahi ho paya.",
            logDetail = "Global nav $upper via Accessibility ($ok)"
        )
    }

    private suspend fun resolvePhoneTarget(rawTarget: String): Pair<String, String>? {
        val cleaned = rawTarget.trim()
        val lower = cleaned.lowercase()

        // Clipboard alias support ("copied number par WhatsApp karo")
        if (cleaned.isEmpty() || lower.contains("clip") || lower.contains("copy") || lower.contains("copied")) {
            val clipNum = contextBrain.getLatestClipboardPhoneNumber()
            if (!clipNum.isNullOrBlank()) {
                return "Copied Number ($clipNum)" to clipNum
            }
        }

        // Direct phone number in query
        val directNum = ContextBrain.extractPhoneNumber(cleaned)
        if (!directNum.isNullOrBlank()) {
            return directNum to directNum
        }

        // Phase 4 Personal Memory Vault alias lookup (e.g. "Papa" -> "+91...")
        val vaultMatch = memoryManager?.resolveSavedPersonContact(cleaned)
        if (vaultMatch != null) {
            return vaultMatch
        }

        // Contacts lookup
        return contextBrain.lookupContactPhone(cleaned)
    }

    private fun resolvePackageName(appQuery: String): String? {
        val cleaned = appQuery.trim()
        if (cleaned.isEmpty()) return null
        if (LocalAdbManager.PACKAGE_NAME_REGEX.matches(cleaned) && cleaned.contains(".")) {
            return cleaned
        }

        val pm = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(launcherIntent, 0)
        // Exact label match first
        apps.firstOrNull {
            it.loadLabel(pm).toString().equals(cleaned, ignoreCase = true)
        }?.let { return it.activityInfo.packageName }

        // Partial label or package match
        apps.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.contains(cleaned, ignoreCase = true) ||
                it.activityInfo.packageName.contains(cleaned, ignoreCase = true)
        }?.let { return it.activityInfo.packageName }

        return null
    }

    companion object {
        private val SAFE_PHONE_REGEX = Regex("^\\+?\\d{7,15}$")
    }
}
