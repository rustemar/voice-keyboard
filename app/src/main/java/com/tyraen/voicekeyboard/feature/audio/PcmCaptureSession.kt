package com.tyraen.voicekeyboard.feature.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Keyboard capture: one microphone feeds a private WAV and a separately bounded detector. */
internal class PcmCaptureSession private constructor(
    private val recorder: AudioRecord,
    private val spool: PcmCaptureFile,
    private val lease: MicrophoneGate.Lease,
    private val onAmplitude: (Int) -> Unit,
    onPcm: (ShortArray, Int, Long) -> Unit,
    onClosed: () -> Unit,
    private val onUnexpectedStop: () -> Unit,
    private val startedAtMs: Long
) {
    private val recorderLock = Any()
    private var microphoneStopped = false
    private var recorderReleased = false
    private val stopRequested = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val amplitudeEnabled = AtomicBoolean(true)
    private val completion = CompletableDeferred<File?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysis = PcmCaptureAnalysis(onPcm, onClosed)
    private val worker = Thread({ capture() }, "PcmCaptureWriter").apply { isDaemon = true }

    init {
        completion.invokeOnCompletion { if (completion.isCancelled) cancel() }
    }

    fun finish(): CaptureFinalization {
        requestStop()
        return CaptureFinalization(
            (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0), completion, ::cancel
        )
    }

    fun cancel() {
        cancelled.set(true)
        requestStop()
        // A writer can be in fsync. Cancellation must never wait for its file lock on Main.
        cleanupScope.launch { spool.cancel() }
        completion.complete(null)
    }

    private fun requestStop() {
        stopRequested.set(true)
        analysis.close()
        // AudioRecord.read is nonblocking and the lock never covers file IO or detector calls.
        // The next capture can acquire the microphone as soon as this method returns.
        stopMicrophone()
    }

    private fun stopMicrophone() = synchronized(recorderLock) {
        if (!microphoneStopped) {
            microphoneStopped = true
            try {
                recorder.stop()
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("Capture", "PCM microphone stop failed", error)
                releaseRecorder()
            }
        }
        lease.close()
    }

    private fun releaseRecorder() = synchronized(recorderLock) {
        if (!recorderReleased) {
            recorderReleased = true
            runCatching { recorder.release() }
        }
    }

    private fun capture() {
        val samples = ShortArray(512)
        var sampleCount = 0L
        var lastAmplitudeAt = startedAtMs - 150
        var unexpectedStop = false
        try {
            while (!stopRequested.get()) {
                val read = synchronized(recorderLock) {
                    if (microphoneStopped) 0
                    else recorder.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
                }
                if (read < 0) {
                    if (!stopRequested.get()) {
                        unexpectedStop = true
                        DiagnosticLog.recordFailure("Capture", "PCM microphone read failed code=$read", IOException())
                    }
                    break
                }
                if (read == 0) {
                    val recorderStopped = synchronized(recorderLock) {
                        !microphoneStopped && recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING
                    }
                    if (recorderStopped && !stopRequested.get() && !cancelled.get()) {
                        unexpectedStop = true
                        DiagnosticLog.record("Capture", "PCM microphone stopped without a stop request")
                        break
                    }
                    Thread.sleep(5)
                    continue
                }
                // A last chunk already read before stop still belongs in the recording.
                if (cancelled.get() || !spool.append(samples, read)) break
                sampleCount += read
                val sampleAtMs = startedAtMs + sampleCount * 1_000 / SAMPLE_RATE
                analysis.offer(samples, read, sampleAtMs)
                val now = SystemClock.elapsedRealtime()
                if (now - lastAmplitudeAt >= 150) {
                    lastAmplitudeAt = now
                    var peak = 0
                    for (index in 0 until read) peak = maxOf(peak, abs(samples[index].toInt()))
                    val level = peak.coerceAtMost(32767)
                    mainHandler.post {
                        if (!stopRequested.get() && !cancelled.get() && amplitudeEnabled.get()) {
                            try {
                                onAmplitude(level)
                            } catch (error: Exception) {
                                if (amplitudeEnabled.compareAndSet(true, false)) {
                                    DiagnosticLog.recordFailure("Capture", "PCM amplitude callback disabled", error)
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            if (!stopRequested.get() && !cancelled.get()) {
                unexpectedStop = true
                DiagnosticLog.recordFailure("Capture", "PCM capture stopped", error)
            }
        } finally {
            stopRequested.set(true)
            stopMicrophone()
            releaseRecorder()
            analysis.close()
            var file: File? = null
            try {
                if (cancelled.get()) spool.cancel()
                else file = spool.finish(MicrophoneCaptureSession.MIN_CAPTURE_MS)
            } catch (error: Exception) {
                DiagnosticLog.recordFailure("Capture", "PCM WAV finalization failed", error)
                if (!cancelled.get()) {
                    try {
                        file = spool.recover(MicrophoneCaptureSession.MIN_CAPTURE_MS)
                        if (file != null) DiagnosticLog.record("Capture", "PCM WAV header recovered")
                    } catch (repairError: Exception) {
                        DiagnosticLog.recordFailure("Capture", "PCM WAV repair failed", repairError)
                    }
                    if (file == null && spool.file.exists()) {
                        DiagnosticLog.record("Capture", "PCM source retained for recovery file=${spool.file.name} " +
                            "durationMs=${spool.durationMs}")
                    }
                }
            }
            if (cancelled.get()) {
                spool.cancel()
                completion.complete(null)
            } else {
                completion.complete(file)
            }
            if (unexpectedStop && !cancelled.get()) {
                mainHandler.post {
                    if (!cancelled.get()) {
                        try {
                            onUnexpectedStop()
                        } catch (error: Exception) {
                            DiagnosticLog.recordFailure("Capture", "PCM stopped callback failed", error)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun start(
            context: Context,
            file: File,
            lease: MicrophoneGate.Lease,
            onAmplitude: (Int) -> Unit,
            onPcm: (ShortArray, Int, Long) -> Unit,
            onClosed: () -> Unit,
            onUnexpectedStop: () -> Unit
        ): PcmCaptureSession {
            var recorder: AudioRecord? = null
            var spool: PcmCaptureFile? = null
            var session: PcmCaptureSession? = null
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) throw SecurityException("Microphone permission unavailable")
                val minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(minimum > 0) { "PCM microphone format unavailable" }
                val output = PcmCaptureFile(file, SAMPLE_RATE)
                spool = output
                val source = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    // More than a second is unnecessary for inference: analysis has its own queue.
                    .setBufferSizeInBytes(maxOf(minimum * 2, SAMPLE_RATE * 2))
                    .build()
                recorder = source
                check(source.state == AudioRecord.STATE_INITIALIZED && source.sampleRate == SAMPLE_RATE &&
                    source.channelCount == 1 && source.audioFormat == AudioFormat.ENCODING_PCM_16BIT) {
                    "PCM microphone initialization failed"
                }
                source.startRecording()
                check(source.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "PCM microphone did not start" }
                session = PcmCaptureSession(source, output, lease, onAmplitude, onPcm, onClosed, onUnexpectedStop,
                    SystemClock.elapsedRealtime())
                session.worker.start()
                return session
            } catch (error: Exception) {
                if (session != null) {
                    session.cancel()
                    session.releaseRecorder()
                }
                else {
                    runCatching { recorder?.stop() }
                    runCatching { recorder?.release() }
                    spool?.cancel()
                    file.delete()
                    lease.close()
                }
                throw error
            }
        }
    }
}
