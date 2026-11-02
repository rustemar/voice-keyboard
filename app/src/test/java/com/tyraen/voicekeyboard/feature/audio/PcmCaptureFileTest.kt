package com.tyraen.voicekeyboard.feature.audio

import java.io.RandomAccessFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmCaptureFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `finished WAV contains only valid sample count with correct PCM sizes`() {
        val file = temporary.newFile("recording.wav")
        val capture = PcmCaptureFile(file)
        val samples = ShortArray(5_000) { if (it % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE }
        assertTrue(capture.append(samples, 4_800))

        assertEquals(file, capture.finish(300))
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals(9_636, header.getInt(4))
        assertEquals(1.toShort(), header.getShort(22))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals(9_600, header.getInt(40))
        assertEquals(9_644, bytes.size)
        assertEquals(300L, capture.durationMs)
        assertArrayEquals(byteArrayOf(0, -128, -1, 127), bytes.copyOfRange(44, 48))
        assertEquals(file, capture.finish(300))
    }

    @Test fun `finishing repairs an incomplete suffix without changing complete samples`() {
        val file = temporary.newFile("recording.wav")
        val capture = PcmCaptureFile(file)
        capture.append(ShortArray(4_800) { 123 }, 4_800)
        RandomAccessFile(file, "rw").use {
            it.seek(it.length())
            it.write(byteArrayOf(99, 88, 77))
        }

        assertEquals(file, capture.finish(300))
        assertEquals(9_644L, file.length())
        assertArrayEquals(byteArrayOf(123, 0), file.readBytes().takeLast(2).toByteArray())
    }

    @Test fun `too short capture removes only its own file`() {
        val file = temporary.newFile("short.wav")
        val unrelated = temporary.newFile("previous-queued.wav").apply { writeText("retained") }
        val capture = PcmCaptureFile(file)
        capture.append(ShortArray(4_799), 4_799)

        assertNull(capture.finish(300))
        assertFalse(file.exists())
        assertEquals("retained", unrelated.readText())
    }

    @Test fun `cancellation wins before writing and after asynchronous finalization`() {
        for (finishFirst in listOf(false, true)) {
            val file = temporary.newFile("cancel-$finishFirst.wav")
            val capture = PcmCaptureFile(file)
            capture.append(ShortArray(4_800), 4_800)
            if (finishFirst) assertEquals(file, capture.finish(300))

            capture.cancel()
            capture.cancel()

            assertFalse(capture.append(shortArrayOf(1), 1))
            assertNull(capture.finish(300))
            assertFalse(file.exists())
        }
    }

    @Test fun `fsync failure preserves source and fresh repair returns the original PCM`() {
        val file = temporary.newFile("sync-failure.wav")
        val capture = PcmCaptureFile(file, syncFile = { throw IOException("simulated sync failure") })
        capture.append(ShortArray(4_800) { 321 }, 4_800)
        val originalPcm = file.readBytes().drop(44).toByteArray()

        assertThrows(IOException::class.java) { capture.finish(300) }
        assertTrue(file.isFile)
        RandomAccessFile(file, "rw").use { it.seek(it.length()); it.write(byteArrayOf(99)) }

        assertEquals(file, capture.recover(300))
        assertEquals(9_644L, file.length())
        assertArrayEquals(originalPcm, file.readBytes().drop(44).toByteArray())
        assertEquals(9_600, ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun `persistent header failure retains complete source instead of deleting or returning it`() {
        val file = temporary.newFile("header-failure.wav")
        val capture = PcmCaptureFile(file, writeHeader = { _, _ -> throw IOException("simulated header failure") })
        capture.append(ShortArray(4_800) { 321 }, 4_800)
        val originalPcm = file.readBytes().drop(44).toByteArray()

        assertThrows(IOException::class.java) { capture.finish(300) }
        assertThrows(IOException::class.java) { capture.recover(300) }

        assertTrue(file.isFile)
        assertEquals(9_644L, file.length())
        assertArrayEquals(originalPcm, file.readBytes().drop(44).toByteArray())
    }

    @Test fun `repair never manufactures replacement samples for missing PCM`() {
        val file = temporary.newFile("truncated-source.wav")
        val capture = PcmCaptureFile(file, syncFile = { throw IOException("simulated sync failure") })
        capture.append(ShortArray(4_800), 4_800)
        assertThrows(IOException::class.java) { capture.finish(300) }
        RandomAccessFile(file, "rw").use { it.setLength(64) }

        assertThrows(IllegalStateException::class.java) { capture.recover(300) }
        assertEquals(64L, file.length())
    }
}
