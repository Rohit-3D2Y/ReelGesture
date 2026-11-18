package com.inhouse.reelgesture

import android.content.Context

object Prefs {
    private const val FILE = "reel_gesture"
    private const val KEY_INSTAGRAM_ONLY = "instagram_only"
    private const val KEY_ALLOW_PREVIOUS = "allow_previous"
    private const val KEY_SCROLL_BY_SWIPE = "scroll_by_swipe"

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun instagramOnly(context: Context) = prefs(context).getBoolean(KEY_INSTAGRAM_ONLY, true)
    fun setInstagramOnly(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_INSTAGRAM_ONLY, value).apply()

    fun allowPrevious(context: Context) = prefs(context).getBoolean(KEY_ALLOW_PREVIOUS, false)
    fun setAllowPrevious(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALLOW_PREVIOUS, value).apply()

    /** Off: use the reels pager's own scroll action. On: always send a real swipe. */
    fun scrollBySwipe(context: Context) = prefs(context).getBoolean(KEY_SCROLL_BY_SWIPE, false)
    fun setScrollBySwipe(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_SCROLL_BY_SWIPE, value).apply()
}
