package com.tyraen.voicekeyboard.core.config

import android.content.Context
import android.content.SharedPreferences

/** Independent opt-ins; installing an update never enables either integration. */
class AdvancedPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("advanced_options", Context.MODE_PRIVATE)

    var recognitionEnabled: Boolean
        get() = preferences.getBoolean(RECOGNITION_ENABLED, false)
        set(value) { preferences.edit().putBoolean(RECOGNITION_ENABLED, value).apply() }

    var compactTerminal: Boolean
        get() = preferences.getBoolean(COMPACT_TERMINAL, false)
        set(value) { preferences.edit().putBoolean(COMPACT_TERMINAL, value).apply() }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val RECOGNITION_ENABLED = "recognition_enabled"
        const val COMPACT_TERMINAL = "compact_terminal"
    }
}
