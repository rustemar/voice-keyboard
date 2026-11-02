package com.tyraen.voicekeyboard.feature.audio

/** Exact 32 ms analysis frames from mono PCM16 at 16 kHz, without modifying the recorded PCM. */
internal class Pcm16VoiceFrameAssembler {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 512
    }

    private val frame = FloatArray(FRAME_SAMPLES)
    private var filled = 0
    private var lastFrameAtMs: Long? = null

    /** [onFrame] may use the reusable frame only for the duration of its callback. */
    fun accept(
        samples: ShortArray,
        count: Int,
        observedAtMs: Long,
        onFrame: (FloatArray, Long) -> Unit
    ) {
        require(count in 0..samples.size)
        var offset = 0
        while (offset < count) {
            val copied = minOf(FRAME_SAMPLES - filled, count - offset)
            for (i in 0 until copied) frame[filled + i] = samples[offset + i] / 32768.0f
            filled += copied
            offset += copied
            if (filled == FRAME_SAMPLES) {
                // A chunk can contain several frames. Date each by its own captured endpoint,
                // rather than making an already-buffered chunk look like one long silence.
                val estimatedAtMs = observedAtMs - (count - offset) * 1000L / SAMPLE_RATE
                val atMs = maxOf(estimatedAtMs, lastFrameAtMs ?: estimatedAtMs)
                filled = 0
                lastFrameAtMs = atMs
                onFrame(frame, atMs)
            }
        }
    }

    fun reset() {
        frame.fill(0f)
        filled = 0
        lastFrameAtMs = null
    }
}
