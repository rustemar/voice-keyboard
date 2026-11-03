package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessingQueueAutoSendTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture(val test: TestScope) {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val store = ParkedRecordingStore(File(dir, "parked"), dispatcher)
        val policy = AutoSendPolicy()
        val inserted = ArrayList<String>()
        val saved = ArrayList<String>()
        var online = true
        var acceptsInsertion = true
        var canInsert: (String) -> Boolean = { acceptsInsertion }
        var sendAttempts = 0
        var transcribe: suspend (String) -> Result<String> = { Result.success(it) }
        var postProcess: suspend () -> Result<String> = { Result.success("Final processed text") }
        var send: () -> Boolean = { true }
        val pp = PostProcessingPreferences(enabled = true, apiKey = "pp-test")
        val queue = ProcessingQueue(
            object : SpeechToTextClient {
                override suspend fun transcribe(audioFile: File, config: TranscriptionConfig) =
                    transcribe(audioFile.readText())
                override suspend fun validateCredentials(
                    apiKey: String, endpoint: String, model: String, cacheDir: File
                ) = Result.success("OK")
            },
            { _, _, _ -> postProcess() },
            store, { online }, { "speech-test" }, { pp },
            { _, text, _ -> saved += text }, test.backgroundScope, { 0L }
        )
        val listener = object : ProcessingQueue.Listener {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long) =
                onTextReady(text, addTrailingSpace, editorSessionToken, null)

            override fun onTextReady(
                text: String, addTrailingSpace: Boolean, editorSessionToken: Long,
                autoSendRequest: AutoSendPolicy.Request?
            ): Boolean {
                if (!canInsert(text)) return false
                inserted += text
                policy.dispatchOnce(autoSendRequest) { sendAttempts++; send() }
                return true // A send failure must not undo an acknowledged insertion.
            }

            override fun onQueueCountChanged(count: Int) {}
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) {}
            override fun onFailedCountChanged(count: Int) {}
            override fun onError(message: String) {}
            override fun onAutoSendInvalidated() { policy.invalidate() }
        }

        init { queue.bindListener(listener) }

        suspend fun load() { store.ensureLoaded() }

        fun item(name: String, auto: Boolean = true, processing: Boolean = false) = ProcessingQueue.QueueItem(
            audioFile = File(dir, "$name.ogg").apply { writeText(name) },
            transcriptionConfig = TranscriptionConfig("speech-test", "https://test.invalid", "test", "en", ""),
            addTrailingSpace = false, singleWordStripPunctuation = false, vocabulary = "",
            ppPreferences = if (processing) pp else null,
            ppFix = processing, ppShorten = false, ppEmoji = false, ppRhyme = false,
            ppTranslate = false, ppTerminal = false, editorSessionToken = 1L,
            autoSendRequest = if (auto) policy.prepare(true) else null,
            workId = name, createdAt = 1L
        )
    }

    @Test fun `auto-send waits for final processing and a successful insertion`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val finalText = CompletableDeferred<Unit>()
        f.postProcess = { finalText.await(); Result.success("The final corrected text") }
        f.queue.enqueue(f.item("Original text", processing = true)); runCurrent()
        assertTrue(f.inserted.isEmpty())
        assertEquals(0, f.sendAttempts)
        finalText.complete(Unit); runCurrent()
        assertEquals(listOf("The final corrected text"), f.inserted)
        assertEquals(1, f.sendAttempts)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `manual dictation has no automatic send intent`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        f.queue.enqueue(f.item("Manual stop", auto = false)); runCurrent()
        assertEquals(listOf("Manual stop"), f.inserted)
        assertEquals(0, f.sendAttempts)
    }

    @Test fun `turning timer off while processing prevents send even after enabling again`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.queue.enqueue(f.item("Pending text")); runCurrent()
        f.policy.invalidate() // OFF, followed by ON without another capture.
        answer.complete(Unit); runCurrent()
        assertEquals(listOf("Pending text"), f.inserted)
        assertEquals(0, f.sendAttempts)
    }

    @Test fun `a new recording permanently revokes older send even when new recording is cancelled`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.queue.enqueue(f.item("Older dictation")); runCurrent()
        assertEquals(1, f.queue.pendingCount)
        val reservation = f.queue.reserveDictation {}
        // The visible counter excludes this recording reservation; automatic sending must not.
        assertEquals(1, f.queue.pendingCount)
        assertFalse(f.queue.isIsolatedAutoSendWork("Older dictation"))
        f.queue.releaseReservation(reservation)
        answer.complete(Unit); runCurrent()
        assertEquals(listOf("Older dictation"), f.inserted)
        assertEquals(0, f.sendAttempts)
    }

    @Test fun `later queued work prevents sending an incomplete draft`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.queue.enqueue(f.item("First")); runCurrent()
        f.queue.enqueue(f.item("Second", auto = false)); runCurrent()
        answer.complete(Unit); runCurrent()
        assertEquals(listOf("First", "Second"), f.inserted)
        assertEquals(0, f.sendAttempts)
    }

    @Test fun `latest silence capture sends once after older queued text has been inserted`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.queue.enqueue(f.item("First chunk")); runCurrent()
        // Capture preparation precedes reservation, just like InputOrchestrator.beginCapture.
        // Reserving the new capture cancels older queued intents, never the fresh request.
        val latest = f.item("Latest chunk")
        val reservation = f.queue.reserveDictation {}
        f.queue.enqueue(latest.copy(workId = reservation.workId, createdAt = reservation.createdAt))
        runCurrent()
        assertTrue(latest.autoSendRequest!!.isPending)
        assertEquals(0, f.sendAttempts)
        answer.complete(Unit); runCurrent()
        assertEquals(listOf("First chunk", "Latest chunk"), f.inserted)
        assertEquals(1, f.sendAttempts)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `older manual processing failure revokes latest queued composed draft send`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.postProcess = { answer.await(); Result.failure(IOException("Older chunk processing failed")) }
        f.queue.enqueue(f.item("Older manually stopped text", auto = false, processing = true)); runCurrent()
        val latest = f.item("Newest silence stopped text")
        f.queue.enqueue(latest); runCurrent()
        answer.complete(Unit); runCurrent()
        assertEquals(listOf("Older manually stopped text", "Newest silence stopped text"), f.inserted)
        assertEquals(0, f.sendAttempts)
        assertTrue(f.saved.isEmpty())
        assertFalse(latest.autoSendRequest!!.isPending)
    }

    @Test fun `older transcription retry revokes newer capture intent before it is enqueued`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        var attempts = 0
        f.transcribe = { text ->
            if (text == "Older manual chunk" && ++attempts == 1) {
                answer.await()
                Result.failure(IOException("network timeout"))
            } else Result.success(text)
        }
        f.queue.enqueue(f.item("Older manual chunk", auto = false)); runCurrent()
        val latest = f.item("Newer capture")
        val reservation = f.queue.reserveDictation {}
        answer.complete(Unit); runCurrent()
        // This request exists only in the recording host, not in the processing queue yet.
        assertFalse(latest.autoSendRequest!!.isPending)
        advanceTimeBy(1_000); runCurrent()
        f.queue.enqueue(latest.copy(workId = reservation.workId, createdAt = reservation.createdAt)); runCurrent()
        assertEquals(listOf("Older manual chunk", "Newer capture"), f.inserted)
        assertEquals(0, f.sendAttempts)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `capture started after an older error is revoked when recovered text arrives`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        var attempts = 0
        f.transcribe = { text ->
            if (text == "Recovered older text" && ++attempts == 1) Result.failure(IOException("network timeout"))
            else Result.success(text)
        }
        f.queue.enqueue(f.item("Recovered older text", auto = false)); runCurrent()
        // A's first error happened before B existed. A is still in its immediate retry backoff.
        val latest = f.item("Newer text")
        f.queue.enqueue(latest); runCurrent()
        assertTrue(latest.autoSendRequest!!.isPending)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("Recovered older text", "Newer text"), f.inserted)
        assertEquals(0, f.sendAttempts)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `saving an older rejected chunk prevents newer draft from sending without it`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.canInsert = { it != "Rejected older text" }
        f.queue.enqueue(f.item("Rejected older text", auto = false)); runCurrent()
        f.queue.enqueue(f.item("Accepted newer text")); runCurrent()
        answer.complete(Unit); runCurrent()
        assertEquals(listOf("Rejected older text"), f.saved)
        assertEquals(listOf("Accepted newer text"), f.inserted)
        assertEquals(0, f.sendAttempts)
    }

    @Test fun `failed older handover revokes newer capture before releasing its reservation`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val olderHandover = f.queue.reserveDictation {}
        assertTrue(f.queue.updateReservation(olderHandover) {})
        val latest = f.item("Newer usable capture")
        val newerCapture = f.queue.reserveDictation {}
        // InputOrchestrator reports failed finalization/encoding before releasing older work.
        f.queue.invalidatePendingAutoSend()
        f.queue.releaseReservation(olderHandover)
        f.queue.enqueue(latest.copy(workId = newerCapture.workId, createdAt = newerCapture.createdAt)); runCurrent()
        assertEquals(listOf("Newer usable capture"), f.inserted)
        assertEquals(0, f.sendAttempts)
        assertTrue(f.saved.isEmpty())
        assertFalse(latest.autoSendRequest!!.isPending)
    }

    @Test fun `an immediate transcription retry keeps text but revokes send`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        var attempts = 0
        f.transcribe = { if (++attempts == 1) Result.failure(IOException("network timeout")) else Result.success(it) }
        f.queue.enqueue(f.item("Recovered text")); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("Recovered text"), f.inserted)
        assertEquals(0, f.sendAttempts)
    }

    @Test fun `failed post-processing and empty fallback insert without sending`() = runTest {
        for (empty in listOf(false, true)) {
            val f = Fixture(this); f.load(); runCurrent()
            f.postProcess = { if (empty) Result.success("") else Result.failure(IOException("Synthetic PP failure")) }
            val text = "A real sentence long enough to preserve after an empty processing result"
            f.queue.enqueue(f.item(text, processing = true)); runCurrent()
            assertEquals(listOf(text), f.inserted)
            assertEquals(0, f.sendAttempts)
        }
    }

    @Test fun `same-process resend inserts recovered text without sending`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        f.online = false
        f.queue.enqueue(f.item("Offline dictation")); runCurrent()
        assertEquals(1, f.store.count.value)
        f.online = true
        f.queue.retryFailed(); runCurrent()
        assertEquals(listOf("Offline dictation"), f.inserted)
        assertEquals(0, f.sendAttempts)
        assertEquals(0, f.store.count.value)
    }

    @Test fun `editor rejection saves text without sending or retaining intent`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        f.acceptsInsertion = false
        val item = f.item("For the original editor")
        f.queue.enqueue(item); runCurrent()
        assertEquals(listOf("For the original editor"), f.saved)
        assertEquals(0, f.sendAttempts)
        assertFalse(item.autoSendRequest!!.isPending)
    }

    @Test fun `rejected and throwing sends preserve insertion with no saved duplicate`() = runTest {
        for (throws in listOf(false, true)) {
            val f = Fixture(this); f.load(); runCurrent()
            f.send = { if (throws) throw IllegalStateException("Host unavailable") else false }
            val item = f.item("Keep this inserted text")
            f.queue.enqueue(item); runCurrent()
            assertEquals(listOf("Keep this inserted text"), f.inserted)
            assertEquals(1, f.sendAttempts)
            assertTrue(f.saved.isEmpty())
            assertFalse(item.autoSendRequest!!.isPending)
            assertFalse(item.audioFile.exists())
        }
    }

    @Test fun `cancelled processing delivers and sends nothing`() = runTest {
        val f = Fixture(this); f.load(); runCurrent()
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.queue.enqueue(f.item("Cancelled dictation")); runCurrent()
        assertTrue(f.queue.cancelLatest())
        answer.complete(Unit); runCurrent()
        assertTrue(f.inserted.isEmpty())
        assertTrue(f.saved.isEmpty())
        assertEquals(0, f.sendAttempts)
    }
}
