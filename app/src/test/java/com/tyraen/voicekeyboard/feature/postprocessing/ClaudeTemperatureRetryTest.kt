package com.tyraen.voicekeyboard.feature.postprocessing

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeTemperatureRetryTest {

    private val requests = mutableListOf<String>()

    /** Answers each request with the next canned reply (the last one repeats); nothing leaves the JVM. */
    private fun process(vararg replies: Pair<Int, String>): Result<String> {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val (code, body) = replies[minOf(requests.size, replies.size) - 1]
            Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(code).message("canned")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val prefs = PostProcessingPreferences(
            provider = PostProcessingPreferences.PROVIDER_CLAUDE,
            apiKey = "test-key",
            model = "claude-test"
        )
        return runBlocking { PostProcessingClient(http).process(PromptParts("guard", "raw text"), prefs) }
    }

    @Test fun `retries without temperature when the model rejects it`() {
        val result = process(
            400 to """{"type":"error","error":{"type":"invalid_request_error","message":"`temperature` is not supported for this model."}}""",
            200 to """{"content":[{"type":"text","text":"Fixed text."}]}"""
        )
        assertEquals("Fixed text.", result.getOrThrow())
        assertEquals(2, requests.size)
        assertTrue(JSONObject(requests[0]).has("temperature"))
        assertFalse(JSONObject(requests[1]).has("temperature"))
    }

    @Test fun `retries only once`() {
        val result = process(400 to """{"type":"error","error":{"type":"invalid_request_error","message":"`temperature` is deprecated for this model."}}""")
        assertTrue(result.isFailure)
        assertEquals(2, requests.size)
        assertEquals(400, (result.exceptionOrNull() as PostProcessingClient.ApiException).statusCode)
    }

    @Test fun `other bad requests are not retried`() {
        val result = process(400 to """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: too large"}}""")
        assertTrue(result.isFailure)
        assertEquals(1, requests.size)
    }

    @Test fun `a model that accepts temperature gets one request`() {
        assertEquals("OK", process(200 to """{"content":[{"type":"text","text":"OK"}]}""").getOrThrow())
        assertEquals(1, requests.size)
        assertTrue(JSONObject(requests[0]).has("temperature"))
    }
}
