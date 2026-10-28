package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SelectedTextSnapshotTest {
    private val original = SelectedTextSnapshot(42, 7, 12, "hello", "before ", " after")

    @Test fun `only unchanged selection in original editor is replaceable`() {
        assertTrue(original.matches(original.copy()))
        assertFalse(original.matches(null))
        assertFalse(original.matches(original.copy(editorSession = 43)))
        assertFalse(original.matches(original.copy(start = 20, end = 25)))
        assertFalse(original.matches(original.copy(text = "Hallo")))
        assertFalse(original.matches(original.copy(before = "edited ")))
        assertFalse(original.matches(original.copy(after = " changed")))
    }

    @Test fun `replacement keeps exact neighbours and counts UTF16 offsets`() {
        val applied = original.afterReplacement("नमस्ते 👋")
        assertEquals(7, applied.start)
        assertEquals(7 + "नमस्ते 👋".length, applied.end)
        assertEquals(original.before, applied.before)
        assertEquals(original.after, applied.after)
        assertTrue(applied.matches(applied.copy()))
        assertFalse(applied.matches(original))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `inconsistent editor offsets are rejected`() {
        SelectedTextSnapshot(42, 7, 10, "hello", "", "")
    }
}
