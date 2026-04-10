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
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
