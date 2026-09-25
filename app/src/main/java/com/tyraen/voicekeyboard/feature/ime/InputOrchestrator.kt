package com.tyraen.voicekeyboard.feature.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.transcription.WhisperPromptBuilder
import kotlinx.coroutines.*
import java.io.File

class InputOrchestrator(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val processingQueue: ProcessingQueue,
    private val capture: MicrophoneCaptureSession,
    private val onTextReady: (text: String, addTrailingSpace: Boolean) -> Unit,
    private val onPhaseChanged: (InputPhase) -> Unit,
    private val onAmplitude: (Int) -> Unit,
    private val onQueueCountChanged: (Int) -> Unit,
    private val onProcessingPhaseChanged: (ProcessingQueue.ProcessingPhase) -> Unit,
    private val onFailedCountChanged: (Int) -> Unit = {},
    private val onPreferencesLoaded: () -> Unit = {},
    /** The user tapped the mic without RECORD_AUDIO; the host should start the permission flow. */
    private val onPermissionNeeded: () -> Unit = {}
) {

    companion object {
        private const val TAG = "Orchestrator"

        /**
         * A recording that ends before the preferences finished loading waits for them here, on a
         * process-wide scope: the view's own scope dies with the view, and that would orphan the
         * file in the cache.
         */
        private val handoverScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    }

    private val scope = MainScope()
    private var preferences: UserPreferences? = null
    private var ppPreferences: PostProcessingPreferences? = null
    private var vocabulary: String = ""

    /** Whether the input view is currently shown. Set by the host from its window callbacks. */
    var viewVisible: Boolean = false

    // Forwards the shared queue's callbacks to this view's UI. Bound on creation, unbound on
    // destroy so a torn-down panel stops receiving updates (the next panel rebinds instantly).
    private val queueListener = object : ProcessingQueue.Listener {
        override fun onTextReady(text: String, addTrailingSpace: Boolean) =
            this@InputOrchestrator.onTextReady(text, addTrailingSpace)
        override fun onQueueCountChanged(count: Int) = this@InputOrchestrator.onQueueCountChanged(count)
        override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) =
            this@InputOrchestrator.onProcessingPhaseChanged(phase)
        override fun onFailedCountChanged(count: Int) = this@InputOrchestrator.onFailedCountChanged(count)
        override fun onError(message: String) {
            DiagnosticLog.record(TAG, "Queue error: $message")
        }
    }

    init {
        processingQueue.bindListener(queueListener)
    }

    /** Re-send every recording that failed transcription and is waiting for retry. */
    fun retryFailed() {
        processingQueue.retryFailed()
    }

    /** Delete every recording that failed permanently; [onDone] receives how many were removed. */
    fun discardFailed(onDone: (Int) -> Unit) {
        processingQueue.discardFailed(onDone)
    }

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
            // Coming back from the permission prompt: the error that sent the user there is stale.
            val failed = currentPhase as? InputPhase.Failed
            if (failed?.reasonRes == R.string.error_mic_permission && hasMicPermission()) {
                moveTo(InputPhase.Ready)
            }
            // The load suspends. If the panel was hidden in the meantime, starting now would
            // record with no window on screen until the next show/hide. Without permission the
            // idle panel is also better than an error on every appearance; the mic tap asks.
            if (preferences?.autoRecord == true && viewVisible &&
                currentPhase is InputPhase.Ready && hasMicPermission()
            ) {
                beginCapture(userInitiated = false)
            }
        }
    }

    private suspend fun loadPreferencesInternal() {
        preferences = preferenceStore.load()
        ppPreferences = preferenceStore.loadPostProcessing()
        toggles = preferenceStore.loadToggleStates()
        vocabulary = preferenceStore.loadVocabulary()
        DiagnosticLog.record(TAG, "Preferences loaded, apiKey=${if (preferences?.apiKey.isNullOrBlank()) "EMPTY" else "SET"}, pp=${ppPreferences?.enabled}")
    }

    fun isPostProcessingEnabled(): Boolean = ppPreferences?.enabled == true

    fun isReturnToPreviousKeyboardEnabled(): Boolean = preferences?.returnToPreviousKeyboard == true

    /** Every dictation language the user configured, in the order they listed them. */
    fun getLanguageCodes(): List<String> = preferences?.languageCodes ?: emptyList()

    /** The dictation language the next recording will use. Blank means Whisper auto-detects. */
    fun getActiveLanguage(): String = preferences?.effectiveLanguage ?: ""

    /** Advance to the next configured dictation language, wrapping around. */
    fun cycleLanguage(): String = selectLanguage(
        TranscriptionLocale.nextCode(getLanguageCodes(), getActiveLanguage())
    )

    /** Switch to a specific dictation language and remember it across sessions. */
    fun selectLanguage(code: String): String {
        val prefs = preferences ?: return code
        if (code.isBlank() || code == prefs.effectiveLanguage) return prefs.effectiveLanguage

        preferences = prefs.copy(activeLanguage = code)
        DiagnosticLog.record(TAG, "Dictation language switched to $code")
        scope.launch(Dispatchers.IO) { preferenceStore.saveActiveLanguage(code) }
        return code
    }

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

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun beginCapture(userInitiated: Boolean = true) {
        if (!hasMicPermission()) {
            moveTo(InputPhase.Failed(R.string.error_mic_permission))
            if (userInitiated) onPermissionNeeded()
            return
        }

        DiagnosticLog.record(TAG, "beginCapture")
        if (!capture.begin { amplitude -> onAmplitude(amplitude) }) {
            moveTo(InputPhase.Failed(R.string.error_mic_unavailable))
            return
        }
        moveTo(InputPhase.Capturing())
    }

    fun finishCaptureAndEnqueue() {
        val file = capture.finalize()
        val durationMs = capture.lastDurationMs
        // Back to Ready right away: the user can start the next recording while this one uploads.
        moveTo(InputPhase.Ready)
        if (file == null) {
            DiagnosticLog.record(TAG, "finishCapture: nothing usable captured (${durationMs}ms)")
            return
        }
        DiagnosticLog.record(TAG, "finishCapture, file=${file.name}, size=${file.length()}, dur=${durationMs}ms")

        val prefs = preferences
        if (prefs == null) {
            // Preferences load asynchronously right after the view is created. A recording that
            // ends before they arrive waits for them instead of being thrown away.
            handoverScope.launch {
                loadPreferencesInternal()
                enqueue(file, durationMs, preferences ?: return@launch)
            }
            return
        }
        enqueue(file, durationMs, prefs)
    }

    private fun enqueue(file: File, durationMs: Long, prefs: UserPreferences) {
        if (prefs.apiKey.isBlank()) {
            // Still queued: the queue parks it as "needs attention", so the recording survives
            // until a key is entered and resend is tapped.
            moveTo(InputPhase.Failed(R.string.error_api_key_missing))
        }

        val language = prefs.effectiveLanguage
        val config = TranscriptionConfig(
            apiKey = prefs.apiKey,
            endpoint = prefs.endpoint,
            model = prefs.model,
            language = language,
            prompt = WhisperPromptBuilder.build(
                TranscriptionLocale.promptFor(language, prefs.prompt), vocabulary
            ),
            vocabulary = vocabulary,
            recordingDurationMs = durationMs
        )

        // Snapshot current post-processing state at enqueue time
        val ppEnabled = ppPreferences?.enabled == true
        val s = toggles
        val item = ProcessingQueue.QueueItem(
            audioFile = file,
            transcriptionConfig = config,
            addTrailingSpace = prefs.addTrailingSpace,
            singleWordStripPunctuation = prefs.singleWordStripPunctuation,
            vocabulary = vocabulary,
            ppPreferences = if (ppEnabled) ppPreferences else null,
            ppFix = ppEnabled && s.fixActive,
            ppShorten = ppEnabled && s.shortenActive,
            ppEmoji = ppEnabled && s.emojiActive,
            ppRhyme = ppEnabled && s.rhymeActive,
            ppTranslate = ppEnabled && s.translateActive,
            ppTerminal = ppEnabled && s.terminalActive
        )
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
        processingQueue.cancelPending()
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
        // Releases only a capture still in progress; files already handed to the queue are its own.
        capture.release()
        // The queue is a process-wide singleton — never destroy it here, just stop receiving its
        // callbacks. Parked recordings and in-flight work survive this input view.
        processingQueue.unbindListener(queueListener)
        scope.cancel()
    }

    private fun moveTo(phase: InputPhase) {
        currentPhase = phase
        onPhaseChanged(phase)
    }
}
