package com.tyraen.voicekeyboard.core.config

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_THEME = "app_theme"

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(resolveNightMode(context))
    }

    fun current(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_THEME, THEME_AUTO) ?: THEME_AUTO
    }

    fun persist(context: Context, theme: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme)
            .apply()
        AppCompatDelegate.setDefaultNightMode(toNightMode(theme))
    }

    private fun resolveNightMode(context: Context): Int = toNightMode(current(context))

    private fun toNightMode(theme: String): Int = when (theme) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
}
