package com.tyraen.voicekeyboard.core.config

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class RecordingFeedbackMigrationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val sounds = RecordingFeedbackMigration.SOUNDS_ENABLED
    private val haptics = RecordingFeedbackMigration.HAPTICS_ENABLED

    @Test fun `fresh installation initializes both options on and then stops migrating`() = runTest {
        val migration = RecordingFeedbackMigration { false }
        assertTrue(migration.shouldMigrate(emptyPreferences()))
        val result = migration.migrate(emptyPreferences())
        assertEquals(true, result[sounds])
        assertEquals(true, result[haptics])
        assertFalse(migration.shouldMigrate(result))
    }

    @Test fun `any saved legacy key keeps both options off without changing existing settings`() = runTest {
        val blankKey = stringPreferencesKey("api_key")
        val defaultKey = booleanPreferencesKey("auto_record")
        for (legacy in listOf(preferencesOf(blankKey to ""), preferencesOf(defaultKey to false))) {
            val result = RecordingFeedbackMigration { false }.migrate(legacy)
            assertEquals(false, result[sounds])
            assertEquals(false, result[haptics])
            assertEquals(legacy.asMap(), result.asMap().filterKeys { it != sounds && it != haptics })
        }
    }

    @Test fun `independently saved theme or advanced options keep otherwise empty installs off`() = runTest {
        val result = RecordingFeedbackMigration { true }.migrate(emptyPreferences())
        assertEquals(false, result[sounds])
        assertEquals(false, result[haptics])
    }

    @Test fun `explicit independent choices survive later initialization attempts`() = runTest {
        val migration = RecordingFeedbackMigration { true }
        for (sound in listOf(false, true)) {
            for (haptic in listOf(false, true)) {
                val chosen = preferencesOf(sounds to sound, haptics to haptic)
                assertFalse(migration.shouldMigrate(chosen))
                assertEquals(chosen, migration.migrate(chosen))
            }
        }
        val partial = preferencesOf(sounds to true, stringPreferencesKey("api_key") to "")
        val repaired = migration.migrate(partial)
        assertEquals(true, repaired[sounds])
        assertEquals(false, repaired[haptics])
    }

    @Test fun `an initialization read failure does not invent fresh installation defaults`() = runTest {
        val failure = IOException("settings unavailable")
        val migration = RecordingFeedbackMigration { throw failure }
        val result = runCatching { migration.migrate(emptyPreferences()) }
        assertTrue(result.exceptionOrNull() === failure)
    }

    @Test fun `DataStore initializes before its first write and concurrent readers see one decision`() = runTest {
        var initializationChecks = 0
        val store = PreferenceDataStoreFactory.create(
            migrations = listOf(RecordingFeedbackMigration { initializationChecks++; false }),
            scope = backgroundScope,
            produceFile = { temporary.root.resolve("settings.preferences_pb") }
        )
        // A first-launch disclosure or another option may write before the settings screen loads.
        // This must not make the freshly installed app look like an upgraded installation.
        store.edit { it[booleanPreferencesKey("mic_disclosure_accepted")] = true }
        val snapshots = (1..8).map { async { store.data.first() } }.awaitAll()
        snapshots.forEach {
            assertEquals(true, it[sounds])
            assertEquals(true, it[haptics])
        }
        assertEquals(1, initializationChecks)
        store.edit { it[sounds] = false }
        val changed = store.data.first()
        assertEquals(false, changed[sounds])
        assertEquals(true, changed[haptics])
    }

    @Test fun `unloaded preference objects never opt in to feedback`() {
        val unloaded = UserPreferences("", "", "", "en", "", false, true, "")
        assertFalse(unloaded.recordingSoundsEnabled)
        assertFalse(unloaded.microphoneHapticsEnabled)
    }
}
