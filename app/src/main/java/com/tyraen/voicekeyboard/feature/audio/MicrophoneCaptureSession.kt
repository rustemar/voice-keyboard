package com.tyraen.voicekeyboard.feature.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.tyraen.voicekeyboard.core.logging.CaptureDiagnostics
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MicrophoneCaptureSession @JvmOverloads constructor(
    private val context: Context,
    private val owner: String = "unspecified"
) {

    val diagnosticId = CaptureDiagnostics.newId("capture")

    private fun trace(event: String, details: String = "") =
        CaptureDiagnostics.event("Capture", event, "session=$diagnosticId owner=$owner $details")

    companion object {
        private const val TAG = "Capture"
        private val fileSeq = AtomicLong(0)

        /**
         * Opus, 48 kHz mono. Reduced from 200 kbps in v1.9.0 to make uploads smaller.
         * Whisper's 16 kHz inference rate alone does not establish equal recognition accuracy
         * at different lossy encoding bitrates.
         */
        private const val OPUS_BITRATE = 64_000

        /**
         * Up to 1.9.6 recordings went to externalCacheDir. The queue deletes or parks every file it
         * finishes, so anything still there is a leftover from a crash; removed at startup.
         */
        fun deleteLegacyRecordings(context: Context) {
            context.externalCacheDir?.listFiles { f -> f.name.startsWith("recording_") }?.forEach { it.delete() }
        }
        private const val AAC_BITRATE = 128_000

        /** Nothing this short contains speech, and MediaRecorder refuses to finalize it anyway. */
        const val MIN_CAPTURE_MS = 300L
    }

    private var recorder: MediaRecorder? = null
    private var microphoneLease: MicrophoneGate.Lease? = null
    private var levelMonitor: Job? = null
    private val scope = MainScope()
    private var startedAtMs: Long = 0

    /** The file of the capture in progress. Null once [finalize] handed it over or [abort] deleted it. */
    var capturedFile: File? = null
        private set
    var lastDurationMs: Long = 0
        private set

    val isActive: Boolean
        get() = recorder != null

    /**
     * Start recording. Returns false, leaving the session idle, when the microphone cannot be
     * opened: another app holds it, storage is unavailable, or the OEM recorder refuses to start.
     * Those used to propagate out of the mic button and take the keyboard process down.
     */
    fun begin(onAmplitude: (Int) -> Unit): Boolean {
        trace("begin_requested", "active=$isActive")
        if (recorder != null) {
            trace("begin_already_active")
            return true
        }

        val useOpus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val extension = if (useOpus) "ogg" else "m4a"
        // Process-wide unique name: a fresh capture session is created per input view, so a plain
        // per-instance counter could collide with another view's still-pending recording_0.
        // The private cache, not externalCacheDir: on Android 9 and lower other apps with the
        // storage permission can read the external one.
        val dir = context.cacheDir
        val file = File(dir, "recording_${fileSeq.getAndIncrement()}.$extension")
        val lease = MicrophoneGate.shared.acquire("$owner/$diagnosticId")
        if (lease == null) {
            trace("begin_refused", "reason=gate_busy holder=${MicrophoneGate.shared.diagnosticOwner}")
            return false
        }

        var candidate: MediaRecorder? = null
        var stage = "construct"
        try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            candidate = mr
            stage = "configure"
            mr.apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                if (useOpus) {
                    setOutputFormat(MediaRecorder.OutputFormat.OGG)
                    setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                    setAudioSamplingRate(48000)
                    setAudioEncodingBitRate(OPUS_BITRATE)
                } else {
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(AAC_BITRATE)
                }
                setAudioChannels(1)
                setOutputFile(file.absolutePath)
                stage = "prepare"
                trace("recorder_prepare")
                prepare()
                stage = "start"
                trace("recorder_start")
                start()
            }
        } catch (e: Exception) {
            trace("begin_failed", "stage=$stage error=${e.javaClass.simpleName}")
            DiagnosticLog.recordFailure(TAG, "Microphone unavailable", e)
            runCatching { candidate?.reset() }
            runCatching { candidate?.release() }
            lease.close()
            file.delete()
            return false
        }

        val mr = checkNotNull(candidate)
        recorder = mr
        microphoneLease = lease
        capturedFile = file
        startedAtMs = System.currentTimeMillis()
        lastDurationMs = 0
        trace("started", "format=$extension file=${file.name}")

        levelMonitor = scope.launch {
            while (isActive) {
                try {
                    onAmplitude(mr.maxAmplitude)
                } catch (error: Exception) {
                    trace("amplitude_monitor_failed", "error=${error.javaClass.simpleName} active=$isActive")
                    break
                }
                delay(150)
            }
        }

        return true
    }

    /**
     * Stop recording and hand the file to the caller, who owns it from now on: a later [abort] or
     * [release] no longer touches it, so tearing the input view down (keyboard switch, theme
     * rebuild) cannot delete a recording the queue is still uploading.
     *
     * Returns null when nothing usable was captured. An immediate double tap makes
     * `MediaRecorder.stop()` throw and leaves a malformed file behind; uploading that earned a
     * permanent "needs attention" badge that resend could never clear.
     */
    fun finalize(): File? {
        trace("finalize_requested", "active=$isActive")
        levelMonitor?.cancel()
        levelMonitor = null
        val mr = recorder ?: run {
            trace("finalize_empty", "reason=no_active_recorder")
            return null
        }
        recorder = null
        val file = capturedFile
        capturedFile = null
        lastDurationMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0

        val stopped = try {
            mr.stop()
            true
        } catch (e: Exception) {
            trace("finalize_stop_failed", "durationMs=$lastDurationMs error=${e.javaClass.simpleName}")
            DiagnosticLog.record(TAG, "stop failed after ${lastDurationMs}ms: ${e.message}")
            false
        }
        runCatching { mr.release() }
        microphoneLease?.close()
        microphoneLease = null

        val usable = stopped && lastDurationMs >= MIN_CAPTURE_MS &&
            file != null && file.exists() && file.length() > 0L
        trace("finalized", "stopped=$stopped usable=$usable durationMs=$lastDurationMs bytes=${file?.length() ?: 0L}")
        if (!usable) {
            DiagnosticLog.record(TAG, "Discarding unusable capture (${lastDurationMs}ms)")
            file?.delete()
            return null
        }
        trace("file_handed_off", "file=${file?.name} bytes=${file?.length() ?: 0L}")
        return file
    }

    /** Throw away the capture in progress, if any. Files already handed over by [finalize] are kept. */
    @JvmOverloads
    fun abort(reason: String = "unspecified") {
        trace("abort_requested", "reason=$reason active=$isActive ownsFile=${capturedFile != null}")
        levelMonitor?.cancel()
        levelMonitor = null
        recorder?.let { mr ->
            try {
                mr.stop()
            } catch (error: Exception) {
                trace("abort_stop_failed", "reason=$reason error=${error.javaClass.simpleName}")
            }
            runCatching { mr.release() }
        }
        recorder = null
        microphoneLease?.close()
        microphoneLease = null
        capturedFile?.delete()
        capturedFile = null
        trace("aborted", "reason=$reason active=$isActive")
    }

    @JvmOverloads
    fun release(reason: String = "unspecified") {
        trace("release_requested", "reason=$reason active=$isActive")
        abort(reason)
        scope.cancel()
        trace("released", "reason=$reason")
    }
}
