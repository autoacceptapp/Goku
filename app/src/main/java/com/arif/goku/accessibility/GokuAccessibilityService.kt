package com.arif.goku.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

data class AccessibilityScreenSignal(
    val eventType: Int,
    val packageName: String,
    val clickedOrFocusedText: String,
    val visibleTexts: List<String>,
    val clickableItems: List<String>,
    val timestampMs: Long = System.currentTimeMillis()
)

data class UiNodeSnapshot(
    val label: String,
    val hint: String,
    val centerX: Int,
    val centerY: Int,
    val isClickable: Boolean,
    val isEditable: Boolean
)

data class ScreenSummary(
    val packageName: String,
    val visibleTexts: List<String>,
    val clickableItems: List<String>,
    val inputFields: List<String>
) {
    fun toCompactPromptText(): String = buildString {
        appendLine("Active App: ${packageName.ifBlank { "unknown" }}")
        if (clickableItems.isNotEmpty()) {
            appendLine("Clickable Buttons: ${clickableItems.take(18).joinToString(" | ")}")
        }
        if (inputFields.isNotEmpty()) {
            appendLine("Input Fields: ${inputFields.take(6).joinToString(" | ")}")
        }
        if (visibleTexts.isNotEmpty()) {
            append("Visible Text: ${visibleTexts.take(20).joinToString(" · ")}")
        }
    }.trim()
}

/**
 * Phase 2 Full AccessibilityService engine for GokuAgent.
 *
 * Capabilities:
 * - Global actions: Home, Back, Notifications.
 * - Full `AccessibilityNodeInfo` tree traversal (`readScreenSummary`).
 * - Smart UI automation: `clickNodeByText`, `typeIntoFocusedOrMatchingField`, `scroll`.
 * - Precision gestures via `dispatchGesture`: `tapAt(x, y)` and `swipe(...)`.
 */
class GokuAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        _isServiceActive.value = true
        _lastActionStatus.value = "GokuAccessibilityService connected (Phase 2 Full Engine)."
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) {
            _currentForegroundPackage.value = pkg
        }

        // Phase 5 Live Mistake Blocker real-time event hook (throttled for content updates, immediate for clicks)
        val eventType = event.eventType
        val isHighPriority = eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val now = System.currentTimeMillis()
        if (!isHighPriority && now - lastSignalEmitMs < 900L) return
        lastSignalEmitMs = now

        val eventTexts = buildList {
            event.text?.forEach { cs ->
                val s = cs?.toString()?.trim().orEmpty()
                if (s.isNotBlank()) add(s)
            }
            event.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            event.source?.let { src ->
                val srcLabel = extractNodeLabel(src)
                if (srcLabel.isNotBlank()) add(srcLabel)
            }
        }.distinct().joinToString(" ").take(180)

        val summary = try {
            readScreenSummary()
        } catch (_: Exception) {
            null
        }

        _screenSignals.tryEmit(
            AccessibilityScreenSignal(
                eventType = eventType,
                packageName = pkg.ifBlank { summary?.packageName.orEmpty() },
                clickedOrFocusedText = eventTexts,
                visibleTexts = summary?.visibleTexts.orEmpty().take(24),
                clickableItems = summary?.clickableItems.orEmpty().take(18),
                timestampMs = now
            )
        )
    }

    override fun onInterrupt() {
        _lastActionStatus.value = "Accessibility service interrupted by system."
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instanceRef = WeakReference(null)
        _isServiceActive.value = false
        _lastActionStatus.value = "GokuAccessibilityService disconnected."
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instanceRef = WeakReference(null)
        _isServiceActive.value = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<GokuAccessibilityService> = WeakReference(null)

        @Volatile
        private var lastSignalEmitMs: Long = 0L

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _lastActionStatus = MutableStateFlow("Not enabled")
        val lastActionStatus: StateFlow<String> = _lastActionStatus.asStateFlow()

        private val _currentForegroundPackage = MutableStateFlow("")
        val currentForegroundPackage: StateFlow<String> = _currentForegroundPackage.asStateFlow()

        private val _screenSignals = MutableSharedFlow<AccessibilityScreenSignal>(extraBufferCapacity = 16)
        val screenSignals: SharedFlow<AccessibilityScreenSignal> = _screenSignals.asSharedFlow()

        fun refreshActiveStatus(context: Context): Boolean {
            val liveBound = instanceRef.get() != null
            val enabledInSettings = isEnabledInSystemSettings(context)
            val active = liveBound || enabledInSettings
            _isServiceActive.value = active
            if (active && _lastActionStatus.value == "Not enabled") {
                _lastActionStatus.value = "Accessibility Service active."
            } else if (!active) {
                _lastActionStatus.value = "Accessibility Service inactive."
            }
            return active
        }

        private fun isEnabledInSystemSettings(context: Context): Boolean {
            val expectedComponent = ComponentName(context, GokuAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val componentNameString = splitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent == expectedComponent) {
                    return true
                }
            }
            return false
        }

        fun openAccessibilitySettings(context: Context): Boolean {
            return try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun performHomeAction(): Boolean {
            val service = instanceRef.get() ?: return false
            val ok = service.performGlobalAction(GLOBAL_ACTION_HOME)
            _lastActionStatus.value = if (ok) "Global action: HOME executed" else "HOME action failed"
            return ok
        }

        fun performBackAction(): Boolean {
            val service = instanceRef.get() ?: return false
            val ok = service.performGlobalAction(GLOBAL_ACTION_BACK)
            _lastActionStatus.value = if (ok) "Global action: BACK executed" else "BACK action failed"
            return ok
        }

        fun performNotificationsAction(): Boolean {
            val service = instanceRef.get() ?: return false
            val ok = service.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            _lastActionStatus.value =
                if (ok) "Global action: NOTIFICATIONS executed" else "NOTIFICATIONS action failed"
            return ok
        }

        /**
         * Traverses the active window's `AccessibilityNodeInfo` tree to build a structured summary
         * of package name, visible texts, clickable buttons, and editable input fields.
         */
        fun readScreenSummary(): ScreenSummary? {
            val service = instanceRef.get() ?: return null
            val root = service.rootInActiveWindow ?: return null

            val pkgName = root.packageName?.toString().orEmpty()
                .ifBlank { _currentForegroundPackage.value }
            val visibleTexts = LinkedHashSet<String>()
            val clickableItems = LinkedHashSet<String>()
            val inputFields = LinkedHashSet<String>()

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
                val node = queue.removeFirst()
                visited++

                if (node.isVisibleToUser) {
                    val label = extractNodeLabel(node)
                    if (node.isEditable) {
                        val hint = node.hintText?.toString()?.trim().orEmpty()
                        val fieldDesc = label.ifBlank { hint }.ifBlank { "Text Field" }
                        inputFields.add(fieldDesc.take(60))
                    } else if (node.isClickable && label.isNotBlank()) {
                        clickableItems.add(label.take(60))
                    } else if (label.isNotBlank()) {
                        visibleTexts.add(label.take(80))
                    }
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }

            val summary = ScreenSummary(
                packageName = pkgName,
                visibleTexts = visibleTexts.toList(),
                clickableItems = clickableItems.toList(),
                inputFields = inputFields.toList()
            )
            _lastActionStatus.value = "Read screen: $pkgName (${clickableItems.size} buttons, ${inputFields.size} inputs)"
            return summary
        }

        /**
         * Phase 5: Reads visible UI nodes along with screen center coordinates for the Universal App Pilot
         * and Form Auto-Fill Co-Pilot.
         */
        fun readDetailedUiNodes(limit: Int = 55): List<UiNodeSnapshot> {
            val service = instanceRef.get() ?: return emptyList()
            val root = service.rootInActiveWindow ?: return emptyList()

            val results = mutableListOf<UiNodeSnapshot>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < MAX_TREE_NODES && results.size < limit) {
                val node = queue.removeFirst()
                visited++
                if (node.isVisibleToUser) {
                    val label = extractNodeLabel(node).take(60)
                    val hint = node.hintText?.toString()?.trim().orEmpty().take(50)
                    if (label.isNotBlank() || hint.isNotBlank() || node.isEditable) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (!rect.isEmpty) {
                            results.add(
                                UiNodeSnapshot(
                                    label = label,
                                    hint = hint,
                                    centerX = rect.centerX(),
                                    centerY = rect.centerY(),
                                    isClickable = node.isClickable,
                                    isEditable = node.isEditable
                                )
                            )
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return results
        }

        /**
         * Phase 5 Screen Vision capture via Android 11+ `AccessibilityService.takeScreenshot()`.
         * Downscales and compresses to compact JPEG bytes ready for Gemini Vision multimodal API.
         */
        suspend fun captureScreenJpegBytes(maxWidth: Int = 720, quality: Int = 68): ByteArray? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
            val service = instanceRef.get() ?: return null

            val rawBitmap: Bitmap? = withTimeoutOrNull(3_500L) {
                suspendCancellableCoroutine { cont ->
                    try {
                        service.takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            service.mainExecutor,
                            object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    var softBitmap: Bitmap? = null
                                    val buffer = screenshot.hardwareBuffer
                                    try {
                                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                                        softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                        hwBitmap?.recycle()
                                    } catch (_: Throwable) {
                                        softBitmap = null
                                    } finally {
                                        try {
                                            buffer.close()
                                        } catch (_: Throwable) {
                                        }
                                    }
                                    if (cont.isActive) cont.resume(softBitmap)
                                }

                                override fun onFailure(errorCode: Int) {
                                    _lastActionStatus.value = "Screenshot failed (code $errorCode)"
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }

            if (rawBitmap == null) return null

            return withContext(Dispatchers.Default) {
                try {
                    val scaled = if (rawBitmap.width > maxWidth && rawBitmap.width > 0) {
                        val scale = maxWidth.toFloat() / rawBitmap.width.toFloat()
                        val targetH = (rawBitmap.height * scale).toInt().coerceAtLeast(1)
                        Bitmap.createScaledBitmap(rawBitmap, maxWidth, targetH, true)
                    } else {
                        rawBitmap
                    }
                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(40, 90), baos)
                    if (scaled !== rawBitmap) scaled.recycle()
                    rawBitmap.recycle()
                    _lastActionStatus.value = "Screen Vision captured (${baos.size() / 1024} KB JPEG)"
                    baos.toByteArray()
                } catch (_: Throwable) {
                    try {
                        rawBitmap.recycle()
                    } catch (_: Throwable) {
                    }
                    null
                }
            }
        }

        /**
         * Finds a visible UI node matching [query] (by text or contentDescription), climbs to a
         * clickable parent if necessary, and performs a click (falling back to a coordinate tap).
         */
        fun clickNodeByText(query: String): Boolean {
            val cleaned = query.trim()
            if (cleaned.isEmpty()) return false
            val service = instanceRef.get() ?: return false
            val root = service.rootInActiveWindow ?: return false

            val candidates = findMatchingNodes(root, cleaned)
            for (node in candidates) {
                // Direct or ancestor click
                var target: AccessibilityNodeInfo? = node
                var depth = 0
                while (target != null && depth < 5) {
                    if (target.isClickable && target.isEnabled) {
                        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            _lastActionStatus.value = "Clicked '$cleaned'"
                            return true
                        }
                    }
                    target = target.parent
                    depth++
                }

                // Fallback: gesture tap at node's center bounds
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.centerX() > 0 && rect.centerY() > 0) {
                    if (tapAt(rect.centerX().toFloat(), rect.centerY().toFloat())) {
                        _lastActionStatus.value = "Tapped '$cleaned' at (${rect.centerX()}, ${rect.centerY()})"
                        return true
                    }
                }
            }

            _lastActionStatus.value = "Could not find clickable item '$cleaned'"
            return false
        }

        /**
         * Types [text] into the currently focused editable field or the editable field whose
         * text, hint, or contentDescription matches [query].
         */
        fun typeIntoFocusedOrMatchingField(query: String?, text: String): Boolean {
            val service = instanceRef.get() ?: return false
            val root = service.rootInActiveWindow ?: return false

            var targetNode: AccessibilityNodeInfo? = null

            if (!query.isNullOrBlank()) {
                targetNode = findEditableNodes(root).firstOrNull { node ->
                    val label = extractNodeLabel(node)
                    val hint = node.hintText?.toString().orEmpty()
                    label.contains(query, ignoreCase = true) || hint.contains(query, ignoreCase = true)
                }
            }

            if (targetNode == null) {
                targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.takeIf { it.isEditable }
            }

            if (targetNode == null) {
                targetNode = findEditableNodes(root).firstOrNull { it.isVisibleToUser && it.isEnabled }
            }

            val node = targetNode ?: run {
                _lastActionStatus.value = "No editable input field found on screen."
                return false
            }

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            _lastActionStatus.value = if (ok) "Typed text into input field" else "Failed to set text"
            return ok
        }

        /**
         * Scrolls the foreground screen forward (down) or backward (up).
         * Uses `ACTION_SCROLL_FORWARD/BACKWARD` first and falls back to a vertical swipe gesture.
         */
        fun scroll(forward: Boolean): Boolean {
            val service = instanceRef.get() ?: return false
            val root = service.rootInActiveWindow
            if (root != null) {
                val scrollable = findScrollableNode(root)
                if (scrollable != null) {
                    val action = if (forward) {
                        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    } else {
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    }
                    if (scrollable.performAction(action)) {
                        _lastActionStatus.value = if (forward) "Scrolled down" else "Scrolled up"
                        return true
                    }
                }
            }

            // Gesture swipe fallback using screen metrics
            val dm = service.resources.displayMetrics
            val x = dm.widthPixels * 0.5f
            val startY = if (forward) dm.heightPixels * 0.72f else dm.heightPixels * 0.28f
            val endY = if (forward) dm.heightPixels * 0.28f else dm.heightPixels * 0.72f
            return swipe(x, startY, x, endY, 320L)
        }

        /**
         * Dispatches a single tap gesture at exact screen coordinates `(x, y)`.
         */
        fun tapAt(x: Float, y: Float): Boolean {
            val service = instanceRef.get() ?: return false
            if (x < 0f || y < 0f) return false
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = service.dispatchGesture(gesture, null, null)
            _lastActionStatus.value = if (ok) "Tap dispatched at (${x.toInt()}, ${y.toInt()})" else "Tap gesture failed"
            return ok
        }

        /**
         * Dispatches a smooth swipe gesture from `(startX, startY)` to `(endX, endY)`.
         */
        fun swipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long = 300L
        ): Boolean {
            val service = instanceRef.get() ?: return false
            val clampedDuration = durationMs.coerceIn(80L, 2000L)
            val path = Path().apply {
                moveTo(startX.coerceAtLeast(0f), startY.coerceAtLeast(0f))
                lineTo(endX.coerceAtLeast(0f), endY.coerceAtLeast(0f))
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = service.dispatchGesture(gesture, null, null)
            _lastActionStatus.value = if (ok) "Swipe gesture dispatched" else "Swipe gesture failed"
            return ok
        }

        private fun extractNodeLabel(node: AccessibilityNodeInfo): String {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) return text
            return node.contentDescription?.toString()?.trim().orEmpty()
        }

        private fun findMatchingNodes(root: AccessibilityNodeInfo, query: String): List<AccessibilityNodeInfo> {
            val exactMatches = mutableListOf<AccessibilityNodeInfo>()
            val partialMatches = mutableListOf<AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
                val node = queue.removeFirst()
                visited++

                if (node.isVisibleToUser) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    if (text.equals(query, ignoreCase = true) || desc.equals(query, ignoreCase = true)) {
                        exactMatches.add(node)
                    } else if (text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)) {
                        partialMatches.add(node)
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return exactMatches + partialMatches
        }

        private fun findEditableNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val result = mutableListOf<AccessibilityNodeInfo>()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
                val node = queue.removeFirst()
                visited++
                if (node.isEditable) result.add(node)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return result
        }

        private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0

            while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
                val node = queue.removeFirst()
                visited++
                if (node.isScrollable && node.isVisibleToUser) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            }
            return null
        }

        private const val MAX_TREE_NODES = 220
    }
}
