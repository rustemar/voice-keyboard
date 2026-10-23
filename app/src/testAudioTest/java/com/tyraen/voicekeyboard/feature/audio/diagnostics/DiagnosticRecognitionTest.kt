package com.tyraen.voicekeyboard.feature.audio.diagnostics

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class DiagnosticRecognitionTest {
    private fun variant(file: File) = DiagnosticAudioVariant(DiagnosticAudioProfile.WAV, DiagnosticVariantStatus.SUCCESS, file)
    private fun config(server: MockWebServer) = DiagnosticRecognitionConfig(server.url("/audio/transcriptions").toString(), "test-model", "ru", "same hint")

    @Test fun `raw transcript and original audio reach the direct comparison client`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("{\"text\":\"Спасибо.\"}"))
        server.start()
        val sample = byteArrayOf(82, 73, 70, 70, 0, 1, 2, 3)
        val file = File.createTempFile("audio-client", ".wav").apply { writeBytes(sample) }
        try {
            val result = DiagnosticTranscriber().transcribe(variant(file), config(server), "test-secret")
            val received = server.takeRequest(2, TimeUnit.SECONDS)!!
            val request = received.body.readByteArray().toString(StandardCharsets.ISO_8859_1)
            assertEquals("Спасибо.", result.text)
            assertEquals(200, result.httpCode)
            assertNull(result.error)
            assertEquals("Bearer test-secret", received.getHeader("Authorization"))
            assertTrue(request.contains(sample.toString(StandardCharsets.ISO_8859_1)))
            fun part(name: String) = request.substringAfter("name=\"$name\"", "")
                .substringAfter("\r\n\r\n", "").substringBefore("\r\n")
            assertEquals("json", part("response_format"))
            assertEquals("0", part("temperature"))
            assertTrue(request.contains("same hint"))
            assertFalse(request.contains("reference"))
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun `provider error does not export a body that echoes the key`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"test-secret\"}"))
        server.start()
        val file = File.createTempFile("audio-client", ".wav").apply { writeBytes(byteArrayOf(0)) }
        try {
            val result = DiagnosticTranscriber().transcribe(variant(file), config(server), "test-secret")
            assertEquals("HTTP 401", result.error)
            assertFalse(result.toString().contains("test-secret"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun `coroutine cancellation terminates a waiting request`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val file = File.createTempFile("audio-cancel", ".wav").apply { writeBytes(byteArrayOf(0)) }
        try {
            val client = DiagnosticTranscriber()
            val pending = launch { client.transcribe(variant(file), config(server), "test-secret"); fail("Cancelled request returned a result") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(1_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown(); file.delete() }
    }

    @Test fun `endpoint cannot hide credentials or route to chat completions`() {
        fun valid(url: String) = DiagnosticRecognitionConfig(url, "model", "", "").isValid()
        assertTrue(valid("https://api.groq.com/openai/v1/audio/transcriptions"))
        assertFalse(valid("https://key:secret@example.com/audio/transcriptions"))
        assertFalse(valid("https://example.com/audio/transcriptions?key=secret"))
        assertFalse(valid("https://example.com/chat/completions"))
    }

    @Test fun `word comparison detects substitutions additions and empty hypotheses`() {
        assertEquals(0, DiagnosticWordErrors.compare("Никки Бич!", "никки бич")!!.errors)
        assertEquals(1, DiagnosticWordErrors.compare("Никки Бич", "некий бич")!!.errors)
        assertEquals(3, DiagnosticWordErrors.compare("one two", "three four five")!!.errors)
        assertEquals(2, DiagnosticWordErrors.compare("one two", "")!!.errors)
        assertNull(DiagnosticWordErrors.compare("...", "anything"))
    }

    @Test fun `export removes a JSON sensitive key from every report field`() {
        val secret = "test\"key\\value"
        val file = File("unused.pcm")
        val capture = DiagnosticPcmCapture(file, 960, 10, 10, 0, "hash", 0, false, mapOf("note" to secret))
        val comparison = DiagnosticAudioComparison(capture, listOf(variant(file)), File("unused"), 0)
        val settings = DiagnosticRecognitionConfig("https://example.com/audio/transcriptions", "model", "ru", secret)
        val result = DiagnosticTranscription("wav", 200, secret, 1, 1, 0, secret, null)
        val json = JSONObject(DiagnosticReport.json(comparison, settings, listOf(result), secret) {
            it.replace(secret, "[redacted]")
        })
        assertEquals("[redacted]", json.getString("reference"))
        assertEquals("[redacted]", json.getJSONObject("recognition").getString("prompt"))
        assertEquals("[redacted]", json.getJSONObject("capture").getJSONObject("source_settings").getString("note"))
        assertEquals("[redacted]", json.getJSONArray("variants").getJSONObject(0).getJSONObject("recognition").getString("request_id"))
    }
}
