package com.inhouse.reelgesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
     * Moves one reel. Prefers the app's own accessibility scroll action (clean page change,
     * same as TalkBack), falling back to a swipe. Returns false if nothing was done.
     * A downward swipe is only ever sent for "previous" — a stray downward swipe is
     * what triggers Instagram's pull-to-refresh.
     */
    fun scroll(next: Boolean, instagramOnly: Boolean): Boolean = try {
        val root = rootInActiveWindow
        val active = root?.packageName?.toString() ?: foregroundPackage
        if (instagramOnly && active != Prefs.INSTAGRAM_PACKAGE) {
            false
        } else if (root != null && scrollByAction(root, next)) {
            true
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

    /** Scrolls the largest visible scrollable view (the reels pager) by one page. */
    private fun scrollByAction(root: AccessibilityNodeInfo, next: Boolean): Boolean {
        val bounds = Rect()
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited++
            if (node.isScrollable && node.isVisibleToUser) {
                node.getBoundsInScreen(bounds)
                val area = bounds.width() * bounds.height()
                if (area > bestArea) {
                    bestArea = area
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        // Only trust it if it covers most of the screen, i.e. it's the full-screen reels pager.
        val metrics = resources.displayMetrics
        if (best == null || bestArea < metrics.widthPixels * metrics.heightPixels / 2) return false
        val action = if (next) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return best.performAction(action)
    }
}
