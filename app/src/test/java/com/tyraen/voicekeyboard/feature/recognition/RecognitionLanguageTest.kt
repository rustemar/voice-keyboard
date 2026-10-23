package com.tyraen.voicekeyboard.feature.recognition

import org.junit.Assert.*
import org.junit.Test

class RecognitionLanguageTest {
    @Test fun `client language overrides saved keyboard language`() {
        assertEquals("en", RecognitionLanguage.resolve("en-US", "ru"))
        assertEquals("pt", RecognitionLanguage.resolve("pt-BR", "en"))
        assertEquals("zh", RecognitionLanguage.resolve("zh-Hant-TW", "ru"))
        assertEquals("en", RecognitionLanguage.resolve("EN_us", "ru"))
    }

    @Test fun `no client language uses configured language or automatic detection`() {
        assertEquals("ru", RecognitionLanguage.resolve(null, "ru"))
        assertEquals("en", RecognitionLanguage.resolve("", "en-GB"))
        assertEquals("", RecognitionLanguage.resolve(null, ""))
        assertEquals("", RecognitionLanguage.resolve("   ", ""))
    }

    @Test fun `valid languages are not restricted to interface translations`() {
        assertEquals("uk", RecognitionLanguage.resolve("uk-UA", "en"))
        assertEquals("yue", RecognitionLanguage.resolve("yue-Hant-HK", "en"))
        assertEquals("en", RecognitionLanguage.resolve("en-u-ca-gregory", "ru"))
    }

    @Test fun `legacy Android language aliases normalize to provider codes`() {
        assertEquals("he", RecognitionLanguage.resolve("iw-IL", ""))
        assertEquals("id", RecognitionLanguage.resolve("in-ID", ""))
        assertEquals("yi", RecognitionLanguage.resolve("ji", ""))
    }

    @Test fun `malformed or undetermined language is rejected rather than silently replaced`() {
        for (language in listOf("en-US,ru", "not a language", "_", "123", "und", "en--US", "x-private")) {
            assertNull(language, RecognitionLanguage.resolve(language, "ru"))
        }
    }
}
