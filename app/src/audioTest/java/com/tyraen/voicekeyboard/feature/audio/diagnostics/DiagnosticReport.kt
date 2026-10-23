package com.tyraen.voicekeyboard.feature.audio.diagnostics

import android.os.Build
import com.tyraen.voicekeyboard.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticReport {
    fun json(
        comparison: DiagnosticAudioComparison,
        recognitionConfig: DiagnosticRecognitionConfig?,
        results: List<DiagnosticTranscription>,
        reference: String,
        redact: (String) -> String = { it }
    ): String {
        val capture = comparison.capture
        val root = JSONObject()
            .put("schema", 1).put("app_version", BuildConfig.VERSION_NAME)
            .put("package", BuildConfig.APPLICATION_ID)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android_api", Build.VERSION.SDK_INT)
            .put("post_processing", false).put("recent_context", false)
            .put("reference_sent_to_provider", false).put("reference", reference)
            .put("timing_note", "Upload is app-to-network write time, not server acknowledgement. Response wait includes network and provider time. No network speed claim.")
            .put("word_error_note", "Case/punctuation ignored. Digits and written numbers remain different. One sample is not a general quality benchmark.")
            .put("capture", JSONObject()
                .put("sample_rate", capture.sampleRate).put("channels", capture.channelCount)
                .put("bits_per_sample", capture.bitsPerSample).put("pcm_bytes", capture.pcmBytes)
                .put("pcm_sha256", capture.sha256).put("duration_ms", capture.durationMs)
                .put("wall_duration_ms", capture.wallDurationMs).put("peak_amplitude", capture.peakAmplitude)
                .put("recorded_at_epoch_ms", capture.recordedAtEpochMs)
                .put("max_duration_reached", capture.maxDurationReached)
                .put("source_settings", JSONObject(capture.sourceSettings)))
        recognitionConfig?.let {
            root.put("recognition", JSONObject().put("endpoint", it.endpoint).put("model", it.model)
                .put("language", it.language).put("prompt", it.prompt)
                .put("temperature", 0).put("response_format", "json"))
        }
        val variants = JSONArray()
        for (variant in comparison.variants) {
            val row = JSONObject().put("id", variant.profile.id).put("status", variant.status.name)
                .put("requested_bitrate", variant.profile.bitRate).put("bytes", variant.sizeBytes)
                .put("encoding_ms", variant.encodingMs).put("codec_name", variant.codecName)
                .put("actual_sample_rate", variant.actualSampleRate).put("actual_channels", variant.actualChannelCount)
                .put("declared_bitrate", variant.declaredBitRate).put("container_average_bitrate", variant.averageBitRate)
                .put("duration_ms", variant.durationMs).put("encoder_format", variant.encoderFormat)
                .put("track_format", variant.trackFormat).put("error", variant.error)
            results.firstOrNull { it.profileId == variant.profile.id }?.let { result ->
                row.put("recognition", JSONObject().put("http_status", result.httpCode).put("text", result.text)
                    .put("elapsed_ms", result.elapsedMs).put("upload_ms", result.uploadMs)
                    .put("response_wait_ms", result.responseWaitMs).put("request_id", result.requestId).put("error", result.error))
                if (result.error == null) {
                    runCatching { DiagnosticWordErrors.compare(reference, result.text) }.getOrNull()?.let {
                        row.put("word_errors", it.errors).put("reference_words", it.referenceWords).put("wer_percent", it.percent)
                    }
                }
            }
            variants.put(row)
        }
        root.put("total_encoding_ms", comparison.totalEncodingMs).put("variants", variants)
        scrubStrings(root, redact)
        return root.toString(2)
    }

    private fun scrubStrings(value: Any?, redact: (String) -> String) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.get(key)
                if (child is String) value.put(key, redact(child)) else scrubStrings(child, redact)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                val child = value.get(index)
                if (child is String) value.put(index, redact(child)) else scrubStrings(child, redact)
            }
        }
    }

    fun writeZip(output: OutputStream, comparison: DiagnosticAudioComparison, redactedJson: String) {
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("report.json"))
            zip.write(redactedJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (variant in comparison.variants) {
                val file = variant.file ?: continue
                if (variant.status != DiagnosticVariantStatus.SUCCESS || !file.isFile) continue
                zip.putNextEntry(ZipEntry("audio/${variant.profile.id}.${variant.profile.extension}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
