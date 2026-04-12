package com.tyraen.voicekeyboard.feature.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.*

class InputOrchestrator(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val speechClient: SpeechToTextClient,
    private val postProcessingClient: PostProcessingClient,
    private val capture: MicrophoneCaptureSession,
    private val onTextReady: (String) -> Unit,
    private val onPhaseChanged: (InputPhase) -> Unit,
    private val onAmplitude: (Int) -> Unit,
    private val onQueueCountChanged: (Int) -> Unit,
    private val onPreferencesLoaded: () -> Unit = {}
) {

    companion object {
        private const val TAG = "Orchestrator"
    }

    private var preferences: UserPreferences? = null
    private var ppPreferences: PostProcessingPreferences? = null

    private val processingQueue = ProcessingQueue(
        speechClient = speechClient,
        postProcessingClient = postProcessingClient,
        onTextReady = onTextReady,
        onQueueCountChanged = { count ->
            onQueueCountChanged(count)
        },
        onError = { message ->
            DiagnosticLog.record(TAG, "Queue error: $message")
        }
    )

    var currentPhase: InputPhase = InputPhase.Ready
        private set

    // Post-processing toggle states
    var ppFixActive = false
    var ppShortenActive = false
    var ppEmojiActive = false
    var ppRhymeActive = false
    var ppTranslateActive = false
    var ppTerminalActive = false

    fun loadPreferences() {
        CoroutineScope(Dispatchers.Main).launch {
            loadPreferencesInternal()
            onPreferencesLoaded()
        }
    }

    fun reloadAndAutoStart() {
        CoroutineScope(Dispatchers.Main).launch {
            loadPreferencesInternal()
            onPreferencesLoaded()
            if (preferences?.autoRecord == true && currentPhase is InputPhase.Ready) {
                beginCapture()
            }
        }
    }

    private suspend fun loadPreferencesInternal() {
        preferences = preferenceStore.load()
        ppPreferences = preferenceStore.loadPostProcessing()
        val toggles = preferenceStore.loadToggleStates()
        ppFixActive = toggles.fixActive
        ppShortenActive = toggles.shortenActive
        ppEmojiActive = toggles.emojiActive
        ppRhymeActive = toggles.rhymeActive
        ppTranslateActive = toggles.translateActive
        ppTerminalActive = toggles.terminalActive
        DiagnosticLog.record(TAG, "Preferences loaded, apiKey=${if (preferences?.apiKey.isNullOrBlank()) "EMPTY" else "SET"}, pp=${ppPreferences?.enabled}")
    }

    fun isPostProcessingEnabled(): Boolean = ppPreferences?.enabled == true

    fun getTranslateLang(): String = ppPreferences?.translateLang ?: "en"

    fun isTerminalVisible(): Boolean = ppPreferences?.terminalVisible == true

    fun saveToggleStates() {
        CoroutineScope(Dispatchers.IO).launch {
            preferenceStore.saveToggleStates(
                PreferenceStore.ToggleStates(ppFixActive, ppShortenActive, ppEmojiActive, ppRhymeActive, ppTranslateActive, ppTerminalActive)
            )
        }
    }

    fun handleAction(action: InputAction) {
        when (currentPhase) {
            is InputPhase.Ready -> when (action) {
                InputAction.ToggleCapture -> beginCapture()
                else -> {}
            }
            is InputPhase.Capturing -> when (action) {
                InputAction.ToggleCapture -> finishCaptureAndEnqueue()
                InputAction.CancelOperation -> cancelCapture()
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

    fun finishCaptureAndEnqueue() {
        val file = capture.finalize() ?: return
        DiagnosticLog.record(TAG, "finishCapture, file=${file.name}, size=${file.length()}")

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

        // Snapshot current post-processing state at enqueue time
        val ppEnabled = ppPreferences?.enabled == true
        val item = ProcessingQueue.QueueItem(
            audioFile = file,
            transcriptionConfig = config,
            addTrailingSpace = prefs.addTrailingSpace,
            ppPreferences = if (ppEnabled) ppPreferences else null,
            ppFix = ppEnabled && ppFixActive,
            ppShorten = ppEnabled && ppShortenActive,
            ppEmoji = ppEnabled && ppEmojiActive,
            ppRhyme = ppEnabled && ppRhymeActive,
            ppTranslate = ppEnabled && ppTranslateActive,
            ppTerminal = ppEnabled && ppTerminalActive
        )

        // Return to Ready immediately — user can start recording again
        moveTo(InputPhase.Ready)
        processingQueue.enqueue(item)
    }

    /** Cancel only the current recording; the queue keeps processing. */
    private fun cancelCapture() {
        if (capture.isActive) {
            capture.abort()
        }
        moveTo(InputPhase.Ready)
    }

    /** Cancel everything: current recording + the entire queue. */
    fun cancelAll() {
        if (capture.isActive) {
            capture.abort()
        }
        processingQueue.cancelAll()
        moveTo(InputPhase.Ready)
    }

    fun destroy() {
        if (capture.isActive) {
            capture.abort()
        }
        processingQueue.destroy()
    }

    private fun moveTo(phase: InputPhase) {
        currentPhase = phase
        onPhaseChanged(phase)
    }
}
