package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactTerminalLayoutPolicyTest {
    @Test fun `compact requires explicit opt in and an available active terminal mode`() {
        assertFalse(CompactTerminalLayoutPolicy.eligible(false, true, true, true))
        assertFalse(CompactTerminalLayoutPolicy.eligible(true, false, true, true))
        assertFalse(CompactTerminalLayoutPolicy.eligible(true, true, false, true))
        assertFalse(CompactTerminalLayoutPolicy.eligible(true, true, true, false))
        assertTrue(CompactTerminalLayoutPolicy.eligible(true, true, true, true))
    }

    @Test fun `narrow or unmeasured panels keep full controls instead of shrinking touch targets`() {
        for (width in listOf(0, 240, 280, 319)) {
            assertFalse(CompactTerminalLayoutPolicy.compact(true, false, width))
        }
        assertTrue(CompactTerminalLayoutPolicy.compact(true, false, 320))
        assertTrue(CompactTerminalLayoutPolicy.compact(true, false, 393))
    }

    @Test fun `manual expansion keeps full controls at any supported width`() {
        assertFalse(CompactTerminalLayoutPolicy.compact(true, true, 320))
        assertFalse(CompactTerminalLayoutPolicy.compact(true, true, 800))
        assertFalse(CompactTerminalLayoutPolicy.compact(false, false, 800))
    }
}
