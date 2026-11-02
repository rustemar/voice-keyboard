package com.tyraen.voicekeyboard.feature.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.SystemClock
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Compresses a detached WAV after the microphone has stopped; codec failure retains the WAV. */
object UploadAudioEncoder {
    private const val TAG = "UploadAudio"
    private const val BUFFER_BYTES = 32 * 1024
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val IDLE_TIMEOUT_MS = 10_000L
    private const val ENCODING_TIMEOUT_MS = 120_000L
    // The Android software AAC encoder has priming and can omit an incomplete final frame.
    // Feed silence beyond the source so neither behavior can remove dictated start/end samples.
    private const val AAC_PADDING_SAMPLES = 8_192L
    // Opus can end before a partial frame after applying its pre-skip. Keep a small silent guard
    // beyond the dictated samples, including recordings whose length is not a codec-frame size.
    private const val OPUS_PADDING_SAMPLES = 640L

    suspend fun prepare(sourceFile: File): PreparedUploadAudio {
        var derivative: File? = null
        try {
            return withContext(Dispatchers.IO) {
                try {
                    currentCoroutineContext().ensureActive()
                    val source = PcmUploadSource.read(sourceFile)
                    val profile = if (Build.VERSION.SDK_INT >= 29) Profile.OPUS else Profile.AAC
                    val requested = MediaFormat.createAudioFormat(profile.mime, PcmUploadSource.SAMPLE_RATE, 1).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, profile.bitRate)
                        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        if (profile == Profile.AAC) setInteger(
                            MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC
                        )
                    }
                    val encoder = findEncoder(profile, requested)
                        ?: return@withContext PreparedUploadAudio(sourceFile, sourceFile)
                    val output = File.createTempFile("upload_", ".${profile.extension}", sourceFile.parentFile)
                    derivative = output
                    encode(source, output, profile, requested, encoder)
                    currentCoroutineContext().ensureActive()
                    validate(output, source, profile)
                    if (output.length() >= sourceFile.length()) {
                        output.delete()
                        derivative = null
                        return@withContext PreparedUploadAudio(sourceFile, sourceFile)
                    }
                    DiagnosticLog.record(TAG, "Prepared ${profile.extension}, source=${sourceFile.length()}, upload=${output.length()}")
                    PreparedUploadAudio(sourceFile, output)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    derivative?.delete()
                    derivative = null
                    DiagnosticLog.recordFailure(TAG, "Compression unavailable; retaining original WAV", error)
                    PreparedUploadAudio(sourceFile, sourceFile)
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext has prompt cancellation on its return as well: cover a completed output
            // whose ownership object was never delivered to the handover coroutine.
            withContext(NonCancellable + Dispatchers.IO) { derivative?.delete() }
            throw cancelled
        }
    }

