package com.tyraen.voicekeyboard.core.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TranscriptionLocaleTest {

    // --- parseCodes ---

    @Test fun `a single code parses to a one element list`() {
        assertEquals(listOf("ru"), TranscriptionLocale.parseCodes("ru"))
    }

    @Test fun `comma separated codes are split and trimmed`() {
        assertEquals(listOf("ru", "en", "de"), TranscriptionLocale.parseCodes(" ru ,en,  de "))
    }

    @Test fun `spaces and semicolons also separate codes`() {
        assertEquals(listOf("ru", "en"), TranscriptionLocale.parseCodes("ru; en"))
        assertEquals(listOf("ru", "en"), TranscriptionLocale.parseCodes("ru en"))
    }

    @Test fun `codes are lowercased and deduplicated with order preserved`() {
        assertEquals(listOf("en", "ru"), TranscriptionLocale.parseCodes("EN, ru, En"))
    }

    @Test fun `blank input parses to an empty list`() {
        assertEquals(emptyList<String>(), TranscriptionLocale.parseCodes("  , ,"))
    }

    @Test fun `codes outside the built-in catalog are kept`() {
        assertEquals(listOf("uk", "nl"), TranscriptionLocale.parseCodes("uk, nl"))
    }

    @Test fun `formatCodes round-trips through parseCodes`() {
        val formatted = TranscriptionLocale.formatCodes(TranscriptionLocale.parseCodes("ru,en"))
        assertEquals("ru, en", formatted)
        assertEquals(listOf("ru", "en"), TranscriptionLocale.parseCodes(formatted))
    }

    // --- nextCode ---

    @Test fun `nextCode advances through the list`() {
        assertEquals("en", TranscriptionLocale.nextCode(listOf("ru", "en", "de"), "ru"))
        assertEquals("de", TranscriptionLocale.nextCode(listOf("ru", "en", "de"), "en"))
    }

    @Test fun `nextCode wraps around at the end`() {
        assertEquals("ru", TranscriptionLocale.nextCode(listOf("ru", "en", "de"), "de"))
    }

    @Test fun `nextCode falls back to the first code when the current one is unknown`() {
        assertEquals("ru", TranscriptionLocale.nextCode(listOf("ru", "en"), "zz"))
    }

    @Test fun `nextCode returns the current code for an empty list`() {
        assertEquals("ru", TranscriptionLocale.nextCode(emptyList(), "ru"))
    }

    @Test fun `nextCode on a single-element list stays put`() {
        assertEquals("ru", TranscriptionLocale.nextCode(listOf("ru"), "ru"))
    }

    // --- promptFor ---

    @Test fun `a built-in prompt is swapped for the one matching the dictated language`() {
        val russian = TranscriptionLocale.resolve("ru")!!.defaultPrompt
        val english = TranscriptionLocale.resolve("en")!!.defaultPrompt
        assertEquals(english, TranscriptionLocale.promptFor("en", russian))
    }

    @Test fun `a customized prompt is never rewritten`() {
        val custom = "Kubernetes, Docker, gRPC."
        assertEquals(custom, TranscriptionLocale.promptFor("en", custom))
    }

    @Test fun `a built-in prompt survives switching to a language we have no default for`() {
        val russian = TranscriptionLocale.resolve("ru")!!.defaultPrompt
        assertEquals(russian, TranscriptionLocale.promptFor("nl", russian))
    }

    @Test fun `an empty prompt stays empty`() {
        assertEquals("", TranscriptionLocale.promptFor("en", ""))
    }

    // --- labels ---

    @Test fun `shortLabel uppercases the code`() {
        assertEquals("RU", TranscriptionLocale.shortLabel("ru"))
    }

    @Test fun `longLabel names known languages and passes unknown codes through`() {
        assertEquals("Русский (ru)", TranscriptionLocale.longLabel("ru"))
        assertEquals("nl", TranscriptionLocale.longLabel("nl"))
    }

    @Test fun `displayNameIn names the language in the interface language`() {
        assertEquals("English", TranscriptionLocale.displayNameIn("en", Locale.ENGLISH))
        assertEquals("русский", TranscriptionLocale.displayNameIn("ru", Locale("ru")))
    }

    @Test fun `displayNameIn passes an unknown or empty code through`() {
        assertEquals("xx", TranscriptionLocale.displayNameIn("xx", Locale.ENGLISH))
        assertEquals("", TranscriptionLocale.displayNameIn("", Locale.ENGLISH))
    }

    @Test fun `every catalog entry has a distinct built-in prompt`() {
        val prompts = TranscriptionLocale.entries.map { it.defaultPrompt }
        assertTrue("built-in prompts must be unique for promptFor to be reversible",
            prompts.size == prompts.toSet().size)
    }
}
