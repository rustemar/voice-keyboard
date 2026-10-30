package com.tyraen.voicekeyboard.feature.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSilenceEndpointerTest {
    private class Fixture(pauseMs: Long = 1_200, enabled: Boolean = true) {
        val detector = AdaptiveSilenceEndpointer(pauseMs, 0, enabled)
        var now = 0L

        init { detector.onAmplitude(0, now) } // MediaRecorder's first call has no measured peak.

        fun sample(peak: Int): Boolean {
            now += 150
            detector.onAmplitude(peak, now)
            return detector.shouldStop(now)
        }

        fun background(frames: Int = 8, peak: Int = 900) {
            repeat(frames) { sample(peak) }
        }

        fun phrase() {
            listOf(4_000, 7_000, 3_500, 5_000).forEach { sample(it) }
        }
    }

    @Test fun `steady nonzero background after artificial zero never establishes speech`() {
        val f = Fixture()
        repeat(100) { assertFalse(f.sample(1_200 + (it % 3 - 1) * 50)) }
        repeat(30) { assertFalse(f.sample(100)) }
    }

    @Test fun `initial real quiet never times out`() {
        val f = Fixture()
        repeat(100) { assertFalse(f.sample(0)) }
    }

    @Test fun `speech over a nonzero background stops at the configured pause`() {
        val f = Fixture()
        f.background()
        f.phrase()
        repeat(7) { assertFalse(f.sample(900)) }
        assertTrue(f.sample(900))
    }

    @Test fun `a modulated half second opening phrase survives startup calibration`() {
        val f = Fixture()
        listOf(3_200, 6_000, 2_200).forEach { assertFalse(f.sample(it)) }
        repeat(7) { assertFalse(f.sample(400)) }
        assertTrue(f.sample(400))
    }

    @Test fun `single clicks do not establish speech or postpone an established pause`() {
        val f = Fixture()
        f.background()
        assertFalse(f.sample(25_000))
        repeat(12) { assertFalse(f.sample(900)) }
        f.phrase()
        repeat(4) { assertFalse(f.sample(900)) }
        assertFalse(f.sample(25_000))
        repeat(2) { assertFalse(f.sample(900)) }
        assertTrue(f.sample(900))
    }

    @Test fun `continuous speech keeps recording beyond the history window`() {
        val f = Fixture()
        f.background()
        f.phrase()
        repeat(200) { assertFalse(f.sample(listOf(3_000, 7_000, 2_500, 4_000)[it % 4])) }
    }

    @Test fun `normal quieter speech continues after a much louder phrase`() {
        val f = Fixture()
        f.background(peak = 100)
        listOf(16_000, 22_000, 14_000, 19_000).forEach { assertFalse(f.sample(it)) }
        repeat(32) { assertFalse(f.sample(listOf(1_500, 3_000, 1_800, 2_500)[it % 4])) }
        repeat(7) { assertFalse(f.sample(100)) }
        assertTrue(f.sample(100))
    }

    @Test fun `a single large click before ordinary speech does not raise its activity gate`() {
        val f = Fixture()
        f.background(peak = 100)
        assertFalse(f.sample(30_000))
        repeat(32) { assertFalse(f.sample(listOf(1_500, 3_000, 1_800, 2_500)[it % 4])) }
        repeat(7) { assertFalse(f.sample(100)) }
        assertTrue(f.sample(100))
    }

    @Test fun `a pause shorter than the setting continues the same dictation`() {
        val f = Fixture()
        f.background()
        f.phrase()
        repeat(5) { assertFalse(f.sample(900)) }
        f.phrase()
        repeat(7) { assertFalse(f.sample(900)) }
        assertTrue(f.sample(900))
    }

    @Test fun `enabling after a long pause gives a fresh full interval`() {
        val f = Fixture(enabled = false)
        f.background()
        f.phrase()
        repeat(30) { assertFalse(f.sample(900)) }
        f.detector.setEnabled(true, f.now)
        repeat(7) { assertFalse(f.sample(900)) }
        assertTrue(f.sample(900))
    }

    @Test fun `disabling during speech keeps manual control`() {
        val f = Fixture()
        f.background()
        f.phrase()
        f.detector.setEnabled(false, f.now)
        repeat(30) { assertFalse(f.sample(900)) }
    }

    @Test fun `changing the pause preserves speech and restarts its full interval`() {
        val f = Fixture()
        f.background()
        f.phrase()
        f.sample(900)
        f.detector.setPauseMs(3_000, f.now)
        repeat(19) { assertFalse(f.sample(900)) }
        assertTrue(f.sample(900))
    }

    @Test fun `reapplying the same setting does not indefinitely postpone stopping`() {
        val f = Fixture()
        f.background()
        f.phrase()
        repeat(7) {
            assertFalse(f.sample(900))
            f.detector.setEnabled(true, f.now)
            f.detector.setPauseMs(1_200, f.now)
        }
        assertTrue(f.sample(900))
    }

    @Test fun `missing observations never imply silence and recovery gets a fresh interval`() {
        val f = Fixture()
        f.background()
        f.phrase()
        f.sample(900)
        assertFalse(f.detector.shouldStop(f.now + 3_000))
        f.now += 3_000
        assertFalse(f.sample(900))
        repeat(7) { assertFalse(f.sample(900)) }
        assertTrue(f.sample(900))
    }

    @Test fun `minimum configured pause still requires sustained speech evidence`() {
        val f = Fixture(pauseMs = 300)
        f.background()
        f.phrase()
        assertFalse(f.sample(900))
        assertTrue(f.sample(900))
    }
}
