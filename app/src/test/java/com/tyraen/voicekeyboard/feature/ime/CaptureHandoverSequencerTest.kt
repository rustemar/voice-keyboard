package com.tyraen.voicekeyboard.feature.ime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureHandoverSequencerTest {
    @Test fun `later finalized recording cannot overtake its predecessor`() = runTest {
        val sequencer = CaptureHandoverSequencer(this)
        val firstReady = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        sequencer.reserve { firstReady.await(); delivered += "first" }.start()
        sequencer.reserve { delivered += "second" }.start()
        runCurrent()
        assertEquals(emptyList<String>(), delivered)
        firstReady.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), delivered)
    }

    @Test fun `cancelled middle handover keeps an earlier ordering barrier`() = runTest {
        val sequencer = CaptureHandoverSequencer(this)
        val firstReady = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        sequencer.reserve { firstReady.await(); delivered += "first" }.start()
        val cancelled = sequencer.reserve { delivered += "cancelled" }
        cancelled.start()
        runCurrent()
        cancelled.cancel()
        sequencer.reserve { delivered += "third" }.start()
        runCurrent()
        assertEquals(emptyList<String>(), delivered)
        firstReady.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "third"), delivered)
    }

    @Test fun `a cancelled first handover releases the next capture`() = runTest {
        val sequencer = CaptureHandoverSequencer(this)
        val firstReady = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val first = sequencer.reserve { firstReady.await(); delivered += "first" }
        first.start()
        sequencer.reserve { delivered += "second" }.start()
        runCurrent()
        first.cancel()
        runCurrent()
        assertEquals(listOf("second"), delivered)
    }

    @Test fun `middle cancellation before dispatch cannot bypass an earlier capture`() = runTest {
        val sequencer = CaptureHandoverSequencer(this)
        val firstReady = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        sequencer.reserve { firstReady.await(); delivered += "first" }.start()
        val cancelled = sequencer.reserve { delivered += "cancelled" }
        cancelled.cancel()
        sequencer.reserve { delivered += "third" }.start()
        runCurrent()
        assertEquals(emptyList<String>(), delivered)
        firstReady.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "third"), delivered)
    }
}
