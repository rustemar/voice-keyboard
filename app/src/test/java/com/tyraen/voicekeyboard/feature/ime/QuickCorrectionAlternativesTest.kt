package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCorrectionAlternativesTest {
    private fun alternatives(code: String, key: String, uppercase: Boolean = false) =
        QuickCorrectionAlternatives.forKey(QuickCorrectionLayouts.resolve(code)!!, key, uppercase)

    @Test fun `Russian alternatives correspond to the held key and respect shift`() {
        assertEquals(listOf("ё"), alternatives("ru", "е"))
        assertEquals(listOf("Ё"), alternatives("ru", "е", true))
        assertEquals(listOf("ъ"), alternatives("ru", "ь"))
        assertEquals(listOf("Ъ"), alternatives("ru", "ь", true))
        assertTrue(alternatives("ru", "ф").isEmpty())
    }

    @Test fun `French and other Latin alternatives are grouped by letter`() {
        assertEquals(listOf("é", "è", "ê", "ë"), alternatives("fr", "e"))
        assertEquals(listOf("É", "È", "Ê", "Ë"), alternatives("fr", "e", true))
        assertEquals(listOf("ç"), alternatives("fr", "c"))
        assertEquals(listOf("ź", "ż"), alternatives("pl", "z"))
        assertEquals(listOf("ẞ"), alternatives("de", "s", true))
        assertEquals(listOf("Î"), alternatives("tr", "i", true))
    }

    @Test fun `non Latin alternatives preserve multi character letters and vowel signs`() {
        assertEquals(listOf("ά"), alternatives("el", "α"))
        assertEquals(listOf("ि"), alternatives("hi", "इ"))
        assertEquals(listOf("ड़"), alternatives("hi", "ड"))
        assertEquals("◌ि", QuickCorrectionLayouts.label(alternatives("hi", "इ").single()))
        assertEquals(listOf("أ", "إ", "آ"), alternatives("ar", "ا"))
    }

    @Test fun `every layout has alternatives for keys it actually displays`() {
        for (layout in QuickCorrectionLayouts.all) {
            val keys = layout.rows.flatten()
            assertTrue(layout.code, keys.any { QuickCorrectionAlternatives.forKey(layout, it, false).isNotEmpty() })
            for (key in keys) {
                val variants = QuickCorrectionAlternatives.forKey(layout, key, false)
                assertEquals("${layout.code}:$key", variants.distinct(), variants)
                assertTrue("${layout.code}:$key", variants.none { it.isEmpty() || it == key })
            }
        }
    }
}
