package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePressGuardTest {
    @Test fun `release after automatic stop does not start another recording`() {
        val guard = MicrophonePressGuard()
        guard.onTouchDown(InputPhase.Capturing(100))

        assertFalse(guard.shouldHandleClick(InputPhase.Ready))
        // The consumed touch must not suppress a later accessibility or hardware click.
        assertTrue(guard.shouldHandleClick(InputPhase.Ready))
    }

    @Test fun `a different recording with the same timestamp is not stopped`() {
        val guard = MicrophonePressGuard()
        guard.onTouchDown(InputPhase.Capturing(100))

        assertFalse(guard.shouldHandleClick(InputPhase.Capturing(100)))
    }

    @Test fun `stop click still handles the recording pressed initially`() {
        val guard = MicrophonePressGuard()
        val capture = InputPhase.Capturing(100)
        guard.onTouchDown(capture)

        assertTrue(guard.shouldHandleClick(capture))
    }

    @Test fun `start clicks and clicks without touch keep their normal behavior`() {
        val guard = MicrophonePressGuard()
        assertTrue(guard.shouldHandleClick(InputPhase.Ready))
        assertTrue(guard.shouldHandleClick(InputPhase.Capturing(100)))
        guard.onTouchDown(InputPhase.Ready)
        assertTrue(guard.shouldHandleClick(InputPhase.Ready))
    }

    @Test fun `cancelled touch cannot suppress a later click`() {
        val guard = MicrophonePressGuard()
        guard.onTouchDown(InputPhase.Capturing(100))
        guard.cancelTouch()

        assertTrue(guard.shouldHandleClick(InputPhase.Ready))
    }
}
