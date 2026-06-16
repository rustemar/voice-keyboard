package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingArtifactStripper
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingPrompts
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class ProcessingQueue(
    private val speechClient: SpeechToTextClient,
    private val postProcessingClient: PostProcessingClient,
    private val onTextReady: (String) -> Unit,
    private val onQueueCountChanged: (Int) -> Unit,
    private val onProcessingPhaseChanged: (ProcessingPhase) -> Unit,
    private val onError: (String) -> Unit,
    private val onFailedCountChanged: (Int) -> Unit = {}
) {

    enum class ProcessingPhase { TRANSCRIBING, POST_PROCESSING }

    companion object {
        private const val TAG = "ProcessingQueue"
        // How many times we re-attempt transcription on a transient network failure
        // before giving up and parking the recording for manual retry.
        private const val MAX_TRANSCRIBE_ATTEMPTS = 3
        // Linear backoff between attempts (1.5s, then 3s) — gives a Wi-Fi↔mobile
        // hand-off time to settle before we try again.
        private const val RETRY_BACKOFF_MS = 1500L
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
        val ppTerminal: Boolean
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private var processingJob: Job? = null
    private var isProcessing = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Recordings that failed every transcription attempt. Their audio is kept on
    // disk (renamed to retry_*.<ext>) so the user can re-send them once the
    // network recovers, instead of silently losing what they dictated.
    private val failedItems = mutableListOf<QueueItem>()
    private var retryFileCounter = 0

    val pendingCount: Int get() = queue.size + if (isProcessing) 1 else 0
    val failedCount: Int get() = failedItems.size

    fun enqueue(item: QueueItem) {
        queue.add(item)
        DiagnosticLog.record(TAG, "Enqueued item, pending=$pendingCount")
        onQueueCountChanged(pendingCount)
        if (!isProcessing) processNext()
    }

    private fun processNext() {
        val item = queue.poll() ?: run {
            isProcessing = false
            onQueueCountChanged(0)
            return
        }
        isProcessing = true
        onQueueCountChanged(pendingCount)

        processingJob = scope.launch {
            // When true, the recording was parked for manual retry and must NOT be deleted.
            var preserved = false
            try {
                preserved = processItem(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.recordFailure(TAG, "Processing failed", e)
                onError(e.message ?: "Unknown error")
                preserved = preserveForRetry(item)
            } finally {
                if (!preserved) item.audioFile.delete()
                processNext()
            }
        }
    }

    /**
     * Runs one queue item end to end.
     * @return true if the recording was parked for manual retry (caller must keep the audio file).
     */
    private suspend fun processItem(item: QueueItem): Boolean {
        // Step 1: Transcribe — retry transient network failures before giving up.
        onProcessingPhaseChanged(ProcessingPhase.TRANSCRIBING)
        DiagnosticLog.record(TAG, "Transcribing ${item.audioFile.name}")

        var rawText: String? = null
        var lastError: Throwable? = null
        for (attempt in 1..MAX_TRANSCRIBE_ATTEMPTS) {
            val result = speechClient.transcribe(item.audioFile, item.transcriptionConfig)
            if (result.isSuccess) {
                rawText = result.getOrNull()
                break
            }
            lastError = result.exceptionOrNull()
            val transient = isTransientError(lastError)
            DiagnosticLog.recordFailure(
                TAG,
                "Transcription attempt $attempt/$MAX_TRANSCRIBE_ATTEMPTS failed (transient=$transient)",
                lastError
            )
            // Permanent errors (bad key, bad request) won't fix themselves — stop retrying,
            // but still park the recording so the user can re-send after correcting settings.
            if (!transient || attempt == MAX_TRANSCRIBE_ATTEMPTS) break
            delay(RETRY_BACKOFF_MS * attempt)
        }

        if (rawText == null) {
            onError(lastError?.message ?: "Transcription failed")
            return preserveForRetry(item)
        }

        if (rawText.isBlank()) {
            DiagnosticLog.record(TAG, "Transcription returned empty text, skipping")
            return false
        }

        DiagnosticLog.record(TAG, "Transcription success: ${rawText.take(50)}")

        val cleanedText = if (item.singleWordStripPunctuation) stripSingleWordPunctuation(rawText) else rawText

        // Step 2: Post-process
        val hasPostProcessing = item.ppPreferences != null && !item.ppPreferences.apiKey.isBlank() &&
            (item.ppFix || item.ppShorten || item.ppEmoji || item.ppRhyme || item.ppTranslate || item.ppTerminal)
        if (hasPostProcessing) {
            onProcessingPhaseChanged(ProcessingPhase.POST_PROCESSING)
        }
        val processed = maybePostProcess(cleanedText, item)

        // Step 3: Insert text
        val output = if (item.addTrailingSpace) "$processed " else processed
        onTextReady(output)
        return false
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

    /**
     * Park a recording that exhausted its transcription attempts: move the audio to a
     * stable retry_*.<ext> file and remember the item so the user can re-send it.
     * @return true (the audio is kept; the caller must not delete it).
     */
    private fun preserveForRetry(item: QueueItem): Boolean {
        val src = item.audioFile
        if (!src.exists()) {
            DiagnosticLog.record(TAG, "preserveForRetry: source file missing (${src.name})")
            return false
        }
        val ext = src.extension.ifBlank { "ogg" }
        val dest = File(src.parentFile, "retry_${retryFileCounter++}.$ext")
        val kept = if (src.renameTo(dest)) item.copy(audioFile = dest) else item
        failedItems.add(kept)
        DiagnosticLog.record(TAG, "Parked recording for manual retry, failed=${failedItems.size}")
        onFailedCountChanged(failedItems.size)
        return true
    }

    /** Re-enqueue every parked recording. Triggered by the user tapping the retry button. */
    fun retryFailed() {
        if (failedItems.isEmpty()) return
        val items = failedItems.toList()
        failedItems.clear()
        onFailedCountChanged(0)
        DiagnosticLog.record(TAG, "Retrying ${items.size} parked recording(s)")
        for (item in items) queue.add(item)
        onQueueCountChanged(pendingCount)
        if (!isProcessing) processNext()
    }

    private fun clearFailed() {
        if (failedItems.isEmpty()) return
        failedItems.forEach { it.audioFile.delete() }
        failedItems.clear()
        onFailedCountChanged(0)
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

    fun cancelAll() {
        processingJob?.cancel()
        processingJob = null
        isProcessing = false
        // Clean up queued audio files
        while (true) {
            val item = queue.poll() ?: break
            item.audioFile.delete()
        }
        clearFailed()
        onQueueCountChanged(0)
    }

    fun destroy() {
        cancelAll()
        scope.cancel()
    }
}
