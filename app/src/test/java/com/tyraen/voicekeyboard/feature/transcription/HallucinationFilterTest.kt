package com.tyraen.voicekeyboard.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class HallucinationFilterTest {

    @Test fun `keeps real speech unchanged`() {
        assertEquals("Hello world", HallucinationFilter.clean("Hello world"))
    }

    @Test fun `trims surrounding whitespace`() {
        assertEquals("Hello", HallucinationFilter.clean("  Hello  "))
    }

    @Test fun `empty input returns empty`() {
        assertEquals("", HallucinationFilter.clean(""))
        assertEquals("", HallucinationFilter.clean("   "))
    }

    @Test fun `punctuation-only input returns empty`() {
        assertEquals("", HallucinationFilter.clean("..."))
        assertEquals("", HallucinationFilter.clean(". , !"))
        assertEquals("", HallucinationFilter.clean("—"))
    }

    @Test fun `known phantom phrases are dropped`() {
        assertEquals("", HallucinationFilter.clean("Thank you"))
        assertEquals("", HallucinationFilter.clean("thanks for watching"))
        assertEquals("", HallucinationFilter.clean("Subscribe!"))
        assertEquals("", HallucinationFilter.clean("продолжение следует."))
    }

    @Test fun `phantom phrase detection is case-insensitive and tolerant of trailing punctuation`() {
        assertEquals("", HallucinationFilter.clean("THANK YOU"))
        assertEquals("", HallucinationFilter.clean("Thank you!"))
    }

    @Test fun `phrases that contain phantom words but are longer survive`() {
        assertEquals("Thank you for the gift", HallucinationFilter.clean("Thank you for the gift"))
        assertEquals("I want to subscribe to the channel", HallucinationFilter.clean("I want to subscribe to the channel"))
    }
}
