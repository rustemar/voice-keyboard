package com.tyraen.voicekeyboard.feature.audio

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparedUploadAudioTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun prepared(source: File, upload: File) = PreparedUploadAudio(source, upload) { files ->
        files.forEach { it.delete() }
    }

    @Test fun `queue admission removes only redundant source and later cancellation leaves queued output alone`() {
        val source = temporary.newFile("source.wav")
        val upload = temporary.newFile("upload.ogg")
        val other = temporary.newFile("other.ogg")
        val audio = prepared(source, upload)

        audio.markEnqueued()
        audio.cancel()
        audio.retainSource()
        audio.markEnqueued()

        assertFalse(source.exists())
        assertTrue(upload.exists())
        assertTrue(other.exists())
    }

    @Test fun `cancel before admission deletes only this handover files and is idempotent`() {
        val source = temporary.newFile("source.wav")
        val upload = temporary.newFile("upload.m4a")
        val other = temporary.newFile("other.wav")
        val audio = prepared(source, upload)

        audio.cancel()
        audio.cancel()
        audio.markEnqueued()

        assertFalse(source.exists())
        assertFalse(upload.exists())
        assertTrue(other.exists())
    }

    @Test fun `handover failure preserves PCM and discards only compressed derivative`() {
        val source = temporary.newFile("source.wav")
        val upload = temporary.newFile("upload.ogg")
        val audio = prepared(source, upload)

        audio.retainSource()
        audio.retainSource()
        audio.cancel()

        assertTrue(source.exists())
        assertFalse(upload.exists())
    }

    @Test fun `WAV fallback remains queue owned on admission or recoverable on error`() {
        for (retain in listOf(false, true)) {
            val source = temporary.newFile("fallback-$retain.wav")
            val audio = prepared(source, source)
            if (retain) audio.retainSource() else audio.markEnqueued()
            audio.cancel()
            assertTrue(source.exists())
        }
    }

    @Test fun `cancelled WAV fallback is deleted once`() {
        val source = temporary.newFile("source.wav")
        var deletionCount = 0
        val audio = PreparedUploadAudio(source, source) { files ->
            deletionCount += files.size
            files.forEach { it.delete() }
        }
        audio.cancel()
        audio.cancel()
        assertEquals(1, deletionCount)
        assertFalse(source.exists())
    }

    @Test fun `cleanup scheduler failure cannot throw after queue admission or reverse ownership`() {
        val source = temporary.newFile("source.wav")
        val upload = temporary.newFile("upload.ogg")
        var attempts = 0
        val audio = PreparedUploadAudio(source, upload) {
            attempts++
            throw IOException("simulated cleanup scheduler failure")
        }
        audio.markEnqueued()
        audio.cancel()
        assertEquals(1, attempts)
        assertTrue(upload.exists())
    }
}
