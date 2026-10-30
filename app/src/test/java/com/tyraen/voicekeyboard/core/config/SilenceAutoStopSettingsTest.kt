package com.tyraen.voicekeyboard.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SilenceAutoStopSettingsTest {
    @Test fun `existing settings keep manual dictation with a short optional pause`() {
        val prefs = UserPreferences("key", "endpoint", "model", "en", "", false, true, "")
        assertFalse(prefs.silenceAutoStopEnabled)
        assertEquals(1_200, prefs.silenceAutoStopPauseMs)
    }

    @Test fun `unsupported stored durations are bounded and rounded without integer overflow`() {
        assertEquals(300, SilenceAutoStopSettings.normalizePauseMs(Int.MIN_VALUE))
        assertEquals(3_000, SilenceAutoStopSettings.normalizePauseMs(Int.MAX_VALUE))
        assertEquals(1_200, SilenceAutoStopSettings.normalizePauseMs(1_249))
        assertEquals(1_300, SilenceAutoStopSettings.normalizePauseMs(1_250))
    }

    @Test fun `every slider stop maps to a supported duration and back`() {
        val durations = (0..SilenceAutoStopSettings.MAX_PROGRESS).map { progress ->
            SilenceAutoStopSettings.pauseMsForProgress(progress).also { duration ->
                assertEquals(progress, SilenceAutoStopSettings.progressForPauseMs(duration))
            }
        }
        assertEquals((300..3_000 step 100).toList(), durations)
        assertEquals(1_200, SilenceAutoStopSettings.pauseMsForProgress(9))
    }

    @Test fun `restored slider positions outside its range stay at an endpoint`() {
        assertEquals(300, SilenceAutoStopSettings.pauseMsForProgress(Int.MIN_VALUE))
        assertEquals(3_000, SilenceAutoStopSettings.pauseMsForProgress(Int.MAX_VALUE))
        assertEquals(0, SilenceAutoStopSettings.progressForPauseMs(-1))
        assertEquals(27, SilenceAutoStopSettings.progressForPauseMs(30_000))
    }
}
