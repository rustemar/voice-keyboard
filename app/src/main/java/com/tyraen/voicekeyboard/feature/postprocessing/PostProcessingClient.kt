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

        /**
         * Turns an HTTP failure into the line shown under the Apply button. The provider's own
         * message is appended when the body carries one: a 404 from OpenRouter means "unknown
         * model" as often as "wrong URL", and only the server can tell the user which it was.
         */
        fun describeFailure(statusCode: Int, body: String): String {
            val base = when (statusCode) {
                401 -> "Invalid API key"
                403 -> "Access denied (check region/permissions)"
                404 -> "Not found: check the endpoint URL and the model name"
                400, 422 -> "Request rejected: check the model name and settings"
                in 500..599 -> "Server error, try again later"
                else -> "API error $statusCode"
            }
            val detail = PostProcessingResponseParser.errorMessage(body)
                ?.replace(Regex("\\s+"), " ")
                ?.take(200)
            return if (detail.isNullOrBlank()) base else "$base ($detail)"
        }
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
            if (e.statusCode == 429) {
                Result.success("Rate limit exceeded, but key is valid")
            } else {
                Result.failure(Exception(describeFailure(e.statusCode, e.body)))
            }
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Validation failed", e)
            Result.failure(Exception(e.message ?: "Unknown error"))
        }
    }

    private fun callOpenAIOrClaude(prompt: String, prefs: PostProcessingPreferences, maxTokens: Int): String {
        return when (prefs.provider) {
            PostProcessingPreferences.PROVIDER_CLAUDE -> callClaude(systemInstruction = null, userText = prompt, prefs = prefs, maxTokens = maxTokens)
            // No token cap here: OpenAI's reasoning models reject max_tokens outright, and the
            // "Reply with exactly: OK" probe is tiny anyway. Claude requires it.
            else -> callOpenAI(systemInstruction = null, userText = prompt, prefs = prefs, maxTokens = null)
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

        fun body(withTemperature: Boolean) = JSONObject().apply {
            put("model", model)
            if (withTemperature) put("temperature", prefs.resolvedTemperature().toDouble())
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("messages", messages)
        }

        var (code, responseBody) = postOpenAI(prefs, body(withTemperature = true))
        // OpenAI's reasoning models (o-series, gpt-5 family) accept only the default temperature
        // and answer 400 to anything else. Retrying without the field keeps them usable.
        if (code == 400 && responseBody.contains("temperature", ignoreCase = true)) {
            DiagnosticLog.record(TAG, "Model rejects temperature, retrying without it")
            val retry = postOpenAI(prefs, body(withTemperature = false))
            code = retry.first
            responseBody = retry.second
        }
        if (code !in 200..299) throw ApiException(code, responseBody)

        return PostProcessingResponseParser.openAiText(responseBody)
    }

    private fun postOpenAI(prefs: PostProcessingPreferences, body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url(prefs.resolvedEndpoint())
            .addHeader("Authorization", "Bearer ${prefs.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw Exception("Empty response")
            response.code to text
        }
    }

    private fun callClaude(
        systemInstruction: String?,
        userText: String,
        prefs: PostProcessingPreferences,
        model: String = prefs.resolvedModel(),
        maxTokens: Int
    ): String {
        fun body(withTemperature: Boolean) = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            // Anthropic accepts 0..1; the settings field allows up to 2 for OpenAI-style APIs.
            if (withTemperature) put("temperature", prefs.resolvedTemperature().toDouble().coerceIn(0.0, 1.0))
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

        var (code, responseBody) = postClaude(prefs, body(withTemperature = true))
        // Newer Claude models reject sampling parameters with a 400; without this retry
        // post-processing would quietly fall back to the raw transcript on them.
        if (code == 400 && responseBody.contains("temperature", ignoreCase = true)) {
            DiagnosticLog.record(TAG, "Model rejects temperature, retrying without it")
            val retry = postClaude(prefs, body(withTemperature = false))
            code = retry.first
            responseBody = retry.second
        }
        if (code !in 200..299) throw ApiException(code, responseBody)

        return PostProcessingResponseParser.claudeText(responseBody)
    }

    private fun postClaude(prefs: PostProcessingPreferences, body: JSONObject): Pair<Int, String> {
        val request = Request.Builder()
            .url(prefs.resolvedEndpoint())
            .addHeader("x-api-key", prefs.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: throw Exception("Empty response")
            response.code to text
        }
    }
}
