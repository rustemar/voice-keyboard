package com.tyraen.voicekeyboard.feature.recognition

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.app.ServiceLocator
import com.tyraen.voicekeyboard.core.config.AdvancedPreferences
import com.tyraen.voicekeyboard.core.locale.InterfaceLanguageManager
import com.tyraen.voicekeyboard.feature.setup.AdvancedSettingsActivity
import com.tyraen.voicekeyboard.feature.setup.MicPermission
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/** Standard visible activity result for apps that launch ACTION_RECOGNIZE_SPEECH. */
class RecognitionActivity : AppCompatActivity() {
    private val scope = MainScope()
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var requestActive = false
    private var firstResume = true
    private var generation = 0L
    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var progress: ProgressBar

    override fun attachBaseContext(newBase: Context) =
        super.attachBaseContext(InterfaceLanguageManager.applyTo(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        // Supplied-PCM sessions are unsupported. Close this activity's parcelled descriptor;
        // the caller owns its separate copy and no microphone is substituted for this input.
        runCatching {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<android.os.Parcelable>("android.speech.extra.AUDIO_SOURCE")
                as? ParcelFileDescriptor)?.close()
        }
        setContentView(R.layout.activity_recognition)
        status = findViewById(R.id.recognitionStatus)
        button = findViewById(R.id.btnRecognitionStartStop)
        progress = findViewById(R.id.recognitionProgress)
        button.setOnClickListener {
            if (listening) {
                listening = false
                button.isEnabled = false
                status.setText(R.string.status_transcribing)
                progress.visibility = View.VISIBLE
                recognizer?.stopListening()
            } else if (!requestActive) startRecognition()
        }
        findViewById<Button>(R.id.btnRecognitionCancel).setOnClickListener { dispose(); finish() }
        findViewById<Button>(R.id.btnRecognitionAdvanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) {
            firstResume = false
            startRecognition()
        }
    }

    private fun startRecognition() {
        if (isFinishing || isDestroyed || requestActive) return
        if (!AdvancedPreferences(this).recognitionEnabled) {
            showError(R.string.recognition_disabled)
            return
        }
        if (!MicPermission.isGranted(this)) {
            showError(R.string.error_mic_permission)
            scope.launch { MicPermission.requestOrOpenSettings(this@RecognitionActivity, ServiceLocator.preferenceStore) }
            return
        }
        val request = try {
            val supplied = intent.extras?.get(RecognizerIntent.EXTRA_LANGUAGE)
            val language = when (supplied) {
                null -> null
                is String -> supplied
                is Locale -> supplied.toLanguageTag()
                else -> throw IllegalArgumentException("Invalid language")
            }
            require(!intent.hasExtra("android.speech.extra.AUDIO_SOURCE"))
            require(!intent.hasExtra("android.speech.extra.SEGMENTED_SESSION"))
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                language?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            }
        } catch (_: Exception) {
            showError(R.string.recognition_failed)
            return
        }
        dispose()
        requestActive = true
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        status.setText(R.string.recognition_start)
        try {
            val speech = SpeechRecognizer.createSpeechRecognizer(
                this, ComponentName(this, DictationRecognitionService::class.java))
            recognizer = speech
            val attempt = generation
            fun current() = requestActive && generation == attempt && recognizer === speech && !isFinishing && !isDestroyed
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (!current()) return
                    listening = true
                    button.isEnabled = true
                    button.setText(R.string.test_stop)
                    status.setText(R.string.status_recording)
                    progress.visibility = View.GONE
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    if (!current()) return
                    listening = false
                    button.isEnabled = false
                    status.setText(R.string.status_transcribing)
                    progress.visibility = View.VISIBLE
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    showError(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.error_mic_permission
                        SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.error_mic_unavailable
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.test_status_no_speech
                        else -> R.string.recognition_failed
                    })
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (texts.isNullOrEmpty()) { showError(R.string.test_status_no_speech); return }
                    dispose()
                    setResult(RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, texts))
                    finish()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speech.startListening(request)
        } catch (_: Exception) { showError(R.string.recognition_failed) }
    }

    private fun showError(message: Int) {
        dispose()
        status.setText(message)
        progress.visibility = View.GONE
        button.isEnabled = true
        button.setText(R.string.recognition_start)
    }

    private fun dispose() {
        generation++
        val old = recognizer
        recognizer = null
        requestActive = false
        listening = false
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MicPermission.REQUEST_CODE) {
            scope.launch { MicPermission.recordResult(this@RecognitionActivity, ServiceLocator.preferenceStore, grantResults) }
            if (MicPermission.isGranted(this)) startRecognition()
        }
    }

    override fun onStop() {
        if (requestActive) showError(R.string.recognition_start)
        super.onStop()
    }

    override fun onDestroy() {
        dispose()
        scope.cancel()
        super.onDestroy()
    }
}
