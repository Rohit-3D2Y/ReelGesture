package com.inhouse.reelgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Performs the actual on-screen swipe inside Instagram (or any app). */
class ReelScrollService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelScrollService"

        @Volatile
        var instance: ReelScrollService? = null
            private set

        /** Last app that came to the foreground (system UI overlays ignored). */
        @Volatile
        var foregroundPackage: String? = null
            private set

        /** Called on the main thread whenever [foregroundPackage] changes. */
        @Volatile
        var onForegroundChanged: (() -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == "com.android.systemui" || pkg == packageName) return
        if (pkg == foregroundPackage) return
        foregroundPackage = pkg
        onForegroundChanged?.invoke()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Swipes the screen. Returns false if nothing was dispatched.
     * A downward swipe is only ever sent for "previous" — a stray downward swipe is
     * what triggers Instagram's pull-to-refresh.
     */
    fun scroll(next: Boolean, instagramOnly: Boolean): Boolean = try {
        val active = rootInActiveWindow?.packageName?.toString() ?: foregroundPackage
        if (instagramOnly && active != Prefs.INSTAGRAM_PACKAGE) {
            false
        } else {
            val metrics = resources.displayMetrics
            // Left of centre keeps clear of the like/comment column on the right.
            val x = metrics.widthPixels * 0.4f
            val h = metrics.heightPixels.toFloat()
            val (fromY, toY) = if (next) h * 0.70f to h * 0.25f else h * 0.30f to h * 0.75f
            val path = Path().apply {
                moveTo(x, fromY)
                lineTo(x, toY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 180))
                .build()
            dispatchGesture(gesture, null, null)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Swipe failed", e)
        false
    }
}
