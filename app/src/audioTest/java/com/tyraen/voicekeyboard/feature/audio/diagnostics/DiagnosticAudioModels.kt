package com.tyraen.voicekeyboard.feature.audio.diagnostics

import java.io.File

/** Fixed settings for the shared source. Encoders never open the microphone themselves. */
object DiagnosticPcmFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_FRAME = CHANNEL_COUNT * BITS_PER_SAMPLE / 8
    const val MAX_CAPTURE_DURATION_MS = 45_000L
    const val MAX_PCM_BYTES = SAMPLE_RATE * BYTES_PER_FRAME * MAX_CAPTURE_DURATION_MS / 1_000

    fun durationMs(pcmBytes: Long): Long = pcmBytes * 1_000 / (SAMPLE_RATE * BYTES_PER_FRAME)
}

enum class DiagnosticAudioProfile(
    val id: String,
    val label: String,
    val extension: String,
    val mimeType: String,
    val bitRate: Int?,
    val minimumApi: Int
) {
    WAV("wav", "WAV / PCM 16", "wav", "audio/wav", null, 24),
    OPUS_64("opus64", "Opus 64 kbps", "ogg", "audio/opus", 64_000, 29),
    OPUS_128("opus128", "Opus 128 kbps", "ogg", "audio/opus", 128_000, 29),
    OPUS_200("opus200", "Opus 200 kbps", "ogg", "audio/opus", 200_000, 29),
    AAC_128("aac128", "AAC 128 kbps", "m4a", "audio/mp4a-latm", 128_000, 24);

    fun isSupportedOn(apiLevel: Int): Boolean = apiLevel >= minimumApi
}

data class DiagnosticCaptureProgress(
    /** Duration of PCM actually received, rather than an estimate from a wall clock. */
    val elapsedMs: Long,
    /** Absolute PCM peak in the last buffer, 0..32768. */
    val amplitude: Int,
    val wallElapsedMs: Long
)

data class DiagnosticPcmCapture(
    val file: File,
    val pcmBytes: Long,
    val durationMs: Long,
    val wallDurationMs: Long,
    val recordedAtEpochMs: Long,
    val sha256: String,
    val peakAmplitude: Int,
    val maxDurationReached: Boolean,
    /** Requested source and observed AudioRecord settings; contains no credentials. */
    val sourceSettings: Map<String, String>,
    val sampleRate: Int = DiagnosticPcmFormat.SAMPLE_RATE,
    val channelCount: Int = DiagnosticPcmFormat.CHANNEL_COUNT,
    val bitsPerSample: Int = DiagnosticPcmFormat.BITS_PER_SAMPLE
)

enum class DiagnosticVariantStatus { SUCCESS, UNSUPPORTED, ERROR }

data class DiagnosticAudioVariant(
    val profile: DiagnosticAudioProfile,
    val status: DiagnosticVariantStatus,
    val file: File? = null,
    val sizeBytes: Long = 0,
    val encodingMs: Long = 0,
    val codecName: String? = null,
    val actualSampleRate: Int? = null,
    val actualChannelCount: Int? = null,
    /** A codec/container declaration; not a measured guarantee of the requested bitrate. */
    val declaredBitRate: Int? = null,
    val durationMs: Long? = null,
    /** File size / container duration, including container overhead. */
    val averageBitRate: Long? = null,
    val encoderFormat: String? = null,
    val trackFormat: String? = null,
    val error: String? = null
)

data class DiagnosticAudioComparison(
    val capture: DiagnosticPcmCapture,
    val variants: List<DiagnosticAudioVariant>,
    /** Files remain valid until the next capture on this session or close(). */
    val sessionDir: File,
    val totalEncodingMs: Long
)
