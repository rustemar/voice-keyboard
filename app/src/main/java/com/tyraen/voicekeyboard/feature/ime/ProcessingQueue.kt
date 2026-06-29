package com.tyraen.voicekeyboard.feature.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.config.PreferenceStore
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.core.network.ConnectivityMonitor
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingArtifactStripper
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingPrompts
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Process-wide engine that turns recordings into inserted text: transcribe → post-process →
 * deliver. There is ONE instance for the whole app (held by `ServiceLocator`); it outlives the IME
 * input view, so recreating the keyboard panel can no longer orphan in-flight work or parked
 * recordings (the bug behind the lost dictation).
 *
 * Offline resilience:
 * - Before each attempt it checks for validated internet ([ConnectivityMonitor]); if offline it
 *   parks the recording instead of firing a doomed request.
 * - On transient failure a recording is parked durably ([ParkedRecordingStore]) and retried
 *   indefinitely with capped exponential backoff, driven both by a timer and by a
 *   network-available callback. It never gives up and never deletes a parked recording on teardown.
 * - Permanent failures (bad/missing key, 4xx) are parked as NEEDS_ATTENTION and surfaced via the
 *   resend button rather than looped on forever.
 *
 * Threading: this object's own state (queue, listener, retry loop) lives on [scope] (Main). The
 * connectivity callback only ever calls [onNetworkAvailable], which hops onto Main. The durable
 * store is independently thread-confined.
 */
