package com.tyraen.voicekeyboard.feature.vocabulary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDictionaryTest {
    private fun encoded(vararg rules: Pair<String, String>): String = TextDictionary.encodeRules(
        rules.map { TextReplacementRules.Rule(it.first, it.second) }
    )

    @Test fun `stored rules preserve Unicode JSON punctuation and literal delimiters`() {
        val rules = listOf(
            TextReplacementRules.Rule("габорд", "Gboard"),
            TextReplacementRules.Rule("x => y", "quoted \"name\" → \\path"),
            TextReplacementRules.Rule("東京", "Tokyo")
        )

        assertEquals(rules, TextDictionary.decodeRules(TextDictionary.encodeRules(rules)))
    }

    @Test fun `malformed stored rules never expose a partially usable dictionary`() {
        val invalid = listOf(
            "", "not JSON", "{}", "[null]",
            "[{\"source\":\"valid\",\"target\":\"good\"},{\"source\":\"missing target\"}]",
            "[{\"source\":\"same\",\"target\":\"one\"},{\"source\":\"SAME\",\"target\":\"two\"}]",
            "[{\"source\":\"word\",\"target\":\"line\\nbreak\"}]"
        )

        for (stored in invalid) {
            assertTrue("Accepted invalid stored definition: $stored", TextDictionary.decodeRules(stored).isEmpty())
        }
    }

    @Test fun `oversized private storage is ignored before JSON parsing`() {
        assertTrue(TextDictionary.decodeRules("[" + " ".repeat(400_000) + "]").isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encoding refuses invalid rules instead of persisting a partial dictionary`() {
        TextDictionary.encodeRules(listOf(
            TextReplacementRules.Rule("valid", "good"), TextReplacementRules.Rule("", "bad")
        ))
    }

    @Test fun `post-processing references contain only selected targets and deduplicate across dictionaries`() {
        val prepared = TextDictionary.prepare(
            text = "new york and ga",
            recognitionHints = "gboard\nLegacy name\nShared term",
            postProcessingVocabulary = "NY\nExpanded name\nshared TERM",
            replacementRules = encoded("new" to "Unused target", "new york" to "NY", "ga" to "Gboard", "other" to "Unmatched target")
        )

        assertEquals("NY and Gboard", prepared.text)
        assertEquals(listOf("NY", "Gboard", "Expanded name", "shared TERM", "Legacy name"), prepared.vocabulary.lines())
        assertFalse(prepared.vocabulary.contains("Unused target"))
        assertFalse(prepared.vocabulary.contains("Unmatched target"))
    }

    @Test fun `one preparation pass never rewrites a target or advertises an unselected target`() {
        val prepared = TextDictionary.prepare("alpha", "", "", encoded("alpha" to "beta", "beta" to "gamma"))

        assertEquals("beta", prepared.text)
        assertEquals("beta", prepared.vocabulary)
    }

    @Test fun `changing the saved dictionary invalidates the compiled rules cache`() {
        val first = encoded("word" to "first")
        val second = encoded("word" to "second")

        assertEquals("first", TextDictionary.prepare("word", "", "", first).text)
        assertEquals("second", TextDictionary.prepare("word", "", "", second).text)
        assertEquals("word", TextDictionary.prepare("word", "", "", "").text)
        assertEquals("first", TextDictionary.prepare("word", "", "", first).text)
    }

    @Test fun `invalid stored rules preserve dictation and usable reference vocabulary`() {
        val prepared = TextDictionary.prepare("Keep this text.", "Legacy", "Expanded", "broken JSON")

        assertEquals("Keep this text.", prepared.text)
        assertEquals("Expanded\nLegacy", prepared.vocabulary)
    }
}
