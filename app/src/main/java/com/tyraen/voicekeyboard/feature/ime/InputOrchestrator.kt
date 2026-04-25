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
    private val onProcessingPhaseChanged: (ProcessingQueue.ProcessingPhase) -> Unit,
    private val onPreferencesLoaded: () -> Unit = {}
) {

    companion object {
        private const val TAG = "Orchestrator"
    }

    private val scope = MainScope()
    private var preferences: UserPreferences? = null
    private var ppPreferences: PostProcessingPreferences? = null

    private val processingQueue = ProcessingQueue(
        speechClient = speechClient,
        postProcessingClient = postProcessingClient,
        onTextReady = onTextReady,
        onQueueCountChanged = { count ->
            onQueueCountChanged(count)
        },
        onProcessingPhaseChanged = { phase ->
            onProcessingPhaseChanged(phase)
        },
        onError = { message ->
            DiagnosticLog.record(TAG, "Queue error: $message")
        }
    )

    var currentPhase: InputPhase = InputPhase.Ready
        private set

    enum class PpMode { FIX, SHORTEN, EMOJI, RHYME, TRANSLATE, TERMINAL }

    var toggles: PreferenceStore.ToggleStates = PreferenceStore.ToggleStates()
        private set

    /**
     * Apply a click on a post-processing toggle, enforcing exclusivity rules:
     * - FIX/SHORTEN/RHYME are mutually exclusive (only one can be on at a time).
     * - TERMINAL excludes everything else.
     * - EMOJI and TRANSLATE are independent toggles, but TERMINAL clears them too.
     */
    fun togglePpMode(mode: PpMode) {
        val s = toggles
        toggles = when (mode) {
            PpMode.FIX -> if (s.fixActive) s.copy(fixActive = false)
                else s.copy(fixActive = true, shortenActive = false, rhymeActive = false, terminalActive = false)
            PpMode.SHORTEN -> if (s.shortenActive) s.copy(shortenActive = false)
                else s.copy(shortenActive = true, fixActive = false, rhymeActive = false, terminalActive = false)
            PpMode.RHYME -> if (s.rhymeActive) s.copy(rhymeActive = false)
                else s.copy(rhymeActive = true, fixActive = false, shortenActive = false, terminalActive = false)
            PpMode.EMOJI -> s.copy(emojiActive = !s.emojiActive)
            PpMode.TRANSLATE -> s.copy(translateActive = !s.translateActive)
            PpMode.TERMINAL -> if (s.terminalActive) s.copy(terminalActive = false)
                else PreferenceStore.ToggleStates(terminalActive = true)
        }
        saveToggleStates()
    }

    fun loadPreferences() {
        scope.launch {
            loadPreferencesInternal()
            onPreferencesLoaded()
        }
    }

    fun reloadAndAutoStart() {
        scope.launch {
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
        toggles = preferenceStore.loadToggleStates()
        DiagnosticLog.record(TAG, "Preferences loaded, apiKey=${if (preferences?.apiKey.isNullOrBlank()) "EMPTY" else "SET"}, pp=${ppPreferences?.enabled}")
    }

    fun isPostProcessingEnabled(): Boolean = ppPreferences?.enabled == true

    fun getTranslateLang(): String = ppPreferences?.translateLang ?: "en"

    fun isTerminalVisible(): Boolean = ppPreferences?.terminalVisible == true

    private fun saveToggleStates() {
        val snapshot = toggles
        scope.launch(Dispatchers.IO) {
            preferenceStore.saveToggleStates(snapshot)
        }
    }

    fun handleAction(action: InputAction) {
        when (currentPhase) {
            is InputPhase.Ready -> when (action) {
                InputAction.ToggleCapture -> beginCapture()
                InputAction.CancelOperation -> {}
            }
            is InputPhase.Capturing -> when (action) {
                InputAction.ToggleCapture -> finishCaptureAndEnqueue()
                InputAction.CancelOperation -> cancelCapture()
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
        val s = toggles
        val item = ProcessingQueue.QueueItem(
            audioFile = file,
            transcriptionConfig = config,
            addTrailingSpace = prefs.addTrailingSpace,
            ppPreferences = if (ppEnabled) ppPreferences else null,
            ppFix = ppEnabled && s.fixActive,
            ppShorten = ppEnabled && s.shortenActive,
            ppEmoji = ppEnabled && s.emojiActive,
            ppRhyme = ppEnabled && s.rhymeActive,
            ppTranslate = ppEnabled && s.translateActive,
            ppTerminal = ppEnabled && s.terminalActive
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

    /**
     * Graceful shutdown: if recording is active, finalize and enqueue it.
     * The queue keeps processing in background (caller should redirect onTextReady to clipboard).
     */
    fun gracefulShutdown() {
        if (currentPhase is InputPhase.Capturing) {
            finishCaptureAndEnqueue()
        }
        // Don't cancel the queue — let it finish in background
    }

    fun destroy() {
        capture.release()
        processingQueue.destroy()
        scope.cancel()
    }

    private fun moveTo(phase: InputPhase) {
        currentPhase = phase
        onPhaseChanged(phase)
    }
}
