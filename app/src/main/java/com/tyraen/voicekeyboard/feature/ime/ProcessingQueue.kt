package com.tyraen.voicekeyboard.feature.ime

import android.os.SystemClock
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.network.ConnectivityMonitor
import com.tyraen.voicekeyboard.feature.postprocessing.PromptParts
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingArtifactStripper
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingPrompts
import com.tyraen.voicekeyboard.feature.postprocessing.RecentDictationContext
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.vocabulary.TextDictionary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide engine that turns recordings into inserted text: transcribe → post-process →
 * deliver. There is ONE instance for the whole app (held by `ServiceLocator`); it outlives the IME
 * input view, so recreating the keyboard panel can no longer orphan in-flight work or parked
 * recordings (the bug behind the lost dictation).
 *
 * Offline resilience:
 * - Before each attempt it checks availability for that provider ([ConnectivityMonitor]). Public
 *   APIs require validated internet; loopback and private-network servers do not.
 * - On transient failure a recording is parked durably ([ParkedRecordingStore]) and retried
 *   indefinitely with capped exponential backoff, driven both by a timer and by a
 *   network-available callback. It never gives up and never deletes a parked recording on teardown.
 * - Permanent failures (bad/missing key, 4xx other than 408 and a rate-limit 429) are parked as
 *   NEEDS_ATTENTION and surfaced via the resend button rather than looped on forever.
 *
 * Threading: this object's own state (queue, listener, retry loop) lives on [scope] (Main). The
 * connectivity callback only ever calls [onNetworkAvailable], which hops onto Main. The durable
 * store is independently thread-confined.
 */
