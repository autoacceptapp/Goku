package com.arif.goku.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import com.arif.goku.system.FocusShieldRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class NotificationCategory(val label: String) {
    WHATSAPP("WhatsApp"),
    SMS("SMS"),
    MISSED_CALL("Missed Call"),
    IMPORTANT("App")
}

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val category: NotificationCategory,
    val title: String,
    val text: String,
    val postTime: Long
) {
    fun formattedTime(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postTime))
}

/**
 * Captures important incoming notifications (WhatsApp, SMS, Missed Calls, key apps),
 * filters promotional spam and ongoing system noise, and formats spoken Hindi/Hinglish digests.
 */
class GokuNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isConnected.value = true
        try {
            activeNotifications?.forEach { captureIfRelevant(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        captureIfRelevant(sbn)
    }

    private fun captureIfRelevant(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == applicationContext.packageName) return
        if (sbn.isOngoing || pkg in IGNORED_SYSTEM_PACKAGES) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()

        val category = classify(pkg, title, text)
        val isCallOrImportant = category == NotificationCategory.MISSED_CALL ||
            pkg in DIALER_PACKAGES ||
            sbn.notification.category == Notification.CATEGORY_CALL ||
            sbn.notification.category == Notification.CATEGORY_ALARM

        // Phase 3 Deep Focus Shield: silently dismiss distraction notifications while allowing calls
        if (FocusShieldRuntime.shouldMutePackage(pkg, isCallOrImportant)) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
            }
            FocusShieldRuntime.recordMutedNotification()
            return
        }

        if (title.isBlank() && text.isBlank()) return
        if (isSpamOrNoise(title, text)) return
        val item = CapturedNotification(
            key = "${sbn.key}_${sbn.postTime}",
            packageName = pkg,
            category = category,
            title = title.take(80),
            text = text.take(220),
            postTime = sbn.postTime
        )

        val current = _recentNotifications.value.toMutableList()
        // Deduplicate identical consecutive title+text from the same app
        current.removeAll { it.packageName == item.packageName && it.title == item.title && it.text == item.text }
        current.add(0, item)
        _recentNotifications.value = current.take(MAX_RECENT_NOTIFICATIONS)
    }

    private fun classify(pkg: String, title: String, text: String): NotificationCategory {
        val combined = "$title $text".lowercase()
        return when {
            pkg in WHATSAPP_PACKAGES -> NotificationCategory.WHATSAPP
            pkg in SMS_PACKAGES -> NotificationCategory.SMS
            pkg in DIALER_PACKAGES || combined.contains("missed call") || combined.contains("मिस्ड कॉल") ->
                NotificationCategory.MISSED_CALL
            else -> NotificationCategory.IMPORTANT
        }
    }

    private fun isSpamOrNoise(title: String, text: String): Boolean {
        val combined = "$title $text".lowercase()
        if (combined.contains("checking for new messages") || combined.contains("whatsapp web is currently active")) {
            return true
        }
        return SPAM_KEYWORDS.any { combined.contains(it) }
    }

    companion object {
        private const val MAX_RECENT_NOTIFICATIONS = 25

        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val SMS_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging",
            "com.oneplus.mms"
        )
        private val DIALER_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.dialer",
            "com.samsung.android.dialer",
            "com.android.server.telecom"
        )
        private val IGNORED_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.providers.downloads",
            "com.google.android.gms"
        )
        private val SPAM_KEYWORDS = listOf(
            "flat 50% off",
            "mega sale",
            "limited time offer",
            "claim your bonus",
            "win cash",
            "rummy",
            "fantasy cricket",
            "loan approved",
            "pre-approved loan",
            "discount coupon",
            "flash sale",
            "unsubscribe"
        )

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _recentNotifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val recentNotifications: StateFlow<List<CapturedNotification>> = _recentNotifications.asStateFlow()

        fun refreshListenerStatus(context: Context): Boolean {
            val enabled = isListenerEnabled(context)
            _isConnected.value = enabled
            return enabled
        }

        fun isListenerEnabled(context: Context): Boolean {
            val expected = ComponentName(context, GokuNotificationListener::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            while (splitter.hasNext()) {
                val comp = ComponentName.unflattenFromString(splitter.next())
                if (comp == expected) return true
            }
            return false
        }

        fun openNotificationListenerSettings(context: Context): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun clearCaptured() {
            _recentNotifications.value = emptyList()
        }

        /**
         * Formats the most recent captured notifications into a clean Hindi/Hinglish spoken readout.
         */
        fun buildHindiDigest(limit: Int = 5): String {
            val list = _recentNotifications.value.take(limit)
            if (list.isEmpty()) {
                return "Abhi koi naya important notification nahi aaya hai."
            }
            val sb = StringBuilder("Aapke aakhiri ${list.size} important notifications: ")
            list.forEachIndexed { idx, item ->
                sb.append("${idx + 1}. ${item.category.label} — ${item.title}")
                if (item.text.isNotBlank()) {
                    sb.append(": ${item.text.take(90)}")
                }
                sb.append(". ")
            }
            return sb.toString().trim()
        }
    }
}
