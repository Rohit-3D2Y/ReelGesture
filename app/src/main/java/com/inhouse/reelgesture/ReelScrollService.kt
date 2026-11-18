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
        cachedPager = null
        onForegroundChanged?.invoke()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Instagram's reels pager, found once and reused so each scroll skips the tree search. */
    private var cachedPager: AccessibilityNodeInfo? = null
    private val bounds = Rect()

    /** Locate the pager ahead of time (called while a pose is being confirmed). */
    fun prepare() {
        try {
            if (validPager() == null) cachedPager = rootInActiveWindow?.let(this::findPager)
        } catch (e: Exception) {
            Log.w(TAG, "Prepare failed", e)
        }
    }

    /**
     * Moves one reel. By default uses the pager's own accessibility scroll action (clean page
     * change, same as TalkBack), falling back to a swipe; [bySwipe] forces a real swipe.
     * Returns false if nothing was done. A downward swipe is only ever sent for "previous" —
     * a stray downward swipe is what triggers Instagram's pull-to-refresh.
     */
    fun scroll(next: Boolean, instagramOnly: Boolean, bySwipe: Boolean): Boolean = try {
        val pager = if (bySwipe) null else validPager() ?: rootInActiveWindow?.let(this::findPager)
        cachedPager = pager
        val active = pager?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()
            ?: foregroundPackage
        when {
            instagramOnly && active != Prefs.INSTAGRAM_PACKAGE -> false
            pager != null && pager.performAction(
                if (next) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            ) -> true
            else -> swipe(next)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Scroll failed", e)
        false
    }

    private fun swipe(next: Boolean): Boolean {
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** The cached pager if it is still on screen and still a full-screen scroller. */
    private fun validPager(): AccessibilityNodeInfo? {
        val node = cachedPager ?: return null
        val ok = node.refresh() && node.isVisibleToUser && node.isScrollable && isFullScreen(node)
        if (!ok) cachedPager = null
        return if (ok) node else null
    }

    /** The largest visible scrollable view, if it covers most of the screen (the reels pager). */
    private fun findPager(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
        return best?.takeIf { isFullScreen(it) }
    }

    private fun isFullScreen(node: AccessibilityNodeInfo): Boolean {
        node.getBoundsInScreen(bounds)
        val metrics = resources.displayMetrics
        return bounds.width() * bounds.height() >= metrics.widthPixels * metrics.heightPixels / 2
    }
}
