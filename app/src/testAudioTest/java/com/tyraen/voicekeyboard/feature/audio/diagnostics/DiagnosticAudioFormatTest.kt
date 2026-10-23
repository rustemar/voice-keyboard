package com.tyraen.voicekeyboard.feature.audio.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DiagnosticAudioFormatTest {
    @Test fun `thirty second baseline has correct RIFF sizes and unchanged PCM format`() {
        val bytes = 48_000L * 2 * 30
        val header = DiagnosticWavHeader.create(bytes)
        val little = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + bytes, little.getInt(4).toLong())
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals(16, little.getInt(16))
        assertEquals(1, little.getShort(20).toInt())
        assertEquals(1, little.getShort(22).toInt())
        assertEquals(48_000, little.getInt(24))
        assertEquals(96_000, little.getInt(28))
        assertEquals(2, little.getShort(32).toInt())
        assertEquals(16, little.getShort(34).toInt())
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(bytes, little.getInt(40).toLong())
    }

    @Test fun `invalid and partial PCM cannot create a misleading WAV`() {
        for (size in listOf(-1L, 1L, 0xffff_ffffL)) {
            assertThrows(IllegalArgumentException::class.java) { DiagnosticWavHeader.create(size) }
        }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticWavHeader.create(4, sampleRate = 0) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticWavHeader.create(4, bitsPerSample = 8) }
        assertThrows(IllegalArgumentException::class.java) { DiagnosticWavHeader.create(2, channelCount = 2) }
    }

    @Test fun `capture cap has a bounded whole number of mono PCM frames`() {
        assertEquals(4_320_000L, DiagnosticPcmFormat.MAX_PCM_BYTES)
        assertEquals(45_000L, DiagnosticPcmFormat.durationMs(DiagnosticPcmFormat.MAX_PCM_BYTES))
        assertEquals(30_000L, DiagnosticPcmFormat.durationMs(2_880_000))
        assertEquals(0L, DiagnosticPcmFormat.MAX_PCM_BYTES % DiagnosticPcmFormat.BYTES_PER_FRAME)
    }

    @Test fun `legacy Android keeps WAV and AAC but never silently replaces Opus`() {
        for (api in listOf(24, 28)) {
            val supported = DiagnosticAudioProfile.values().filter { it.isSupportedOn(api) }
            assertEquals(listOf(DiagnosticAudioProfile.WAV, DiagnosticAudioProfile.AAC_128), supported)
        }
        assertTrue(DiagnosticAudioProfile.values().all { it.isSupportedOn(29) })
        assertEquals(listOf(64_000, 128_000, 200_000), DiagnosticAudioProfile.values()
            .filter { it.mimeType == "audio/opus" }.map { it.bitRate })
        assertEquals(128_000, DiagnosticAudioProfile.AAC_128.bitRate)
        assertNull(DiagnosticAudioProfile.WAV.bitRate)
    }
}
