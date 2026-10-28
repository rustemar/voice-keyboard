package com.tyraen.voicekeyboard.feature.transcription

import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class WhisperApiClientTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `OpenRouter validation sends multipart WAV with a JSON response format`() = runBlocking {
        for (host in listOf("openrouter.ai", "eu.openrouter.ai")) {
            withApi(host) { client, endpoint, server ->
                val cache = files.newFolder()
                assertTrue(client.validateCredentials("test-key", endpoint, "mistralai/voxtral-mini-transcribe", cache).isSuccess)

                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/api/v1/audio/transcriptions", request.path)
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
                val body = request.body.readUtf8()
                assertPart(body, "model", "mistralai/voxtral-mini-transcribe")
                assertPart(body, "response_format", "json")
                assertTrue(body.contains("name=\"file\"; filename=\"test.wav\""))
                assertTrue(body.contains("Content-Type: audio/wav"))
                assertTrue(body.contains("RIFF"))
                assertFalse(body.contains("name=\"language\""))
                assertFalse(body.contains("name=\"prompt\""))
                assertFalse(File(cache, "validate_test.wav").exists())
            }
        }
    }

    @Test fun `OpenRouter dictation requests JSON and inserts only the transcript`() = runBlocking {
        val cases = listOf("openrouter.ai" to "ogg", "eu.openrouter.ai" to "m4a")
        for ((host, extension) in cases) {
            withApi(host) { client, endpoint, server ->
                val audio = files.newFile("dictation.$extension").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val config = TranscriptionConfig("test-key", endpoint, "mistralai/voxtral-mini-transcribe", "ru", "Keep names")
                assertEquals("Привет, мир!", client.transcribe(audio, config).getOrThrow())

                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("Bearer test-key", request.getHeader("Authorization"))
                val body = request.body.readUtf8()
                assertPart(body, "response_format", "json")
                assertPart(body, "model", "mistralai/voxtral-mini-transcribe")
                assertPart(body, "language", "ru")
                assertPart(body, "prompt", "Keep names")
                assertTrue(body.contains("name=\"file\"; filename=\"dictation.$extension\""))
                assertTrue(body.contains("Content-Type: ${if (extension == "ogg") "audio/ogg" else "audio/mp4"}"))
                assertTrue(audio.exists())
            }
        }
    }

    @Test fun `other providers and custom hosts keep text mode for validation and dictation`() = runBlocking {
        val hosts = listOf("api.groq.com", "api.mistral.ai", "speech.example.org", "evil-openrouter.ai")
        for (host in hosts) {
            withApi(host) { client, endpoint, server ->
                assertTrue(client.validateCredentials("test-key", endpoint, "speech-model", files.newFolder()).isSuccess)
                assertPart(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8(), "response_format", "text")

                server.enqueue(MockResponse().setBody("Привет, мир!"))
                val audio = files.newFile("$host.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
                val config = TranscriptionConfig("test-key", endpoint, "speech-model", "", "")
                assertEquals("Привет, мир!", client.transcribe(audio, config).getOrThrow())
                val body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
                assertPart(body, "response_format", "text")
                assertFalse(body.contains("name=\"language\""))
                assertFalse(body.contains("name=\"prompt\""))
            }
        }
    }

    @Test fun `OpenRouter errors remain failures and validation removes its temporary file`() = runBlocking {
        withApi("openrouter.ai", MockResponse().setResponseCode(404)
            .setBody("""{"error":{"message":"Unknown model"}}""")) { client, endpoint, server ->
            val cache = files.newFolder()
            val validation = client.validateCredentials("test-key", endpoint, "unknown", cache)
            assertTrue(validation.exceptionOrNull()!!.message!!.contains("Unknown model"))
            assertFalse(File(cache, "validate_test.wav").exists())

            server.enqueue(MockResponse().setResponseCode(402).setBody("""{"error":{"message":"Insufficient credits"}}"""))
            val audio = files.newFile("retry.ogg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val result = client.transcribe(audio, TranscriptionConfig("test-key", endpoint, "speech-model", "", ""))
            assertTrue(result.exceptionOrNull()!!.message!!.contains("API error 402"))
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Insufficient credits"))
            assertTrue(audio.exists())
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        }
    }

    private fun assertPart(body: String, name: String, value: String) {
        val part = Regex(
            "Content-Disposition: form-data; name=\"${Regex.escape(name)}\"\r\n" +
                "(?:[^\r\n]+\r\n)*\r\n${Regex.escape(value)}\r\n"
        )
        assertTrue("Expected multipart $name=$value", part.containsMatchIn(body))
    }

    private suspend fun withApi(
        host: String,
        response: MockResponse = MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"text":"Привет, мир!","language":"ru"}"""),
        block: suspend (WhisperApiClient, String, MockWebServer) -> Unit
    ) {
        val loopback = InetAddress.getByName("127.0.0.1")
        // Exercise the real hostname selection while every request stays on the local server.
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(loopback)
        }).build()
        try {
            MockWebServer().use { server ->
                server.start(loopback, 0)
                server.enqueue(response)
                val endpoint = server.url("/api/v1/audio/transcriptions").newBuilder().host(host).build().toString()
                block(WhisperApiClient(http), endpoint, server)
            }
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
