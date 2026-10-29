package com.tyraen.voicekeyboard.feature.recognition

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.AdvancedPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.audio.MicrophoneGate
import com.tyraen.voicekeyboard.feature.setup.MicPermission
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.*
import java.util.Locale

/** A short, caller-owned session; keyboard queues, modes and history never enter this path. */
class DictationRecognitionService : RecognitionService() {
    private val scope = MainScope()
    private lateinit var options: AdvancedPreferences
    private var active: Request? = null

    private class Request(val callback: Callback, val captureContext: Context) {
        var loading: Job? = null
        var capture: MicrophoneCaptureSession? = null
        var session: RecognitionSession? = null
    }

    private val optionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AdvancedPreferences.RECOGNITION_ENABLED && !options.recognitionEnabled) {
            active?.let { fail(it, SpeechRecognizer.ERROR_CLIENT) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        options = AdvancedPreferences(this)
        options.register(optionListener)
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (!MicPermission.isGranted(this)) {
            rejectUnsupportedInput(recognizerIntent) // Close any supplied descriptor as well.
            // Android 12+ checks the full caller/proxy permission chain after this method and
            // emits its own error. Sending another one here would duplicate the terminal event.
            if (Build.VERSION.SDK_INT < 31) error(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        // These input modes need different capture protocols. Reject them rather than silently
        // recording the microphone or implying offline/segmented recognition is available.
        if (rejectUnsupportedInput(recognizerIntent)) {
            error(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (!options.recognitionEnabled) {
            error(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (active != null || MicrophoneGate.shared.isBusy) {
            error(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        val language = try {
            // A few clients send Locale despite the documented String extra. Both represent a
            // language; arbitrary parcelables and malformed tags are rejected without a crash.
            val supplied = recognizerIntent.extras?.get(RecognizerIntent.EXTRA_LANGUAGE)
            val tag = when (supplied) {
                null -> null
                is String -> supplied
                is Locale -> supplied.toLanguageTag()
                else -> throw IllegalArgumentException("Invalid language")
            }
            RecognitionLanguage.resolve(tag, Locale.getDefault().toLanguageTag())
                ?: throw IllegalArgumentException("Invalid language")
        } catch (_: Exception) {
            error(listener, languageError())
            return
        }

        val attributed = try {
            if (Build.VERSION.SDK_INT >= 31) {
                createContext(ContextParams.Builder()
                    .setNextAttributionSource(listener.callingAttributionSource).build())
            } else this
        } catch (_: Exception) {
            error(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val request = Request(listener, attributed)
        active = request
        val load = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val prefs = withTimeout(10_000) { ServiceLocator.preferenceStore.load() }
                ensureActive()
                if (active !== request) return@launch
                if (!options.recognitionEnabled || prefs.apiKey.isBlank()) {
                    fail(request, SpeechRecognizer.ERROR_CLIENT)
                    return@launch
                }
                if (MicrophoneGate.shared.isBusy) {
                    fail(request, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                    return@launch
                }
                val capture = MicrophoneCaptureSession(request.captureContext, "recognition-service")
                request.capture = capture
                val session = RecognitionSession(
                    capture = object : RecognitionSession.Capture {
                        override fun start(onAmplitude: (Int) -> Unit): Boolean {
                            if (!MicPermission.isGranted(this@DictationRecognitionService)) throw SecurityException()
                            return capture.begin(onAmplitude)
                        }
                        override fun finish(): RecognitionSession.CapturedAudio? {
                            val file = capture.finalize() ?: return null
                            return RecognitionSession.CapturedAudio(file, capture.lastDurationMs)
                        }
                        override fun abort() = capture.abort()
                    },
                    speechClient = ServiceLocator.speechToTextClient,
                    scope = scope,
                    elapsedRealtime = SystemClock::elapsedRealtime,
                    callbacks = object : RecognitionSession.Callbacks {
                        override fun onReady() = notify(request) { readyForSpeech(Bundle()) }
                        override fun onBeginningOfSpeech() = notify(request) { beginningOfSpeech() }
                        override fun onRmsChanged(value: Float) = notify(request) { rmsChanged(value) }
                        override fun onEndOfSpeech() = notify(request) { endOfSpeech() }
                        override fun onResults(text: String) {
                            complete(request) {
                                results(Bundle().apply {
                                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                                })
                            }
                        }
                        override fun onError(error: RecognitionSession.Error) {
                            complete(request) { error(errorCode(error)) }
                        }
                    }
                )
                request.session = session
                session.start(TranscriptionConfig(prefs.apiKey, prefs.endpoint, prefs.model, language, ""))
            } catch (_: TimeoutCancellationException) {
                fail(request, SpeechRecognizer.ERROR_CLIENT)
            } catch (_: CancellationException) {
                // Cancellation by stop, client death, service destruction or option disable is
                // already handled by the corresponding owner. Never emit another terminal event.
            } catch (e: Exception) {
                DiagnosticLog.recordFailure("Recognition", "Could not start recognition", e)
                fail(request, SpeechRecognizer.ERROR_CLIENT)
            }
        }
        request.loading = load
        load.start()
    }

    override fun onStopListening(listener: Callback) {
        val request = active?.takeIf { it.callback === listener } ?: return
        // Stopping before settings have loaded must never start a microphone afterwards.
        val session = request.session
        if (session == null) fail(request, SpeechRecognizer.ERROR_NO_MATCH) else session.stop()
    }

    override fun onCancel(listener: Callback) {
        active?.takeIf { it.callback === listener }?.let(::cancel)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        active?.let(::cancel)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        active?.let(::cancel)
        options.unregister(optionListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun cancel(request: Request) {
        if (active !== request) return
        active = null
        request.loading?.cancel()
        request.session?.cancel()
        request.capture?.release()
    }

    private fun fail(request: Request, code: Int) {
        if (active !== request) return
        cancel(request)
        error(request.callback, code)
    }

    private fun notify(request: Request, block: Callback.() -> Unit) {
        if (active !== request) return
        try { request.callback.block() } catch (_: Exception) { cancel(request) }
    }

    private fun complete(request: Request, block: Callback.() -> Unit) {
        if (active !== request) return
        active = null
        request.capture?.release()
        try { request.callback.block() } catch (_: Exception) { /* Client has gone away. */ }
    }

    private fun error(callback: Callback, code: Int) {
        try { callback.error(code) } catch (_: Exception) { /* Client has gone away. */ }
    }

    private fun rejectUnsupportedInput(intent: Intent): Boolean = try {
        val audioKey = "android.speech.extra.AUDIO_SOURCE"
        val external = intent.hasExtra(audioKey)
        if (external) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<android.os.Parcelable>(audioKey) as? ParcelFileDescriptor)?.close()
        }
        external || intent.hasExtra("android.speech.extra.SEGMENTED_SESSION") ||
            intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
    } catch (_: Exception) { true }

    private fun languageError() = if (Build.VERSION.SDK_INT >= 31) {
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
    } else SpeechRecognizer.ERROR_CLIENT

    private fun errorCode(error: RecognitionSession.Error): Int = when (error) {
        RecognitionSession.Error.AUDIO -> SpeechRecognizer.ERROR_AUDIO
        RecognitionSession.Error.SPEECH_TIMEOUT -> SpeechRecognizer.ERROR_SPEECH_TIMEOUT
        RecognitionSession.Error.NO_MATCH -> SpeechRecognizer.ERROR_NO_MATCH
        RecognitionSession.Error.NETWORK -> SpeechRecognizer.ERROR_NETWORK
        RecognitionSession.Error.NETWORK_TIMEOUT -> SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        RecognitionSession.Error.SERVER -> SpeechRecognizer.ERROR_SERVER
        RecognitionSession.Error.CLIENT -> SpeechRecognizer.ERROR_CLIENT
        RecognitionSession.Error.INSUFFICIENT_PERMISSIONS -> SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
        RecognitionSession.Error.RECOGNIZER_BUSY -> SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        RecognitionSession.Error.LANGUAGE_NOT_SUPPORTED -> languageError()
        RecognitionSession.Error.TOO_MANY_REQUESTS -> if (Build.VERSION.SDK_INT >= 31) {
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
        } else SpeechRecognizer.ERROR_SERVER
    }
}
