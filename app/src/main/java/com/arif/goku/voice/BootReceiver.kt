package com.arif.goku.voice

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arif.goku.data.SecurePrefs
import com.example.MainActivity
import com.example.R

/**
 * Restarts the 24x7 "Hey Goku" WakeWordService after device reboot if enabled by the user.
 *
 * On Android 14/15 where direct background microphone Foreground Service launch from BOOT_COMPLETED
 * may be restricted by OS policy, gracefully falls back to a 1-tap notification to resume the mic.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val appContext = context.applicationContext
        val prefs = try {
            SecurePrefs(appContext)
        } catch (_: Exception) {
            return
        }

        if (!prefs.isWakeWordEnabled()) return

        val hasMic = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        val started = WakeWordService.start(appContext)
        if (!started) {
            postResumeWakeNotification(appContext)
        }
    }

    private fun postResumeWakeNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "GokuAgent Startup",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "One-tap reminder to resume 24x7 Hey Goku mic after reboot."
            }
            nm.createNotificationChannel(channel)

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pending = PendingIntent.getActivity(
                context,
                3050,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Resume 24x7 \"Hey Goku\" Mic")
                .setContentText("Phone reboot complete. Tap once to reactivate hands-free voice wake.")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(BOOT_NOTIF_ID, notification)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val BOOT_CHANNEL_ID = "goku_boot_resume_channel"
        private const val BOOT_NOTIF_ID = 3051
    }
}
