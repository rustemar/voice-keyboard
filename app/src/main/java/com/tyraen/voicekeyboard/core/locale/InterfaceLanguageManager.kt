package com.tyraen.voicekeyboard.core.locale

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object InterfaceLanguageManager {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "ui_language"

    fun applyTo(context: Context): Context {
        val lang = resolveActive(context)
        return configure(context, lang)
    }

    fun resolveActive(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANGUAGE, null)
        if (stored != null) return stored

        val systemLang = Locale.getDefault().language
        val known = TranscriptionLocale.resolve(systemLang) != null
        val lang = if (known) systemLang else "en"
        prefs.edit().putString(KEY_LANGUAGE, lang).apply()
        return lang
    }

    fun persist(context: Context, languageCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, languageCode)
            .apply()
    }

    private fun configure(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        // Override only the locale (and the layout direction setLocale derives from it). The
        // platform re-applies an override on every configuration change, so a full copy of the
        // current configuration froze night mode, font scale and the rest for as long as the
        // context lived: with the Auto theme the long-lived keyboard kept the old light/dark look.
        val config = Configuration()
        // Android 7.x starts a new Configuration at fontScale 1, which would override the user's
        // font size; 0 means "not set" on every version (AppCompat does the same).
        config.fontScale = 0f
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
