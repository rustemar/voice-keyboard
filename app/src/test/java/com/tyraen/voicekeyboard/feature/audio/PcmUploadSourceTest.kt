package com.tyraen.voicekeyboard.feature.audio

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmUploadSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun wav(name: String, bytes: Int, sampleRate: Int = 16_000): File = temporary.newFile(name).apply {
        outputStream().use {
            it.write(PcmCaptureFile.header(bytes.toLong(), sampleRate))
            it.write(ByteArray(bytes))
        }
    }

    @Test fun `reads exact finalized capture sizes without treating WAV header as PCM`() {
        val file = wav("complete.wav", 9_600)
        val source = PcmUploadSource.read(file)
        assertEquals(file, source.file)
        assertEquals(9_600L, source.pcmBytes)
        assertEquals(4_800L, source.sampleCount)
        assertEquals(300_000L, source.durationUs)
        assertEquals(9_644L, file.length())
    }

    @Test fun `rejects different sample rate rather than silently changing playback speed`() {
        val file = wav("48k.wav", 9_600, 48_000)
        assertThrows(IllegalStateException::class.java) { PcmUploadSource.read(file) }
        assertTrue(file.exists())
    }

    @Test fun `rejects stale or truncated WAV header and preserves source`() {
        for (delta in listOf(-2L, 2L)) {
            val file = wav("size-$delta.wav", 9_600)
            RandomAccessFile(file, "rw").use { it.setLength(it.length() + delta) }
            assertThrows(IllegalStateException::class.java) { PcmUploadSource.read(file) }
            assertTrue(file.exists())
        }
    }

    @Test fun `rejects empty or partial final PCM sample`() {
        val empty = wav("empty.wav", 0)
        assertThrows(IllegalStateException::class.java) { PcmUploadSource.read(empty) }
        val odd = wav("odd.wav", 2)
        RandomAccessFile(odd, "rw").use { it.setLength(it.length() - 1) }
        assertThrows(IllegalStateException::class.java) { PcmUploadSource.read(odd) }
    }

    @Test fun `rejects PCM bytes masquerading as a header`() {
        val file = temporary.newFile("raw.wav").apply { writeBytes(ByteArray(9_644)) }
        assertThrows(IllegalStateException::class.java) { PcmUploadSource.read(file) }
        assertEquals(9_644L, file.length())
    }
}