class ProcessingQueue internal constructor(
    private val speechClient: SpeechToTextClient,
    private val processText: suspend (PromptParts, PostProcessingPreferences, String?) -> Result<String>,
    private val store: ParkedRecordingStore,
    private val isOnline: () -> Boolean,
    private val loadApiKey: suspend () -> String,
    private val loadPostProcessing: suspend () -> PostProcessingPreferences,
    private val savedTranscriptDelivery: suspend (WorkIdentity, String, Boolean) -> Unit,
    private val scope: CoroutineScope,
    elapsedRealtime: () -> Long,
    private val isTranscriptCompleted: suspend (String) -> Boolean = { false },
    private val acknowledgeTranscriptCleanup: suspend (String) -> Unit = {},
    private val canAccessEndpoint: suspend (String) -> Boolean = { isOnline() },
    private val dictionaryDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
) {
    constructor(
        speechClient: SpeechToTextClient,
        postProcessingClient: PostProcessingClient,
        store: ParkedRecordingStore,
        connectivity: ConnectivityMonitor,
        preferenceStore: PreferenceStore,
        pendingTranscriptStore: PendingTranscriptStore
    ) : this(
        speechClient,
        { prompt, prefs, model -> postProcessingClient.process(prompt, prefs, model) },
        store,
        connectivity::isOnline,
        { preferenceStore.load().apiKey },
        preferenceStore::loadPostProcessing,
        { identity, text, trailingSpace ->
            pendingTranscriptStore.save(identity.workId, text, trailingSpace, identity.createdAt)
        },
        CoroutineScope(Dispatchers.Main + SupervisorJob()),
        SystemClock::elapsedRealtime,
        pendingTranscriptStore::isCompleted,
        pendingTranscriptStore::acknowledgeAudioCleanup,
        connectivity::canAccessEndpoint,
        Dispatchers.Default
    )

    enum class ProcessingPhase { TRANSCRIBING, POST_PROCESSING }

    /** The input view may accept a result only in its original editor session. Results that
     * cannot be inserted are saved privately for the user to insert or copy later. */
    interface Listener {
        /** Return true only after the original editor accepted the text. This call cannot suspend. */
        fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean
        fun onQueueCountChanged(count: Int)
        fun onProcessingPhaseChanged(phase: ProcessingPhase)
        fun onFailedCountChanged(count: Int)
        fun onError(message: String)
        fun onCancellableWorkChanged(available: Boolean) {}
    }

    companion object {
        private const val TAG = "ProcessingQueue"
        private val creationOrder = AtomicLong(0)
        private fun nextCreatedAt(): Long = creationOrder.updateAndGet {
            maxOf(System.currentTimeMillis(), it + 1)
        }
        // A couple of quick in-line attempts absorb a momentary blip without the parking round-trip.
        private const val IMMEDIATE_ATTEMPTS = 2
        private const val IMMEDIATE_BACKOFF_MS = 1000L
        // Indefinite background retry: starts fast, backs off, capped. Reset when internet returns.
        private const val INITIAL_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_BACKOFF_MS = 5 * 60_000L
        /** Transcripts up to this length may legitimately post-process to nothing ("um, uh"). */
        private const val FILLER_MAX_CHARS = 25

        private val BILLING_429_MARKERS = listOf("insufficient_quota", "credit_balance", "spend_limit", "usage_limit")

        /**
         * Heuristic: is this failure worth retrying (network blip, rate limit, server error) or
         * permanent (bad key, bad request)? HTTP codes arrive as "API error <code>: ..." from
         * WhisperApiClient. 408 and a rate-limit 429 are 4xx but temporary, so they are checked
         * before the blanket 4xx rule: such a recording waits and resends instead of needing
         * attention. A billing 429 (no credit, a spend or usage cap) does not lift by waiting.
         * "billing" itself is not a marker: Groq's rate-limit message links to its billing page.
         */
        internal fun isTransientError(error: Throwable?): Boolean {
            when (error) {
                null -> return false
                // A policy refusal such as "CLEARTEXT communication ... not permitted by network
                // security policy": it mentions the network but waiting never fixes it.
                is java.net.UnknownServiceException -> return false
                is java.net.SocketTimeoutException,
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.SocketException,
                is java.io.InterruptedIOException,
                is javax.net.ssl.SSLException -> return true
            }
            val msg = error?.message?.lowercase() ?: return true // unknown I/O error → assume transient
            if (msg.contains("api error 429")) return BILLING_429_MARKERS.none { msg.contains(it) }
            if (msg.contains("api error 408")) return true
            if (Regex("api error 4\\d\\d").containsMatchIn(msg)) return false
            return listOf(
                "timeout", "timed out", "connection abort", "connection reset",
                "unreachable", "failed to connect", "broken pipe", "network", "abort"
            ).any { msg.contains(it) } || msg.contains("api error 5")
        }
    }

    data class WorkIdentity(val workId: String, val createdAt: Long)

    data class QueueItem(
        val audioFile: File,
        val transcriptionConfig: TranscriptionConfig,
        val addTrailingSpace: Boolean,
        val singleWordStripPunctuation: Boolean,
        val vocabulary: String,
        val ppPreferences: PostProcessingPreferences?,
        val ppFix: Boolean,
        val ppShorten: Boolean,
        val ppEmoji: Boolean,
        val ppRhyme: Boolean,
        val ppTranslate: Boolean,
        val ppTerminal: Boolean,
        /** Non-null when this item came from the durable parked store (vs a fresh capture). */
        val parkedId: String? = null,
        /** RAM-only capture generation. Restored parked recordings deliberately have none. */
        val contextToken: Long? = null,
        /** Original editor session; never serialized into parked recordings. */
        val editorSessionToken: Long? = null,
        val workId: String = UUID.randomUUID().toString(),
        val createdAt: Long = nextCreatedAt(),
        /** Capture-time text preferences; never sent to the speech provider. */
        val postProcessingVocabulary: String = "",
        val replacementRules: String = ""
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private var processingJob: Job? = null
    private var isProcessing = false
    private var activeItem: QueueItem? = null
    private var activePhase: ProcessingPhase? = null
    private val cancelledWork = HashSet<String>()
    private val deliveredWork = HashSet<String>()
    /** A short, non-cancellable insertion/save transaction; a failed save is still retryable. */
    private val committingWork = HashSet<String>()
    /** Keep the original editor through same-process retries, never across process death. */
    private val editorSessionTokens = HashMap<String, Long>()
    private data class Reservation(val identity: WorkIdentity, val onCancel: () -> Unit, val finalized: Boolean = false)
    private val reservations = LinkedHashMap<String, Reservation>()
    // Includes device sleep, so returning to the phone does not revive hours-old references.
    private val recentContext = RecentDictationContext(elapsedRealtime)

    @Volatile
    private var listener: Listener? = null
    private var failedCountCache = 0

    private var retryJob: Job? = null
    private var retryBackoffMs = INITIAL_RETRY_BACKOFF_MS

    init {
        // Mirror the durable parked count into the bound panel's resend badge. The store emits from
        // its own thread, so hop to Main before touching any UI-facing listener.
        store.count
            .onEach { count ->
                failedCountCache = count
                listener?.onFailedCountChanged(count)
                pushCancellableWork()
            }
            .launchIn(scope)
        store.recordings.onEach { records ->
            records.maxOfOrNull { it.createdAt }?.let { latest ->
                creationOrder.updateAndGet { maxOf(it, latest) }
            }
            val liveWork = records.mapTo(HashSet()) { it.workId }
            queue.forEach { liveWork.add(it.workId) }
            activeItem?.let { liveWork.add(it.workId) }
            editorSessionTokens.keys.retainAll(liveWork)
            pushCancellableWork()
        }.launchIn(scope)
    }

    val pendingCount: Int get() = queue.size + reservations.values.count { it.finalized } +
        if (activeItem?.let { it.workId !in cancelledWork && it.workId !in deliveredWork } == true) 1 else 0

    fun setPostProcessingContextScope(packageName: String?) = recentContext.setScope(packageName)

    fun capturePostProcessingContextToken(): Long? = recentContext.captureToken()

    /** Also invalidates work already in flight; a late result must not resurrect the history. */
    fun clearPostProcessingContext() = recentContext.clear()

    /** Called once from Application.onCreate: recover persisted recordings and resume retrying. */
    fun bootstrap() {
        scope.launch {
            // Loopback works even when Android has no network and will never emit onAvailable.
            drainParked()
            if (store.waitingCount() > 0) ensureRetryLoop()
        }
    }

    /** Bind the current input view. Immediately pushes live counts so its badges are correct. */
    fun bindListener(l: Listener) {
        listener = l
        l.onQueueCountChanged(pendingCount)
        l.onFailedCountChanged(failedCountCache)
        activePhase?.let(l::onProcessingPhaseChanged)
        l.onCancellableWorkChanged(hasCancellableWork)
    }

    fun unbindListener(l: Listener) {
        if (listener === l) listener = null
    }

    /** Reserve capture order before asynchronous preferences or view handover can delay enqueue. */
    fun reserveDictation(onCancel: () -> Unit): WorkIdentity {
        val identity = WorkIdentity(UUID.randomUUID().toString(), nextCreatedAt())
        reservations[identity.workId] = Reservation(identity, onCancel)
        pushCancellableWork()
        return identity
    }

    fun updateReservation(identity: WorkIdentity, onCancel: () -> Unit): Boolean {
        if (!reservations.containsKey(identity.workId) || identity.workId in cancelledWork) return false
        reservations[identity.workId] = Reservation(identity, onCancel, finalized = true)
        pushQueueCount()
        return true
    }

    fun releaseReservation(identity: WorkIdentity) {
        reservations.remove(identity.workId)
        pushQueueCount()
    }

    fun enqueue(item: QueueItem) {
        reservations.remove(item.workId)
        if (item.workId in cancelledWork) {
            item.audioFile.delete()
            pushCancellableWork()
            return
        }
        item.editorSessionToken?.let { editorSessionTokens[item.workId] = it }
        queue.add(item)
        DiagnosticLog.record(TAG, "Enqueued item, pending=$pendingCount")
        pushQueueCount()
        if (!isProcessing) processNext()
    }

    /** A network link changed: reset backoff and check each parked provider right away. */
    fun onNetworkAvailable() {
        scope.launch {
            retryBackoffMs = INITIAL_RETRY_BACKOFF_MS
            drainParked()
            restartRetryLoop()
        }
    }

    /** User chose to delete the unsent recordings; [onDone] gets the number removed. */
    fun discardFailed(onDone: (Int) -> Unit) {
        scope.launch {
            val removed = store.discardUnsent()
            DiagnosticLog.record(TAG, "Discarded $removed failed recording(s)")
            onDone(removed)
        }
    }

    /** User tapped resend: give permanently-failed items another chance and drain now. */
    fun retryFailed() {
        scope.launch {
            store.promoteNeedsAttentionToWaiting()
            retryBackoffMs = INITIAL_RETRY_BACKOFF_MS
            DiagnosticLog.record(TAG, "Manual resend requested")
            drainParked()
            restartRetryLoop()
        }
    }

    private fun processNext() {
        val item = queue.poll() ?: run {
            isProcessing = false
            activeItem = null
            activePhase = null
            processingJob = null
            pushQueueCount()
            return
        }
        isProcessing = true
        activeItem = item
        activePhase = ProcessingPhase.TRANSCRIBING
        pushQueueCount()

        // Assign before starting: a synchronous cancellation must always reach this exact job.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                processItem(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                DiagnosticLog.recordFailure(TAG, "Processing failed", e)
                listener?.onError(e.message ?: "Unknown error")
                handleFailure(item, permanent = false)
            }
        }
        processingJob = job
        // Completion also runs when cancel wins before the dispatched coroutine body starts.
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) cancelledWork.add(item.workId)
            scope.launch {
                if (item.workId in cancelledWork) {
                    withContext(NonCancellable) {
                        store.cancel(item.workId)
                        item.audioFile.delete()
                        editorSessionTokens.remove(item.workId)
                    }
                }
                deliveredWork.remove(item.workId)
                if (activeItem === item) processNext()
            }
        }
        job.start()
    }

    private fun setPhase(phase: ProcessingPhase) {
        activePhase = phase
        listener?.onProcessingPhaseChanged(phase)
    }

    private suspend fun processItem(item: QueueItem) {
        checkActive(item)
        setPhase(ProcessingPhase.TRANSCRIBING)
        DiagnosticLog.record(TAG, "Transcribing ${item.audioFile.name}")

        if (!item.audioFile.exists()) {
            DiagnosticLog.record(TAG, "Audio file missing, dropping ${item.audioFile.name}")
            if (item.parkedId != null) store.markDone(item.parkedId)
            editorSessionTokens.remove(item.workId)
            return
        }

        // The process may have died after saving a finished result but before deleting its
        // parked audio. A durable result already owns this work: do not bill/retranscribe it
        // again, or allow an old recording to insert a second copy into the current editor.
        if (isTranscriptCompleted(item.workId)) {
            checkActive(item)
            deliveredWork.add(item.workId)
            pushCancellableWork()
            withContext(NonCancellable) { complete(item) }
            DiagnosticLog.record(TAG, "Finished transcript already saved — released leftover audio")
            return
        }

        // No key means no request can succeed: park straight away as "needs attention" and let
        // resend pick it up once a key has been entered.
        if (item.transcriptionConfig.apiKey.isBlank()) {
            DiagnosticLog.record(TAG, "No API key — parking ${item.audioFile.name} for attention")
            handleFailure(item, permanent = true)
            return
        }

        // Internet validation describes a public API, not a loopback/NAS provider. The same
        // endpoint-specific check is used for fresh captures and restored recordings.
        if (!canAccessEndpoint(item.transcriptionConfig.endpoint)) {
            DiagnosticLog.record(TAG, "Provider network unavailable — parking ${item.audioFile.name} for later")
            handleFailure(item, permanent = false)
            return
        }

        var rawText: String? = null
        var lastError: Throwable? = null
        var permanent = false
        for (attempt in 1..IMMEDIATE_ATTEMPTS) {
            val result = speechClient.transcribe(item.audioFile, item.transcriptionConfig)
            checkActive(item)
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            if (result.isSuccess) {
                rawText = result.getOrNull()
                break
            }
            lastError = result.exceptionOrNull()
            if (!isTransientError(lastError)) {
                permanent = true
                DiagnosticLog.recordFailure(TAG, "Transcription failed (permanent)", lastError)
                break
            }
            DiagnosticLog.recordFailure(
                TAG, "Transcription attempt $attempt/$IMMEDIATE_ATTEMPTS failed (transient)", lastError
            )
            // A rate limit rarely lifts within a second; park now and let the timed loop resend.
            if (lastError?.message?.contains("API error 429") == true) break
            if (attempt < IMMEDIATE_ATTEMPTS) delay(IMMEDIATE_BACKOFF_MS)
        }

        if (rawText == null) {
            listener?.onError(lastError?.message ?: "Transcription failed")
            handleFailure(item, permanent)
            return
        }

        if (rawText.isBlank()) {
            DiagnosticLog.record(TAG, "Transcription returned empty text, discarding")
            complete(item)
            return
        }

        DiagnosticLog.record(TAG, "Transcription success: ${rawText.length} characters")

        val punctuatedText = if (item.singleWordStripPunctuation) stripSingleWordPunctuation(rawText) else rawText
        val prepared = withContext(dictionaryDispatcher) {
            TextDictionary.prepare(punctuatedText, item.vocabulary,
                item.postProcessingVocabulary, item.replacementRules)
        }
        checkActive(item)
        val cleanedText = prepared.text

        val hasPostProcessing = item.ppPreferences != null && item.ppPreferences.apiKey.isNotBlank() &&
            (item.ppFix || item.ppShorten || item.ppEmoji || item.ppRhyme || item.ppTranslate || item.ppTerminal)
        if (hasPostProcessing) {
            setPhase(ProcessingPhase.POST_PROCESSING)
        }
        val processed = maybePostProcess(cleanedText, item, prepared.vocabulary)

        // The prompts say "output nothing" for filler-only input; honour that instead of typing a
        // lone space into the field or saving an empty result. A model that blanks a real
        // sentence must not lose it, though: anything longer than a few filler sounds falls back
        // to the transcript, like an LLM error does.
        val output = if (processed.isBlank()) {
            if (cleanedText.trim().length <= FILLER_MAX_CHARS) {
                DiagnosticLog.record(TAG, "Post-processing produced empty text, nothing inserted")
                complete(item)
                return
            }
            DiagnosticLog.record(TAG, "Post-processing produced empty text for a real sentence, using raw text")
            cleanedText
        } else processed

        // Resolve live consent before the delivery transaction. Cancellation wins before this
        // fence; once insertion/save starts it applies only to other unfinished dictations.
        val remember = canUseContext(item)
        checkActive(item)
        committingWork.add(item.workId)
        pushCancellableWork()
        try {
            deliver(item, output)
            deliveredWork.add(item.workId)
            if (remember) recentContext.remember(item.contextToken, output)
            // Insertion or durable save has succeeded. Finish releasing owned audio even if the
            // UI/process-wide scope is torn down while the parked store switches dispatchers.
            withContext(NonCancellable) { complete(item) }
        } finally {
            committingWork.remove(item.workId)
            pushCancellableWork()
        }
    }

    private suspend fun complete(item: QueueItem) {
        if (item.parkedId != null) store.markDone(item.parkedId) else item.audioFile.delete()
        // Never clear the durable completion marker while replayable source audio remains.
        if (item.audioFile.exists()) throw IOException("Could not release the completed dictation audio")
        acknowledgeTranscriptCleanup(item.workId)
        editorSessionTokens.remove(item.workId)
    }

    /** Inserting cannot suspend; an unavailable/changed editor instead gets a durable result. */
    private suspend fun deliver(item: QueueItem, text: String) {
        val l = listener
        val editorToken = item.editorSessionToken
        if (editorToken != null && l?.onTextReady(text, item.addTrailingSpace, editorToken) == true) return
        // A save failure must propagate: processNext parks the original audio and retries instead
        // of deleting the only copy of a dictation. No system clipboard is changed automatically.
        withContext(NonCancellable) {
            savedTranscriptDelivery(WorkIdentity(item.workId, item.createdAt), text, item.addTrailingSpace)
        }
        DiagnosticLog.record(TAG, "Original editor unavailable — saved transcript for later")
    }

    /**
     * A recording could not be transcribed. Park it durably (or update an existing parked entry),
     * and keep the indefinite retry loop alive for transient failures.
     */
    private suspend fun handleFailure(item: QueueItem, permanent: Boolean) {
        checkActive(item)
        val state = if (permanent) ParkedRecording.State.NEEDS_ATTENTION else ParkedRecording.State.WAITING_NETWORK
        // Once disk mutation starts, finish it even if cancelled. The completion handler then
        // serializes exact cancellation behind that mutation, including a just-created sidecar.
        withContext(NonCancellable) {
            if (item.parkedId != null) store.update(item.parkedId, state)
            else store.park(item, item.audioFile, state, attemptCount = 1)
        }
        checkActive(item)
        if (!permanent) ensureRetryLoop()
    }

    /** Move every claimable parked recording into the live queue, re-reading API keys from prefs. */
    private suspend fun drainParked() {
        // Completed results need no network, even if their text was already consumed after a
        // restart. Release crash leftovers before applying the connectivity gate to real retries.
        store.ensureLoaded()
        reconcileCompletedRecordings()
        val endpoints = store.recordings.value.asSequence()
            .filter { it.state == ParkedRecording.State.WAITING_NETWORK }
            .map { it.endpoint }.distinct().toList()
        val eligibleEndpoints = endpoints.filter { canAccessEndpoint(it) }.toSet()
        if (eligibleEndpoints.isEmpty()) return
        val claimed = store.claimWaiting(eligibleEndpoints)
        if (claimed.isEmpty()) return

        val apiKey = loadApiKey()
        val ppApiKey = loadPostProcessing().apiKey
        var enqueued = 0
        for (rec in claimed) {
            if (rec.workId in cancelledWork || rec.workId in deliveredWork) continue
            if (apiKey.isBlank()) {
                // Can't transcribe without a key — surface for attention instead of looping.
                store.update(rec.id, ParkedRecording.State.NEEDS_ATTENTION)
                continue
            }
            queue.add(rec.toQueueItem(apiKey, ppApiKey).copy(editorSessionToken = editorSessionTokens[rec.workId]))
            enqueued++
        }
        if (enqueued > 0) {
            DiagnosticLog.record(TAG, "Re-queued $enqueued parked recording(s)")
            pushQueueCount()
            if (!isProcessing) processNext()
        }
    }

    private suspend fun reconcileCompletedRecordings() {
        for (rec in store.recordings.value) {
            // Live items own their source until their processing coroutine finishes. Their own
            // completion check performs the same reconciliation before any provider request.
            if (activeItem?.workId == rec.workId || queue.any { it.workId == rec.workId }) continue
            try {
                if (isTranscriptCompleted(rec.workId)) {
                    withContext(NonCancellable) { complete(rec.toQueueItem("", "")) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Storage lookup/cleanup failures cannot justify deleting or retranscribing
                // source audio. Normal retry processing will retain it and report the failure.
                DiagnosticLog.recordFailure(TAG, "Could not reconcile a completed dictation", error)
            }
        }
    }

    /**
     * Keep retrying parked recordings forever (capped exponential backoff). Driven by a timer so it
     * covers the "validated but endpoint still down" case; the network-available callback resets the
     * backoff and kicks an immediate drain on top of this. Exits when nothing is left to retry and
     * is re-armed by [handleFailure] / [onNetworkAvailable].
     */
    private fun ensureRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive && store.waitingCount() > 0) {
                delay(retryBackoffMs)
                drainParked()
                retryBackoffMs = (retryBackoffMs * 2).coerceAtMost(MAX_RETRY_BACKOFF_MS)
            }
        }
    }

    /**
     * Cancel any pending backoff delay and start a fresh loop, so a backoff reset (network returned
     * / manual resend) takes effect immediately instead of being swallowed by an in-flight delay.
     */
    private fun restartRetryLoop() {
        retryJob?.cancel()
        retryJob = null
        ensureRetryLoop()
    }

    private fun pushQueueCount() {
        listener?.onQueueCountChanged(pendingCount)
        pushCancellableWork()
    }

    private fun stripSingleWordPunctuation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        // "Single word" = no whitespace inside the trimmed string.
        if (trimmed.any { it.isWhitespace() }) return text
        return trimmed.trimEnd('.', '!', '?', '。', '！', '？')
    }

    private suspend fun maybePostProcess(text: String, item: QueueItem, referenceVocabulary: String): String {
        checkActive(item)
        val pp = item.ppPreferences ?: return text
        if (pp.apiKey.isBlank()) return text
        if ((item.ppFix || item.ppShorten || item.ppEmoji || item.ppRhyme || item.ppTranslate || item.ppTerminal) &&
            !canAccessEndpoint(pp.resolvedEndpoint())) {
            DiagnosticLog.record(TAG, "Post-processing provider unavailable, using recognized text")
            return text
        }

        // The option is live consent, even for a recording whose other preferences were
        // snapshotted earlier. Settings also invalidate the generation synchronously on disable.
        if (!canUseContext(item)) {
            val live = loadPostProcessing()
            if (!live.enabled || !live.contextEnabled) recentContext.clear()
        }
        // Re-read this same generation before each stage: turning the option off during a call
        // removes history from subsequent stages too, without ever adding this dictation to itself.
        fun context() = if (pp.contextEnabled) recentContext.snapshot(item.contextToken) else emptyList()

        checkActive(item)
        var processed = text

        // Terminal mode: convert voice to shell commands (exclusive, skips all other modes)
        if (item.ppTerminal) {
            DiagnosticLog.record(TAG, "Terminal mode")
            val terminalPrompt = PostProcessingPrompts.buildTerminal(processed, pp)
            val result = processText(terminalPrompt, pp, null)
            checkActive(item)
            val raw = result.getOrElse { error ->
                if (error is CancellationException) throw error
                DiagnosticLog.recordFailure(TAG, "Terminal processing failed, using raw text", error)
                processed
            }
            return PostProcessingArtifactStripper.strip(raw)
        }

        // First: fix/shorten/emoji
        if (PostProcessingPrompts.hasAnyMode(item.ppFix, item.ppShorten, item.ppEmoji)) {
            DiagnosticLog.record(TAG, "Post-processing: fix=${item.ppFix}, shorten=${item.ppShorten}, emoji=${item.ppEmoji}")
            val promptParts = PostProcessingPrompts.build(item.ppFix, item.ppShorten, item.ppEmoji, processed, pp, referenceVocabulary, context())
            val result = processText(promptParts, pp, null)
            checkActive(item)
            processed = result.getOrElse { error ->
                if (error is CancellationException) throw error
                DiagnosticLog.recordFailure(TAG, "Post-processing failed, using raw text", error)
                processed
            }
            processed = PostProcessingArtifactStripper.strip(processed)
        }

        // Then: rhyme
        if (item.ppRhyme) {
            DiagnosticLog.record(TAG, "Rhyming text")
            val rhymePrompt = PostProcessingPrompts.buildRhyme(processed, referenceVocabulary, context())
            val result = processText(rhymePrompt, pp, pp.resolvedTranslateModel())
            checkActive(item)
            processed = result.getOrElse { error ->
                if (error is CancellationException) throw error
                DiagnosticLog.recordFailure(TAG, "Rhyming failed, using pre-rhyme text", error)
                processed
            }
            processed = PostProcessingArtifactStripper.strip(processed)
        }

        // Then: translate
        if (item.ppTranslate) {
            DiagnosticLog.record(TAG, "Translating to: ${pp.translateLang}")
            val translatePrompt = PostProcessingPrompts.buildTranslate(processed, pp.translateLang, referenceVocabulary, context())
            val result = processText(translatePrompt, pp, pp.resolvedTranslateModel())
            checkActive(item)
            processed = result.getOrElse { error ->
                if (error is CancellationException) throw error
                DiagnosticLog.recordFailure(TAG, "Translation failed, using pre-translate text", error)
                processed
            }
            processed = PostProcessingArtifactStripper.strip(processed)
        }

        return processed
    }

    private suspend fun canUseContext(item: QueueItem): Boolean {
        if (item.ppPreferences?.contextEnabled != true || item.ppTerminal || item.contextToken == null) return false
        val live = loadPostProcessing()
        return live.enabled && live.contextEnabled
    }

    private suspend fun checkActive(item: QueueItem) {
        currentCoroutineContext().ensureActive()
        if (item.workId in cancelledWork) throw CancellationException("Dictation cancelled")
    }

    private fun latestWork(): WorkIdentity? {
        val candidates = sequence {
            reservations.values.forEach { yield(it.identity) }
            queue.forEach { yield(WorkIdentity(it.workId, it.createdAt)) }
            activeItem?.let { yield(WorkIdentity(it.workId, it.createdAt)) }
            store.recordings.value.forEach { yield(WorkIdentity(it.workId, it.createdAt)) }
        }
        return candidates.filter {
            it.workId !in cancelledWork && it.workId !in deliveredWork && it.workId !in committingWork
        }
            .maxWithOrNull(compareBy<WorkIdentity> { it.createdAt }.thenBy { it.workId })
    }

    val hasCancellableWork: Boolean get() = latestWork() != null

    private fun pushCancellableWork() {
        listener?.onCancellableWorkChanged(hasCancellableWork)
    }

    /** Cancel one newest-created undelivered item, regardless of its current processing stage. */
    fun cancelLatest(): Boolean {
        val identity = latestWork() ?: return false
        cancelledWork.add(identity.workId) // synchronous fence before callbacks or any suspension
        editorSessionTokens.remove(identity.workId)
        val reservation = reservations.remove(identity.workId)
        val removed = queue.filter { it.workId == identity.workId }
        removed.forEach(queue::remove)
        if (activeItem?.workId == identity.workId) processingJob?.cancel()
        try {
            reservation?.onCancel?.invoke()
        } catch (e: Exception) {
            DiagnosticLog.recordFailure(TAG, "Capture cancellation failed", e)
        }
        scope.launch {
            withContext(NonCancellable) {
                store.cancel(identity.workId)
                removed.forEach { it.audioFile.delete() }
            }
        }
        pushQueueCount()
        return true
    }

    /** Legacy teardown action. Parked recordings remain durable; the cancel button uses cancelLatest. */
    fun cancelPending() {
        val fresh = queue.filter { it.parkedId == null }
        fresh.forEach {
            cancelledWork.add(it.workId); queue.remove(it); it.audioFile.delete()
            editorSessionTokens.remove(it.workId)
        }
        activeItem?.takeIf {
            it.parkedId == null && it.workId !in committingWork && it.workId !in deliveredWork
        }?.let {
            cancelledWork.add(it.workId)
            editorSessionTokens.remove(it.workId)
            processingJob?.cancel()
        }
        pushQueueCount()
    }
}
