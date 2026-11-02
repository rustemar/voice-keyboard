package com.tyraen.voicekeyboard.feature.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechProbabilityEndpointerTest {
    private class Fixture(pauseMs: Long = 500, enabled: Boolean = true) {
        val detector = SpeechProbabilityEndpointer(pauseMs, 0, enabled)
        var now = 0L

        fun frame(probability: Float): Boolean {
            now += 32
            detector.onProbability(probability, now)
            return detector.shouldStop(now)
        }

        fun phrase() {
            repeat(6) { assertFalse(frame(.9f)) }
        }
    }

    @Test fun `initial engine-like low probabilities never establish speech or force a stop`() {
        val f = Fixture()
        repeat(1_000) { assertFalse(f.frame(.02f)) }
    }

    @Test fun `one isolated speech probability does not establish a phrase`() {
        val f = Fixture()
        assertFalse(f.frame(.95f))
        repeat(50) { assertFalse(f.frame(.01f)) }
    }

    @Test fun `separated high frames do not manufacture sustained speech across a measurement gap`() {
        val f = Fixture()
        assertFalse(f.frame(.95f))
        f.now += 200
        assertFalse(f.frame(.95f))
        repeat(50) { assertFalse(f.frame(.01f)) }
    }

    @Test fun `two real frames establish speech and fresh quiet reaches the configured half second`() {
        val f = Fixture()
        assertFalse(f.frame(.8f))
        assertFalse(f.frame(.9f))
        repeat(15) { assertFalse(f.frame(.02f)) }
        assertTrue(f.frame(.02f))
    }

    @Test fun `uncertain voiced tails postpone stopping until probability falls below release threshold`() {
        val f = Fixture()
        f.phrase()
        repeat(30) { assertFalse(f.frame(.4f)) }
        repeat(15) { assertFalse(f.frame(.1f)) }
        assertTrue(f.frame(.1f))
    }

    @Test fun `a shorter pause followed by continued speech stays in the same recording`() {
        val f = Fixture()
        f.phrase()
        repeat(10) { assertFalse(f.frame(.01f)) }
        f.phrase()
        repeat(15) { assertFalse(f.frame(.01f)) }
        assertTrue(f.frame(.01f))
    }

    @Test fun `disabled observations retain speech while enabling grants a full fresh pause`() {
        val f = Fixture(enabled = false)
        f.phrase()
        repeat(40) { assertFalse(f.frame(.01f)) }
        f.detector.setEnabled(true, f.now)
        repeat(15) { assertFalse(f.frame(.01f)) }
        assertTrue(f.frame(.01f))
    }

    @Test fun `changing the duration grants a fresh interval but reapplying it does not postpone forever`() {
        val f = Fixture()
        f.phrase()
        repeat(5) { assertFalse(f.frame(.01f)) }
        f.detector.setPauseMs(300, f.now)
        repeat(9) {
            assertFalse(f.frame(.01f))
            f.detector.setPauseMs(300, f.now)
            f.detector.setEnabled(true, f.now)
        }
        assertTrue(f.frame(.01f))
    }

    @Test fun `missing callbacks never turn elapsed wall time into evidence of quiet`() {
        val f = Fixture()
        f.phrase()
        assertFalse(f.frame(.01f))
        assertFalse(f.detector.shouldStop(f.now + 100))
        assertFalse(f.detector.shouldStop(f.now + 1_000))
        f.now += 1_000
        assertFalse(f.frame(.01f))
        repeat(15) { assertFalse(f.frame(.01f)) }
        assertTrue(f.frame(.01f))
    }

    @Test fun `invalid probabilities cannot act as silence and recovery gets a fresh interval`() {
        val f = Fixture()
        f.phrase()
        repeat(10) { assertFalse(f.frame(.01f)) }
        assertFalse(f.frame(Float.NaN))
        assertFalse(f.frame(Float.POSITIVE_INFINITY))
        assertFalse(f.frame(-.1f))
        assertFalse(f.frame(1.1f))
        repeat(15) { assertFalse(f.frame(.01f)) }
        assertTrue(f.frame(.01f))
    }

    @Test fun `backward timestamps cannot manufacture a pause or reset the observed voice`() {
        val f = Fixture()
        f.phrase()
        f.detector.onProbability(.01f, f.now - 100)
        assertFalse(f.detector.shouldStop(f.now))
        repeat(15) { assertFalse(f.frame(.01f)) }
        assertTrue(f.frame(.01f))
    }

    @Test fun `disabling the timer retains manual completion even after a classified pause`() {
        val f = Fixture()
        f.phrase()
        f.detector.setEnabled(false, f.now)
        repeat(100) { assertFalse(f.frame(.01f)) }
    }
}
