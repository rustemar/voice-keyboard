package com.tyraen.voicekeyboard.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SilentWavGenerator is private to WhisperApiClient.kt — exercised via reflection
 * to keep the production surface small while still verifying header correctness.
 */
class SilentWavGeneratorTest {

    private val bytes: ByteArray by lazy {
        val cls = Class.forName("com.tyraen.voicekeyboard.feature.transcription.SilentWavGenerator")
        val instance = cls.getDeclaredField("INSTANCE").get(null)
        val method: Method = cls.getDeclaredMethod("generate")
        method.invoke(instance) as ByteArray
    }

    @Test fun `header starts with RIFF and WAVE markers`() {
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('I'.code.toByte(), bytes[1])
        assertEquals('F'.code.toByte(), bytes[2])
        assertEquals('F'.code.toByte(), bytes[3])
        assertEquals('W'.code.toByte(), bytes[8])
        assertEquals('A'.code.toByte(), bytes[9])
        assertEquals('V'.code.toByte(), bytes[10])
        assertEquals('E'.code.toByte(), bytes[11])
    }

    @Test fun `format chunk is PCM mono 16-bit at 16kHz`() {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val audioFormat = buf.getShort(20)   // 1 = PCM
        val numChannels = buf.getShort(22)
        val sampleRate = buf.getInt(24)
        val bitsPerSample = buf.getShort(34)
        assertEquals(1, audioFormat.toInt())
        assertEquals(1, numChannels.toInt())
        assertEquals(16000, sampleRate)
        assertEquals(16, bitsPerSample.toInt())
    }

    @Test fun `data length matches advertised data chunk size`() {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = buf.getInt(40)
        // 100ms at 16kHz mono 16-bit = 1600 samples * 2 bytes
        assertEquals(3200, dataSize)
        assertEquals(44 + dataSize, bytes.size)
    }

    @Test fun `silent payload is all zero bytes`() {
        for (i in 44 until bytes.size) {
            assertEquals("byte at $i should be 0", 0, bytes[i].toInt())
        }
    }
}
