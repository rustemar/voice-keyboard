package com.tyraen.voicekeyboard.core.config

import android.content.Context
import android.content.SharedPreferences

/** Independent opt-ins; installing an update never enables an integration or experiment. */
class AdvancedPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("advanced_options", Context.MODE_PRIVATE)

    var recognitionEnabled: Boolean
        get() = preferences.getBoolean(RECOGNITION_ENABLED, false)
        set(value) { preferences.edit().putBoolean(RECOGNITION_ENABLED, value).apply() }

    var compactTerminal: Boolean
        get() = preferences.getBoolean(COMPACT_TERMINAL, false)
        set(value) { preferences.edit().putBoolean(COMPACT_TERMINAL, value).apply() }

    var quickCorrectionEnabled: Boolean
        get() = preferences.getBoolean(QUICK_CORRECTION_ENABLED, false)
        set(value) { preferences.edit().putBoolean(QUICK_CORRECTION_ENABLED, value).apply() }

    var textActionsEnabled: Boolean
        get() = preferences.getBoolean(TEXT_ACTIONS_ENABLED, false)
        set(value) { preferences.edit().putBoolean(TEXT_ACTIONS_ENABLED, value).apply() }

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val RECOGNITION_ENABLED = "recognition_enabled"
        const val COMPACT_TERMINAL = "compact_terminal"
        const val QUICK_CORRECTION_ENABLED = "quick_correction_enabled"
        const val TEXT_ACTIONS_ENABLED = "text_actions_enabled"
    }
}
