package com.tyraen.voicekeyboard.feature.audio.diagnostics

import com.tyraen.voicekeyboard.core.network.HttpClientProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

data class DiagnosticRecognitionConfig(
    val endpoint: String,
    val model: String,
    val language: String,
    val prompt: String
) {
    fun isValid(): Boolean {
        val url = endpoint.toHttpUrlOrNull() ?: return false
        return model.isNotBlank() && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && url.encodedPath.endsWith("/audio/transcriptions")
    }
}

data class DiagnosticTranscription(
    val profileId: String,
    val httpCode: Int?,
    val text: String,
    val elapsedMs: Long,
    val uploadMs: Long?,
    val responseWaitMs: Long?,
    val requestId: String?,
    val error: String?
)

/** Direct raw STT: no hallucination filter, post-processing, background retries or saved key. */
class DiagnosticTranscriber {
    private val baseClient = HttpClientProvider.create().newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    @Volatile private var activeCall: Call? = null

    fun cancel() { activeCall?.cancel() }

    suspend fun transcribe(
        variant: DiagnosticAudioVariant,
        config: DiagnosticRecognitionConfig,
        apiKey: String
    ): DiagnosticTranscription = suspendCancellableCoroutine { continuation ->
        require(config.isValid()) { "Invalid transcription endpoint or model" }
        require(apiKey.isNotBlank()) { "API key is empty" }
        val file = requireNotNull(variant.file)
        val mime = when (variant.profile) {
            DiagnosticAudioProfile.WAV -> "audio/wav"
            DiagnosticAudioProfile.AAC_128 -> "audio/mp4"
            else -> "audio/ogg"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .addFormDataPart("model", config.model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("temperature", "0")
            .apply {
                if (config.language.isNotBlank()) addFormDataPart("language", config.language)
                if (config.prompt.isNotBlank()) addFormDataPart("prompt", config.prompt)
            }.build()
        val timing = Timing()
        val client = baseClient.newBuilder().eventListener(timing).build()
        val request = Request.Builder().url(config.endpoint)
            .header("Authorization", "Bearer $apiKey").post(body).build()
        val call = client.newCall(request)
        activeCall = call
        continuation.invokeOnCancellation { call.cancel() }
        val started = System.nanoTime()
        fun result(code: Int?, text: String = "", id: String? = null, error: String? = null) =
            DiagnosticTranscription(variant.profile.id, code, text, (System.nanoTime() - started) / 1_000_000,
                timing.uploadMs(), timing.responseWaitMs(), id, error?.replace(apiKey, "[redacted]"))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (activeCall === call) activeCall = null
                if (continuation.isActive) continuation.resume(result(null, error = e.javaClass.simpleName))
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = response.use {
                    try {
                        val source = it.body?.source()
                        source?.request(1_048_577)
                        val tooLarge = (source?.buffer?.size ?: 0) > 1_048_576
                        val raw = source?.readUtf8(minOf(source.buffer.size, 1_048_576))?.trim().orEmpty()
                        val id = it.header("x-request-id") ?: it.header("x-groq-request-id")
                        if (!it.isSuccessful) {
                            // Provider bodies can echo input/headers; export a stable status only.
                            result(it.code, id = id, error = "HTTP ${it.code}")
                        } else if (tooLarge) {
                            result(it.code, id = id, error = "Response exceeds 1 MiB")
                        } else {
                            val text = if (raw.startsWith("{")) {
                                JSONObject(raw).optString("text", "")
                            } else raw
                            result(it.code, text = text.replace(apiKey, "[redacted]"), id = id)
                        }
                    } catch (e: Exception) {
                        result(it.code, error = e.javaClass.simpleName)
                    }
                }
                if (activeCall === call) activeCall = null
                if (continuation.isActive) continuation.resume(outcome)
            }
        })
    }

    private class Timing : EventListener() {
        var bodyStart: Long? = null
        var bodyEnd: Long? = null
        var headersStart: Long? = null
        override fun requestBodyStart(call: Call) { bodyStart = System.nanoTime() }
        override fun requestBodyEnd(call: Call, byteCount: Long) { bodyEnd = System.nanoTime() }
        override fun responseHeadersStart(call: Call) { headersStart = System.nanoTime() }
        fun uploadMs(): Long? = bodyEnd?.let { end -> bodyStart?.let { (end - it) / 1_000_000 } }
        fun responseWaitMs(): Long? = headersStart?.let { end -> bodyEnd?.let { (end - it).coerceAtLeast(0) / 1_000_000 } }
    }
}
