package com.tyraen.voicekeyboard.feature.audio.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * One local comparison, with one microphone source and no network/API dependency.
 *
 * UI owns this session: capture() suspends until stopCapture() or the 45-second limit;
 * encode() creates all variants sequentially. Both callbacks run on Main. Cancel the calling
 * coroutine or close() to abort; partial files are deleted. Keep the session open while exporting
 * results, then close it. A new capture replaces the previous test's files.
 */
class DiagnosticAudioSession(
    context: Context,
    val maxCaptureDurationMs: Long = DiagnosticPcmFormat.MAX_CAPTURE_DURATION_MS
) : Closeable {
    companion object {
        private val sessionInUse = AtomicBoolean(false)
        private const val MIN_CAPTURE_MS = 300L
        private const val MIN_FREE_BYTES = 16L * 1024 * 1024
    }

    private val appContext = context.applicationContext
    val sessionDir = File(appContext.cacheDir, "audio_diagnostics/session")
    private val lifecycleLock = Any()
    private val operation = Mutex()
    private val closed = AtomicBoolean(false)
    private val leaseReleased = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)

    val isCapturing: Boolean get() = capturing.get()

    init {
        require(maxCaptureDurationMs in MIN_CAPTURE_MS..DiagnosticPcmFormat.MAX_CAPTURE_DURATION_MS)
        check(sessionInUse.compareAndSet(false, true)) { "Another audio comparison is still open" }
        try {
            check(!sessionDir.exists() || sessionDir.deleteRecursively()) { "Cannot remove the previous audio comparison" }
            check(sessionDir.mkdirs()) { "Cannot create the private audio comparison directory" }
            check(sessionDir.usableSpace >= MIN_FREE_BYTES) { "Not enough free space for an audio comparison" }
        } catch (error: Throwable) {
            try { sessionDir.deleteRecursively() } finally { sessionInUse.set(false) }
            throw error
        }
    }

    /** Gracefully finish the current PCM source; never starts an encoder or an upload. */
    fun stopCapture() {
        stopRequested.set(true)
    }

    suspend fun capture(
        onProgress: (DiagnosticCaptureProgress) -> Unit = {}
    ): DiagnosticPcmCapture = runOperation(beforeDispatch = { stopRequested.set(false) }) {
        clearFiles()
        check(appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        val minimum = AudioRecord.getMinBufferSize(
            DiagnosticPcmFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "48 kHz mono PCM recording is not supported ($minimum)" }
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(DiagnosticPcmFormat.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, DiagnosticPcmFormat.SAMPLE_RATE / 5 * 2))
            .build()
        val file = File(sessionDir, "source.pcm")
        val digest = MessageDigest.getInstance("SHA-256")
        val samples = ShortArray(2_400)
        val bytes = ByteArray(samples.size * 2)
        var count = 0L
        var peak = 0
        var wallDuration = 0L
        var capped = false
        val settings = linkedMapOf<String, String>()
        val recordedAt = System.currentTimeMillis()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone recorder was not initialized" }
            check(recorder.sampleRate == DiagnosticPcmFormat.SAMPLE_RATE &&
                recorder.channelCount == DiagnosticPcmFormat.CHANNEL_COUNT &&
                recorder.audioFormat == AudioFormat.ENCODING_PCM_16BIT) { "Recorder changed the requested PCM format" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not start" }
            capturing.set(true)
            val started = SystemClock.elapsedRealtime()
            var lastProgress = started - 100
            val maximumBytes = maxCaptureDurationMs * DiagnosticPcmFormat.SAMPLE_RATE * 2 / 1_000
            settings["requestedAudioSource"] = "VOICE_RECOGNITION"
            settings["audioSource"] = recorder.audioSource.toString()
            settings["audioRecordSampleRate"] = recorder.sampleRate.toString()
            settings["audioRecordChannels"] = recorder.channelCount.toString()
            settings["audioRecordEncoding"] = recorder.audioFormat.toString()
            settings["routedDeviceType"] = recorder.routedDevice?.type?.toString() ?: "unknown"
            if (Build.VERSION.SDK_INT >= 29) {
                settings["clientSilenced"] = recorder.activeRecordingConfiguration?.isClientSilenced?.toString() ?: "unknown"
            }
            file.outputStream().buffered().use { output ->
                while (!stopRequested.get()) {
                    ensureRunning()
                    val now = SystemClock.elapsedRealtime()
                    if (now - started >= maxCaptureDurationMs || count >= maximumBytes) {
                        capped = true
                        break
                    }
                    val wanted = minOf(samples.size.toLong(), (maximumBytes - count) / 2).toInt()
                    val read = recorder.read(samples, 0, wanted, AudioRecord.READ_NON_BLOCKING)
                    check(read >= 0) { "PCM microphone read failed ($read)" }
                    if (read == 0) {
                        delay(10)
                        continue
                    }
                    var bufferPeak = 0
                    for (index in 0 until read) {
                        val sample = samples[index].toInt()
                        bytes[index * 2] = sample.toByte()
                        bytes[index * 2 + 1] = (sample shr 8).toByte()
                        bufferPeak = maxOf(bufferPeak, abs(sample))
                    }
                    val byteCount = read * 2
                    output.write(bytes, 0, byteCount)
                    digest.update(bytes, 0, byteCount)
                    count += byteCount
                    peak = maxOf(peak, bufferPeak)
                    if (now - lastProgress >= 100) {
                        lastProgress = now
                        withContext(Dispatchers.Main) {
                            onProgress(DiagnosticCaptureProgress(DiagnosticPcmFormat.durationMs(count), bufferPeak, now - started))
                        }
                    }
                }
            }
            wallDuration = SystemClock.elapsedRealtime() - started
            ensureRunning()
            check(DiagnosticPcmFormat.durationMs(count) >= MIN_CAPTURE_MS) { "The audio comparison recording is too short" }
            withContext(Dispatchers.Main) {
                onProgress(DiagnosticCaptureProgress(DiagnosticPcmFormat.durationMs(count), 0, wallDuration))
            }
        } finally {
            capturing.set(false)
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        DiagnosticPcmCapture(
            file, count, DiagnosticPcmFormat.durationMs(count), wallDuration, recordedAt,
            digest.digest().hex(), peak, capped, settings.toMap()
        )
    }

    suspend fun encode(
        capture: DiagnosticPcmCapture,
        onVariantReady: (DiagnosticAudioVariant) -> Unit = {}
    ): DiagnosticAudioComparison = runOperation {
        require(capture.file.canonicalFile == File(sessionDir, "source.pcm").canonicalFile) { "PCM belongs to another session" }
        require(capture.sampleRate == DiagnosticPcmFormat.SAMPLE_RATE && capture.channelCount == 1 && capture.bitsPerSample == 16)
        require(capture.pcmBytes in 1..DiagnosticPcmFormat.MAX_PCM_BYTES && capture.pcmBytes % 2 == 0L)
        require(capture.durationMs == DiagnosticPcmFormat.durationMs(capture.pcmBytes) && capture.durationMs >= MIN_CAPTURE_MS)
        check(capture.file.isFile && capture.file.length() == capture.pcmBytes) { "PCM source is missing or changed" }
        check(capture.file.sha256() == capture.sha256) { "PCM source has changed since recording" }
        val started = SystemClock.elapsedRealtime()
        val variants = mutableListOf<DiagnosticAudioVariant>()
        for (profile in DiagnosticAudioProfile.values()) {
            ensureRunning()
            val variant = DiagnosticAudioEncoder.encode(capture, profile, sessionDir) { ensureRunning() }
            variants.add(variant)
            withContext(Dispatchers.Main) { onVariantReady(variant) }
        }
        DiagnosticAudioComparison(capture, variants.toList(), sessionDir, SystemClock.elapsedRealtime() - started)
    }

    /** Does not wait for an encoder; its loop observes cancellation and releases resources. */
    override fun close() {
        synchronized(lifecycleLock) {
            closed.set(true)
            stopRequested.set(true)
            if (operation.tryLock()) {
                try { deleteAndRelease() } finally { operation.unlock() }
            }
        }
    }

    private suspend fun ensureRunning() {
        currentCoroutineContext().ensureActive()
        if (closed.get()) throw CancellationException("Audio comparison session was closed")
    }

    private suspend fun <T> runOperation(beforeDispatch: () -> Unit = {}, block: suspend () -> T): T {
        synchronized(lifecycleLock) {
            check(!closed.get()) { "Audio comparison session is closed" }
            check(operation.tryLock()) { "An audio comparison operation is already running" }
        }
        var failed = false
        try {
            beforeDispatch()
            return withContext(Dispatchers.IO) {
                ensureRunning()
                block()
            }
        } catch (error: Throwable) {
            failed = true
            throw error
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(lifecycleLock) {
                    try {
                        if (closed.get()) deleteAndRelease() else if (failed) clearFiles()
                    } finally {
                        operation.unlock()
                    }
                }
            }
        }
    }

    private fun clearFiles() {
        sessionDir.listFiles()?.forEach { check(it.deleteRecursively()) { "Cannot clear diagnostic audio" } }
    }

    private fun deleteAndRelease() {
        // An old Activity may close its session again after a replacement Activity acquired
        // the shared directory. It must never delete the replacement session's files.
        if (!leaseReleased.compareAndSet(false, true)) return
        try { sessionDir.deleteRecursively() } finally { sessionInUse.set(false) }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().hex()
}
