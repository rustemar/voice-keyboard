package com.tyraen.voicekeyboard.feature.audio

import org.junit.Assert.*
import org.junit.Test

class MicrophoneGateTest {
    @Test fun `another recorder is refused until the owner releases the microphone`() {
        val gate = MicrophoneGate()
        val first = gate.acquire()!!
        assertTrue(gate.isBusy)
        assertNull(gate.acquire())
        first.close()
        assertFalse(gate.isBusy)
        assertNotNull(gate.acquire())
    }

    @Test fun `late repeated release cannot free a newer recording`() {
        val gate = MicrophoneGate()
        val first = gate.acquire()!!
        first.close()
        val second = gate.acquire()!!
        first.close()
        assertNull(gate.acquire())
        second.close()
        assertFalse(gate.isBusy)
    }

    @Test fun `refused requester does not replace the diagnostic owner`() {
        val gate = MicrophoneGate()
        val settings = gate.acquire("settings-test/capture-1")!!
        assertEquals("settings-test/capture-1", gate.diagnosticOwner)

        assertNull(gate.acquire("ime/capture-2"))
        assertEquals("settings-test/capture-1", gate.diagnosticOwner)

        settings.close()
        assertNull(gate.diagnosticOwner)
        val keyboard = gate.acquire("ime/capture-2")!!
        assertEquals("ime/capture-2", gate.diagnosticOwner)
        keyboard.close()
    }

    @Test fun `late release keeps the newer owner's label until its own lease closes`() {
        val gate = MicrophoneGate()
        val old = gate.acquire("ime/capture-1")!!
        old.close()
        val current = gate.acquire("ime/capture-2")!!

        old.close()
        assertTrue(gate.isBusy)
        assertEquals("ime/capture-2", gate.diagnosticOwner)
        assertNull(gate.acquire("settings-test/capture-3"))

        current.close()
        assertFalse(gate.isBusy)
        assertNull(gate.diagnosticOwner)
    }
}
