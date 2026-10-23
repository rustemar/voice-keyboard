package com.tyraen.voicekeyboard.feature.audio.diagnostics

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream

/** Offline encoders. A rejected profile is reported, never substituted with another format. */
internal object DiagnosticAudioEncoder {
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val IDLE_TIMEOUT_MS = 10_000L
    private const val ENCODING_TIMEOUT_MS = 120_000L

    suspend fun encode(
        source: DiagnosticPcmCapture,
        profile: DiagnosticAudioProfile,
        directory: File,
        ensureRunning: suspend () -> Unit
    ): DiagnosticAudioVariant {
        val started = SystemClock.elapsedRealtime()
        if (!profile.isSupportedOn(Build.VERSION.SDK_INT)) {
            return DiagnosticAudioVariant(profile, DiagnosticVariantStatus.UNSUPPORTED,
                error = "Requires Android API ${profile.minimumApi}; this device is API ${Build.VERSION.SDK_INT}")
        }
        val file = File(directory, "${profile.id}.${profile.extension}")
        var selectedEncoder: String? = null
        file.delete()
        try {
            ensureRunning()
            if (profile == DiagnosticAudioProfile.WAV) {
                file.outputStream().buffered().use { output ->
                    output.write(DiagnosticWavHeader.create(source.pcmBytes))
                    source.file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            ensureRunning()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                check(file.length() == source.pcmBytes + DiagnosticWavHeader.HEADER_BYTES) { "WAV copy is incomplete" }
                return DiagnosticAudioVariant(profile, DiagnosticVariantStatus.SUCCESS, file, file.length(),
                    SystemClock.elapsedRealtime() - started, "PCM 16 (no encoder)",
                    source.sampleRate, source.channelCount, source.sampleRate * source.channelCount * source.bitsPerSample,
                    source.durationMs, file.length() * 8_000 / source.durationMs,
                    trackFormat = "PCM signed 16-bit little-endian, ${source.sampleRate} Hz, ${source.channelCount} channel")
            }

            val requested = MediaFormat.createAudioFormat(profile.mimeType, source.sampleRate, source.channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, requireNotNull(profile.bitRate))
                setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                if (profile == DiagnosticAudioProfile.AAC_128) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
            }
            val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(requested)
                ?: return DiagnosticAudioVariant(profile, DiagnosticVariantStatus.UNSUPPORTED,
                    encodingMs = SystemClock.elapsedRealtime() - started,
                    error = "No encoder supports the requested ${profile.label} / 48 kHz mono profile")
            selectedEncoder = encoderName
            val encoderFormat = encodeCompressed(source, profile, requested, encoderName, file, ensureRunning)
            ensureRunning()
            check(file.isFile && file.length() > 0) { "Encoder produced an empty file" }
            val observed = inspectTrack(file)
            return DiagnosticAudioVariant(
                profile = profile,
                status = DiagnosticVariantStatus.SUCCESS,
                file = file,
                sizeBytes = file.length(),
                encodingMs = SystemClock.elapsedRealtime() - started,
                codecName = encoderName,
                actualSampleRate = observed.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                actualChannelCount = observed.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                declaredBitRate = observed.intOrNull(MediaFormat.KEY_BIT_RATE)
                    ?: encoderFormat.intOrNull(MediaFormat.KEY_BIT_RATE),
                durationMs = observed.longOrNull(MediaFormat.KEY_DURATION)?.div(1_000),
                averageBitRate = observed.longOrNull(MediaFormat.KEY_DURATION)?.takeIf { it > 0 }
                    ?.let { file.length() * 8_000_000 / it },
                encoderFormat = encoderFormat.toString(),
                trackFormat = observed.toString()
            )
        } catch (cancelled: CancellationException) {
            file.delete()
            throw cancelled
        } catch (error: Exception) {
            file.delete()
            return DiagnosticAudioVariant(profile, DiagnosticVariantStatus.ERROR,
                encodingMs = SystemClock.elapsedRealtime() - started,
                codecName = selectedEncoder,
                error = "${error.javaClass.simpleName}: ${error.message ?: "Encoding failed"}")
        }
    }

