package com.answerly.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Single entry point for the app's [SharedPreferences].
 *
 * The store used to be called `manual_relay`; [migrate] copies any legacy
 * values into the current `answerly` store once so upgrades keep settings.
 */
object Prefs {
    const val NAME = "answerly"
    private const val LEGACY_NAME = "manual_relay"
    private const val KEY_MIGRATED = "legacy_prefs_migrated"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun migrate(context: Context) {
        val current = get(context)
        if (current.getBoolean(KEY_MIGRATED, false)) return

        val legacy = context.getSharedPreferences(LEGACY_NAME, Context.MODE_PRIVATE)
        val editor = current.edit()
        legacy.all.forEach { (key, value) ->
            if (current.contains(key)) return@forEach
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.mapNotNull { it as? String }.toSet())
            }
        }
        editor.putBoolean(KEY_MIGRATED, true).apply()
    }
}