package com.tyraen.voicekeyboard.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserPreferencesTest {

    private fun prefs(languages: String, active: String) = UserPreferences(
        apiKey = "k",
        endpoint = "e",
        model = "m",
        languages = languages,
        activeLanguage = active,
        autoRecord = false,
        addTrailingSpace = true,
        prompt = ""
    )

    @Test fun `the active language is used when it is still in the list`() {
        assertEquals("en", prefs("ru, en", "en").effectiveLanguage)
    }

    @Test fun `falls back to the first language when the active one was removed`() {
        assertEquals("ru", prefs("ru, de", "en").effectiveLanguage)
    }

    @Test fun `falls back to the first language when nothing was ever picked`() {
        assertEquals("ru", prefs("ru, en", "").effectiveLanguage)
    }

    @Test fun `an empty language list means auto-detect`() {
        assertEquals("", prefs("", "en").effectiveLanguage)
    }

    @Test fun `a legacy single-code setting keeps working`() {
        val p = prefs("ru", "")
        assertEquals(listOf("ru"), p.languageCodes)
        assertEquals("ru", p.effectiveLanguage)
    }

    @Test fun `automatic sending remains opt-in when silence auto-stop is enabled`() {
        val p = prefs("en", "en").copy(silenceAutoStopEnabled = true)
        assertFalse(p.autoSendAfterSilenceEnabled)
    }
}
