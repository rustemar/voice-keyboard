package com.tyraen.voicekeyboard.feature.ime

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PendingTranscriptStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `separate completed dictations survive store recreation in capture order`() = runTest {
        val dir = temporary.newFolder()
        val store = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        val newer = store.save("capture-b", "Second\nparagraph", true, 20L)
        val older = store.save("capture-a", "Первый результат 👋", false, 10L)
        assertEquals(listOf(older, newer), store.transcripts.value)

        val restored = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        restored.ensureLoaded()
        assertEquals(listOf(older, newer), restored.transcripts.value)
        assertEquals(2, jsonFiles(dir).size)
    }

    @Test fun `repeated save of the same capture keeps first result and does not duplicate it`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        val original = store.save("capture", "Original result", false, 7L)
        assertEquals(original, store.save("capture", "Different provider retry", true, 99L))

        val restored = PendingTranscriptStore(dir, dispatcher)
        restored.ensureLoaded()
        assertEquals(original, restored.save("capture", "Another retry", true, 100L))
        assertEquals(listOf(original), restored.transcripts.value)
        assertEquals(1, jsonFiles(dir).size)
    }

    @Test fun `explicit deletion survives restart and leaves other results intact`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        store.save("first", "First", false, 1L)
        val second = store.save("second", "Second", true, 2L)
        assertTrue(store.remove("first"))
        assertFalse(store.remove("first"))

        val restored = PendingTranscriptStore(dir, dispatcher)
        restored.ensureLoaded()
        assertEquals(listOf(second), restored.transcripts.value)
    }

    @Test fun `capture lookup restores persisted results and reflects explicit consumption`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        assertFalse(store.contains("capture"))
        store.save("capture", "Already finished before process death", false, 1L)
        store.save("other", "Keep this result", false, 2L)
        assertTrue(store.contains("capture"))

        // contains performs loading itself; retries must not depend on the UI loading first.
        val restored = PendingTranscriptStore(dir, dispatcher)
        assertTrue(restored.contains("capture"))
        assertTrue(restored.remove("capture"))
        assertFalse(restored.contains("capture"))

        val afterConsumption = PendingTranscriptStore(dir, dispatcher)
        assertFalse(afterConsumption.contains("capture"))
        assertTrue(afterConsumption.contains("other"))
    }

    @Test fun `capture lookup propagates a storage loading failure`() = runTest {
        val blockedDirectory = temporary.newFile()
        val store = PendingTranscriptStore(blockedDirectory, StandardTestDispatcher(testScheduler))
        expectIOException { store.contains("capture") }
        assertTrue(store.transcripts.value.isEmpty())
    }

    @Test fun `consumed text keeps only a completion marker until original audio cleanup`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        store.save("capture", "Private finished dictation", false, 1L)
        assertTrue(store.isCompleted("capture"))
        assertTrue(store.remove("capture"))
        assertFalse(store.contains("capture"))
        assertTrue(jsonFiles(dir).isEmpty())
        val marker = dir.listFiles()!!.single()
        assertEquals("done", marker.extension)
        assertEquals("capture", marker.readText())

        val restored = PendingTranscriptStore(dir, dispatcher)
        assertTrue(restored.isCompleted("capture"))
        assertTrue(restored.transcripts.value.isEmpty())
        restored.save("capture", "A retry must not resurrect consumed text", true, 2L)
        assertTrue(restored.transcripts.value.isEmpty())
        assertTrue(jsonFiles(dir).isEmpty())

        restored.acknowledgeAudioCleanup("capture")
        assertFalse(restored.isCompleted("capture"))
        assertTrue(dir.listFiles()!!.isEmpty())
        assertFalse(PendingTranscriptStore(dir, dispatcher).isCompleted("capture"))
    }

    @Test fun `audio acknowledgement before consumption survives restart without recreating marker`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        val item = store.save("capture", "Still available for insertion", false, 1L)
        store.acknowledgeAudioCleanup(item.id)
        assertEquals(listOf("json"), dir.listFiles()!!.map { it.extension })

        val restored = PendingTranscriptStore(dir, dispatcher)
        assertTrue(restored.isCompleted(item.id))
        assertEquals(listOf(item), restored.transcripts.value)
        assertEquals(listOf("json"), dir.listFiles()!!.map { it.extension })
        assertTrue(restored.remove(item.id))
        assertTrue(dir.listFiles()!!.isEmpty())
        assertFalse(PendingTranscriptStore(dir, dispatcher).isCompleted(item.id))
    }

    @Test fun `restart repairs marker after crash between text and completion writes`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        val item = store.save("capture", "Committed text before interrupted marker write", false, 1L)
        val marker = dir.listFiles()!!.single { it.extension == "done" }
        assertTrue(marker.delete())

        val restored = PendingTranscriptStore(dir, dispatcher)
        restored.ensureLoaded()
        assertEquals(listOf(item), restored.transcripts.value)
        assertEquals("capture", marker.readText())
        assertTrue(restored.remove(item.id))
        assertTrue(restored.isCompleted(item.id))
        assertTrue(jsonFiles(dir).isEmpty())
    }

    @Test fun `failed marker repair exposes no consumable result and retries loading after repair`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        val item = store.save("capture", "Preserve durable text on marker failure", false, 1L)
        val marker = dir.listFiles()!!.single { it.extension == "done" }
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        val blocker = File(marker, "blocker").apply { writeText("prevent marker creation") }

        val restored = PendingTranscriptStore(dir, dispatcher)
        expectIOException { restored.ensureLoaded() }
        assertTrue(restored.transcripts.value.isEmpty())
        assertEquals(item, PendingTranscript.fromJson(jsonFiles(dir).single().readText()))
        expectIOException { restored.isCompleted(item.id) }
        assertTrue(restored.transcripts.value.isEmpty())

        assertTrue(blocker.delete())
        assertTrue(marker.delete())
        restored.ensureLoaded()
        assertEquals(listOf(item), restored.transcripts.value)
        assertEquals(item.id, marker.readText())
    }

    @Test fun `removing unacknowledged text restores a missing marker before deleting text`() = runTest {
        val dir = temporary.newFolder()
        val store = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        val item = store.save("capture", "Consume only after completion is durable", false, 1L)
        val marker = dir.listFiles()!!.single { it.extension == "done" }
        assertTrue(marker.delete())
        assertTrue(store.remove(item.id))
        assertEquals(item.id, marker.readText())
        assertTrue(jsonFiles(dir).isEmpty())
        assertTrue(store.isCompleted(item.id))
    }

    @Test fun `invalid completion marker prevents save from reporting success`() = runTest {
        val dir = temporary.newFolder()
        val store = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        val item = store.save("capture", "Initial result", false, 1L)
        val marker = dir.listFiles()!!.single { it.extension == "done" }
        store.acknowledgeAudioCleanup(item.id)
        store.remove(item.id)
        assertTrue(marker.mkdir())

        expectIOException { store.save(item.id, "Must not be published", false, 2L) }
        assertTrue(store.transcripts.value.isEmpty())
        assertTrue(jsonFiles(dir).isEmpty())
        expectIOException { store.isCompleted(item.id) }
    }

    @Test fun `failed durable save throws and never publishes a completed result`() = runTest {
        val dir = File(temporary.newFolder(), "pending")
        val store = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        store.ensureLoaded()
        assertTrue(dir.delete())
        dir.writeText("a file now blocks the private directory")

        expectIOException { store.save("capture", "Must remain recoverable", false, 1L) }
        assertTrue(store.transcripts.value.isEmpty())
        assertEquals("a file now blocks the private directory", dir.readText())
    }

    @Test fun `failed deletion keeps the result available instead of publishing success`() = runTest {
        val dir = temporary.newFolder()
        val store = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        val item = store.save("capture", "Keep this text", false, 1L)
        val file = jsonFiles(dir).single()
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        File(file, "blocker").writeText("prevent directory deletion")

        expectIOException { store.remove(item.id) }
        assertEquals(listOf(item), store.transcripts.value)
    }

    @Test fun `unreadable saved files are retained and never overwritten by a retry`() = runTest {
        val dir = temporary.newFolder()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = PendingTranscriptStore(dir, dispatcher)
        first.save("broken", "Original", false, 1L)
        val file = jsonFiles(dir).single()
        file.writeText("damaged bytes remain available for recovery")
        val restored = PendingTranscriptStore(dir, dispatcher)
        restored.ensureLoaded()
        val valid = restored.save("valid", "Still load valid results", false, 2L)

        expectIOException { restored.save("broken", "Do not silently replace", false, 3L) }
        assertEquals("damaged bytes remain available for recovery", file.readText())
        assertEquals(listOf(valid), restored.transcripts.value)
    }

    @Test fun `capture identities cannot choose a path outside private result directory`() = runTest {
        val parent = temporary.newFolder()
        val dir = File(parent, "pending")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = PendingTranscriptStore(dir, dispatcher)
        val item = store.save("../outside/path", "Saved privately", false, 1L)
        assertEquals(listOf("pending"), parent.listFiles()!!.map { it.name })
        assertTrue(jsonFiles(dir).single().name.matches(Regex("[a-f0-9]{64}\\.json")))

        val restored = PendingTranscriptStore(dir, dispatcher)
        restored.ensureLoaded()
        assertEquals(listOf(item), restored.transcripts.value)
    }

    @Test fun `concurrent arrivals retain every result without count eviction`() = runTest {
        val dir = temporary.newFolder()
        val store = PendingTranscriptStore(dir, StandardTestDispatcher(testScheduler))
        (1..60).reversed().map { number ->
            async { store.save("capture-$number", "Result $number", false, number.toLong()) }
        }.awaitAll()
        assertEquals((1..60).map { "Result $it" }, store.transcripts.value.map { it.text })
        assertEquals(60, jsonFiles(dir).size)
    }

    private fun jsonFiles(dir: File): List<File> = dir.listFiles()!!.filter { it.extension == "json" }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected storage failure to be reported")
        } catch (_: IOException) {
            // The queue needs this failure to retain its source audio for a retry.
        }
    }
}
