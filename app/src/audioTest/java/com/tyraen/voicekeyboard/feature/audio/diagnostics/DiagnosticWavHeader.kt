package com.tyraen.voicekeyboard.feature.audio.diagnostics

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A PCM RIFF header; pure so its sizes/alignment can be checked without Android media stubs. */
object DiagnosticWavHeader {
    const val HEADER_BYTES = 44

    fun create(
        pcmBytes: Long,
        sampleRate: Int = DiagnosticPcmFormat.SAMPLE_RATE,
        channelCount: Int = DiagnosticPcmFormat.CHANNEL_COUNT,
        bitsPerSample: Int = DiagnosticPcmFormat.BITS_PER_SAMPLE
    ): ByteArray {
        require(sampleRate in 1..192_000) { "Invalid PCM sample rate" }
        require(channelCount in 1..8) { "Invalid PCM channel count" }
        require(bitsPerSample == 16) { "Diagnostic WAV requires PCM 16" }
        val blockAlign = channelCount * bitsPerSample / 8
        require(pcmBytes in 0..(0xffff_ffffL - 36)) { "PCM does not fit RIFF" }
        require(pcmBytes % blockAlign == 0L) { "PCM ends with a partial frame" }
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + pcmBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes.toInt())
        }.array()
    }
}
