package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessingQueueDeliveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture(val test: TestScope) {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val store = ParkedRecordingStore(File(dir, "parked"), dispatcher)
        val saved = PendingTranscriptStore(File(dir, "saved"), dispatcher)
        var currentEditor = 1L
        var acceptsInsertion = true
        var online = true
        val inserted = ArrayList<String>()
        val requested = ArrayList<String>()
        var insertAttempts = 0
        var transcribe: suspend (String) -> Result<String> = { Result.success(it) }
        var beforeSave: suspend () -> Unit = {}
        var savedExists: suspend (String) -> Boolean = { saved.isCompleted(it) }
        val queue = ProcessingQueue(
            object : SpeechToTextClient {
                override suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String> {
                    val text = audioFile.readText()
                    requested += text
                    return transcribe(text)
                }

                override suspend fun validateCredentials(
                    apiKey: String, endpoint: String, model: String, cacheDir: File
                ) = Result.success("OK")
            },
            { _, _, _ -> error("Post-processing is not enabled") },
            store, { online }, { "synthetic-key" }, { PostProcessingPreferences() },
            { identity, text, trailing ->
                beforeSave()
                saved.save(identity.workId, text, trailing, identity.createdAt)
            },
            test.backgroundScope, { 0L }, { savedExists(it) }, { saved.acknowledgeAudioCleanup(it) }
        )
        val listener = object : ProcessingQueue.Listener {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                insertAttempts++
                if (editorSessionToken != currentEditor || !acceptsInsertion) return false
                inserted += text
                return true
            }

            override fun onQueueCountChanged(count: Int) {}
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) {}
            override fun onFailedCountChanged(count: Int) {}
            override fun onError(message: String) {}
        }

        init { queue.bindListener(listener) }

        fun item(name: String, order: Long = 1L) = ProcessingQueue.QueueItem(
            audioFile = File(dir, "$name.ogg").apply { writeText(name) },
            transcriptionConfig = TranscriptionConfig("synthetic-key", "https://test.invalid", "test", "en", ""),
            addTrailingSpace = true, singleWordStripPunctuation = false, vocabulary = "",
            ppPreferences = null, ppFix = false, ppShorten = false, ppEmoji = false,
            ppRhyme = false, ppTranslate = false, ppTerminal = false,
            editorSessionToken = currentEditor, workId = name, createdAt = order
        )
    }

    @Test fun `a result accepted by its original editor does not create a saved duplicate`() = runTest {
        val f = Fixture(this)
        val item = f.item("first")
        f.queue.enqueue(item); runCurrent()
        assertEquals(listOf("first"), f.inserted)
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertFalse(item.audioFile.exists())
        assertFalse(f.queue.hasCancellableWork)
    }

    @Test fun `a delayed result is saved when a different editor becomes active`() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        val item = f.item("for-editor-A")
        f.queue.enqueue(item); runCurrent()
        f.currentEditor = 2L
        answer.complete(Unit); runCurrent()
        assertTrue(f.inserted.isEmpty())
        assertEquals("for-editor-A", f.saved.transcripts.value.single().text)
        assertTrue(f.saved.transcripts.value.single().addTrailingSpace)
        assertFalse(item.audioFile.exists())
    }

    @Test fun `several results completed without a keyboard survive independently after restart`() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        val first = f.item("first", 1); val second = f.item("second", 2)
        f.queue.enqueue(first); f.queue.enqueue(second); runCurrent()
        f.queue.unbindListener(f.listener)
        answer.complete(Unit); runCurrent()
        assertTrue(f.inserted.isEmpty())
        assertEquals(listOf("first", "second"), f.saved.transcripts.value.map { it.text })
        val restarted = PendingTranscriptStore(File(f.dir, "saved"), f.dispatcher)
        restarted.ensureLoaded()
        assertEquals(listOf("first", "second"), restarted.transcripts.value.map { it.text })
        assertFalse(first.audioFile.exists()); assertFalse(second.audioFile.exists())
    }

    @Test fun `a visible editor that declines insertion saves the result`() = runTest {
        val f = Fixture(this)
        f.acceptsInsertion = false
        f.queue.enqueue(f.item("disconnected-editor")); runCurrent()
        assertEquals(1, f.insertAttempts)
        assertTrue(f.inserted.isEmpty())
        assertEquals("disconnected-editor", f.saved.transcripts.value.single().text)
    }

    @Test fun `a restored recording without a runtime editor session cannot insert automatically`() = runTest {
        val f = Fixture(this)
        val fresh = f.item("restored")
        val parked = f.store.park(fresh, fresh.audioFile, ParkedRecording.State.WAITING_NETWORK, 1)!!
        val restored = parked.toQueueItem("synthetic-key", "")
        assertNull(restored.editorSessionToken)
        f.queue.enqueue(restored); runCurrent()
        assertEquals(0, f.insertAttempts)
        assertTrue(f.inserted.isEmpty())
        assertEquals("restored", f.saved.transcripts.value.single().text)
        assertFalse(File(parked.audioPath).exists())
        assertEquals(0, f.store.count.value)
    }

    @Test fun `same-process retry retains its original editor instead of following the new one`() = runTest {
        val f = Fixture(this)
        f.online = false
        f.queue.enqueue(f.item("delayed-offline")); runCurrent()
        assertEquals(1, f.store.count.value)
        f.currentEditor = 2L
        f.online = true
        f.queue.retryFailed(); runCurrent()
        assertTrue(f.inserted.isEmpty())
        assertEquals("delayed-offline", f.saved.transcripts.value.single().text)
        assertEquals(0, f.store.count.value)
    }

    @Test fun `a finished result with leftover parked audio is recovered without another provider call`() = runTest {
        val f = Fixture(this)
        val original = f.item("already-finished")
        val parked = f.store.park(original, original.audioFile, ParkedRecording.State.WAITING_NETWORK, 1)!!
        val result = f.saved.save(original.workId, "The completed result", false, original.createdAt)

        // This is the on-disk state after a crash between save and source-audio cleanup.
        assertTrue(File(parked.audioPath).exists())
        f.queue.retryFailed(); runCurrent()

        assertTrue(f.requested.isEmpty())
        assertEquals(0, f.insertAttempts)
        assertTrue(f.inserted.isEmpty())
        assertEquals(listOf(result), f.saved.transcripts.value)
        assertFalse(File(parked.audioPath).exists())
        assertEquals(0, f.store.count.value)
        assertFalse(f.queue.hasCancellableWork)
    }

    @Test fun `offline startup cleans completed audio after its saved text was already consumed`() = runTest {
        val f = Fixture(this)
        val original = f.item("consumed-before-audio-cleanup")
        val parked = f.store.park(original, original.audioFile, ParkedRecording.State.WAITING_NETWORK, 1)!!
        f.saved.save(original.workId, "Already manually inserted", false, original.createdAt)
        assertTrue(f.saved.remove(original.workId))
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertTrue(f.saved.isCompleted(original.workId))
        assertTrue(File(parked.audioPath).exists())

        // A restart can be offline for hours. Consumption must not make this old capture eligible
        // for another request or recreate a deleted/inserted result when connectivity returns.
        f.online = false
        f.queue.bootstrap(); runCurrent()
        assertTrue(f.requested.isEmpty())
        assertTrue(f.inserted.isEmpty())
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertFalse(File(parked.audioPath).exists())
        assertEquals(0, f.store.count.value)
        assertFalse(f.saved.isCompleted(original.workId))

        f.online = true
        f.queue.retryFailed(); runCurrent()
        assertTrue(f.requested.isEmpty())
        assertTrue(f.saved.transcripts.value.isEmpty())
    }

    @Test fun `failed saved-result lookup keeps audio recoverable without a provider request`() = runTest {
        val f = Fixture(this)
        f.savedExists = { throw IOException("Synthetic saved-result lookup failure") }
        f.queue.enqueue(f.item("must-not-be-retranscribed")); runCurrent()

        assertTrue(f.requested.isEmpty())
        assertTrue(f.inserted.isEmpty())
        assertEquals(1, f.store.count.value)
        assertTrue(File(f.store.recordings.value.single().audioPath).exists())
        assertTrue(f.queue.hasCancellableWork)

        f.savedExists = { f.saved.isCompleted(it) }
        f.queue.retryFailed(); runCurrent()
        assertEquals(listOf("must-not-be-retranscribed"), f.inserted)
        assertEquals(0, f.store.count.value)
    }

    @Test fun `failed result persistence keeps durable audio and remains cancellable`() = runTest {
        val f = Fixture(this)
        f.acceptsInsertion = false
        f.beforeSave = { throw IOException("Synthetic disk write failure") }
        f.queue.enqueue(f.item("must-survive")); runCurrent()
        assertTrue(f.inserted.isEmpty())
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertEquals(1, f.store.count.value)
        assertTrue(File(f.store.recordings.value.single().audioPath).exists())
        assertTrue(f.queue.hasCancellableWork)
        assertTrue(f.queue.cancelLatest()); runCurrent()
        assertEquals(0, f.store.count.value)
        assertTrue(f.saved.transcripts.value.isEmpty())
    }

    @Test fun `a failed save can retry successfully without dropping or duplicating the result`() = runTest {
        val f = Fixture(this)
        f.acceptsInsertion = false
        f.beforeSave = { throw IOException("Synthetic disk write failure") }
        f.queue.enqueue(f.item("save-after-retry")); runCurrent()
        assertEquals(1, f.store.count.value)
        f.beforeSave = {}
        f.queue.retryFailed(); runCurrent()
        assertEquals(listOf("save-after-retry"), f.saved.transcripts.value.map { it.text })
        assertEquals(0, f.store.count.value)
        assertFalse(f.queue.hasCancellableWork)
    }

    @Test fun `cancellation before a delayed response saves nothing`() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.acceptsInsertion = false
        val item = f.item("cancelled")
        f.queue.enqueue(item); runCurrent()
        assertTrue(f.queue.cancelLatest())
        answer.complete(Unit); runCurrent()
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertTrue(f.inserted.isEmpty())
        assertFalse(item.audioFile.exists())
    }

    @Test fun `cancellation during the durable save transaction affects only later work`() = runTest {
        val f = Fixture(this)
        val saving = CompletableDeferred<Unit>()
        f.beforeSave = { saving.await() }
        f.acceptsInsertion = false
        val first = f.item("first", 1); val second = f.item("second", 2)
        f.queue.enqueue(first); f.queue.enqueue(second); runCurrent()
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertTrue(first.audioFile.exists())
        assertTrue(f.queue.cancelLatest()); runCurrent()
        assertFalse(second.audioFile.exists())
        assertFalse("The result is already committing to durable storage", f.queue.cancelLatest())
        saving.complete(Unit); runCurrent()
        assertEquals(listOf("first"), f.saved.transcripts.value.map { it.text })
        assertFalse(first.audioFile.exists())
        assertFalse(f.queue.hasCancellableWork)
        assertEquals(0, f.queue.pendingCount)
    }
}
