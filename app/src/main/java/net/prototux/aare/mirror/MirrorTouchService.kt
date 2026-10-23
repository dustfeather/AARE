package net.prototux.aare.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Touch for the phone screen shown in the car: replays the driver's taps and swipes on the phone
 * as accessibility gestures, and tells which app is in front (so the mirror can stay paused
 * while driving unless it's a navigation app). Optional; the user turns it on in the phone's
 * accessibility settings.
 */
class MirrorTouchService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // System UI (shade, volume dialog) and keyboards don't change which app is in front.
        if (pkg == "com.android.systemui" || event.className?.contains("InputMethod") == true) return
        _foregroundApp.value = pkg
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _foregroundApp.value = null
        super.onDestroy()
    }

    /** Taps at ([x], [y]) in phone screen pixels. */
    fun tap(x: Float, y: Float) = stroke(listOf(x to y), 1L)

    /** Drags along [points] (phone screen pixels) over [durationMs]. */
    fun swipe(points: List<Pair<Float, Float>>, durationMs: Long) = stroke(points, durationMs.coerceIn(1, 2_000))

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    private fun stroke(points: List<Pair<Float, Float>>, durationMs: Long): Boolean {
        if (points.isEmpty()) return false
        val path = Path().apply {
            moveTo(points[0].first.coerceAtLeast(0f), points[0].second.coerceAtLeast(0f))
            points.drop(1).forEach { (x, y) -> lineTo(x.coerceAtLeast(0f), y.coerceAtLeast(0f)) }
        }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(gesture, null, null)
    }

    companion object {
        @Volatile var instance: MirrorTouchService? = null
            private set

        private val _foregroundApp = MutableStateFlow<String?>(null)
        /** The app in front on the phone, as last reported; null without the service. */
        val foregroundApp: StateFlow<String?> = _foregroundApp
    }
}
