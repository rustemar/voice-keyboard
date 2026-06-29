package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ParkedRecordingTest {

    private fun sampleItem(withPp: Boolean): ProcessingQueue.QueueItem {
        val pp = if (withPp) PostProcessingPreferences(
            enabled = true,
            provider = PostProcessingPreferences.PROVIDER_CLAUDE,
            apiKey = "SECRET_PP_KEY",
            endpoint = "https://api.anthropic.com/v1/messages",
            model = "claude-sonnet-4-6",
            temperature = 0.4f,
            promptFix = "fix prompt",
            promptShorten = "shorten prompt",
            promptEmoji = "emoji prompt",
            promptSuffix = "suffix",
            translateLang = "de",
            translateModel = "claude-opus",
            terminalVisible = true
        ) else null
        return ProcessingQueue.QueueItem(
            audioFile = File("/cache/recording_3.ogg"),
            transcriptionConfig = TranscriptionConfig(
                apiKey = "SECRET_WHISPER_KEY",
                endpoint = "https://api.groq.com/openai/v1/audio/transcriptions",
                model = "whisper-large-v3",
                language = "ru",
                prompt = "контекст, словарь",
                vocabulary = "Рамиль\nкогорта",
                recordingDurationMs = 147252L
            ),
            addTrailingSpace = true,
            singleWordStripPunctuation = true,
            vocabulary = "Рамиль\nкогорта",
            ppPreferences = pp,
            ppFix = withPp,
            ppShorten = false,
            ppEmoji = withPp,
            ppRhyme = false,
            ppTranslate = withPp,
            ppTerminal = false
        )
    }

    @Test fun `json round-trip preserves all non-secret fields`() {
        val original = ParkedRecording.fromQueueItem(
            sampleItem(withPp = true),
            id = "parked_123_0",
            audioPath = "/files/parked/parked_123_0.ogg",
            state = ParkedRecording.State.WAITING_NETWORK,
            attemptCount = 2
        )
        val restored = ParkedRecording.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test fun `secrets are never written to disk`() {
        val json = ParkedRecording.fromQueueItem(
            sampleItem(withPp = true),
            id = "parked_1_0",
            audioPath = "/files/parked/parked_1_0.ogg",
            state = ParkedRecording.State.WAITING_NETWORK,
            attemptCount = 0
        ).toJson()
        assertFalse("Whisper key leaked", json.contains("SECRET_WHISPER_KEY"))
        assertFalse("Post-processing key leaked", json.contains("SECRET_PP_KEY"))
    }

    @Test fun `toQueueItem re-injects keys read from preferences`() {
        val rec = ParkedRecording.fromQueueItem(
            sampleItem(withPp = true),
            id = "parked_1_0",
            audioPath = "/files/parked/parked_1_0.ogg",
            state = ParkedRecording.State.WAITING_NETWORK,
            attemptCount = 0
        )
        val rebuilt = rec.toQueueItem(apiKey = "NEW_WHISPER", ppApiKey = "NEW_PP")
        assertEquals("NEW_WHISPER", rebuilt.transcriptionConfig.apiKey)
        assertEquals("NEW_PP", rebuilt.ppPreferences?.apiKey)
        // Non-secret snapshot survives unchanged.
        assertEquals("whisper-large-v3", rebuilt.transcriptionConfig.model)
        assertEquals(147252L, rebuilt.transcriptionConfig.recordingDurationMs)
        assertEquals("de", rebuilt.ppPreferences?.translateLang)
        assertTrue(rebuilt.ppEmoji)
        assertTrue(rebuilt.ppTranslate)
        assertFalse(rebuilt.ppShorten)
        assertEquals("parked_1_0", rebuilt.parkedId)
    }

    @Test fun `recording without post-processing round-trips with null pp`() {
        val rec = ParkedRecording.fromQueueItem(
            sampleItem(withPp = false),
            id = "parked_2_0",
            audioPath = "/files/parked/parked_2_0.ogg",
            state = ParkedRecording.State.NEEDS_ATTENTION,
            attemptCount = 5
        )
        val restored = ParkedRecording.fromJson(rec.toJson())!!
        assertNull(restored.pp)
        assertEquals(ParkedRecording.State.NEEDS_ATTENTION, restored.state)
        assertEquals(5, restored.attemptCount)
        val rebuilt = restored.toQueueItem(apiKey = "K", ppApiKey = "")
        assertNull(rebuilt.ppPreferences)
    }

    @Test fun `malformed json returns null instead of throwing`() {
        assertNull(ParkedRecording.fromJson("not json"))
        assertNull(ParkedRecording.fromJson("{}"))
    }
}
