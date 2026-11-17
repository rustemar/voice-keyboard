package com.tyraen.voicekeyboard.core.config

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

/** Resolve first-install defaults before any settings read or write can populate an empty store. */
internal class RecordingFeedbackMigration(
    private val hasOtherSavedSettings: () -> Boolean
) : DataMigration<Preferences> {

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[SOUNDS_ENABLED] == null || currentData[HAPTICS_ENABLED] == null

    override suspend fun migrate(currentData: Preferences): Preferences {
        // Even a saved blank string or false/default value establishes an existing installation.
        // The new keys do not count as legacy settings if only one needs initialization.
        val hasLegacySettings = currentData.asMap().keys.any {
            it != SOUNDS_ENABLED && it != HAPTICS_ENABLED
        } || hasOtherSavedSettings()
        return currentData.toMutablePreferences().apply {
            if (this[SOUNDS_ENABLED] == null) this[SOUNDS_ENABLED] = !hasLegacySettings
            if (this[HAPTICS_ENABLED] == null) this[HAPTICS_ENABLED] = !hasLegacySettings
        }
    }

    override suspend fun cleanUp() = Unit

    companion object {
        val SOUNDS_ENABLED = booleanPreferencesKey("recording_sounds_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("microphone_haptics_enabled")
    }
}
