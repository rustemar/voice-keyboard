package com.tyraen.voicekeyboard.feature.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.*

class InputOrchestrator(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val speechClient: SpeechToTextClient,
    private val capture: MicrophoneCaptureSession,
    private val onTextReady: (String) -> Unit,
    private val onPhaseChanged: (InputPhase) -> Unit,
    private val onAmplitude: (Int) -> Unit
) {

    companion object {
        private const val TAG = "Orchestrator"
    }

    private var activeJob: Job? = null
    private var preferences: UserPreferences? = null

    var currentPhase: InputPhase = InputPhase.Ready
        private set

    fun loadPreferences() {
        CoroutineScope(Dispatchers.Main).launch {
            preferences = preferenceStore.load()
            DiagnosticLog.record(TAG, "Preferences loaded, apiKey=${if (preferences?.apiKey.isNullOrBlank()) "EMPTY" else "SET"}")
        }
    }

    fun reloadAndAutoStart() {
        CoroutineScope(Dispatchers.Main).launch {
            preferences = preferenceStore.load()
            if (preferences?.autoRecord == true && currentPhase is InputPhase.Ready) {
                beginCapture()
            }
        }
    }

    fun handleAction(action: InputAction) {
        when (currentPhase) {
            is InputPhase.Ready -> when (action) {
                InputAction.ToggleCapture -> beginCapture()
                else -> {}
            }
            is InputPhase.Capturing -> when (action) {
                InputAction.ToggleCapture -> finishCaptureAndProcess()
                InputAction.CancelOperation -> cancelAll()
                else -> {}
            }
            is InputPhase.Processing -> when (action) {
                InputAction.CancelOperation -> cancelAll()
                else -> {}
            }
            is InputPhase.Failed -> {
                moveTo(InputPhase.Ready)
                if (action is InputAction.ToggleCapture) beginCapture()
            }
        }
    }

    fun beginCapture() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            moveTo(InputPhase.Failed("Microphone permission required"))
            return
        }

        DiagnosticLog.record(TAG, "beginCapture")
        moveTo(InputPhase.Capturing())
        capture.begin { amplitude -> onAmplitude(amplitude) }
    }

    fun finishCaptureAndProcess() {
        val file = capture.finalize() ?: return
        DiagnosticLog.record(TAG, "finishCapture, file=${file.name}, size=${file.length()}")
        moveTo(InputPhase.Processing)

        val prefs = preferences ?: run {
            moveTo(InputPhase.Failed("Settings not loaded"))
            return
        }

        if (prefs.apiKey.isBlank()) {
            moveTo(InputPhase.Failed("API key not set"))
            return
        }

        val config = TranscriptionConfig(
            apiKey = prefs.apiKey,
            endpoint = prefs.endpoint,
            model = prefs.model,
            language = prefs.language,
            prompt = prefs.prompt
        )

        activeJob = CoroutineScope(Dispatchers.Main).launch {
            val result = speechClient.transcribe(file, config)
            result.onSuccess { text ->
                DiagnosticLog.record(TAG, "Transcription success: ${text.take(50)}")
                if (text.isNotBlank()) {
                    val output = if (prefs.addTrailingSpace) "$text " else text
                    onTextReady(output)
                }
                moveTo(InputPhase.Ready)
            }.onFailure { error ->
                DiagnosticLog.recordFailure(TAG, "Transcription failed", error)
                moveTo(InputPhase.Failed(error.message ?: "Unknown error"))
                moveTo(InputPhase.Ready)
            }
        }
    }

    fun cancelAll() {
        activeJob?.cancel()
        activeJob = null
        if (capture.isActive) {
            capture.abort()
        }
        moveTo(InputPhase.Ready)
    }

    private fun moveTo(phase: InputPhase) {
        currentPhase = phase
        onPhaseChanged(phase)
    }
}