class ProcessingQueue(
    private val speechClient: SpeechToTextClient,
    private val postProcessingClient: PostProcessingClient,
    private val store: ParkedRecordingStore,
    private val connectivity: ConnectivityMonitor,
    private val preferenceStore: PreferenceStore,
    private val appContext: Context
) {

    enum class ProcessingPhase { TRANSCRIBING, POST_PROCESSING }

    /** The IME input view binds one of these so results land in the focused field; rebound on every
     *  view recreation. When nothing is bound (keyboard closed/torn down) results go to clipboard. */
    interface Listener {
        fun onTextReady(text: String)
        fun onQueueCountChanged(count: Int)
        fun onProcessingPhaseChanged(phase: ProcessingPhase)
        fun onFailedCountChanged(count: Int)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "ProcessingQueue"
        // A couple of quick in-line attempts absorb a momentary blip without the parking round-trip.
        private const val IMMEDIATE_ATTEMPTS = 2
        private const val IMMEDIATE_BACKOFF_MS = 1000L
        // Indefinite background retry: starts fast, backs off, capped. Reset when internet returns.
        private const val INITIAL_RETRY_BACKOFF_MS = 5_000L
        private const val MAX_RETRY_BACKOFF_MS = 5 * 60_000L
    }

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
        val parkedId: String? = null
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private var processingJob: Job? = null
    private var isProcessing = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
                withContext(Dispatchers.Main.immediate) {
                    failedCountCache = count
                    listener?.onFailedCountChanged(count)
                }
            }
            .launchIn(scope)
    }

    val pendingCount: Int get() = queue.size + if (isProcessing) 1 else 0

    /** Called once from Application.onCreate: recover persisted recordings and resume retrying. */
    fun bootstrap() {
        scope.launch {
            store.ensureLoaded()
            if (store.waitingCount() > 0) ensureRetryLoop()
        }
    }

    /** Bind the current input view. Immediately pushes live counts so its badges are correct. */
    fun bindListener(l: Listener) {
        listener = l
        l.onQueueCountChanged(pendingCount)
        l.onFailedCountChanged(failedCountCache)
    }

    fun unbindListener(l: Listener) {
        if (listener === l) listener = null
    }

    fun enqueue(item: QueueItem) {
        queue.add(item)
        DiagnosticLog.record(TAG, "Enqueued item, pending=$pendingCount")
        pushQueueCount()
        if (!isProcessing) processNext()
    }

    /** Validated internet returned: reset backoff and drain parked recordings right away. */
    fun onNetworkAvailable() {
        scope.launch {
            retryBackoffMs = INITIAL_RETRY_BACKOFF_MS
            drainParked()
            restartRetryLoop()
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
            pushQueueCount()
            return
        }
        isProcessing = true
        pushQueueCount()

        processingJob = scope.launch {
            try {
                processItem(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.recordFailure(TAG, "Processing failed", e)
                listener?.onError(e.message ?: "Unknown error")
                handleFailure(item, permanent = false)
            } finally {
                processNext()
            }
        }
    }

    private suspend fun processItem(item: QueueItem) {
        listener?.onProcessingPhaseChanged(ProcessingPhase.TRANSCRIBING)
        DiagnosticLog.record(TAG, "Transcribing ${item.audioFile.name}")

        if (!item.audioFile.exists()) {
            DiagnosticLog.record(TAG, "Audio file missing, dropping ${item.audioFile.name}")
            if (item.parkedId != null) store.markDone(item.parkedId)
            return
        }

        // Pre-flight: don't fire into a dead network and wait minutes for a timeout — park and let
        // the connectivity-driven loop pick it up when validated internet returns.
        if (!connectivity.isOnline()) {
            DiagnosticLog.record(TAG, "Offline — parking ${item.audioFile.name} for later")
            handleFailure(item, permanent = false)
            return
        }

        var rawText: String? = null
        var lastError: Throwable? = null
        var permanent = false
        for (attempt in 1..IMMEDIATE_ATTEMPTS) {
            val result = speechClient.transcribe(item.audioFile, item.transcriptionConfig)
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
            if (attempt < IMMEDIATE_ATTEMPTS) delay(IMMEDIATE_BACKOFF_MS)
        }

        if (rawText == null) {
            listener?.onError(lastError?.message ?: "Transcription failed")
            handleFailure(item, permanent)
            return
        }

        if (rawText.isBlank()) {
            DiagnosticLog.record(TAG, "Transcription returned empty text, discarding")
            if (item.parkedId != null) store.markDone(item.parkedId) else item.audioFile.delete()
            return
        }

        DiagnosticLog.record(TAG, "Transcription success: ${rawText.take(50)}")

        val cleanedText = if (item.singleWordStripPunctuation) stripSingleWordPunctuation(rawText) else rawText

        val hasPostProcessing = item.ppPreferences != null && item.ppPreferences.apiKey.isNotBlank() &&
            (item.ppFix || item.ppShorten || item.ppEmoji || item.ppRhyme || item.ppTranslate || item.ppTerminal)
        if (hasPostProcessing) {
            listener?.onProcessingPhaseChanged(ProcessingPhase.POST_PROCESSING)
        }
        val processed = maybePostProcess(cleanedText, item)

        val output = if (item.addTrailingSpace) "$processed " else processed
        deliver(output)
        if (item.parkedId != null) store.markDone(item.parkedId) else item.audioFile.delete()
    }

    /** Deliver finished text to the bound input view, or fall back to the clipboard if none. */
    private fun deliver(text: String) {
        val l = listener
        if (l != null) {
            l.onTextReady(text)
        } else {
            try {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Voice transcription", text))
                DiagnosticLog.record(TAG, "No input view bound — delivered to clipboard")
            } catch (e: Exception) {
                DiagnosticLog.recordFailure(TAG, "Clipboard delivery failed", e)
            }
        }
    }

    /**
     * A recording could not be transcribed. Park it durably (or update an existing parked entry),
     * and keep the indefinite retry loop alive for transient failures.
     */
    private suspend fun handleFailure(item: QueueItem, permanent: Boolean) {
        val state = if (permanent) ParkedRecording.State.NEEDS_ATTENTION else ParkedRecording.State.WAITING_NETWORK
        if (item.parkedId != null) {
            store.update(item.parkedId, state)
        } else {
            store.park(item, item.audioFile, state, attemptCount = 1)
        }
        if (!permanent) ensureRetryLoop()
    }

    /** Move every claimable parked recording into the live queue, re-reading API keys from prefs. */
    private suspend fun drainParked() {
        if (!connectivity.isOnline()) {
            DiagnosticLog.record(TAG, "drainParked skipped — still offline")
            return
        }
        val claimed = store.claimWaiting()
        if (claimed.isEmpty()) return

        val apiKey = preferenceStore.load().apiKey
        val ppApiKey = preferenceStore.loadPostProcessing().apiKey
        var enqueued = 0
        for (rec in claimed) {
            if (apiKey.isBlank()) {
                // Can't transcribe without a key — surface for attention instead of looping.
                store.update(rec.id, ParkedRecording.State.NEEDS_ATTENTION)
                continue
            }
            queue.add(rec.toQueueItem(apiKey, ppApiKey))
            enqueued++
        }
        if (enqueued > 0) {
            DiagnosticLog.record(TAG, "Re-queued $enqueued parked recording(s)")
            pushQueueCount()
            if (!isProcessing) processNext()
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
                if (connectivity.isOnline()) {
                    drainParked()
                }
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

    /** Heuristic: is this failure worth retrying (network blip) or permanent (bad key)? */
    private fun isTransientError(error: Throwable?): Boolean {
        when (error) {
            null -> return false
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.SocketException,
            is java.io.InterruptedIOException,
            is javax.net.ssl.SSLException -> return true
        }
        val msg = error?.message?.lowercase() ?: return true // unknown I/O error → assume transient
        // Permanent HTTP errors surfaced by WhisperApiClient as "API error <code>: ...".
        if (Regex("api error 4\\d\\d").containsMatchIn(msg)) return false
        return listOf(
            "timeout", "timed out", "connection abort", "connection reset",
            "unreachable", "failed to connect", "broken pipe", "network", "abort"
        ).any { msg.contains(it) } || msg.contains("api error 5") || msg.contains("api error 429")
    }

    private fun pushQueueCount() {
        listener?.onQueueCountChanged(pendingCount)
    }

    private fun stripSingleWordPunctuation(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return text
        // "Single word" = no whitespace inside the trimmed string.
        if (trimmed.any { it.isWhitespace() }) return text
        return trimmed.trimEnd('.', '!', '?', '。', '！', '？')
    }

    private suspend fun maybePostProcess(text: String, item: QueueItem): String {
        val pp = item.ppPreferences ?: return text
        if (pp.apiKey.isBlank()) return text

        var processed = text

        // Terminal mode: convert voice to shell commands (exclusive, skips all other modes)
        if (item.ppTerminal) {
            DiagnosticLog.record(TAG, "Terminal mode")
            val terminalPrompt = PostProcessingPrompts.buildTerminal(processed, pp)
            val result = postProcessingClient.process(terminalPrompt, pp)
            val raw = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Terminal processing failed, using raw text", error)
                processed
            }
            return PostProcessingArtifactStripper.strip(raw)
        }

        // First: fix/shorten/emoji
        if (PostProcessingPrompts.hasAnyMode(item.ppFix, item.ppShorten, item.ppEmoji)) {
            DiagnosticLog.record(TAG, "Post-processing: fix=${item.ppFix}, shorten=${item.ppShorten}, emoji=${item.ppEmoji}")
            val promptParts = PostProcessingPrompts.build(item.ppFix, item.ppShorten, item.ppEmoji, processed, pp, item.vocabulary)
            val result = postProcessingClient.process(promptParts, pp)
            processed = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Post-processing failed, using raw text", error)
                processed
            }
            processed = PostProcessingArtifactStripper.strip(processed)
        }

        // Then: rhyme
        if (item.ppRhyme) {
            DiagnosticLog.record(TAG, "Rhyming text")
            val rhymePrompt = PostProcessingPrompts.buildRhyme(processed, item.vocabulary)
            val result = postProcessingClient.process(rhymePrompt, pp, modelOverride = pp.resolvedTranslateModel())
            processed = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Rhyming failed, using pre-rhyme text", error)
                processed
            }
            processed = PostProcessingArtifactStripper.strip(processed)
        }

        // Then: translate
        if (item.ppTranslate) {
            DiagnosticLog.record(TAG, "Translating to: ${pp.translateLang}")
            val translatePrompt = PostProcessingPrompts.buildTranslate(processed, pp.translateLang, item.vocabulary)
            val result = postProcessingClient.process(translatePrompt, pp, modelOverride = pp.resolvedTranslateModel())
            processed = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Translation failed, using pre-translate text", error)
                processed
            }
            processed = PostProcessingArtifactStripper.strip(processed)
        }

        return processed
    }

    /** Cancel only the not-yet-parked pending queue. Parked recordings are durable and untouched. */
    fun cancelPending() {
        processingJob?.cancel()
        processingJob = null
        isProcessing = false
        while (true) {
            val item = queue.poll() ?: break
            if (item.parkedId == null) item.audioFile.delete()
        }
        pushQueueCount()
    }
}
