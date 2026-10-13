package com.tyraen.voicekeyboard.feature.transcription

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import com.tyraen.voicekeyboard.feature.ime.ProcessingQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatCompletionsSpeechClientTest {

    @Test fun `records use the right input audio format`() {
        assertEquals("ogg", ChatCompletionsSpeechClient.audioFormat(File("speech.ogg")))
        assertEquals("mp4", ChatCompletionsSpeechClient.audioFormat(File("speech.m4a")))
        assertEquals("wav", ChatCompletionsSpeechClient.audioFormat(File("speech.wav")))
        assertEquals("mp3", ChatCompletionsSpeechClient.audioFormat(File("speech.mp3")))
    }

    @Test fun `chat response returns text and rejects a missing transcript`() {
        assertEquals("Hello", ChatCompletionsSpeechClient.responseText("""{"choices":[{"finish_reason":"stop","message":{"content":" Hello "}}]}"""))
        assertEquals("Hi there", ChatCompletionsSpeechClient.responseText("""{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":"Hi "},{"type":"text","text":"there"}]}}]}"""))
        assertEquals("", ChatCompletionsSpeechClient.responseText("""{"choices":[{"finish_reason":"stop","message":{"content":""}}]}"""))
        assertEquals("", ChatCompletionsSpeechClient.responseText("""{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text","text":""}]}}]}"""))
    }

    @Test fun `incomplete filtered or missing chat transcripts are failures`() {
        val invalid = listOf(
            """{"choices":[{"finish_reason":"length","message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"content_filter","message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"partial"}}]}""",
            """{"choices":[{"message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":null,"message":{"content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"refusal":"Cannot comply","content":"partial"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":null}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":[]}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"refusal","refusal":"No"}]}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"image_url","image_url":{"url":"x"}}]}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"text"}]}}]}"""
        )
        invalid.forEach { response ->
            assertTrue(response, runCatching { ChatCompletionsSpeechClient.responseText(response) }.isFailure)
        }
    }

    @Test fun `sends chat audio and reads transcript without a network request`() {
        val audio = File.createTempFile("dictation", ".ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var sent: JSONObject? = null
        var auth = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            auth = request.header("Authorization").orEmpty()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            sent = JSONObject(buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"choices":[{"finish_reason":"stop","message":{"content":"Привет"}}]}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        try {
            val config = TranscriptionConfig("test-key", "https://example.com/v1/chat/completions", "audio-model", "ru", "Preserve names")
            val result = runBlocking { ChatCompletionsSpeechClient(http).transcribe(audio, config) }
            assertEquals("Привет", result.getOrThrow())
            assertEquals("Bearer test-key", auth)
            assertEquals("audio-model", sent!!.getString("model"))
            assertFalse(sent!!.getBoolean("stream"))
            val messages = sent!!.getJSONArray("messages")
            val instruction = messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(instruction.contains("Language: ru"))
            assertTrue(instruction.contains("Preserve names"))
            val input = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getJSONObject("input_audio")
            assertEquals("ogg", input.getString("format"))
            assertEquals("AQID", input.getString("data"))
        } finally {
            audio.delete()
        }
    }

    @Test fun `mocked incomplete responses fail and retain the original audio for retry`() {
        val audio = File.createTempFile("dictation", ".ogg").apply { writeBytes(byteArrayOf(10, 20, 30)) }
        val responses = listOf(
            """{"choices":[{"finish_reason":"length","message":{"content":"half"}}]}""",
            """{"choices":[{"finish_reason":"content_filter","message":{"content":"filtered"}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"refusal","refusal":"No"}]}}]}""",
            """{"choices":[{"finish_reason":"stop","message":{"content":[{"type":"image_url","image_url":{"url":"x"}}]}}]}"""
        )
        try {
            for (body in responses) {
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
                val config = TranscriptionConfig("key", "https://example.com/v1/chat/completions", "model", "", "")
                val result = runBlocking { ChatCompletionsSpeechClient(http).transcribe(audio, config) }
                assertTrue(body, result.isFailure)
                assertFalse("Queue must park, not discard, failed transcripts", ProcessingQueue.isTransientError(result.exceptionOrNull()))
                assertTrue("Audio must still exist for the queue to park it", audio.exists())
                assertEquals(listOf(10, 20, 30), audio.readBytes().map { it.toInt() })
            }
        } finally {
            audio.delete()
        }
    }
}
