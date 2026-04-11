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
                PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(prompt, prefs, maxTokens = 4096)
                else -> callOpenAI(prompt, prefs, maxTokens = 4096)
            }
            DiagnosticLog.record(TAG, "Success: ${result.take(80)}")
            Result.success(result)
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Failed", e)
            Result.failure(e)
        }
    }

    suspend fun validateCredentials(
        prefs: PostProcessingPreferences
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val testPrompt = "Reply with exactly: OK"
            when (prefs.provider) {
                PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(testPrompt, prefs, maxTokens = 10)
                else -> callOpenAI(testPrompt, prefs, maxTokens = 10)
            }
            DiagnosticLog.record(TAG, "Validation success")
            Result.success("API key is valid.")
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Validation failed", e)
            val message = when {
                e.message?.contains("401") == true -> "Invalid API key"
                e.message?.contains("403") == true -> "Access denied (check region/permissions)"
                e.message?.contains("404") == true -> "Invalid endpoint URL"
                e.message?.contains("429") == true -> "Rate limit exceeded, but key is valid"
                e.message?.contains("5") == true && e.message?.contains("API error 5") == true -> "Server error, try again later"
                else -> e.message ?: "Unknown error"
            }
            if (e.message?.contains("429") == true) {
                Result.success(message)
            } else {
                Result.failure(Exception(message))
            }
        }
    }

    private fun callOpenAI(prompt: String, prefs: PostProcessingPreferences, maxTokens: Int): String {
        val body = JSONObject().apply {
            put("model", prefs.resolvedModel())
            put("temperature", 0.3)
            put("max_tokens", maxTokens)
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

    private fun callClaude(prompt: String, prefs: PostProcessingPreferences, maxTokens: Int): String {
        val body = JSONObject().apply {
            put("model", prefs.resolvedModel())
            put("max_tokens", maxTokens)
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
