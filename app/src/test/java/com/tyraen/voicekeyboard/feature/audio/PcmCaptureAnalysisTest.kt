package com.tyraen.voicekeyboard.feature.audio

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmCaptureAnalysisTest {
    private fun CountDownLatch.waitForTest() = assertTrue("Worker did not finish", await(3, TimeUnit.SECONDS))

    private fun CountDownLatch.awaitUninterruptibly() {
        while (count > 0) {
            try { await() } catch (_: InterruptedException) {}
        }
    }

    @Test fun `analysis owns a copy and closes only after the active callback returns`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closedCalls = AtomicInteger()
        val observed = AtomicInteger()
        val analysis = PcmCaptureAnalysis(
            onPcm = { samples, count, atMs ->
                entered.countDown()
                release.awaitUninterruptibly()
                assertEquals(2, count)
                assertEquals(123L, atMs)
                observed.set(samples[0].toInt())
            },
            onClosed = { closedCalls.incrementAndGet(); closed.countDown() }
        )
        try {
            val reusedMicrophoneBuffer = shortArrayOf(7, 8, 9)
            analysis.offer(reusedMicrophoneBuffer, 2, 123)
            entered.waitForTest()
            reusedMicrophoneBuffer[0] = 99
            analysis.close()
            analysis.close()
            assertEquals(1L, closed.count)
            release.countDown()
            closed.waitForTest()
            assertEquals(7, observed.get())
            assertEquals(1, closedCalls.get())
        } finally {
            release.countDown()
            analysis.close()
        }
    }

    @Test fun `slow analysis overflow disables detection without blocking the producer`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val analysis = PcmCaptureAnalysis(
            onPcm = { _, _, _ ->
                callbacks.incrementAndGet()
                entered.countDown()
                release.awaitUninterruptibly()
            },
            onClosed = { closed.countDown() },
            capacity = 1
        )
        try {
            analysis.offer(shortArrayOf(1), 1, 10)
            entered.waitForTest()
            analysis.offer(shortArrayOf(2), 1, 20)
            analysis.offer(shortArrayOf(3), 1, 30)
            analysis.offer(shortArrayOf(4), 1, 40)
            release.countDown()
            closed.waitForTest()
            assertEquals(1, callbacks.get())
        } finally {
            release.countDown()
            analysis.close()
        }
    }

    @Test fun `analysis failure closes once and prevents further detector calls`() {
        val closed = CountDownLatch(1)
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val analysis = PcmCaptureAnalysis(
            onPcm = { _, _, _ -> calls.incrementAndGet(); throw IOException("private provider content") },
            onClosed = { closes.incrementAndGet(); closed.countDown() }
        )
        analysis.offer(shortArrayOf(1), 1, 10)
        closed.waitForTest()
        analysis.offer(shortArrayOf(2), 1, 20)
        analysis.close()
        assertEquals(1, calls.get())
        assertEquals(1, closes.get())
    }

    @Test fun `native detector initialization failure stays within analysis`() {
        assertNativeFailureContained(failDuringCleanup = false)
    }

    @Test fun `native detector cleanup failure stays within analysis`() {
        assertNativeFailureContained(failDuringCleanup = true)
    }

    private fun assertNativeFailureContained(failDuringCleanup: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val uncaught = AtomicReference<Throwable?>(null)
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val analysis = PcmCaptureAnalysis(
            onPcm = { _, _, _ ->
                calls.incrementAndGet()
                worker.set(Thread.currentThread())
                entered.countDown()
                release.awaitUninterruptibly()
                if (!failDuringCleanup) throw UnsatisfiedLinkError("Native detector unavailable")
            },
            onClosed = {
                try {
                    closes.incrementAndGet()
                    if (failDuringCleanup) throw ExceptionInInitializerError("Native cleanup unavailable")
                } finally {
                    closed.countDown()
                }
            }
        )
        try {
            analysis.offer(shortArrayOf(1), 1, 10)
            entered.waitForTest()
            worker.get().setUncaughtExceptionHandler { _, error -> uncaught.set(error) }
            if (failDuringCleanup) analysis.close()
            release.countDown()
            closed.waitForTest()
            worker.get().join(3_000)
            assertFalse("Analysis worker did not exit", worker.get().isAlive)
            assertNull("Native detector errors must not escape the worker", uncaught.get())
            analysis.offer(shortArrayOf(2), 1, 20)
            analysis.close()
            assertEquals(1, calls.get())
            assertEquals(1, closes.get())
        } finally {
            release.countDown()
            analysis.close()
        }
    }
}
