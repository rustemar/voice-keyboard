package com.tyraen.voicekeyboard.feature.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class MicrophoneCaptureSession(private val context: Context) {

    companion object {
        private const val TAG = "Capture"
        private val fileSeq = AtomicLong(0)

        /**
         * Opus, 48 kHz mono. Whisper resamples everything to 16 kHz before inference, so bits
         * above roughly 64 kbps are thrown away server-side; the previous 200 kbps only made every
         * upload three times larger, which is exactly what stalls on a weak mobile connection.
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
        if (recorder != null) return true

        val useOpus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val extension = if (useOpus) "ogg" else "m4a"
        // Process-wide unique name: a fresh capture session is created per input view, so a plain
        // per-instance counter could collide with another view's still-pending recording_0.
        // The private cache, not externalCacheDir: on Android 9 and lower other apps with the
        // storage permission can read the external one.
        val dir = context.cacheDir
        val file = File(dir, "recording_${fileSeq.getAndIncrement()}.$extension")

        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
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
                prepare()
                start()
            }
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Microphone unavailable", e)
            runCatching { mr.reset() }
            runCatching { mr.release() }
            file.delete()
            return false
        }

        recorder = mr
        capturedFile = file
        startedAtMs = System.currentTimeMillis()
        lastDurationMs = 0

        levelMonitor = scope.launch {
            while (isActive) {
                try {
                    onAmplitude(mr.maxAmplitude)
                } catch (_: Exception) {
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
        levelMonitor?.cancel()
        levelMonitor = null
        val mr = recorder ?: return null
        recorder = null
        val file = capturedFile
        capturedFile = null
        lastDurationMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0

        val stopped = try {
            mr.stop()
            true
        } catch (e: Exception) {
            DiagnosticLog.record(TAG, "stop failed after ${lastDurationMs}ms: ${e.message}")
            false
        }
        runCatching { mr.release() }

        val usable = stopped && lastDurationMs >= MIN_CAPTURE_MS &&
            file != null && file.exists() && file.length() > 0L
        if (!usable) {
            DiagnosticLog.record(TAG, "Discarding unusable capture (${lastDurationMs}ms)")
            file?.delete()
            return null
        }
        return file
    }

    /** Throw away the capture in progress, if any. Files already handed over by [finalize] are kept. */
    fun abort() {
        levelMonitor?.cancel()
        levelMonitor = null
        recorder?.let { mr ->
            try {
                mr.stop()
            } catch (_: Exception) {}
            runCatching { mr.release() }
        }
        recorder = null
        capturedFile?.delete()
        capturedFile = null
    }

    fun release() {
        abort()
        scope.cancel()
    }
}
