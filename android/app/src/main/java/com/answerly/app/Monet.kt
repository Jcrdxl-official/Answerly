package com.answerly.app

import android.app.Activity
import android.content.Context
import com.google.android.material.color.DynamicColors

/**
 * Material You (Monet) dynamic color support.
 *
 * On Android 12+ the palette is derived from the user's wallpaper. On older
 * versions [DynamicColors.applyToActivityIfAvailable] is a no-op and the
 * static Material 3 palette from the theme is used instead.
 */
object Monet {
    private const val KEY_ENABLED = "monet_enabled"

    fun isEnabled(context: Context): Boolean =
        Prefs.get(context)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        Prefs.get(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun apply(activity: Activity) {
        if (isEnabled(activity)) {
            DynamicColors.applyToActivityIfAvailable(activity)
        }
    }
}
