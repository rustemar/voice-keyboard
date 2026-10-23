package com.tyraen.voicekeyboard.feature.recognition

import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.log10

/**
 * One client-owned recognition request. All methods and capture callbacks run on the caller's
 * Main scope. It never uses the keyboard queue, clipboard, post-processing or retained history.
 */
internal class RecognitionSession(
    private val capture: Capture,
    private val speechClient: SpeechToTextClient,
    private val scope: CoroutineScope,
    private val callbacks: Callbacks,
    private val elapsedRealtime: () -> Long,
    private val limits: Limits = Limits()
) {
    interface Capture {
        fun start(onAmplitude: (Int) -> Unit): Boolean
        /** Stops the microphone and transfers ownership of a usable file to this session. */
        fun finish(): CapturedAudio?
        /** Releases the microphone and deletes only audio still owned by the capture. */
        fun abort()
    }

    data class CapturedAudio(val file: File, val durationMs: Long)

    interface Callbacks {
        fun onReady()
        fun onBeginningOfSpeech()
        /** Relative level derived from peak amplitude, not a calibrated acoustic measurement. */
        fun onRmsChanged(value: Float)
        fun onEndOfSpeech()
        fun onResults(text: String)
        fun onError(error: Error)
    }

    enum class Error {
        AUDIO, SPEECH_TIMEOUT, NO_MATCH, NETWORK, NETWORK_TIMEOUT, SERVER, CLIENT,
        INSUFFICIENT_PERMISSIONS, RECOGNIZER_BUSY, LANGUAGE_NOT_SUPPORTED, TOO_MANY_REQUESTS
    }

    data class Limits(
        val maximumCaptureMs: Long = 45_000,
        val noSpeechTimeoutMs: Long = 5_000,
        val completeSilenceMs: Long = 1_200,
        val minimumListeningMs: Long = 0,
        val maximumRequestMs: Long = 45_000,
        val speechAmplitude: Int = 600
    ) {
        init {
            require(maximumCaptureMs > 0 && noSpeechTimeoutMs > 0 && completeSilenceMs > 0)
            require(minimumListeningMs in 0..maximumCaptureMs && maximumRequestMs > 0)
            require(speechAmplitude in 1..32767)
        }
    }

    private enum class State { IDLE, RECORDING, PROCESSING, FINISHED, CANCELLED }
    private var state = State.IDLE
    private var config: TranscriptionConfig? = null
    private var endpointer: AmplitudeEndpointer? = null
    private var monitorJob: Job? = null
    private var requestJob: Job? = null
    private var ownedAudio: File? = null

    val isActive: Boolean get() = state == State.RECORDING || state == State.PROCESSING
    val isRecording: Boolean get() = state == State.RECORDING

    /** A session is single-use, including after cancel or a failed start. */
    fun start(config: TranscriptionConfig): Boolean {
        if (state != State.IDLE) return false
        this.config = config
        if (config.apiKey.isBlank()) {
            fail(Error.CLIENT)
            return false
        }
        val started = try {
            capture.start(::onAmplitude)
        } catch (_: SecurityException) {
            fail(Error.INSUFFICIENT_PERMISSIONS)
            return false
        } catch (_: Exception) {
            fail(Error.AUDIO)
            return false
        }
        if (!started) {
            fail(Error.AUDIO)
            return false
        }
        endpointer = AmplitudeEndpointer(limits, elapsedRealtime())
        state = State.RECORDING
        if (!emit { onReady() }) return false
        if (state != State.RECORDING) return true
        monitorJob = scope.launch {
            while (state == State.RECORDING) {
                delay(100)
                when (endpointer?.action(elapsedRealtime())) {
                    AmplitudeEndpointer.Action.STOP -> stop()
                    AmplitudeEndpointer.Action.SPEECH_TIMEOUT -> fail(Error.SPEECH_TIMEOUT)
                    else -> Unit
                }
            }
        }
        return true
    }

    private fun onAmplitude(value: Int) {
        if (state != State.RECORDING) return
        val peak = value.coerceIn(0, 32767)
        if (endpointer?.amplitude(peak, elapsedRealtime()) == true) {
            if (!emit { onBeginningOfSpeech() }) return
        }
        if (state == State.RECORDING) {
            val relativeDb = (20.0 * log10(peak.coerceAtLeast(1).toDouble()) - 90.3)
                .coerceIn(-90.3, 0.0).toFloat()
            emit { onRmsChanged(relativeDb) }
        }
    }

    /** Explicit stop flushes usable audio even when a quiet voice did not trip the amplitude gate. */
    fun stop() {
        if (state == State.IDLE) {
            fail(Error.CLIENT)
            return
        }
        if (state != State.RECORDING) return
        state = State.PROCESSING
        monitorJob?.cancel()
        monitorJob = null
        val audio = try {
            capture.finish()
        } catch (_: SecurityException) {
            fail(Error.INSUFFICIENT_PERMISSIONS)
            return
        } catch (_: Exception) {
            fail(Error.AUDIO)
            return
        }
        ownedAudio = audio?.file
        if (!emit { onEndOfSpeech() } || state != State.PROCESSING) return
        if (audio == null || audio.durationMs < 300 || !audio.file.isFile || audio.file.length() == 0L) {
            fail(Error.NO_MATCH)
            return
        }
        val requestConfig = config!!.copy(recordingDurationMs = audio.durationMs)
        // Assign first so cancel from a callback always reaches the exact active request.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = withTimeout(limits.maximumRequestMs) {
                    speechClient.transcribe(audio.file, requestConfig)
                }
                currentCoroutineContext().ensureActive()
                if (state != State.PROCESSING) return@launch
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                if (error != null) {
                    fail(classifyError(error))
                    return@launch
                }
                val text = result.getOrNull()?.trim().orEmpty()
                if (text.isEmpty()) fail(Error.NO_MATCH)
                else {
                    state = State.FINISHED
                    emit { onResults(text) }
                }
            } catch (_: TimeoutCancellationException) {
                if (state == State.PROCESSING) fail(Error.NETWORK_TIMEOUT)
            } catch (e: CancellationException) {
                // Explicit client cancellation is silent. A provider cancelling independently
                // must still finish the live client request instead of leaving it busy forever.
                if (state == State.PROCESSING) fail(Error.CLIENT)
                else throw e
            } catch (e: Exception) {
                if (state == State.PROCESSING) fail(classifyError(e))
            } finally {
                audio.file.delete()
                if (ownedAudio === audio.file) ownedAudio = null
            }
        }
        requestJob = job
        // Also handles cancellation before the dispatched coroutine body/finally starts.
        job.invokeOnCompletion {
            audio.file.delete()
            if (ownedAudio === audio.file) ownedAudio = null
        }
        job.start()
    }

    /** Client cancellation never emits a terminal result/error callback. */
    fun cancel() {
        if (state == State.CANCELLED) return
        state = State.CANCELLED
        monitorJob?.cancel()
        requestJob?.cancel()
        runCatching { capture.abort() }
        ownedAudio?.delete()
        ownedAudio = null
    }

    private fun fail(error: Error) {
        if (state == State.CANCELLED || state == State.FINISHED) return
        state = State.FINISHED
        monitorJob?.cancel()
        requestJob?.cancel()
        runCatching { capture.abort() }
        ownedAudio?.delete()
        ownedAudio = null
        emit { onError(error) }
    }

    private fun emit(block: Callbacks.() -> Unit): Boolean = try {
        callbacks.block()
        true
    } catch (_: Exception) {
        // A dead or cancelled client cannot receive further callbacks or retain the microphone.
        cancel()
        false
    }

    companion object {
        internal fun classifyError(error: Throwable): Error {
            if (error is SecurityException) return Error.INSUFFICIENT_PERMISSIONS
            if (error is SocketTimeoutException || error is java.io.InterruptedIOException) {
                return Error.NETWORK_TIMEOUT
            }
            val message = error.message.orEmpty().lowercase()
            if (message.contains("api error 429")) return Error.TOO_MANY_REQUESTS
            if (message.contains("language") &&
                (message.contains("unsupported") || message.contains("not supported") || message.contains("invalid"))
            ) return Error.LANGUAGE_NOT_SUPPORTED
            if (message.contains("api error")) return Error.SERVER
            return if (error is IOException || message.contains("network") || message.contains("connection")) {
                Error.NETWORK
            } else Error.SERVER
        }
    }
}
