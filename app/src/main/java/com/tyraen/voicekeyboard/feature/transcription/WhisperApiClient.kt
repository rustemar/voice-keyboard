package com.tyraen.voicekeyboard.feature.transcription

import com.tyraen.voicekeyboard.core.network.ApiEndpoint
import com.tyraen.voicekeyboard.core.network.awaitResponse
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingResponseParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class WhisperApiClient(private val http: OkHttpClient) : SpeechToTextClient {

    override suspend fun validateCredentials(
        apiKey: String,
        endpoint: String,
        model: String,
        cacheDir: File
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (apiKey.isBlank()) {
                return@withContext Result.failure(Exception("API key is empty"))
            }

            val silentWav = SilentWavGenerator.generate()
            val tempFile = File(cacheDir, "validate_test.wav")
            tempFile.writeBytes(silentWav)

            try {
                val request = transcriptionRequest(tempFile, apiKey, endpoint, model, fileName = "test.wav")

                http.newCall(request).awaitResponse { response ->
                    val responseBody = response.body?.string()?.trim() ?: ""

                    // The provider's own message tells a retired or mistyped model apart from a
                    // wrong address: both are a bare 404 on the status line.
                    val detail = PostProcessingResponseParser.errorMessage(responseBody)
                        ?.replace(Regex("\\s+"), " ")?.take(200)
                    fun failure(base: String) =
                        Result.failure<String>(Exception(if (detail.isNullOrBlank()) base else "$base ($detail)"))
                    when (response.code) {
                        200 -> Result.success("API key is valid")
                        401 -> failure("Invalid API key. Check that the key is correct and active.")
                        403 -> failure("Forbidden. The API may be unavailable in your region.")
                        404 -> failure("Not found: check the API URL and the model name.")
                        400, 422 -> failure("Request rejected: check the model name.")
                        429 -> failure("Rate limit exceeded. Try again later.")
                        in 500..599 -> failure("Server error (${response.code}). Try again later.")
                        else -> Result.failure(Exception("Error ${response.code}: $responseBody"))
                    }
                }
            } finally {
                tempFile.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("Cannot reach server. Check internet connection and endpoint URL."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Connection timed out. Check internet connection."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        config: TranscriptionConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = transcriptionRequest(
                audioFile, config.apiKey, config.endpoint, config.model,
                language = config.language, prompt = config.prompt
            )

            http.newCall(request).awaitResponse { response ->
                val responseBody = response.body?.string()?.trim() ?: ""

                if (response.isSuccessful) {
                    Result.success(
                        HallucinationFilter.clean(
                            rawText = TranscriptionResponseParser.extractText(responseBody),
                            prompt = config.prompt,
                            vocabulary = config.vocabulary,
                            recordingDurationMs = config.recordingDurationMs
                        )
                    )
                } else {
                    Result.failure(Exception("API error ${response.code}: $responseBody"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun transcriptionRequest(
        audioFile: File,
        apiKey: String,
        endpoint: String,
        model: String,
        fileName: String = audioFile.name,
        language: String = "",
        prompt: String = ""
    ): Request {
        val mimeType = when (audioFile.extension.lowercase()) {
            "ogg" -> "audio/ogg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }
        // OpenRouter rejects text responses; keep the existing format for custom servers.
        // Apply and dictation share this builder so their format negotiation stays identical.
        val responseFormat = if (ApiEndpoint.isOpenRouter(endpoint)) "json" else "text"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", fileName, audioFile.asRequestBody(mimeType.toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", responseFormat)
            .apply {
                if (language.isNotBlank()) addFormDataPart("language", language)
                if (prompt.isNotBlank()) addFormDataPart("prompt", prompt)
            }
            .build()

        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }
}

internal object SilentWavGenerator {
    fun generate(): ByteArray {
        val sampleRate = 16000
        val numSamples = sampleRate / 10
        val dataSize = numSamples * 2
        val fileSize = 36 + dataSize
        val buf = java.nio.ByteBuffer.allocate(44 + dataSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(fileSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16)
        buf.putShort(1); buf.putShort(1)
        buf.putInt(sampleRate); buf.putInt(sampleRate * 2)
        buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataSize)
        repeat(numSamples) { buf.putShort(0) }
        return buf.array()
    }
}
