package com.tyraen.voicekeyboard.feature.audio

import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** A slow detector must never block microphone reads or writes of the user's recording. */
internal class PcmCaptureAnalysis(
    private val onPcm: (ShortArray, Int, Long) -> Unit,
    private val onClosed: () -> Unit,
    capacity: Int = 64
) {
    private data class Frame(val samples: ShortArray, val atMs: Long)
    private val frames = ArrayBlockingQueue<Frame>(capacity)
    private val closed = AtomicBoolean(false)
    private val worker = Thread({ run() }, "PcmSpeechAnalysis").apply {
        isDaemon = true
        start()
    }

    fun offer(samples: ShortArray, count: Int, atMs: Long) {
        require(count in 0..samples.size)
        if (closed.get()) return
        if (!frames.offer(Frame(samples.copyOf(count), atMs))) {
            if (closed.compareAndSet(false, true)) {
                DiagnosticLog.record("Capture", "PCM analysis disabled: backlog limit reached")
                frames.clear()
                worker.interrupt()
            }
        }
    }

    /** Does not join an in-flight model call. Its owner closes only after that call returns. */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            frames.clear()
            worker.interrupt()
        }
    }

    private fun run() {
        try {
            while (!closed.get()) {
                val frame = try {
                    frames.take()
                } catch (_: InterruptedException) {
                    if (closed.get()) break else continue
                }
                if (closed.get()) break
                try {
                    onPcm(frame.samples, frame.samples.size, frame.atMs)
                } catch (error: Exception) {
                    if (closed.compareAndSet(false, true)) {
                        DiagnosticLog.recordFailure("Capture", "PCM analysis disabled", error)
                    }
                    break
                }
            }
        } finally {
            closed.set(true)
            frames.clear()
            try {
                onClosed()
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("Capture", "PCM analysis cleanup failed", error)
            }
        }
    }
}
