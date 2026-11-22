package com.arif.goku.brain

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.arif.goku.accessibility.GokuAccessibilityService
import com.arif.goku.adb.LocalAdbManager
import com.arif.goku.notifications.GokuNotificationListener
import java.io.File

data class DownloadFileInfo(
    val name: String,
    val sizeKb: Long,
    val modifiedMs: Long
)

data class LiveContextSnapshot(
    val clipboardText: String = "",
    val clipboardPhoneNumber: String? = null,
    val recentDownloads: List<String> = emptyList(),
    val screenSummary: String = "",
    val recentNotificationCount: Int = 0,
    val adbConnected: Boolean = false,
    val accessibilityActive: Boolean = false
) {
    fun toPromptBlock(): String = buildString {
        appendLine("[DEVICE CONTEXT]")
        appendLine("Local ADB (127.0.0.1:5555): ${if (adbConnected) "CONNECTED" else "DISCONNECTED"}")
        appendLine("Accessibility Engine: ${if (accessibilityActive) "ACTIVE" else "INACTIVE"}")
        if (clipboardText.isNotBlank()) {
            appendLine("Latest Clipboard: \"${clipboardText.take(120)}\"")
        }
        if (!clipboardPhoneNumber.isNullOrBlank()) {
            appendLine("Detected Clipboard Phone Number: $clipboardPhoneNumber")
        }
        if (recentDownloads.isNotEmpty()) {
            appendLine("Recent Downloads: ${recentDownloads.take(5).joinToString(", ")}")
        }
        appendLine("Captured Unread Notifications: $recentNotificationCount")
        if (screenSummary.isNotBlank()) {
            appendLine(screenSummary.take(650))
        }
    }.trim()
}

/**
 * Provides GokuAgent with live awareness of Clipboard contents, phone numbers, Contacts lookup,
 * recent `/sdcard/Download` files, active screen tree, and notifications.
 */
class ContextBrain(private val context: Context) {

    private val appContext = context.applicationContext

    fun getLatestClipboardText(): String {
        return try {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return ""
            val clip = cm.primaryClip ?: return ""
            if (clip.itemCount == 0) return ""
            clip.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun getLatestClipboardPhoneNumber(): String? {
        return extractPhoneNumber(getLatestClipboardText())
    }

    /**
     * Resolves a contact name to a phone number when `READ_CONTACTS` permission is granted.
     */
    fun lookupContactPhone(nameQuery: String): Pair<String, String>? {
        val cleaned = nameQuery.trim()
        if (cleaned.isEmpty()) return null
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }

        return try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$cleaned%")

            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0).orEmpty()
                    val num = normalizePhoneNumber(cursor.getString(1).orEmpty())
                    if (num.isNotBlank()) name to num else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Lists recent files in `/sdcard/Download` using scoped `MediaStore.Downloads` first,
     * falling back to direct `DIRECTORY_DOWNLOADS` directory scan or Local ADB listing.
     */
    suspend fun listRecentDownloads(
        limit: Int = 8,
        adbManager: LocalAdbManager? = null
    ): List<DownloadFileInfo> {
        val items = mutableListOf<DownloadFileInfo>()

        // 1. Scoped MediaStore.Downloads (API 29+ safe without broad storage permissions)
        try {
            val projection = arrayOf(
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            )
            appContext.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Downloads.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext() && items.size < limit) {
                    val name = cursor.getString(0).orEmpty()
                    val size = cursor.getLong(1) / 1024L
                    val modified = cursor.getLong(2) * 1000L
                    if (name.isNotBlank()) {
                        items.add(DownloadFileInfo(name, size, modified))
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (items.isNotEmpty()) return items

        // 2. Direct public Downloads folder scan (works on many Android ROMs for readable files)
        try {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (dir != null && dir.exists() && dir.canRead()) {
                dir.listFiles()
                    ?.filter(File::isFile)
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(limit)
                    ?.forEach { file ->
                        items.add(
                            DownloadFileInfo(
                                name = file.name,
                                sizeKb = file.length() / 1024L,
                                modifiedMs = file.lastModified()
                            )
                        )
                    }
            }
        } catch (_: Exception) {
        }

        if (items.isNotEmpty()) return items

        // 3. Fallback via Local ADB (`ls -t /sdcard/Download`) when connected
        if (adbManager?.isConnected == true) {
            adbManager.executeShellCommand("ls -t /sdcard/Download | head -n $limit")
                .onSuccess { output ->
                    output.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.startsWith("ls:") }
                        .take(limit)
                        .forEach { fileName ->
                            items.add(DownloadFileInfo(fileName, 0L, System.currentTimeMillis()))
                        }
                }
        }

        return items
    }

    suspend fun buildSnapshot(adbManager: LocalAdbManager): LiveContextSnapshot {
        val clip = getLatestClipboardText()
        val clipPhone = extractPhoneNumber(clip)
        val downloads = listRecentDownloads(limit = 5, adbManager = adbManager).map { it.name }
        val screen = GokuAccessibilityService.readScreenSummary()?.toCompactPromptText().orEmpty()
        val notifCount = GokuNotificationListener.recentNotifications.value.size

        return LiveContextSnapshot(
            clipboardText = clip,
            clipboardPhoneNumber = clipPhone,
            recentDownloads = downloads,
            screenSummary = screen,
            recentNotificationCount = notifCount,
            adbConnected = adbManager.isConnected,
            accessibilityActive = GokuAccessibilityService.isServiceActive.value
        )
    }

    companion object {
        private val PHONE_CANDIDATE_REGEX = Regex("(?:\\+?91[\\s-]?)?[6-9]\\d{9}|\\+?\\d[\\d\\s-]{8,14}\\d")

        fun extractPhoneNumber(text: String): String? {
            if (text.isBlank()) return null
            val match = PHONE_CANDIDATE_REGEX.find(text) ?: return null
            return normalizePhoneNumber(match.value).takeIf { it.length >= 10 }
        }

        fun normalizePhoneNumber(raw: String): String {
            val cleaned = raw.trim().replace(Regex("[^+\\d]"), "")
            return when {
                cleaned.startsWith("+") -> cleaned
                cleaned.length == 10 && cleaned.first() in '6'..'9' -> "+91$cleaned"
                cleaned.length == 12 && cleaned.startsWith("91") -> "+$cleaned"
                else -> cleaned
            }
        }
    }
}
