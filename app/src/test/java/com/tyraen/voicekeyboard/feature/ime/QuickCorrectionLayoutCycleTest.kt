package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickCorrectionLayoutCycleTest {
    @Test fun `cycle follows configured supported languages and wraps around`() {
        val codes = listOf("ru", "ja", "en", "ru", "fr")
        assertEquals("en", QuickCorrectionLayouts.nextForDictation("ru", codes)?.code)
        assertEquals("fr", QuickCorrectionLayouts.nextForDictation("en", codes)?.code)
        assertEquals("ru", QuickCorrectionLayouts.nextForDictation("fr", codes)?.code)
        assertEquals("ru", QuickCorrectionLayouts.nextForDictation("de", codes)?.code)
    }

    @Test fun `zero or one supported language does not change the selected layout`() {
        assertNull(QuickCorrectionLayouts.nextForDictation("en", emptyList()))
        assertNull(QuickCorrectionLayouts.nextForDictation("en", listOf("ja", "zh")))
        assertNull(QuickCorrectionLayouts.nextForDictation("en", listOf("ru", "ru", "ja")))
    }
}