    private enum class Profile(val mime: String, val bitRate: Int, val extension: String) {
        OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, 64_000, "ogg"),
        AAC(MediaFormat.MIMETYPE_AUDIO_AAC, 128_000, "m4a")
    }

    private fun findEncoder(profile: Profile, format: MediaFormat): String? {
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        if (profile == Profile.OPUS) return codecs.findEncoderForFormat(format)
        // Vendor AAC encoders can have different, undeclared priming. Use the Android software
        // encoders whose padded full-source output is checked on both legacy and current Android.
        for (name in listOf("c2.android.aac.encoder", "OMX.google.aac.encoder")) {
            val codec = codecs.codecInfos.firstOrNull { it.isEncoder && it.name == name } ?: continue
            val supported = runCatching { codec.getCapabilitiesForType(profile.mime).isFormatSupported(format) }
                .getOrDefault(false)
            if (supported) return codec.name
        }
        return null
    }

    private suspend fun encode(
        source: PcmUploadSource,
        output: File,
        profile: Profile,
        requested: MediaFormat,
        encoderName: String
    ) {
        val coroutine = currentCoroutineContext()
        val encoder = MediaCodec.createByCodecName(encoderName)
        var encoderStarted = false
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            encoder.configure(requested, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true
            val container = if (profile == Profile.OPUS) {
                check(Build.VERSION.SDK_INT >= 29)
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            } else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val writer = MediaMuxer(output.absolutePath, container)
            muxer = writer
            val paddingSamples = if (profile == Profile.AAC) AAC_PADDING_SAMPLES else OPUS_PADDING_SAMPLES
            val paddingBytes = paddingSamples * PcmUploadSource.BYTES_PER_SAMPLE
            val totalBytes = source.pcmBytes + paddingBytes
            val endUs = totalBytes / PcmUploadSource.BYTES_PER_SAMPLE * 1_000_000 / PcmUploadSource.SAMPLE_RATE
            val scratch = ByteArray(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var consumed = 0L
            var encodedBytes = 0L
            var inputEnded = false
            var outputEnded = false
            var lastOutputUs = -1L
            val started = SystemClock.elapsedRealtime()
            var lastProgress = started
            source.file.inputStream().buffered(BUFFER_BYTES).use { input ->
                input.readFully(ByteArray(PcmUploadSource.HEADER_BYTES), PcmUploadSource.HEADER_BYTES)
                while (!outputEnded) {
                    coroutine.ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    check(now - started < ENCODING_TIMEOUT_MS) { "Audio compression exceeded its time limit" }
                    check(now - lastProgress < IDLE_TIMEOUT_MS) { "Audio encoder stopped making progress" }
                    var madeProgress = false
                    if (!inputEnded) {
                        val index = encoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = checkNotNull(encoder.getInputBuffer(index))
                            buffer.clear()
                            val count = minOf(buffer.remaining().toLong(), scratch.size.toLong(), totalBytes - consumed)
                                .toInt().let { it - it % PcmUploadSource.BYTES_PER_SAMPLE }
                            check(count > 0) { "Audio encoder cannot accept a PCM sample" }
                            val fromSource = minOf(count.toLong(), (source.pcmBytes - consumed).coerceAtLeast(0)).toInt()
                            if (fromSource > 0) input.readFully(scratch, fromSource)
                            if (fromSource < count) scratch.fill(0, fromSource, count)
                            buffer.put(scratch, 0, count)
                            val timestampUs = consumed / PcmUploadSource.BYTES_PER_SAMPLE * 1_000_000 / PcmUploadSource.SAMPLE_RATE
                            consumed += count
                            inputEnded = consumed == totalBytes
                            encoder.queueInputBuffer(index, 0, count, timestampUs,
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            madeProgress = true
                        }
                    }
                    while (!outputEnded) {
                        coroutine.ensureActive()
                        check(SystemClock.elapsedRealtime() - started < ENCODING_TIMEOUT_MS) { "Audio compression exceeded its time limit" }
                        val index = encoder.dequeueOutputBuffer(info, if (inputEnded) DEQUEUE_TIMEOUT_US else 0)
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            check(!muxerStarted) { "Audio encoder changed its output format twice" }
                            track = writer.addTrack(encoder.outputFormat)
                            writer.start()
                            muxerStarted = true
                            madeProgress = true
                        } else if (index >= 0) {
                            try {
                                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                if (!isConfig && info.size > 0) {
                                    check(muxerStarted && info.presentationTimeUs >= lastOutputUs) { "Invalid audio output ordering" }
                                    val buffer = checkNotNull(encoder.getOutputBuffer(index))
                                    buffer.position(info.offset)
                                    buffer.limit(info.offset + info.size)
                                    writer.writeSampleData(track, buffer, info)
                                    lastOutputUs = info.presentationTimeUs
                                    encodedBytes += info.size
                                }
                                if (outputEnded && profile == Profile.AAC && muxerStarted) {
                                    // MPEG4 otherwise infers the final sample duration from the
                                    // previous sample and can trim a partial final AAC frame.
                                    check(endUs > lastOutputUs) { "AAC output extends beyond its PCM input" }
                                    val end = MediaCodec.BufferInfo().apply {
                                        set(0, 0, endUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    }
                                    writer.writeSampleData(track, ByteBuffer.allocate(0), end)
                                }
                            } finally {
                                encoder.releaseOutputBuffer(index, false)
                            }
                            madeProgress = true
                        }
                    }
                    if (madeProgress) lastProgress = SystemClock.elapsedRealtime()
                }
            }
            check(consumed == totalBytes && encodedBytes > 0 && muxerStarted) { "Encoded audio is incomplete" }
            writer.stop()
            muxerStarted = false
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            if (encoderStarted) runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun validate(output: File, source: PcmUploadSource, profile: Profile) {
        check(output.isFile && output.length() > 0) { "Audio encoder produced an empty file" }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(output.absolutePath)
            check(extractor.trackCount == 1) { "Encoded audio has an unexpected track count" }
            val format = extractor.getTrackFormat(0)
            check(format.getString(MediaFormat.KEY_MIME) == profile.mime && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) {
                "Encoded audio has an unexpected format"
            }
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            // Opus uses a 48 kHz container clock even when its PCM input was 16 kHz.
            check(rate == if (profile == Profile.OPUS) 48_000 else PcmUploadSource.SAMPLE_RATE) { "Encoded audio has an unexpected sample rate" }
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val minimum = source.durationUs + if (profile == Profile.AAC) 256_000 else 0
            val maximum = source.durationUs + if (profile == Profile.AAC) 768_000 else 160_000
            check(durationUs in minimum..maximum) { "Encoded audio does not cover its source duration" }
            extractor.selectTrack(0)
            check(extractor.sampleTime >= 0 && extractor.readSampleData(ByteBuffer.allocate(BUFFER_BYTES), 0) > 0) {
                "Encoded audio has no readable samples"
            }
        } finally {
            extractor.release()
        }
    }

    private fun InputStream.readFully(bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            check(read > 0) { "PCM source ended before its declared length" }
            offset += read
        }
    }
}
