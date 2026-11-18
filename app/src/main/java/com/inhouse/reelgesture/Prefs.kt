package com.inhouse.reelgesture

import android.content.Context

object Prefs {
    private const val FILE = "reel_gesture"
    private const val KEY_INSTAGRAM_ONLY = "instagram_only"
    private const val KEY_ALLOW_PREVIOUS = "allow_previous"
    private const val KEY_SENSITIVITY = "sensitivity"

    const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun instagramOnly(context: Context) = prefs(context).getBoolean(KEY_INSTAGRAM_ONLY, true)
    fun setInstagramOnly(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_INSTAGRAM_ONLY, value).apply()

    fun allowPrevious(context: Context) = prefs(context).getBoolean(KEY_ALLOW_PREVIOUS, false)
    fun setAllowPrevious(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_ALLOW_PREVIOUS, value).apply()

    /** 0..100, higher = smaller hand movement needed. */
    fun sensitivity(context: Context) = prefs(context).getInt(KEY_SENSITIVITY, 60)
    fun setSensitivity(context: Context, value: Int) =
        prefs(context).edit().putInt(KEY_SENSITIVITY, value.coerceIn(0, 100)).apply()

    /** Fraction of frame height the fingertips must travel to count as a swipe (0.05..0.20). */
    fun swipeThreshold(context: Context): Float = 0.20f - 0.15f * sensitivity(context) / 100f
}
