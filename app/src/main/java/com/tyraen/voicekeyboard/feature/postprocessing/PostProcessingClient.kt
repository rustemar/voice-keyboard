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
        prompt: PromptParts,
        prefs: PostProcessingPreferences
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val result = when (prefs.provider) {
                PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(
                    systemInstruction = prompt.systemInstruction,
                    userText = prompt.userText,
                    prefs = prefs,
                    maxTokens = 16384
                )
                else -> callOpenAI(
                    systemInstruction = prompt.systemInstruction,
                    userText = prompt.userText,
                    prefs = prefs,
                    maxTokens = null
                )
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
            callOpenAIOrClaude("Reply with exactly: OK", prefs, maxTokens = 50)
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

    private fun callOpenAIOrClaude(prompt: String, prefs: PostProcessingPreferences, maxTokens: Int): String {
        return when (prefs.provider) {
            PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(null, prompt, prefs, maxTokens)
            else -> callOpenAI(null, prompt, prefs, maxTokens)
        }
    }

    private fun callOpenAI(
        systemInstruction: String?,
        userText: String,
        prefs: PostProcessingPreferences,
        maxTokens: Int? = null
    ): String {
        val messages = JSONArray()
        if (systemInstruction != null) {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", userText)
        })

        val body = JSONObject().apply {
            put("model", prefs.resolvedModel())
            put("temperature", prefs.resolvedTemperature().toDouble())
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("messages", messages)
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

    private fun callClaude(
        systemInstruction: String?,
        userText: String,
        prefs: PostProcessingPreferences,
        maxTokens: Int
    ): String {
        val body = JSONObject().apply {
            put("model", prefs.resolvedModel())
            put("max_tokens", maxTokens)
            if (systemInstruction != null) {
                put("system", systemInstruction)
            }
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
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
