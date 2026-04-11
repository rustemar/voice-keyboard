package com.tyraen.voicekeyboard.feature.postprocessing

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class PostProcessingClient(private val httpClient: OkHttpClient) {

    companion object {
        private const val TAG = "PostProcessing"
    }

    suspend fun process(
        prompt: String,
        prefs: PostProcessingPreferences
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = when (prefs.provider) {
                PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(prompt, prefs)
                else -> callOpenAI(prompt, prefs)
            }
            DiagnosticLog.record(TAG, "Success: ${result.take(80)}")
            Result.success(result)
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Failed", e)
            Result.failure(e)
        }
    }

    private fun callOpenAI(prompt: String, prefs: PostProcessingPreferences): String {
        val body = JSONObject().apply {
            put("model", prefs.resolvedModel())
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(prefs.resolvedEndpoint())
            .addHeader("Authorization", "Bearer ${prefs.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: ${responseBody.take(200)}")
        }

        val json = JSONObject(responseBody)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }

    private fun callClaude(prompt: String, prefs: PostProcessingPreferences): String {
        val body = JSONObject().apply {
            put("model", prefs.resolvedModel())
            put("max_tokens", 2048)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(prefs.resolvedEndpoint())
            .addHeader("x-api-key", prefs.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("API error ${response.code}: ${responseBody.take(200)}")
        }

        val json = JSONObject(responseBody)
        return json.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }
}
