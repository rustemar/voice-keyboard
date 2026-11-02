package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessingQueueListenerSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    private open class TestListener : ProcessingQueue.Listener {
        override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long) = true
        override fun onQueueCountChanged(count: Int) {}
        override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) {}
        override fun onFailedCountChanged(count: Int) {}
        override fun onError(message: String) {}
    }

    private inner class Fixture(test: TestScope) {
        val directory = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val store = ParkedRecordingStore(File(directory, "parked"), dispatcher)
        val requested = mutableListOf<String>()
        val saved = mutableListOf<String>()
        var transcribe: suspend (String) -> Result<String> = { Result.success(it) }
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
            { _, _, _ -> error("Post-processing is disabled") },
            store, { true }, { "synthetic-key" }, { PostProcessingPreferences() },
            { _, text, _ -> saved += text }, test.backgroundScope, { 0L }
        )

        fun item(name: String) = ProcessingQueue.QueueItem(
            audioFile = File(directory, "$name.wav").apply { writeText(name) },
            transcriptionConfig = TranscriptionConfig("synthetic-key", "https://test.invalid", "test", "en", ""),
            addTrailingSpace = false, singleWordStripPunctuation = false, vocabulary = "",
            ppPreferences = null, ppFix = false, ppShorten = false, ppEmoji = false,
            ppRhyme = false, ppTranslate = false, ppTerminal = false,
            editorSessionToken = 1L, workId = name
        )
    }

    @Test fun `initial notification failures do not escape bind or prevent the next admission`() = runTest {
        for (failure in listOf("count", "failed", "cancellable")) {
            val f = Fixture(this)
            f.queue.bindListener(object : TestListener() {
                override fun onQueueCountChanged(count: Int) {
                    if (failure == "count") throw IllegalStateException("broken view")
                }
                override fun onFailedCountChanged(count: Int) {
                    if (failure == "failed") throw IllegalStateException("broken view")
                }
                override fun onCancellableWorkChanged(available: Boolean) {
                    if (failure == "cancellable") throw IllegalStateException("broken view")
                }
            })
            val item = f.item(failure)
            f.queue.enqueue(item)
            runCurrent()

            assertEquals(listOf(failure), f.requested)
            assertEquals(listOf(failure), f.saved)
            assertFalse(item.audioFile.exists())
            assertEquals(0, f.queue.pendingCount)
        }
    }

    @Test fun `count failure after queue admission still starts exactly one provider call`() = runTest {
        for (failOnPositiveCount in listOf(1, 2)) {
            val f = Fixture(this)
            var positiveCounts = 0
            f.queue.bindListener(object : TestListener() {
                override fun onQueueCountChanged(count: Int) {
                    if (count > 0 && ++positiveCounts == failOnPositiveCount) {
                        throw IllegalStateException("view disappeared during admission")
                    }
                }
            })
            val item = f.item("once-$failOnPositiveCount")
            f.queue.enqueue(item)
            runCurrent()

            assertEquals(listOf("once-$failOnPositiveCount"), f.requested)
            assertEquals(listOf("once-$failOnPositiveCount"), f.saved)
            assertEquals(0, f.queue.pendingCount)
            assertFalse(item.audioFile.exists())
        }
    }

    @Test fun `cancellable notification failure cannot interrupt an admitted recording`() = runTest {
        val f = Fixture(this)
        f.queue.bindListener(object : TestListener() {
            override fun onCancellableWorkChanged(available: Boolean) {
                if (available) throw IllegalArgumentException("stale button")
            }
        })
        f.queue.enqueue(f.item("cancellable-view-failure"))
        runCurrent()

        assertEquals(listOf("cancellable-view-failure"), f.requested)
        assertEquals(listOf("cancellable-view-failure"), f.saved)
        assertFalse(f.queue.hasCancellableWork)
    }

    @Test fun `phase notification failure falls back to saving without another transcription`() = runTest {
        val f = Fixture(this)
        f.queue.bindListener(object : TestListener() {
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) {
                throw IllegalStateException("destroyed status view")
            }
        })
        f.queue.enqueue(f.item("phase-view-failure"))
        runCurrent()

        assertEquals(listOf("phase-view-failure"), f.requested)
        assertEquals(listOf("phase-view-failure"), f.saved)
        assertEquals(0, f.queue.pendingCount)
    }

    @Test fun `text callback exception keeps the completed result through saved fallback`() = runTest {
        val f = Fixture(this)
        var deliveries = 0
        f.queue.bindListener(object : TestListener() {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                deliveries++
                throw IllegalStateException("editor disconnected while inserting")
            }
        })
        val item = f.item("must-not-be-deleted-without-result")
        f.queue.enqueue(item)
        runCurrent()

        assertEquals(1, deliveries)
        assertEquals(listOf("must-not-be-deleted-without-result"), f.requested)
        assertEquals(listOf("must-not-be-deleted-without-result"), f.saved)
        assertFalse(item.audioFile.exists())
        assertEquals(0, f.queue.pendingCount)
    }

    @Test fun `listener replacing itself before throwing cannot unbind the replacement`() = runTest {
        val f = Fixture(this)
        val inserted = mutableListOf<String>()
        val replacement = object : TestListener() {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                inserted += text
                return true
            }
        }
        f.queue.bindListener(object : TestListener() {
            override fun onQueueCountChanged(count: Int) {
                if (count > 0) {
                    f.queue.bindListener(replacement)
                    throw IllegalStateException("old view failed after replacement")
                }
            }
        })
        f.queue.enqueue(f.item("replacement-retained"))
        runCurrent()

        assertEquals(listOf("replacement-retained"), f.requested)
        assertEquals(listOf("replacement-retained"), inserted)
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `initial phase failure cannot strand an already active request`() = runTest {
        val f = Fixture(this)
        val answer = CompletableDeferred<Unit>()
        f.transcribe = { answer.await(); Result.success(it) }
        f.queue.enqueue(f.item("active-while-view-binds"))
        runCurrent()
        f.queue.bindListener(object : TestListener() {
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) {
                throw IllegalStateException("initial phase cannot render")
            }
        })
        answer.complete(Unit)
        runCurrent()

        assertEquals(listOf("active-while-view-binds"), f.requested)
        assertEquals(listOf("active-while-view-binds"), f.saved)
    }

    @Test fun `failed-count observer survives a broken view and keeps notifying a replacement`() = runTest {
        val f = Fixture(this)
        f.transcribe = { Result.failure(IllegalArgumentException("API error 400: rejected")) }
        var failures = 0
        f.queue.bindListener(object : TestListener() {
            override fun onFailedCountChanged(count: Int) {
                if (count > 0) {
                    failures++
                    throw IllegalStateException("failed-count badge disappeared")
                }
            }
        })
        f.queue.enqueue(f.item("first-parked"))
        runCurrent()
        val observedCounts = mutableListOf<Int>()
        f.queue.bindListener(object : TestListener() {
            override fun onFailedCountChanged(count: Int) { observedCounts += count }
        })
        f.queue.enqueue(f.item("second-parked"))
        runCurrent()

        assertEquals(1, failures)
        assertEquals(listOf("first-parked", "second-parked"), f.requested)
        assertEquals(2, f.store.count.value)
        assertTrue(observedCounts.contains(2))
        assertTrue(f.store.recordings.value.all { File(it.audioPath).exists() })
    }

    @Test fun `replacement during failed delivery keeps this result saved and handles later work`() = runTest {
        val f = Fixture(this)
        val inserted = mutableListOf<String>()
        val replacement = object : TestListener() {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                inserted += text
                return true
            }
        }
        f.queue.bindListener(object : TestListener() {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                f.queue.bindListener(replacement)
                throw IllegalStateException("old editor failed after replacement")
            }
        })
        f.queue.enqueue(f.item("first-saved"))
        runCurrent()
        f.queue.enqueue(f.item("second-inserted"))
        runCurrent()

        assertEquals(listOf("first-saved", "second-inserted"), f.requested)
        assertEquals(listOf("first-saved"), f.saved)
        assertEquals(listOf("second-inserted"), inserted)
    }

    @Test fun `error notification failure still parks the source for attention`() = runTest {
        val f = Fixture(this)
        f.transcribe = { Result.failure(IllegalArgumentException("API error 400: rejected")) }
        f.queue.bindListener(object : TestListener() {
            override fun onError(message: String) { throw IllegalStateException("error view destroyed") }
        })
        f.queue.enqueue(f.item("park-after-view-failure"))
        runCurrent()

        assertEquals(listOf("park-after-view-failure"), f.requested)
        assertEquals(1, f.store.count.value)
        assertTrue(File(f.store.recordings.value.single().audioPath).exists())
        assertTrue(f.saved.isEmpty())
    }

    @Test fun `cancellation from an initial callback is not swallowed as a view failure`() = runTest {
        val f = Fixture(this)
        val cancellation = CancellationException("cancel binding")
        try {
            f.queue.bindListener(object : TestListener() {
                override fun onQueueCountChanged(count: Int) { throw cancellation }
            })
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun `cancellation from delivery does not become a successful saved fallback`() = runTest {
        val f = Fixture(this)
        f.queue.bindListener(object : TestListener() {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                throw CancellationException("cancel delivery")
            }
        })
        val item = f.item("cancelled-delivery")
        f.queue.enqueue(item)
        runCurrent()

        assertEquals(listOf("cancelled-delivery"), f.requested)
        assertTrue(f.saved.isEmpty())
        assertFalse(item.audioFile.exists())
        assertEquals(0, f.queue.pendingCount)
    }
}
