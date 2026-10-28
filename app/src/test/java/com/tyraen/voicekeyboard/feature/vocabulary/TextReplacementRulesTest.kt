package com.tyraen.voicekeyboard.feature.vocabulary

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplacementRulesTest {
    private fun matcher(vararg rules: Pair<String, String>) = TextReplacementRules.compile(
        rules.map { TextReplacementRules.Rule(it.first, it.second) }
    )

    @Test fun `bulk parsing accepts both delimiters and preserves literal target punctuation`() {
        val parsed = TextReplacementRules.parse("\n габорд => Gboard\r\nnew york → New York\narrow => x => y → z\n")
        assertTrue(parsed.isValid)
        assertEquals(listOf(
            TextReplacementRules.Rule("габорд", "Gboard"),
            TextReplacementRules.Rule("new york", "New York"),
            TextReplacementRules.Rule("arrow", "x => y → z")
        ), parsed.rules)
    }

    @Test fun `malformed bulk input reports original line numbers and never applies a partial set`() {
        val parsed = TextReplacementRules.parse("\nvalid => good\nmissing delimiter\n => target\nsource => \n")
        assertFalse(parsed.isValid)
        assertTrue(parsed.rules.isEmpty())
        assertEquals(listOf(
            TextReplacementRules.ParseError(3, TextReplacementRules.ErrorKind.MISSING_SEPARATOR),
            TextReplacementRules.ParseError(4, TextReplacementRules.ErrorKind.EMPTY_SOURCE),
            TextReplacementRules.ParseError(5, TextReplacementRules.ErrorKind.EMPTY_TARGET)
        ), parsed.errors)
    }

    @Test fun `duplicate case variants are rejected instead of depending on rule order`() {
        val parsed = TextReplacementRules.parse("габорд => Gboard\nГАБОРД => other")
        assertEquals(listOf(TextReplacementRules.ParseError(2,
            TextReplacementRules.ErrorKind.DUPLICATE_SOURCE)), parsed.errors)
    }

    @Test fun `structured rules can contain delimiter characters literally`() {
        assertEquals("Use arrow.", matcher("x => y" to "arrow").apply("Use x => y."))
    }

    @Test fun `word boundaries protect larger words numbers accents and connector characters`() {
        val engine = matcher("cat" to "dog", "cafe" to "coffee", "1" to "one", "c++" to "C++")
        assertEquals("dog, concatenate cat2 cat_name cat́; café coffee; one 1½; C++ c++17.",
            engine.apply("cat, concatenate cat2 cat_name cat́; café cafe; 1 1½; c++ c++17."))
    }

    @Test fun `case matching handles Cyrillic Greek accents and supplementary letters`() {
        val engine = matcher("габорд" to "Gboard", "κόσμος" to "world", "café" to "coffee", "𐐀" to "letter")
        assertEquals("Gboard world coffee letter", engine.apply("ГАБОРД ΚΌΣΜΟΣ CAFÉ 𐐨"))
    }

    @Test fun `matching is independent of the device locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("Gboard", matcher("gboard" to "Gboard").apply("GBOARD"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `Hindi and Arabic combining marks remain part of a word`() {
        val engine = matcher("नमस्ते" to "hello", "سلام" to "hello", "a" to "A")
        assertEquals("hello! hello! नमस्तेजी سلامي a\u0301", engine.apply("नमस्ते! سلام! नमस्तेजी سلامي a\u0301"))
    }

    @Test fun `CJK replacements require explicit phrase boundaries rather than guessed segmentation`() {
        val engine = matcher("東京" to "Tokyo", "你好" to "hello")
        assertEquals("Tokyo、hello。東京都 你好世界", engine.apply("東京、你好。東京都 你好世界"))
    }

    @Test fun `emoji sequences are never split by base-character replacements`() {
        val engine = matcher("👩" to "woman", "👍" to "yes", "❤" to "heart", "🇺" to "U")
        assertEquals("woman yes heart U; 👩‍💻 👍🏽 ❤️ 🇺🇦", engine.apply("👩 👍 ❤ 🇺; 👩‍💻 👍🏽 ❤️ 🇺🇦"))
    }

    @Test fun `longest phrase wins at the earliest start regardless of rule ordering`() {
        val engine = matcher("new" to "N", "york city" to "Y", "new york" to "NY", "new york city" to "NYC")
        assertEquals("NYC / NY / N", engine.apply("new york city / new york / new"))
        val overlap = matcher("a b" to "X", "b c d" to "Y")
        assertEquals("X c d", overlap.apply("a b c d"))
    }

    @Test fun `replacement targets are literal and never recursively rewritten`() {
        val engine = matcher("first" to "second", "second" to "third", "money" to "\$1\\path")
        assertEquals("second third \$1\\path", engine.apply("first second money"))
    }

    @Test fun `details report ordered unique targets selected by this pass only`() {
        val result = matcher("new" to "unused", "new york" to "NY", "nyc" to "NY", "ga" to "Gboard")
            .applyWithDetails("new york ga nyc ga")
        assertEquals("NY Gboard NY Gboard", result.text)
        assertEquals(listOf("NY", "Gboard"), result.targets)
    }

    @Test fun `unmatched text whitespace and malformed input UTF16 remain unchanged`() {
        val original = "  Unknown\ttext\n😀\uD800.  "
        val result = matcher("other" to "word").applyWithDetails(original)
        assertSame(original, result.text)
        assertTrue(result.targets.isEmpty())
        assertEquals("  Gboard\ttext\n😀  ", matcher("ga" to "Gboard").apply("  ga\ttext\n😀  "))
    }

    @Test fun `invalid controls malformed rules and resource limits are rejected`() {
        assertTrue(TextReplacementRules.validate(listOf(TextReplacementRules.Rule("a\tb", "c")))
            .any { it.kind == TextReplacementRules.ErrorKind.INVALID_CHARACTERS })
        assertTrue(TextReplacementRules.validate(listOf(TextReplacementRules.Rule("a", "\uD800")))
            .any { it.kind == TextReplacementRules.ErrorKind.INVALID_CHARACTERS })
        assertEquals(TextReplacementRules.ErrorKind.INPUT_TOO_LONG,
            TextReplacementRules.parse("a".repeat(TextReplacementRules.MAX_CHARS + 1)).errors.single().kind)
        assertTrue(TextReplacementRules.validate(listOf(
            TextReplacementRules.Rule("a".repeat(TextReplacementRules.MAX_SOURCE_CHARS + 1), "b")))
            .any { it.kind == TextReplacementRules.ErrorKind.SOURCE_TOO_LONG })
        assertTrue(TextReplacementRules.validate(listOf(
            TextReplacementRules.Rule("a", "b".repeat(TextReplacementRules.MAX_TARGET_CHARS + 1))))
            .any { it.kind == TextReplacementRules.ErrorKind.TARGET_TOO_LONG })
        val many = (0..TextReplacementRules.MAX_RULES).map { TextReplacementRules.Rule("a$it", "b") }
        assertTrue(TextReplacementRules.validate(many).any { it.kind == TextReplacementRules.ErrorKind.TOO_MANY_RULES })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `compilation refuses an invalid structured definition`() {
        TextReplacementRules.compile(listOf(TextReplacementRules.Rule("", "target")))
    }

    @Test(timeout = 5_000L)
    fun `large dictionary handles long text without scanning each rule for each position`() {
        val rules = (0 until TextReplacementRules.MAX_RULES).map {
            TextReplacementRules.Rule("term$it", "T$it")
        }
        val engine = TextReplacementRules.compile(rules)
        val input = "term4999 term1 unknown ".repeat(4_000)
        assertEquals("T4999 T1 unknown ".repeat(4_000), engine.apply(input))
    }
}
