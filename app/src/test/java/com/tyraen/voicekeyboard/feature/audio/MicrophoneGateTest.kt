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
}
