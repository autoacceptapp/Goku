package com.arif.goku.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class OverlayOrbState(val label: String, val glowHex: Int) {
    IDLE("GOKU", Color.parseColor("#00E5FF")),
    LISTENING("MIC", Color.parseColor("#00E676")),
    ACTING("ACT", Color.parseColor("#FF9100")),
    PILOT("PILOT", Color.parseColor("#AA66FF"))
}

enum class OverlayCommand {
    TAP_ORB,
    EMERGENCY_STOP
}

data class MistakeOverlayAlert(
    val signature: String,
    val title: String,
    val reasonHindi: String,
    val targetPackage: String
)

data class OverlayMistakeDecision(
    val signature: String,
    val allowOnce: Boolean
)

/**
 * Shared reactive bridge between GokuDashboardViewModel, Mistake Blocker, Background Queue,
 * and GokuOverlayService.
 */
object OverlayBridge {
    private val _isOverlayRunning = MutableStateFlow(false)
    val isOverlayRunning: StateFlow<Boolean> = _isOverlayRunning.asStateFlow()

    private val _orbState = MutableStateFlow(OverlayOrbState.IDLE)
    val orbState: StateFlow<OverlayOrbState> = _orbState.asStateFlow()

    private val _queueProgressLabel = MutableStateFlow("")
    val queueProgressLabel: StateFlow<String> = _queueProgressLabel.asStateFlow()

    private val _activeMistakeAlert = MutableStateFlow<MistakeOverlayAlert?>(null)
    val activeMistakeAlert: StateFlow<MistakeOverlayAlert?> = _activeMistakeAlert.asStateFlow()

    private val _commands = MutableSharedFlow<OverlayCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<OverlayCommand> = _commands.asSharedFlow()

    private val _mistakeDecisions = MutableSharedFlow<OverlayMistakeDecision>(extraBufferCapacity = 8)
    val mistakeDecisions: SharedFlow<OverlayMistakeDecision> = _mistakeDecisions.asSharedFlow()

    fun setRunning(running: Boolean) {
        _isOverlayRunning.value = running
    }

    fun updateState(state: OverlayOrbState) {
        _orbState.value = state
    }

    fun updateQueueProgress(label: String) {
        _queueProgressLabel.value = label.trim()
    }

    fun showMistakeWarning(alert: MistakeOverlayAlert) {
        _activeMistakeAlert.value = alert
    }

    fun dismissMistakeWarning() {
        _activeMistakeAlert.value = null
    }

    fun emitMistakeDecision(signature: String, allowOnce: Boolean) {
        _activeMistakeAlert.value = null
        _mistakeDecisions.tryEmit(OverlayMistakeDecision(signature = signature, allowOnce = allowOnce))
    }

    fun emitCommand(cmd: OverlayCommand) {
        _commands.tryEmit(cmd)
    }
}

/**
 * Phase 2 Floating System Overlay Orb Foreground Service (`SYSTEM_ALERT_WINDOW`).
 *
 * Renders a compact, draggable glowing Goku Orb over any app with live state color
 * (Idle / Listening / Acting) and an always-reachable 1-tap crimson STOP kill switch.
 */
class GokuOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var overlayContainer: LinearLayout? = null
    private var orbLabelView: TextView? = null
    private var orbBgDrawable: GradientDrawable? = null
    private var queueBadgeView: TextView? = null
    private var mistakeAlertView: LinearLayout? = null
    private var observersStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_OVERLAY) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            OverlayBridge.setRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        attachOverlayViewIfNeeded()
        OverlayBridge.setRunning(true)

        if (!observersStarted) {
            observersStarted = true
            serviceScope.launch {
                OverlayBridge.orbState.collect { state ->
                    updateOrbVisual(state)
                }
            }
            serviceScope.launch {
                OverlayBridge.queueProgressLabel.collect { label ->
                    updateQueueBadge(label)
                }
            }
            serviceScope.launch {
                OverlayBridge.activeMistakeAlert.collect { alert ->
                    renderMistakeWarningOverlay(alert)
                }
            }
        }

        return START_STICKY
    }

    private fun attachOverlayViewIfNeeded() {
        if (overlayContainer != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(240)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val orbSize = dp(58)
        val orbDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E6070B14"))
            setStroke(dp(3), OverlayOrbState.IDLE.glowHex)
        }
        orbBgDrawable = orbDrawable

        val orbView = TextView(this).apply {
            text = OverlayOrbState.IDLE.label
            setTextColor(OverlayOrbState.IDLE.glowHex)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            background = orbDrawable
            elevation = dp(8).toFloat()
            contentDescription = "Goku Floating Orb — tap to talk or drag to move"
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize)
        }
        orbLabelView = orbView

        // Drag + tap handler on the mini glowing orb
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        orbView.setOnTouchListener { _: View, event: MotionEvent ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragging = true
                    }
                    windowParams.x = initialX + dx
                    windowParams.y = initialY + dy
                    try {
                        wm.updateViewLayout(root, windowParams)
                    } catch (_: Exception) {
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        OverlayBridge.emitCommand(OverlayCommand.TAP_ORB)
                        val openIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(EXTRA_TRIGGER_VOICE_LISTEN, true)
                        }
                        startActivity(openIntent)
                    }
                    true
                }

                else -> false
            }
        }

        // 1-tap crimson Emergency STOP pill right below the mini orb
        val stopDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#E6FF3B5C"))
            setStroke(dp(1), Color.WHITE)
        }

        val stopButton = TextView(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            background = stopDrawable
            setPadding(dp(10), dp(4), dp(10), dp(4))
            contentDescription = "Emergency Goku Stop kill switch"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                OverlayBridge.emitCommand(OverlayCommand.EMERGENCY_STOP)
            }
        }

        // Phase 5 Background Queue progress pill (shown silently while background tasks run)
        val queueBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#E60F172A"))
            setStroke(dp(1), Color.parseColor("#00E5FF"))
        }
        val queueBadge = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            gravity = Gravity.CENTER
            background = queueBg
            setPadding(dp(8), dp(3), dp(8), dp(3))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
        }
        queueBadgeView = queueBadge

        root.addView(orbView)
        root.addView(stopButton)
        root.addView(queueBadge)

        try {
            wm.addView(root, windowParams)
            overlayContainer = root
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun updateOrbVisual(state: OverlayOrbState) {
        orbLabelView?.apply {
            text = state.label
            setTextColor(state.glowHex)
        }
        orbBgDrawable?.setStroke(dp(3), state.glowHex)
    }

    private fun updateQueueBadge(label: String) {
        queueBadgeView?.apply {
            if (label.isBlank()) {
                visibility = View.GONE
            } else {
                text = label
                visibility = View.VISIBLE
            }
        }
    }

    /**
     * Phase 5 Live Mistake Blocker warning overlay displayed over risky taps/dialogs
     * with immediate "Rok Do (Cancel)" and "Allow Once" actions.
     */
    private fun renderMistakeWarningOverlay(alert: MistakeOverlayAlert?) {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        removeMistakeOverlayView(wm)
        if (alert == null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            x = 0
            y = 0
        }

        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(Color.parseColor("#F50B1120"))
            setStroke(dp(2), Color.parseColor("#FF3B5C"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = cardBg
            elevation = dp(16).toFloat()
        }

        val titleView = TextView(this).apply {
            text = "GOKU MISTAKE BLOCKER — ${alert.title}"
            setTextColor(Color.parseColor("#FF3B5C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val bodyView = TextView(this).apply {
            text = alert.reasonHindi
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(8), 0, dp(14))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#FF3B5C"))
        }
        val cancelBtn = TextView(this).apply {
            text = "ROK DO (CANCEL)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = cancelBg
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                OverlayBridge.emitMistakeDecision(alert.signature, allowOnce = false)
            }
        }

        val allowBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#1E293B"))
            setStroke(dp(1), Color.parseColor("#FF9100"))
        }
        val allowBtn = TextView(this).apply {
            text = "ALLOW ONCE"
            setTextColor(Color.parseColor("#FF9100"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = allowBg
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(10)
            }
            setOnClickListener {
                OverlayBridge.emitMistakeDecision(alert.signature, allowOnce = true)
            }
        }

        btnRow.addView(cancelBtn)
        btnRow.addView(allowBtn)

        container.addView(titleView)
        container.addView(bodyView)
        container.addView(btnRow)

        val outerWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            addView(
                container,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        try {
            wm.addView(outerWrapper, params)
            mistakeAlertView = outerWrapper
        } catch (_: Exception) {
        }
    }

    private fun removeMistakeOverlayView(wm: WindowManager?) {
        mistakeAlertView?.let { view ->
            try {
                wm?.removeView(view)
            } catch (_: Exception) {
            }
        }
        mistakeAlertView = null
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GokuAgent Floating Orb",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the draggable Goku Orb and 1-tap STOP kill switch active over other apps."
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, GokuOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GokuAgent Orb Active")
            .setContentText("Draggable voice orb & 1-tap STOP kill switch running.")
            .setContentIntent(openPending)
            .addAction(0, "Hide Orb", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        removeMistakeOverlayView(windowManager)
        overlayContainer?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        overlayContainer = null
        OverlayBridge.setRunning(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "goku_overlay_channel"
        private const val NOTIFICATION_ID = 2045
        const val ACTION_STOP_OVERLAY = "com.arif.goku.action.STOP_OVERLAY"
        const val EXTRA_TRIGGER_VOICE_LISTEN = "com.arif.goku.extra.TRIGGER_LISTEN"

        fun start(context: Context): Boolean {
            if (!Settings.canDrawOverlays(context)) return false
            val intent = Intent(context, GokuOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun stop(context: Context) {
            val intent = Intent(context, GokuOverlayService::class.java).apply {
                action = ACTION_STOP_OVERLAY
            }
            context.startService(intent)
        }
    }
}
