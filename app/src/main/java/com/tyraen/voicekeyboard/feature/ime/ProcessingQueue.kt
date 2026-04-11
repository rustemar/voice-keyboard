package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.logging.DiagnosticLog
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
    private val onError: (String) -> Unit
) {

    companion object {
        private const val TAG = "ProcessingQueue"
    }

    data class QueueItem(
        val audioFile: File,
        val transcriptionConfig: TranscriptionConfig,
        val addTrailingSpace: Boolean,
        val ppPreferences: PostProcessingPreferences?,
        val ppFix: Boolean,
        val ppShorten: Boolean,
        val ppEmoji: Boolean,
        val ppRhyme: Boolean,
        val ppTranslate: Boolean
    )

    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private var processingJob: Job? = null
    private var isProcessing = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val pendingCount: Int get() = queue.size + if (isProcessing) 1 else 0

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
            try {
                processItem(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DiagnosticLog.recordFailure(TAG, "Processing failed", e)
                onError(e.message ?: "Unknown error")
            } finally {
                item.audioFile.delete()
                processNext()
            }
        }
    }

    private suspend fun processItem(item: QueueItem) {
        // Step 1: Transcribe
        DiagnosticLog.record(TAG, "Transcribing ${item.audioFile.name}")
        val transcriptionResult = speechClient.transcribe(item.audioFile, item.transcriptionConfig)

        val rawText = transcriptionResult.getOrElse { error ->
            DiagnosticLog.recordFailure(TAG, "Transcription failed", error)
            onError(error.message ?: "Transcription failed")
            return
        }

        if (rawText.isBlank()) {
            DiagnosticLog.record(TAG, "Transcription returned empty text, skipping")
            return
        }

        DiagnosticLog.record(TAG, "Transcription success: ${rawText.take(50)}")

        // Step 2: Post-process
        val processed = maybePostProcess(rawText, item)

        // Step 3: Insert text
        val output = if (item.addTrailingSpace) "$processed " else processed
        onTextReady(output)
    }

    private suspend fun maybePostProcess(text: String, item: QueueItem): String {
        val pp = item.ppPreferences ?: return text
        if (pp.apiKey.isBlank()) return text

        var processed = text

        // First: fix/shorten/emoji
        if (PostProcessingPrompts.hasAnyMode(item.ppFix, item.ppShorten, item.ppEmoji)) {
            DiagnosticLog.record(TAG, "Post-processing: fix=${item.ppFix}, shorten=${item.ppShorten}, emoji=${item.ppEmoji}")
            val promptParts = PostProcessingPrompts.build(item.ppFix, item.ppShorten, item.ppEmoji, processed, pp)
            val result = postProcessingClient.process(promptParts, pp)
            processed = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Post-processing failed, using raw text", error)
                processed
            }
        }

        // Then: rhyme
        if (item.ppRhyme) {
            DiagnosticLog.record(TAG, "Rhyming text")
            val rhymePrompt = PostProcessingPrompts.buildRhyme(processed)
            val result = postProcessingClient.process(rhymePrompt, pp, modelOverride = pp.resolvedTranslateModel())
            processed = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Rhyming failed, using pre-rhyme text", error)
                processed
            }
        }

        // Then: translate
        if (item.ppTranslate) {
            DiagnosticLog.record(TAG, "Translating to: ${pp.translateLang}")
            val translatePrompt = PostProcessingPrompts.buildTranslate(processed, pp.translateLang)
            val result = postProcessingClient.process(translatePrompt, pp, modelOverride = pp.resolvedTranslateModel())
            processed = result.getOrElse { error ->
                DiagnosticLog.recordFailure(TAG, "Translation failed, using pre-translate text", error)
                processed
            }
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
        onQueueCountChanged(0)
    }

    fun destroy() {
        cancelAll()
        scope.cancel()
    }
}
