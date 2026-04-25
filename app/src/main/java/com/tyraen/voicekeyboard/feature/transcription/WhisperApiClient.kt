package com.tyraen.voicekeyboard.feature.transcription

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
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "test.wav", tempFile.asRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", model)
                    .addFormDataPart("response_format", "text")
                    .build()

                val request = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                http.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string()?.trim() ?: ""

                    when (response.code) {
                        200 -> Result.success("API key is valid")
                        401 -> Result.failure(Exception("Invalid API key. Check that the key is correct and active."))
                        403 -> Result.failure(Exception("Forbidden. The API may be unavailable in your region."))
                        404 -> Result.failure(Exception("Endpoint not found. Check the API URL."))
                        429 -> Result.failure(Exception("Rate limit exceeded. Try again later."))
                        in 500..599 -> Result.failure(Exception("Server error (${response.code}). Try again later."))
                        else -> Result.failure(Exception("Error ${response.code}: $responseBody"))
                    }
                }
            } finally {
                tempFile.delete()
            }
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
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/mp4".toMediaType()))
                .addFormDataPart("model", config.model)
                .addFormDataPart("response_format", "text")
                .apply {
                    if (config.language.isNotBlank()) addFormDataPart("language", config.language)
                    if (config.prompt.isNotBlank()) addFormDataPart("prompt", config.prompt)
                }
                .build()

            val request = Request.Builder()
                .url(config.endpoint)
                .header("Authorization", "Bearer ${config.apiKey}")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()?.trim() ?: ""

                if (response.isSuccessful) {
                    Result.success(HallucinationFilter.clean(responseBody))
                } else {
                    Result.failure(Exception("API error ${response.code}: $responseBody"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private object SilentWavGenerator {
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
