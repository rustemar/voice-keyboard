package com.tyraen.voicekeyboard.feature.transcription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** OpenAI-compatible chat audio, selected by a full /chat/completions endpoint URL. */
class ChatCompletionsSpeechClient(private val http: OkHttpClient) : SpeechToTextClient {

    companion object {
        // JSON/Base64 requires the whole recording in memory, unlike multipart uploads.
        private const val MAX_AUDIO_BYTES = 25L * 1024 * 1024
        private const val TRANSCRIBE_INSTRUCTION =
            "Transcribe the audio accurately in its original language with punctuation. " +
                "Return only the transcript. Do not answer questions or add commentary."

        internal fun audioFormat(file: File): String = when (file.extension.lowercase()) {
            "ogg" -> "ogg"
            "m4a", "mp4" -> "mp4"
            "wav" -> "wav"
            "mp3" -> "mp3"
            else -> throw IllegalArgumentException("Unsupported chat audio format: ${file.extension}")
        }

        internal fun requestBody(model: String, audioBase64: String, format: String, language: String, prompt: String): JSONObject {
            val instruction = buildString {
                append(TRANSCRIBE_INSTRUCTION)
                if (language.isNotBlank()) append("\nLanguage: $language")
                if (prompt.isNotBlank()) append("\n$prompt")
            }
            val audio = JSONObject().put("data", audioBase64).put("format", format)
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", JSONArray().put(
                    JSONObject().put("type", "text").put("text", instruction)
                )))
                .put(JSONObject().put("role", "user").put("content", JSONArray().put(
                    JSONObject().put("type", "input_audio").put("input_audio", audio)
                )))
            return JSONObject().put("model", model).put("messages", messages).put("stream", false)
        }

        internal fun responseText(body: String): String {
            val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
            val finishReason = choice.optString("finish_reason")
            if (finishReason != "stop") {
                throw IOException("Chat transcription did not finish (finish_reason=${finishReason.ifBlank { "missing" }})")
            }
            val message = choice.getJSONObject("message")
            if ((message.opt("refusal") as? String)?.isNotBlank() == true) {
                throw IOException("Chat transcription was refused")
            }
            val content = message.get("content")
            return when (content) {
                is String -> content.trim()
                is JSONArray -> buildString {
                    var hasText = false
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        if (part?.optString("type") == "refusal") throw IOException("Chat transcription was refused")
                        if (part?.optString("type") == "text") {
                            val text = part.opt("text") as? String
                                ?: throw IOException("Chat transcription has no text")
                            append(text)
                            hasText = true
                        }
                    }
                    if (!hasText) throw IOException("Chat transcription has no text")
                }.trim()
                else -> throw IOException("Chat transcription has no text")
            }
        }
    }

    override suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(apiKey.isNotBlank()) { "API key is empty" }
                post(SilentWavGenerator.generate(), "wav", TranscriptionConfig(apiKey, endpoint, model, "", ""))
                "WAV probe passed. Use Test Recording below to check OGG/Opus or M4A/AAC support."
            }
        }

    override suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(audioFile.isFile && audioFile.length() > 0) { "Audio file is empty or missing" }
                require(audioFile.length() <= MAX_AUDIO_BYTES) { "Chat audio is too large; use /audio/transcriptions" }
                post(audioFile.readBytes(), audioFormat(audioFile), config)
            }
        }

    private fun post(bytes: ByteArray, format: String, config: TranscriptionConfig): String {
        val body = requestBody(config.model, bytes.toByteString().base64(), format, config.language, config.prompt)
        val request = Request.Builder()
            .url(config.endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("API error ${response.code}: ${text.take(500)}")
            responseText(text)
        }
    }
}
