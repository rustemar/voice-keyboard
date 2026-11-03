package com.tyraen.voicekeyboard.feature.ime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.tyraen.voicekeyboard.R
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.config.SilenceAutoStopSettings
import com.tyraen.voicekeyboard.core.config.TextDictionaryPreferences
import com.tyraen.voicekeyboard.core.config.UserPreferences
import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.audio.UploadAudioEncoder
import com.tyraen.voicekeyboard.feature.audio.PreparedUploadAudio
import com.tyraen.voicekeyboard.feature.audio.MicrophoneCaptureSession
import com.tyraen.voicekeyboard.feature.audio.SileroVoiceDetector
import com.tyraen.voicekeyboard.feature.audio.SpeechProbabilityEndpointer
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.transcription.WhisperPromptBuilder
import kotlinx.coroutines.*
import java.io.File

class InputOrchestrator(
    private val context: Context,
    private val preferenceStore: PreferenceStore,
    private val processingQueue: ProcessingQueue,
    private val capture: MicrophoneCaptureSession,
    private val onTextReady: (text: String, addTrailingSpace: Boolean, editorSessionToken: Long, autoSendRequest: AutoSendPolicy.Request?) -> Boolean,
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
    private val editorSessionToken: () -> Long? = { null },
    private val createAutoSendRequest: (enabled: Boolean) -> AutoSendPolicy.Request? = { null },
    private val invalidateAutoSend: () -> Unit = {}
) {

    companion object {
        private const val TAG = "Orchestrator"

        /**
         * A recording that ends before the preferences finished loading waits for them here, on a
         * process-wide scope: the view's own scope dies with the view, and that would orphan the
         * file in the cache.
         */
        private val handoverScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private val captureHandovers = CaptureHandoverSequencer(handoverScope)

        // A keyboard toggle must survive closing/rebuilding its view. Loads wait for the last
        // narrow write, including one initiated by the previous view, before binding the switch.
        private var silenceToggleSave: Job? = null
    }

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
    private var captureAutoSendRequest: AutoSendPolicy.Request? = null
    private var speechEndpointer: SpeechProbabilityEndpointer? = null
    private var silenceAutoStopEnabled = false
    private var silenceToggleRevision = 0L

    /** Whether the input view is currently shown. Set by the host from its window callbacks. */
    var viewVisible: Boolean = false

    // Forwards the shared queue's callbacks to this view's UI. Bound on creation, unbound on
    // destroy so a torn-down panel stops receiving updates (the next panel rebinds instantly).
    private val queueListener = object : ProcessingQueue.Listener {
        override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean =
            onTextReady(text, addTrailingSpace, editorSessionToken, null)
        override fun onTextReady(
            text: String, addTrailingSpace: Boolean, editorSessionToken: Long,
            autoSendRequest: AutoSendPolicy.Request?
        ): Boolean = viewVisible && this@InputOrchestrator.onTextReady(
            text, addTrailingSpace, editorSessionToken, autoSendRequest
        )
        override fun onQueueCountChanged(count: Int) = this@InputOrchestrator.onQueueCountChanged(count)
        override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) =
            this@InputOrchestrator.onProcessingPhaseChanged(phase)
        override fun onFailedCountChanged(count: Int) = this@InputOrchestrator.onFailedCountChanged(count)
        override fun onCancellableWorkChanged(available: Boolean) =
            this@InputOrchestrator.onCancellableWorkChanged(available)
        override fun onAutoSendInvalidated() {
            captureAutoSendRequest?.cancel()
            invalidateAutoSend()
        }
        override fun onError(message: String) {
            // Provider errors may echo the dictated text or credentials.
            DiagnosticLog.record(TAG, "Queue error reported")
        }
    }

    init {
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
            val silenceRevision = silenceToggleRevision
            silenceToggleSave?.join()
            val loaded = preferenceStore.load()
            // A tap while the asynchronous load is suspended takes precedence over its snapshot.
            preferences = if (silenceRevision == silenceToggleRevision) loaded
                else loaded.copy(silenceAutoStopEnabled = silenceAutoStopEnabled)
            silenceAutoStopEnabled = preferences!!.silenceAutoStopEnabled
            if (!silenceAutoStopEnabled || preferences?.autoSendAfterSilenceEnabled != true) {
                captureAutoSendRequest?.cancel()
                invalidateAutoSend()
            }
            val now = SystemClock.elapsedRealtime()
            speechEndpointer?.setPauseMs(preferences!!.silenceAutoStopPauseMs.toLong(), now)
            speechEndpointer?.setEnabled(silenceAutoStopEnabled, now)
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

    fun isSilenceAutoStopEnabled(): Boolean = silenceAutoStopEnabled

    fun canAutoSendNow(): Boolean = reloadsInFlight == 0 && silenceAutoStopEnabled &&
        preferences?.autoSendAfterSilenceEnabled == true && currentPhase is InputPhase.Ready &&
        !toggles.terminalActive

    /** Changes only automatic endpointing; toggling never pauses, discards or restarts audio. */
    fun toggleSilenceAutoStop(): Boolean {
        val enabled = !silenceAutoStopEnabled
        silenceAutoStopEnabled = enabled
        silenceToggleRevision++
        if (!enabled) {
            captureAutoSendRequest?.cancel()
            invalidateAutoSend()
        }
        preferences = preferences?.copy(silenceAutoStopEnabled = enabled)
        speechEndpointer?.setEnabled(enabled, SystemClock.elapsedRealtime())
        val previousSave = silenceToggleSave
        silenceToggleSave = handoverScope.launch {
            previousSave?.join()
            try {
                preferenceStore.setSilenceAutoStopEnabled(enabled)
            } catch (error: java.io.IOException) {
                DiagnosticLog.record(TAG, "Could not save silence auto-stop: ${error.javaClass.simpleName}")
            }
        }
        return enabled
    }

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
            onVoiceBlocked()
            return
        }
        beginCapture()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun beginCapture(userInitiated: Boolean = true) {
        if (capture.isActive || currentPhase is InputPhase.Capturing) return
        if (!hasMicPermission()) {
            moveTo(InputPhase.Failed(R.string.error_mic_permission))
            if (userInitiated) onPermissionNeeded()
            return
        }
        // Known to have no key: say so before the user talks, not after the whole recording.
        // Before the preferences have loaded, or while they reload, the recording goes ahead,
        // and enqueue() parks it if the key really is missing.
        if (reloadsInFlight == 0 && preferences?.apiKey?.isBlank() == true) {
            DiagnosticLog.record(TAG, "beginCapture: no API key")
            moveTo(InputPhase.Failed(R.string.error_api_key_missing))
            if (userInitiated) onApiKeyNeeded()
            return
        }

        DiagnosticLog.record(TAG, "beginCapture")
        val pauseMs = (preferences?.silenceAutoStopPauseMs ?: SilenceAutoStopSettings.DEFAULT_PAUSE_MS).toLong()
        val now = SystemClock.elapsedRealtime()
        val began = run {
            val endpointer = SpeechProbabilityEndpointer(pauseMs, now, silenceAutoStopEnabled)
            speechEndpointer = endpointer
            // Each recording owns its recurrent model state. Creation and inference stay on the
            // analysis worker; the recorder writes independently even while the model warms up.
            var detector: SileroVoiceDetector? = null
            var nextDiagnosticAt = 0L
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            capture.beginPcm(
                onAmplitude = onAmplitude,
                onPcm = { samples, count, observedAt ->
                    val current = detector ?: SileroVoiceDetector.create(context).also { detector = it }
                    current.accept(samples, count, observedAt) { probability, frameAt ->
                        main.post {
                            if (speechEndpointer === endpointer) {
                                endpointer.onProbability(probability, frameAt)
                                if (frameAt >= nextDiagnosticAt) {
                                    DiagnosticLog.record("SpeechDetector", "probability=${(probability * 1000).toInt()} " +
                                        "enabled=$silenceAutoStopEnabled frameMs=$frameAt")
                                    nextDiagnosticAt = frameAt + 1_000
                                }
                                if (captureIdentity != null && currentPhase is InputPhase.Capturing &&
                                    endpointer.shouldStop(SystemClock.elapsedRealtime())
                                ) {
                                    DiagnosticLog.record(TAG, "Auto-stop after speech detector pause")
                                    finishCaptureAndEnqueue(FinishReason.SILENCE)
                                }
                            }
                        }
                    }
                },
                onClosed = { detector?.close(); detector = null },
                onUnexpectedStop = {
                    if (speechEndpointer === endpointer && captureIdentity != null &&
                        currentPhase is InputPhase.Capturing
                    ) finishCaptureAndEnqueue()
                }
            )
        }
        if (!began) {
            speechEndpointer = null
            moveTo(InputPhase.Failed(R.string.error_mic_unavailable))
            return
        }
        captureAutoSendRequest = createAutoSendRequest(
            reloadsInFlight == 0 && preferences?.autoSendAfterSilenceEnabled == true &&
                silenceAutoStopEnabled && !toggles.terminalActive
        )
        lateinit var identity: ProcessingQueue.WorkIdentity
        identity = processingQueue.reserveDictation {
            if (captureIdentity == identity) cancelCapture()
        }
        captureIdentity = identity
        captureContextToken = processingQueue.capturePostProcessingContextToken()
        captureEditorSessionToken = editorSessionToken()
        moveTo(InputPhase.Capturing())
    }

    private data class EnqueueSnapshot(
        val prefs: UserPreferences,
        val postProcessing: PostProcessingPreferences?,
        val toggles: PreferenceStore.ToggleStates,
        val vocabulary: String,
        val dictionary: TextDictionaryPreferences
    )

    private fun enqueueSnapshot(prefs: UserPreferences) = EnqueueSnapshot(
        prefs, ppPreferences, toggles, vocabulary, textDictionary
    )

    enum class FinishReason { MANUAL, SILENCE }

    fun finishCaptureAndEnqueue(reason: FinishReason = FinishReason.MANUAL) {
        speechEndpointer = null
        val identity = captureIdentity ?: return
        captureIdentity = null
        val contextToken = captureContextToken
        captureContextToken = null
        val editorToken = captureEditorSessionToken
        captureEditorSessionToken = null
        val autoSendRequest = captureAutoSendRequest?.takeIf {
            reason == FinishReason.SILENCE && silenceAutoStopEnabled && reloadsInFlight == 0 &&
                preferences?.autoSendAfterSilenceEnabled == true && !toggles.terminalActive
        }
        if (autoSendRequest == null) captureAutoSendRequest?.cancel()
        captureAutoSendRequest = null
        val pending = capture.finalizeAsync()
        val snapshot = preferences?.takeIf { reloadsInFlight == 0 }?.let { enqueueSnapshot(it) }
        var handoverJob: Job? = null
        if (!processingQueue.updateReservation(identity) {
                pending.cancel()
                handoverJob?.cancel()
            }) {
            pending.cancel()
            moveTo(InputPhase.Ready)
            return
        }
        // Stopping relinquishes the microphone immediately. Closing the WAV header and analysis
        // happens off Main; neither an IME rebuild nor the next capture owns this handed-off file.
        moveTo(InputPhase.Ready)
        handoverJob = captureHandovers.reserve {
            var ownedFile: File? = null
            var prepared: PreparedUploadAudio? = null
            try {
                val file = pending.result.await()
                if (file == null) {
                    autoSendRequest?.cancel()
                    processingQueue.invalidatePendingAutoSend()
                    processingQueue.releaseReservation(identity)
                    DiagnosticLog.record(TAG, "finishCapture: nothing usable captured (${pending.durationMs}ms)")
                    return@reserve
                }
                ownedFile = file
                currentCoroutineContext().ensureActive()
                val readySnapshot = snapshot ?: loadEnqueueSnapshotUntilAvailable()
                currentCoroutineContext().ensureActive()
                prepared = UploadAudioEncoder.prepare(file)
                currentCoroutineContext().ensureActive()
                val upload = prepared.file
                DiagnosticLog.record(TAG, "finishCapture, file=${upload.name}, size=${upload.length()}, dur=${pending.durationMs}ms")
                enqueue(upload, pending.durationMs, readySnapshot, contextToken, editorToken, identity, autoSendRequest)
                prepared.markEnqueued()
                ownedFile = null // The process-wide queue now owns it.
            } catch (cancelled: CancellationException) {
                pending.cancel()
                prepared?.cancel()
                ownedFile?.delete()
                processingQueue.releaseReservation(identity)
                throw cancelled
            } catch (error: Exception) {
                // Keep the source file for diagnosis rather than silently deleting usable audio.
                prepared?.retainSource()
                autoSendRequest?.cancel()
                processingQueue.invalidatePendingAutoSend()
                processingQueue.releaseReservation(identity)
                DiagnosticLog.recordFailure(TAG, "Capture handover failed; source retained", error)
            }
        }
        checkNotNull(handoverJob).start()
    }

    /** A detached recording must not mutate a newer view's live settings while waiting for disk. */
    private suspend fun loadEnqueueSnapshotUntilAvailable(): EnqueueSnapshot {
        var retryDelayMs = 250L
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return EnqueueSnapshot(
                    preferenceStore.load(), preferenceStore.loadPostProcessing(),
                    preferenceStore.loadToggleStates(), preferenceStore.loadVocabulary(),
                    preferenceStore.loadTextDictionary()
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                DiagnosticLog.recordFailure(TAG, "Capture waiting for saved settings", error)
                delay(retryDelayMs)
                retryDelayMs = (retryDelayMs * 2).coerceAtMost(5_000)
            }
        }
    }

    private fun enqueue(
        file: File, durationMs: Long, snapshot: EnqueueSnapshot, contextToken: Long?, editorToken: Long?,
        identity: ProcessingQueue.WorkIdentity, autoSendRequest: AutoSendPolicy.Request?
    ) {
        val prefs = snapshot.prefs
        if (prefs.apiKey.isBlank() && currentPhase !is InputPhase.Capturing) {
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
                TranscriptionLocale.promptFor(language, prefs.prompt), snapshot.vocabulary
            ),
            vocabulary = snapshot.vocabulary,
            recordingDurationMs = durationMs
        )

        // Snapshot current post-processing state at enqueue time
        val ppEnabled = snapshot.postProcessing?.enabled == true
        val s = snapshot.toggles
        val item = ProcessingQueue.QueueItem(
            audioFile = file,
            transcriptionConfig = config,
            addTrailingSpace = prefs.addTrailingSpace,
            singleWordStripPunctuation = prefs.singleWordStripPunctuation,
            vocabulary = snapshot.vocabulary,
            ppPreferences = if (ppEnabled) snapshot.postProcessing else null,
            ppFix = ppEnabled && s.fixActive,
            ppShorten = ppEnabled && s.shortenActive,
            ppEmoji = ppEnabled && s.emojiActive,
            ppRhyme = ppEnabled && s.rhymeActive,
            ppTranslate = ppEnabled && s.translateActive,
            ppTerminal = ppEnabled && s.terminalActive,
            contextToken = contextToken,
            editorSessionToken = editorToken,
            autoSendRequest = autoSendRequest,
            workId = identity.workId,
            createdAt = identity.createdAt,
            postProcessingVocabulary = snapshot.dictionary.postProcessingVocabulary,
            replacementRules = snapshot.dictionary.replacementRules
        )
        processingQueue.enqueue(item)
    }

    /** Cancel only the current recording; the queue keeps processing. */
    private fun cancelCapture() {
        speechEndpointer = null
        captureIdentity = null
        captureContextToken = null
        captureEditorSessionToken = null
        captureAutoSendRequest?.cancel()
        captureAutoSendRequest = null
        if (capture.isActive) {
            capture.abort()
        }
        moveTo(InputPhase.Ready)
    }

    /** Cancel everything: current recording + the entire queue. */
    fun cancelAll() {
        speechEndpointer = null
        captureIdentity?.let { processingQueue.releaseReservation(it) }
        captureIdentity = null
        captureContextToken = null
        captureEditorSessionToken = null
        captureAutoSendRequest?.cancel()
        captureAutoSendRequest = null
        if (capture.isActive) {
            capture.abort()
        }
        processingQueue.cancelPending()
        moveTo(InputPhase.Ready)
    }

    /**
     * Graceful shutdown: if recording is active, finalize and enqueue it.
     * The queue keeps processing in background and saves results if the original editor is gone.
     */
    fun gracefulShutdown() {
        if (currentPhase is InputPhase.Capturing) {
            finishCaptureAndEnqueue()
        }
        // Don't cancel the queue — let it finish in background
    }

    fun destroy() {
        invalidateAutoSend()
        speechEndpointer = null
        // Releases only a capture still in progress; files already handed to the queue are its own.
        captureIdentity?.let { processingQueue.releaseReservation(it) }
        captureIdentity = null
        captureContextToken = null
        captureEditorSessionToken = null
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
