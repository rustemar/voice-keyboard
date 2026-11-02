package com.tyraen.voicekeyboard.feature.audio

import java.io.File
import java.io.RandomAccessFile

/** The canonical, finalized PCM file written by [PcmCaptureFile], never a guessed audio format. */
internal data class PcmUploadSource(val file: File, val pcmBytes: Long) {
    val sampleCount: Long get() = pcmBytes / BYTES_PER_SAMPLE
    val durationUs: Long get() = sampleCount * 1_000_000 / SAMPLE_RATE

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val HEADER_BYTES = 44

        fun read(file: File): PcmUploadSource {
            RandomAccessFile(file, "r").use { input ->
                val pcmBytes = input.length() - HEADER_BYTES
                check(pcmBytes > 0 && pcmBytes % BYTES_PER_SAMPLE == 0L) { "PCM source has no complete samples" }
                val expected = PcmCaptureFile.header(pcmBytes, SAMPLE_RATE)
                val actual = ByteArray(HEADER_BYTES)
                input.readFully(actual)
                check(expected.contentEquals(actual)) { "PCM source is not a finalized 16 kHz mono WAV" }
                return PcmUploadSource(file, pcmBytes)
            }
        }
    }
}
