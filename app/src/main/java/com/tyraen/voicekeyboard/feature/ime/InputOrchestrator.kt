package com.tyraen.voicekeyboard.feature.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.TextDictionaryPreferences
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.logging.CaptureDiagnostics
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
    private val onTextReady: (text: String, addTrailingSpace: Boolean, editorSessionToken: Long) -> Boolean,
    private val onPhaseChanged: (InputPhase) -> Unit,
    private val onAmplitude: (Int) -> Unit,
    private val onQueueCountChanged: (Int) -> Unit,
    private val onProcessingPhaseChanged: (ProcessingQueue.ProcessingPhase) -> Unit,
    private val onFailedCountChanged: (Int) -> Unit = {},
    private val onPreferencesLoaded: () -> Unit = {},
    /** The user tapped the mic without RECORD_AUDIO; the host should start the permission flow. */
    private val onPermissionNeeded: () -> Unit = {},
    /** False in fields where dictation must not start, such as passwords; asked on every start. */
    private val voiceAllowed: () -> Boolean = { true },
    /** The user tapped the mic where [voiceAllowed] says no; the host explains why. */
    private val onVoiceBlocked: () -> Unit = {},
    /** The user tapped the mic or resend with no API key saved; the host opens the settings. */
    private val onApiKeyNeeded: () -> Unit = {},
    private val onCancellableWorkChanged: (Boolean) -> Unit = {},
    /** The live editor's RAM-only identity, captured when recording starts, not when it finishes. */
    private val editorSessionToken: () -> Long? = { null }
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

    val diagnosticId = CaptureDiagnostics.newId("orchestrator")
    private var diagnosticAttempt: String? = null
    private val scope = MainScope()
    private var preferences: UserPreferences? = null

    /**
     * Preference loads still running. Coming back from the settings, the panel reloads while it
     * appears; a mic tap in that moment must not trust the old, blank key and send the user
     * straight back to the settings.
     */
    private var reloadsInFlight = 0
    private var ppPreferences: PostProcessingPreferences? = null
    private var vocabulary: String = ""
    private var textDictionary = TextDictionaryPreferences()
    private var captureContextToken: Long? = null
    private var captureEditorSessionToken: Long? = null
    private var captureIdentity: ProcessingQueue.WorkIdentity? = null

    /** Whether the input view is currently shown. Set by the host from its window callbacks. */
    var viewVisible: Boolean = false

    // Forwards the shared queue's callbacks to this view's UI. Bound on creation, unbound on
    // destroy so a torn-down panel stops receiving updates (the next panel rebinds instantly).
    private val queueListener = object : ProcessingQueue.Listener {
        override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean =
            viewVisible && this@InputOrchestrator.onTextReady(text, addTrailingSpace, editorSessionToken)
        override fun onQueueCountChanged(count: Int) = this@InputOrchestrator.onQueueCountChanged(count)
        override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) =
            this@InputOrchestrator.onProcessingPhaseChanged(phase)
        override fun onFailedCountChanged(count: Int) = this@InputOrchestrator.onFailedCountChanged(count)
        override fun onCancellableWorkChanged(available: Boolean) =
            this@InputOrchestrator.onCancellableWorkChanged(available)
        override fun onError(message: String) {
            DiagnosticLog.record(TAG, "Queue error: $message")
        }
    }

    init {
        CaptureDiagnostics.event(TAG, "created", "orchestrator=$diagnosticId capture=${capture.diagnosticId}")
        processingQueue.bindListener(queueListener)
    }

    /**
     * Re-send every recording that failed transcription and is waiting for retry. Without a key
     * nothing can go out, and the queue would park everything again without a word, so the user
     * is sent to the settings instead.
     */
    fun retryFailed() {
        scope.launch {
            // Only the key is read: a full reload could overwrite a toggle change still being saved.
            if (preferenceStore.load().apiKey.isBlank()) {
                if (currentPhase !is InputPhase.Capturing) moveTo(InputPhase.Failed(R.string.error_api_key_missing))
                onApiKeyNeeded()
                return@launch
            }
            processingQueue.retryFailed()
        }
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
            // Coming back from the permission prompt or from the settings: the error that sent the
            // user there is stale once the permission is granted or a key is saved.
            val failed = currentPhase as? InputPhase.Failed
            if (failed?.reasonRes == R.string.error_mic_permission && hasMicPermission()) {
                moveTo(InputPhase.Ready)
            }
            if (failed?.reasonRes == R.string.error_api_key_missing && preferences?.apiKey?.isNotBlank() == true) {
                moveTo(InputPhase.Ready)
            }
            // The load suspends. If the panel was hidden in the meantime, starting now would
            // record with no window on screen until the next show/hide. Without permission the
            // idle panel is also better than an error on every appearance; the mic tap asks.
            if (preferences?.autoRecord == true && viewVisible &&
                currentPhase is InputPhase.Ready && hasMicPermission() && voiceAllowed()
            ) {
                beginCapture(userInitiated = false)
            }
        }
    }

    private suspend fun loadPreferencesInternal() {
        reloadsInFlight++
        try {
            preferences = preferenceStore.load()
            ppPreferences = preferenceStore.loadPostProcessing()
            toggles = preferenceStore.loadToggleStates()
            vocabulary = preferenceStore.loadVocabulary()
            textDictionary = preferenceStore.loadTextDictionary()
        } finally {
            reloadsInFlight--
        }
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
        trace("action", "action=${if (action is InputAction.CancelOperation) "cancel" else "toggle_capture"}")
        if (action is InputAction.CancelOperation) {
            if (processingQueue.cancelLatest() && currentPhase is InputPhase.Failed) {
                moveTo(InputPhase.Ready)
            }
            return
        }
        when (currentPhase) {
            is InputPhase.Ready -> when (action) {
                InputAction.ToggleCapture -> beginCaptureIfAllowed()
                InputAction.CancelOperation -> {}
            }
            is InputPhase.Capturing -> when (action) {
                InputAction.ToggleCapture -> finishCaptureAndEnqueue()
                InputAction.CancelOperation -> {}
            }
            is InputPhase.Failed -> {
                moveTo(InputPhase.Ready)
                if (action is InputAction.ToggleCapture) beginCaptureIfAllowed()
            }
        }
    }

    /** A mic tap. Stopping a recording is never blocked, only starting one where voice is off. */
    private fun beginCaptureIfAllowed() {
        if (!voiceAllowed()) {
            trace("begin_refused", "reason=voice_blocked")
            onVoiceBlocked()
            return
        }
        beginCapture()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun beginCapture(userInitiated: Boolean = true) {
        diagnosticAttempt = CaptureDiagnostics.newId("attempt")
        trace("begin_requested", "userInitiated=$userInitiated reloads=$reloadsInFlight")
        if (!hasMicPermission()) {
            trace("begin_refused", "reason=permission")
            moveTo(InputPhase.Failed(R.string.error_mic_permission))
            if (userInitiated) onPermissionNeeded()
            return
        }
        // Known to have no key: say so before the user talks, not after the whole recording.
        // Before the preferences have loaded, or while they reload, the recording goes ahead,
        // and enqueue() parks it if the key really is missing.
        if (reloadsInFlight == 0 && preferences?.apiKey?.isBlank() == true) {
            trace("begin_refused", "reason=missing_key")
            DiagnosticLog.record(TAG, "beginCapture: no API key")
            moveTo(InputPhase.Failed(R.string.error_api_key_missing))
            if (userInitiated) onApiKeyNeeded()
            return
        }

        DiagnosticLog.record(TAG, "beginCapture")
        if (!capture.begin { amplitude -> onAmplitude(amplitude) }) {
            trace("begin_refused", "reason=recorder_or_gate")
            moveTo(InputPhase.Failed(R.string.error_mic_unavailable))
            return
        }
        lateinit var identity: ProcessingQueue.WorkIdentity
        identity = processingQueue.reserveDictation {
            if (captureIdentity == identity) cancelCapture()
        }
        captureIdentity = identity
        captureContextToken = processingQueue.capturePostProcessingContextToken()
        captureEditorSessionToken = editorSessionToken()
        trace("capture_reserved", "work=${identity.workId}")
        moveTo(InputPhase.Capturing())
    }

    fun finishCaptureAndEnqueue(reason: String = "user_stop") {
        trace("finish_requested", "reason=$reason")
        val identity = captureIdentity ?: run {
            trace("finish_skipped", "reason=$reason cause=no_capture_identity")
            return
        }
        captureIdentity = null
        val contextToken = captureContextToken
        captureContextToken = null
        val editorToken = captureEditorSessionToken
        captureEditorSessionToken = null
        val file = capture.finalize()
        val durationMs = capture.lastDurationMs
        if (file == null) {
            trace("finish_unusable", "reason=$reason work=${identity.workId} durationMs=$durationMs")
            processingQueue.releaseReservation(identity)
            moveTo(InputPhase.Ready)
            DiagnosticLog.record(TAG, "finishCapture: nothing usable captured (${durationMs}ms)")
            return
        }
        // Keep the same queue identity while the process-wide handover waits for preferences.
        // A view rebuild or a second recording must not hide this item from cancellation.
        var handoverJob: Job? = null
        if (!processingQueue.updateReservation(identity) {
                trace("handover_cancelled", "work=${identity.workId} file=${file.name}")
                handoverJob?.cancel()
                file.delete()
            }) {
            trace("reservation_rejected", "reason=$reason work=${identity.workId} file=${file.name}")
            file.delete()
            moveTo(InputPhase.Ready)
            return
        }
        // Back to Ready right away: the user can start the next recording while this one uploads.
        moveTo(InputPhase.Ready)
        trace("capture_finalized", "reason=$reason work=${identity.workId} file=${file.name} durationMs=$durationMs")
        DiagnosticLog.record(TAG, "finishCapture, file=${file.name}, size=${file.length()}, dur=${durationMs}ms")

        val prefs = preferences
        if (prefs == null) {
            trace("handover_waiting_preferences", "work=${identity.workId} file=${file.name}")
            // Preferences load asynchronously right after the view is created. A recording that
            // ends before they arrive waits for them instead of being thrown away.
            handoverJob = handoverScope.launch {
                loadPreferencesInternal()
                ensureActive()
                trace("handover_preferences_ready", "work=${identity.workId}")
                enqueue(file, durationMs, preferences ?: return@launch, contextToken, editorToken, identity)
            }
            return
        }
        enqueue(file, durationMs, prefs, contextToken, editorToken, identity)
    }

    private fun enqueue(
        file: File, durationMs: Long, prefs: UserPreferences, contextToken: Long?, editorToken: Long?,
        identity: ProcessingQueue.WorkIdentity
    ) {
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
            ppTerminal = ppEnabled && s.terminalActive,
            contextToken = contextToken,
            editorSessionToken = editorToken,
            workId = identity.workId,
            createdAt = identity.createdAt,
            postProcessingVocabulary = textDictionary.postProcessingVocabulary,
            replacementRules = textDictionary.replacementRules
        )
        trace("enqueue", "work=${identity.workId} file=${file.name} originEditor=${editorToken ?: "none"}")
        processingQueue.enqueue(item)
    }

    /** Cancel only the current recording; the queue keeps processing. */
    private fun cancelCapture() {
        trace("capture_cancelled", "reason=cancel")
        captureIdentity = null
        captureContextToken = null
        captureEditorSessionToken = null
        if (capture.isActive) {
            capture.abort("cancel")
        }
        moveTo(InputPhase.Ready)
    }

    /** Cancel everything: current recording + the entire queue. */
    fun cancelAll() {
        trace("cancel_all", "reason=cancel")
        captureIdentity?.let { processingQueue.releaseReservation(it) }
        captureIdentity = null
        captureContextToken = null
        captureEditorSessionToken = null
        if (capture.isActive) {
            capture.abort("cancel_all")
        }
        processingQueue.cancelPending()
        moveTo(InputPhase.Ready)
    }

    /**
     * Graceful shutdown: if recording is active, finalize and enqueue it.
     * The queue keeps processing in background and saves results if the original editor is gone.
     */
    fun gracefulShutdown(reason: String = "unspecified") {
        trace("graceful_shutdown", "reason=$reason")
        if (currentPhase is InputPhase.Capturing) {
            finishCaptureAndEnqueue(reason)
        }
        // Don't cancel the queue — let it finish in background
    }

    fun destroy(reason: String = "unspecified") {
        trace("destroy", "reason=$reason")
        // Releases only a capture still in progress; files already handed to the queue are its own.
        captureIdentity?.let { processingQueue.releaseReservation(it) }
        captureIdentity = null
        captureContextToken = null
        captureEditorSessionToken = null
        capture.release(reason)
        // The queue is a process-wide singleton — never destroy it here, just stop receiving its
        // callbacks. Parked recordings and in-flight work survive this input view.
        processingQueue.unbindListener(queueListener)
        scope.cancel()
    }

    private fun moveTo(phase: InputPhase) {
        trace("phase_changed", "next=${phaseName(phase)} reasonRes=${(phase as? InputPhase.Failed)?.reasonRes ?: 0}")
        currentPhase = phase
        onPhaseChanged(phase)
    }

    private fun trace(event: String, details: String = "") {
        CaptureDiagnostics.event(TAG, event,
            "orchestrator=$diagnosticId capture=${capture.diagnosticId} attempt=${diagnosticAttempt ?: "none"} " +
                "phase=${phaseName(currentPhase)} active=${capture.isActive} " +
                "currentWork=${captureIdentity?.workId ?: "none"} capturedEditor=${captureEditorSessionToken ?: "none"} " +
                "visible=$viewVisible $details")
    }

    private fun phaseName(phase: InputPhase?): String = when (phase) {
        is InputPhase.Ready -> "ready"
        is InputPhase.Capturing -> "capturing"
        is InputPhase.Failed -> "failed"
        null -> "uninitialized"
    }
}
