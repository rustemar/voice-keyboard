package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCorrectionLayoutsTest {
    private fun characters(code: String): String = QuickCorrectionLayouts.resolve(code)!!.let {
        it.rows.flatten().joinToString("") + it.extraCharacters.joinToString("")
    }

    @Test fun `initial language follows the interface only until user chooses a layout`() {
        assertEquals("fr", QuickCorrectionLayouts.initial(null, "fr").code)
        assertEquals("ru", QuickCorrectionLayouts.initial("ru", "fr").code)
        assertEquals("en", QuickCorrectionLayouts.initial(null, "ja").code)
        assertEquals("de", QuickCorrectionLayouts.initial("unavailable", "de").code)
    }

    @Test fun `unsupported language engines are not presented as available layouts`() {
        val codes = QuickCorrectionLayouts.all.map { it.code }
        assertEquals(14, codes.size)
        assertEquals(codes.size, codes.distinct().size)
        for (unsupported in listOf("zh", "ja", "ko")) assertFalse(codes.contains(unsupported))
    }

    @Test fun `French and German layouts include letters needed for ordinary corrections`() {
        for (letter in "éèêëàâçîïôœùûüÿ") assertTrue("French $letter", letter in characters("fr"))
        for (letter in "äöüß") assertTrue("German $letter", letter in characters("de"))
        assertEquals("azertyuiop", QuickCorrectionLayouts.resolve("fr")!!.rows.first().joinToString(""))
        assertEquals("qwertz", QuickCorrectionLayouts.resolve("de")!!.rows.first().take(6).joinToString(""))
    }

    @Test fun `non-Latin layouts cover their basic alphabets`() {
        for (letter in "абвгдеёжзийклмнопрстуфхцчшщъыьэюя") assertTrue("Russian $letter", letter in characters("ru"))
        for (letter in "абвгдеёжзійклмнопрстуўфхцчшыьэюя") assertTrue("Belarusian $letter", letter in characters("be"))
        for (letter in "αβγδεζηθικλμνξοπρστυφχψως") assertTrue("Greek $letter", letter in characters("el"))
        for (letter in "ابتثجحخدذرزسشصضطظعغفقكلمنهوي") assertTrue("Arabic $letter", letter in characters("ar"))
    }

    @Test fun `Hindi offers consonants vowels vowel signs and virama for direct editing`() {
        for (letter in "अआइईउऊऋएऐओऔकखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसहािीुूृेैोौंःँ़्") {
            assertTrue("Hindi $letter", letter in characters("hi"))
        }
        assertEquals("◌ि", QuickCorrectionLayouts.label("ि"))
        assertEquals("◌्", QuickCorrectionLayouts.label("्"))
    }

    @Test fun `case conversion respects layout locale and may expand a character`() {
        val turkish = QuickCorrectionLayouts.resolve("tr")!!
        assertEquals("İ", turkish.letter("i", true))
        assertEquals("I", turkish.letter("ı", true))
        assertEquals("SS", QuickCorrectionLayouts.resolve("de")!!.letter("ß", true))
        assertEquals("क", QuickCorrectionLayouts.resolve("hi")!!.letter("क", true))
        assertEquals("i", turkish.letter("i", false))
    }

    @Test fun `each available layout has usable rows and symbol entry stays language independent`() {
        for (layout in QuickCorrectionLayouts.all) {
            assertTrue(layout.nativeName.isNotBlank())
            assertTrue(layout.rows.isNotEmpty())
            assertTrue(layout.rows.all { row -> row.isNotEmpty() && row.none { it.isBlank() } })
            assertNotNull(QuickCorrectionLayouts.resolve(layout.code))
        }
        val symbols = QuickCorrectionLayouts.symbols.flatten()
        for (digit in "0123456789") assertTrue(symbols.contains(digit.toString()))
        for (symbol in listOf("@", "#", "$", "€", "\\", "/", "\"", "'")) assertTrue(symbols.contains(symbol))
    }
}
