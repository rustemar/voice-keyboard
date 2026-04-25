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

    class ApiException(val statusCode: Int, val body: String) :
        Exception("API error $statusCode: ${body.take(200)}")

    suspend fun process(
        prompt: PromptParts,
        prefs: PostProcessingPreferences,
        modelOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val model = modelOverride ?: prefs.resolvedModel()
            val result = when (prefs.provider) {
                PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(
                    systemInstruction = prompt.systemInstruction,
                    userText = prompt.userText,
                    prefs = prefs,
                    model = model,
                    maxTokens = 16384
                )
                else -> callOpenAI(
                    systemInstruction = prompt.systemInstruction,
                    userText = prompt.userText,
                    prefs = prefs,
                    model = model,
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
        } catch (e: ApiException) {
            DiagnosticLog.recordFailure(TAG, "Validation failed", e)
            when (e.statusCode) {
                401 -> Result.failure(Exception("Invalid API key"))
                403 -> Result.failure(Exception("Access denied (check region/permissions)"))
                404 -> Result.failure(Exception("Invalid endpoint URL"))
                429 -> Result.success("Rate limit exceeded, but key is valid")
                in 500..599 -> Result.failure(Exception("Server error, try again later"))
                else -> Result.failure(Exception("API error ${e.statusCode}"))
            }
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Validation failed", e)
            Result.failure(Exception(e.message ?: "Unknown error"))
        }
    }

    private fun callOpenAIOrClaude(prompt: String, prefs: PostProcessingPreferences, maxTokens: Int): String {
        return when (prefs.provider) {
            PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(systemInstruction = null, userText = prompt, prefs = prefs, maxTokens = maxTokens)
            else -> callOpenAI(systemInstruction = null, userText = prompt, prefs = prefs, maxTokens = maxTokens)
        }
    }

    private fun callOpenAI(
        systemInstruction: String?,
        userText: String,
        prefs: PostProcessingPreferences,
        model: String = prefs.resolvedModel(),
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
            put("model", model)
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

        val responseBody = httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                throw ApiException(response.code, text)
            }
            text
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
        model: String = prefs.resolvedModel(),
        maxTokens: Int
    ): String {
        val body = JSONObject().apply {
            put("model", model)
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

        val responseBody = httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                throw ApiException(response.code, text)
            }
            text
        }

        val json = JSONObject(responseBody)
        return json.getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }
}
