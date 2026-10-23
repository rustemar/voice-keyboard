package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.postprocessing.PromptParts
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessingQueueCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runAll(): Boolean {
            if (pending.isEmpty()) return false
            while (pending.isNotEmpty()) pending.removeFirst().run()
            return true
        }
    }

    private inner class Fixture(val test: TestScope, storeDispatcher: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler)) {
        val dir = temporary.newFolder()
        val store = ParkedRecordingStore(File(dir, "parked"), storeDispatcher)
        var online = true
        var prefs = PostProcessingPreferences(enabled = true, apiKey = "pp-test", contextEnabled = true)
        val delivered = ArrayList<String>()
        val clipboard = ArrayList<String>()
        val requested = ArrayList<String>()
        val phases = ArrayList<ProcessingQueue.ProcessingPhase>()
        val ppRequests = ArrayList<PromptParts>()
        var transcribe: suspend (String) -> Result<String> = { Result.success(it) }
        var postProcess: suspend (PromptParts) -> Result<String> = { Result.success(it.userText) }
        var apiKey: suspend () -> String = { "stt-test" }
        val queue = ProcessingQueue(
            object : SpeechToTextClient {
                override suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String> {
                    val name = audioFile.readText()
                    requested += name
                    return transcribe(name)
                }
                override suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File) = Result.success("OK")
            },
            { prompt, _, _ -> ppRequests += prompt; postProcess(prompt) },
            store, { online }, { apiKey() }, { prefs },
            { text, _ -> clipboard += text }, test.backgroundScope, { 0L }
        )
        val listener = object : ProcessingQueue.Listener {
            override fun onTextReady(text: String, addTrailingSpace: Boolean) { delivered += text }
            override fun onQueueCountChanged(count: Int) {}
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) { phases += phase }
            override fun onFailedCountChanged(count: Int) {}
            override fun onError(message: String) {}
        }
        init { queue.bindListener(listener); queue.setPostProcessingContextScope("test.editor") }

        fun item(name: String, order: Long, mode: String = ""): ProcessingQueue.QueueItem = ProcessingQueue.QueueItem(
            audioFile = File(dir, "$name.ogg").apply { writeText(name) },
            transcriptionConfig = TranscriptionConfig("stt-test", "https://test.invalid", "test", "en", ""),
            addTrailingSpace = false, singleWordStripPunctuation = false, vocabulary = "",
            ppPreferences = if (mode.isEmpty()) null else prefs,
            ppFix = mode == "fix" || mode == "all", ppShorten = mode == "shorten", ppEmoji = mode == "emoji",
            ppRhyme = mode == "rhyme" || mode == "all", ppTranslate = mode == "translate" || mode == "all", ppTerminal = mode == "terminal",
            contextToken = queue.capturePostProcessingContextToken(), workId = name, createdAt = order
        )
    }

    @Test fun `queued C cancellation preserves A B order and deletes only C`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        f.transcribe = { if (it == "A") release.await(); Result.success(it) }
        val a = f.item("A", 1); val b = f.item("B", 2); val c = f.item("C", 3)
        f.queue.enqueue(a); f.queue.enqueue(b); f.queue.enqueue(c)
        runCurrent()
        assertTrue(f.queue.cancelLatest())
        runCurrent()
        assertFalse(c.audioFile.exists())
        assertTrue(a.audioFile.exists()); assertTrue(b.audioFile.exists())
        assertEquals(2, f.queue.pendingCount)
        release.complete(Unit); runCurrent()
        assertEquals(listOf("A", "B"), f.delivered)
        assertEquals(listOf("A", "B"), f.requested)
        assertFalse(f.queue.hasCancellableWork)
        assertEquals(0, f.queue.pendingCount)
    }

    @Test fun `cancel before dispatched processing starts does not strand the queue`() = runTest {
        val f = Fixture(this)
        val a = f.item("A", 1)
        f.queue.enqueue(a)
        assertTrue(f.queue.cancelLatest())
        f.queue.enqueue(f.item("B", 2))
        runCurrent()
        assertEquals(listOf("B"), f.delivered)
        assertEquals(listOf("B"), f.requested)
        assertFalse(a.audioFile.exists())
        assertEquals(0, f.queue.pendingCount)
    }

    @Test fun `STT cancellation suppresses even a noncooperative late success and clipboard`() = runTest {
        val f = Fixture(this)
        val late = CompletableDeferred<Unit>()
        f.transcribe = { withContext(NonCancellable) { late.await() }; Result.success("late C") }
        val c = f.item("C", 3)
        f.queue.enqueue(c); runCurrent()
        f.queue.unbindListener(f.listener)
        assertTrue(f.queue.cancelLatest())
        late.complete(Unit); runCurrent()
        assertTrue(f.delivered.isEmpty()); assertTrue(f.clipboard.isEmpty())
        assertFalse(c.audioFile.exists())
        assertEquals(0, f.store.count.value)
    }

    @Test fun `every PP mode cancels without raw fallback later stages or context append`() = runTest {
        for (mode in listOf("fix", "shorten", "emoji", "rhyme", "translate", "terminal", "all")) {
            val f = Fixture(this)
            val late = CompletableDeferred<Unit>()
            f.postProcess = { withContext(NonCancellable) { late.await() }; Result.success("cancelled secret") }
            val c = f.item("C", 3, mode)
            f.queue.enqueue(c); runCurrent()
            assertEquals(ProcessingQueue.ProcessingPhase.POST_PROCESSING, f.phases.last())
            assertTrue(f.queue.cancelLatest())
            late.complete(Unit); runCurrent()
            assertTrue("delivered cancelled $mode", f.delivered.isEmpty())
            assertEquals("ran later PP stage for $mode", 1, f.ppRequests.size)
            assertEquals(0, f.store.count.value)
            f.postProcess = { Result.success("D") }
            f.queue.enqueue(f.item("D", 4, "fix")); runCurrent()
            assertFalse("remembered cancelled $mode", f.ppRequests.last().userText.contains("cancelled secret"))
            assertFalse(c.audioFile.exists())
        }
    }

    @Test fun `PP cancellation represented as Result failure is never a raw transcript success`() = runTest {
        val f = Fixture(this)
        f.postProcess = { Result.failure(CancellationException("cancelled")) }
        val c = f.item("C", 3, "all")
        f.queue.enqueue(c); runCurrent()
        assertTrue(f.delivered.isEmpty())
        assertEquals(1, f.ppRequests.size)
        assertEquals(0, f.store.count.value)
        assertFalse(c.audioFile.exists())
    }

    @Test fun `offline C cancellation deletes its sidecar and survives store restart`() = runTest {
        val f = Fixture(this)
        f.online = false
        for ((index, name) in listOf("A", "B", "C").withIndex()) {
            f.queue.enqueue(f.item(name, index.toLong())); runCurrent()
        }
        assertEquals(3, f.store.count.value)
        assertTrue(f.queue.cancelLatest()); runCurrent()
        assertEquals(listOf("A", "B"), f.store.recordings.value.map { it.workId })
        val restored = ParkedRecordingStore(File(f.dir, "parked"), StandardTestDispatcher(testScheduler))
        restored.ensureLoaded()
        assertEquals(listOf("A", "B"), restored.recordings.value.map { it.workId })
        f.online = true; f.queue.retryFailed(); runCurrent()
        assertEquals(listOf("A", "B"), f.delivered)
        assertEquals(0, f.store.count.value)
    }

    @Test fun `cancel during park completion cannot leave resurrected durable audio`() = runTest {
        val parkedDispatcher = PausedDispatcher()
        val f = Fixture(this, parkedDispatcher)
        f.online = false
        val c = f.item("C", 3)
        f.queue.enqueue(c); runCurrent()
        // The store operation writes the sidecar/audio, but the caller has not resumed on Main.
        assertTrue(parkedDispatcher.runAll())
        assertEquals(1, f.store.count.value)
        assertTrue(f.queue.cancelLatest())
        repeat(5) { runCurrent(); parkedDispatcher.runAll() }
        runCurrent()
        assertEquals(0, f.store.count.value)
        assertFalse(c.audioFile.exists())
        assertTrue(File(f.dir, "parked").listFiles()!!.isEmpty())
        assertFalse(f.queue.hasCancellableWork)
    }

    @Test fun `cancel between retry claim and preferences blocks stale requeue`() = runTest {
        val f = Fixture(this)
        f.online = false
        f.queue.enqueue(f.item("A", 1)); f.queue.enqueue(f.item("C", 3)); runCurrent()
        val credentials = CompletableDeferred<Unit>()
        f.apiKey = { credentials.await(); "stt-test" }
        f.online = true; f.queue.retryFailed(); runCurrent()
        assertTrue(f.queue.cancelLatest()); runCurrent()
        credentials.complete(Unit); runCurrent()
        assertEquals(listOf("A"), f.delivered)
        assertEquals(listOf("A"), f.requested)
        assertEquals(0, f.store.count.value)
    }

    @Test fun `retry keeps original order so older retried A is not cancelled ahead of newer B`() = runTest {
        val f = Fixture(this)
        f.online = false
        f.queue.enqueue(f.item("A", 1)); runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.apiKey = { gate.await(); "stt-test" }
        f.online = true; f.queue.retryFailed(); runCurrent()
        val b = f.queue.reserveDictation {}
        assertTrue(b.createdAt > 1)
        assertTrue(f.queue.cancelLatest())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf("A"), f.delivered)
        assertFalse(f.queue.hasCancellableWork)
    }

    @Test fun `reservation survives listener rebuild and rejects delayed canceled enqueue`() = runTest {
        val f = Fixture(this)
        var capturesAborted = 0
        val reserved = f.queue.reserveDictation { capturesAborted++ }
        assertEquals(0, f.queue.pendingCount)
        val c = f.item("C", reserved.createdAt).copy(workId = reserved.workId)
        assertTrue(f.queue.updateReservation(reserved) { c.audioFile.delete(); capturesAborted++ })
        assertEquals(1, f.queue.pendingCount)
        var cancellable = false
        val rebound = object : ProcessingQueue.Listener by f.listener {
            override fun onCancellableWorkChanged(available: Boolean) { cancellable = available }
        }
        f.queue.unbindListener(f.listener); f.queue.bindListener(rebound)
        assertTrue(cancellable)
        assertTrue(f.queue.cancelLatest())
        assertFalse(f.queue.cancelLatest())
        assertEquals(1, capturesAborted)
        f.queue.enqueue(c); runCurrent()
        assertTrue(f.requested.isEmpty()); assertTrue(f.delivered.isEmpty())
        assertFalse(c.audioFile.exists())
        assertEquals(0, f.queue.pendingCount)
        assertFalse(cancellable)
    }

    @Test fun `repeated cancellation chooses successive unfinished work and cannot undo delivered text`() = runTest {
        val f = Fixture(this)
        val wait = CompletableDeferred<Unit>()
        f.transcribe = { wait.await(); Result.success(it) }
        f.queue.enqueue(f.item("A", 1)); f.queue.enqueue(f.item("B", 2)); f.queue.enqueue(f.item("C", 3)); runCurrent()
        assertTrue(f.queue.cancelLatest()); assertTrue(f.queue.cancelLatest()); runCurrent()
        wait.complete(Unit); runCurrent()
        assertEquals(listOf("A"), f.delivered)
        assertFalse(f.queue.cancelLatest())
        assertEquals(listOf("A"), f.delivered)
    }

    @Test fun `late cancellation at delivery boundary applies only to next eligible item`() = runTest {
        val f = Fixture(this)
        val b = f.item("B", 2)
        val deliveryCanceller = object : ProcessingQueue.Listener by f.listener {
            override fun onTextReady(text: String, addTrailingSpace: Boolean) {
                f.delivered += text
                assertTrue(f.queue.cancelLatest())
            }
        }
        f.queue.bindListener(deliveryCanceller)
        f.queue.enqueue(f.item("A", 1)); f.queue.enqueue(b); runCurrent()
        assertEquals(listOf("A"), f.delivered)
        assertFalse(b.audioFile.exists())
        assertFalse(f.queue.hasCancellableWork)
    }
    @Test fun `cancelled sidecar is never restored even when audio deletion was interrupted`() = runTest {
        val f = Fixture(this)
        val c = f.item("C", 3)
        val rec = ParkedRecording.fromQueueItem(c, "parked_123_0", c.audioFile.path, ParkedRecording.State.CANCELLED, 1)
        File(f.dir, "parked/${rec.id}.json").writeText(rec.toJson())
        f.store.ensureLoaded()
        assertEquals(0, f.store.count.value)
        assertFalse(c.audioFile.exists())
        assertTrue(File(f.dir, "parked").listFiles()!!.isEmpty())
    }

    @Test fun `store cancellation before park blocks late mutation with same capture identity`() = runTest {
        val f = Fixture(this)
        val c = f.item("C", 3)
        f.store.cancel(c.workId)
        assertNull(f.store.park(c, c.audioFile, ParkedRecording.State.WAITING_NETWORK, 1))
        assertFalse(c.audioFile.exists())
        assertTrue(f.store.recordings.value.isEmpty())
        assertTrue(File(f.dir, "parked").listFiles()!!.isEmpty())
    }

    @Test fun `cancelling any stage of combined PP preserves previously remembered context`() = runTest {
        for (cancelStage in 1..3) {
            val f = Fixture(this)
            f.postProcess = { Result.success("remembered A") }
            f.queue.enqueue(f.item("A", 1, "fix")); runCurrent()
            assertEquals(listOf("remembered A"), f.delivered)
            var stage = 0
            val late = CompletableDeferred<Unit>()
            f.postProcess = {
                stage++
                if (stage == cancelStage) withContext(NonCancellable) { late.await() }
                Result.success("cancelled C stage $stage")
            }
            f.queue.enqueue(f.item("C", 3, "all")); runCurrent()
            assertEquals(cancelStage, stage)
            assertTrue(f.ppRequests.last().userText.contains("remembered A"))
            assertTrue(f.queue.cancelLatest())
            late.complete(Unit); runCurrent()
            assertEquals("ran a subsequent stage", cancelStage, stage)
            assertEquals(listOf("remembered A"), f.delivered)
            f.postProcess = { Result.success("D") }
            f.queue.enqueue(f.item("D", 4, "fix")); runCurrent()
            val contextPrompt = f.ppRequests.last().userText
            assertTrue("earlier context was erased", contextPrompt.contains("remembered A"))
            assertFalse("cancelled result entered context", contextPrompt.contains("cancelled C"))
        }
    }

    @Test fun `STT Result cancellation never retries parks or delivers and deletes its owned audio`() = runTest {
        val f = Fixture(this)
        f.transcribe = { Result.failure(CancellationException("cancelled")) }
        val c = f.item("C", 3)
        f.queue.enqueue(c); runCurrent()
        assertEquals(listOf("C"), f.requested)
        assertTrue(f.delivered.isEmpty()); assertTrue(f.clipboard.isEmpty())
        assertEquals(0, f.store.count.value)
        assertFalse(c.audioFile.exists())
    }

    @Test fun `rebinding immediately publishes the active postprocessing phase`() = runTest {
        val f = Fixture(this)
        val wait = CompletableDeferred<Unit>()
        f.postProcess = { wait.await(); Result.success("C") }
        f.queue.enqueue(f.item("C", 3, "fix")); runCurrent()
        val observed = ArrayList<ProcessingQueue.ProcessingPhase>()
        val rebuilt = object : ProcessingQueue.Listener by f.listener {
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) { observed += phase }
        }
        f.queue.unbindListener(f.listener)
        f.queue.bindListener(rebuilt)
        assertEquals(listOf(ProcessingQueue.ProcessingPhase.POST_PROCESSING), observed)
        assertTrue(f.queue.cancelLatest()); runCurrent()
    }

}