    private suspend fun encodeCompressed(
        source: DiagnosticPcmCapture,
        profile: DiagnosticAudioProfile,
        requested: MediaFormat,
        encoderName: String,
        file: File,
        ensureRunning: suspend () -> Unit
    ): MediaFormat {
        val encoder = MediaCodec.createByCodecName(encoderName)
        var encoderStarted = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputFormat: MediaFormat? = null
        try {
            encoder.configure(requested, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            val container = if (profile == DiagnosticAudioProfile.AAC_128) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            } else {
                check(Build.VERSION.SDK_INT >= 29) { "OGG muxing requires API 29" }
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            }
            val writer = MediaMuxer(file.absolutePath, container)
            muxer = writer
            val info = MediaCodec.BufferInfo()
            var track = -1
            var inputBytes = 0L
            var inputEnded = false
            var outputEnded = false
            var encodedBytes = 0L
            val started = SystemClock.elapsedRealtime()
            var lastProgress = started
            source.file.inputStream().buffered().use { input ->
                while (!outputEnded) {
                    ensureRunning()
                    val now = SystemClock.elapsedRealtime()
                    check(now - started < ENCODING_TIMEOUT_MS) { "Encoder exceeded its time limit" }
                    check(now - lastProgress < IDLE_TIMEOUT_MS) { "Encoder stopped making progress" }
                    var madeProgress = false
                    if (!inputEnded) {
                        val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = checkNotNull(encoder.getInputBuffer(index)) { "Encoder input buffer is missing" }
                            buffer.clear()
                            val count = minOf(buffer.remaining().toLong(), source.pcmBytes - inputBytes)
                                .toInt().let { it - it % DiagnosticPcmFormat.BYTES_PER_FRAME }
                            val presentationUs = inputBytes / DiagnosticPcmFormat.BYTES_PER_FRAME * 1_000_000 / source.sampleRate
                            if (inputBytes == source.pcmBytes) {
                                encoder.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                check(count > 0) { "Encoder input buffer cannot hold a PCM frame" }
                                val chunk = ByteArray(count)
                                input.readFully(chunk)
                                buffer.put(chunk)
                                encoder.queueInputBuffer(index, 0, count, presentationUs, 0)
                                inputBytes += count
                            }
                            madeProgress = true
                        }
                    }
                    while (!outputEnded) {
                        ensureRunning()
                        check(SystemClock.elapsedRealtime() - started < ENCODING_TIMEOUT_MS) { "Encoder exceeded its time limit" }
                        val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted) { "Encoder changed format after muxer start" }
                            val format = encoder.outputFormat
                            outputFormat = format
                            track = writer.addTrack(format)
                            writer.start()
                            muxerStarted = true
                            madeProgress = true
                        } else if (index >= 0) {
                            try {
                                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                    check(muxerStarted) { "Audio arrived before its output format" }
                                    val buffer = checkNotNull(encoder.getOutputBuffer(index)) { "Encoder output buffer is missing" }
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    writer.writeSampleData(track, buffer, info)
                                    encodedBytes += info.size
                                }
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            } finally {
                                encoder.releaseOutputBuffer(index, false)
                            }
                            madeProgress = true
                        }
                    }
                    if (madeProgress) lastProgress = SystemClock.elapsedRealtime()
                }
            }
            check(inputBytes == source.pcmBytes && encodedBytes > 0 && muxerStarted) { "Encoded audio is incomplete" }
            writer.stop()
            muxerStarted = false
            return checkNotNull(outputFormat) { "Encoder did not publish an output format" }
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun inspectTrack(file: File): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            check(extractor.trackCount == 1) { "Expected exactly one encoded audio track" }
            return extractor.getTrackFormat(0)
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val read = read(bytes, offset, bytes.size - offset)
            check(read > 0) { "PCM source ended before its declared length" }
            offset += read
        }
    }
}
