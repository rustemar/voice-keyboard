package com.tyraen.voicekeyboard.core.network

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import com.tyraen.voicekeyboard.feature.postprocessing.PromptParts
import com.tyraen.voicekeyboard.feature.transcription.ChatCompletionsSpeechClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.transcription.WhisperApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ClientCancellationTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `cancel an upload before its request body finishes`() = runBlocking {
        val uploading = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
        }).build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = null
                override fun writeTo(sink: BufferedSink) {
                    val chunk = ByteArray(8_192)
                    repeat(65_536) {
                        sink.write(chunk)
                        if (it == 8) uploading.countDown()
                    }
                }
            }
            val call = http.newCall(Request.Builder().url(server.url("/upload")).post(body).build())
            val job = launch(Dispatchers.Default) { call.awaitResponse { it.body?.string() } }
            try {
                assertTrue("The upload must begin", uploading.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue(call.isCanceled())
                assertTrue("The upload must release its HTTP exchange", failed.await(2, TimeUnit.SECONDS))
            } finally {
                job.cancelAndJoin()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }

    @Test fun `cancel Whisper while waiting for response headers`() = cancelRequest { http, endpoint, audio ->
        WhisperApiClient(http).transcribe(audio, config(endpoint))
    }

    @Test fun `cancel chat audio while waiting for response headers`() = cancelRequest { http, endpoint, audio ->
        ChatCompletionsSpeechClient(http).transcribe(audio, config(endpoint))
    }

    @Test fun `cancel Whisper while reading the response body`() =
        cancelRequest(duringBody = true) { http, endpoint, audio ->
            WhisperApiClient(http).transcribe(audio, config(endpoint))
        }

    @Test fun `cancel OpenAI post-processing without returning a failure result`() =
        cancelPostProcessing(PostProcessingPreferences.PROVIDER_OPENAI)

    @Test fun `cancel Claude post-processing without returning a failure result`() =
        cancelPostProcessing(PostProcessingPreferences.PROVIDER_CLAUDE)

    @Test fun `cancel the OpenAI temperature retry`() =
        cancelPostProcessing(PostProcessingPreferences.PROVIDER_OPENAI, temperatureRetry = true)

    @Test fun `cancel the Claude temperature retry`() =
        cancelPostProcessing(PostProcessingPreferences.PROVIDER_CLAUDE, temperatureRetry = true)

    @Test fun `cancel the Whisper validation probe and delete its temporary audio`() {
        val cache = files.newFolder()
        cancelRequest { http, endpoint, _ ->
            WhisperApiClient(http).validateCredentials("test", endpoint, "test", cache)
        }
        assertFalse(File(cache, "validate_test.wav").exists())
    }

    @Test fun `cancel the chat audio validation probe`() = cancelRequest { http, endpoint, _ ->
        ChatCompletionsSpeechClient(http).validateCredentials("test", endpoint, "test", files.root)
    }

    @Test fun `cancel the post-processing validation probe`() = cancelRequest { http, endpoint, _ ->
        PostProcessingClient(http).validateCredentials(preferences(PostProcessingPreferences.PROVIDER_OPENAI, endpoint))
    }

    private fun cancelPostProcessing(provider: String, temperatureRetry: Boolean = false) =
        cancelRequest(temperatureRetry = temperatureRetry) { http, endpoint, _ ->
            PostProcessingClient(http).process(PromptParts("Keep only text", "Original text"), preferences(provider, endpoint))
        }

    private fun preferences(provider: String, endpoint: String) = PostProcessingPreferences(
        enabled = true, provider = provider, apiKey = "test", endpoint = endpoint, model = "test"
    )

    private fun config(endpoint: String) = TranscriptionConfig("test", endpoint, "test", "", "")

    private fun cancelRequest(
        duringBody: Boolean = false,
        temperatureRetry: Boolean = false,
        action: suspend (OkHttpClient, String, File) -> Any
    ) = runBlocking {
        val canceled = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val bodyStarted = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.countDown() }
            override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
            override fun responseBodyStart(call: Call) { bodyStarted.countDown() }
        }).build()
        val audio = files.newFile("audio-${System.nanoTime()}.wav").apply { writeBytes(ByteArray(48)) }
        MockWebServer().use { server ->
            if (temperatureRetry) server.enqueue(MockResponse().setResponseCode(400).setBody("temperature unsupported"))
            if (duringBody) {
                // Dispatcher.peek remains unthrottled, so only the response stalls, not upload.
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody("Late response text").throttleBody(1, 2, TimeUnit.SECONDS)
                }
            } else server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val job = launch(Dispatchers.Default) {
                action(http, server.url("/test").toString(), audio)
                returned.set(true)
            }
            try {
                repeat(if (temperatureRetry) 2 else 1) {
                    assertNotNull("The local request must start", server.takeRequest(5, TimeUnit.SECONDS))
                }
                if (duringBody) assertTrue("The client must start consuming the response body", bodyStarted.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { job.cancelAndJoin() }
                assertTrue("Cancellation must call Call.cancel", canceled.await(2, TimeUnit.SECONDS))
                assertTrue("The underlying HTTP exchange must stop", failed.await(2, TimeUnit.SECONDS))
                assertFalse("Cancellation must propagate instead of returning Result.failure", returned.get())
                assertEquals(if (temperatureRetry) 2 else 1, server.requestCount)
            } finally {
                job.cancelAndJoin()
                http.dispatcher.executorService.shutdown()
                http.connectionPool.evictAll()
            }
        }
    }
}
