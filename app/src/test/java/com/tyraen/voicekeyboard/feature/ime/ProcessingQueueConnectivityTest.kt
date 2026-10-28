package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.core.network.EndpointConnectivityPolicy
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.vocabulary.TextDictionary
import com.tyraen.voicekeyboard.feature.vocabulary.TextReplacementRules
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
import java.net.InetAddress

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessingQueueConnectivityTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture(val test: TestScope) {
        val dir = temporary.newFolder()
        val store = ParkedRecordingStore(File(dir, "parked"), StandardTestDispatcher(test.testScheduler))
        var internet = false
        var link = true
        var resolve: suspend (String) -> List<InetAddress> = { emptyList() }
        val policy = EndpointConnectivityPolicy({ internet }, { link }) { resolve(it) }
        val requested = ArrayList<Pair<String, String>>()
        val saved = ArrayList<String>()
        val queue = ProcessingQueue(
            object : SpeechToTextClient {
                override suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String> {
                    val text = audioFile.readText()
                    requested += text to config.endpoint
                    return Result.success(text)
                }
                override suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File) = Result.success("OK")
            },
            { _, _, _ -> error("Post-processing not enabled") },
            store, { internet }, { "current-key" }, { PostProcessingPreferences() },
            { _, text, _ -> saved += text }, test.backgroundScope, { 0L },
            canAccessEndpoint = policy::canAccessEndpoint
        )

        fun item(name: String, endpoint: String) = ProcessingQueue.QueueItem(
            audioFile = File(dir, "$name.ogg").apply { writeText(name) },
            transcriptionConfig = TranscriptionConfig("synthetic-key", endpoint, "test-model", "ru", ""),
            addTrailingSpace = false, singleWordStripPunctuation = false, vocabulary = "",
            ppPreferences = null, ppFix = false, ppShorten = false, ppEmoji = false,
            ppRhyme = false, ppTranslate = false, ppTerminal = false,
            workId = name
        )
    }

    @Test fun `fresh LAN dictation transcribes without validated internet`() = runTest {
        val f = Fixture(this)
        val item = f.item("local", "http://192.168.1.5:8000/v1")
        f.queue.enqueue(item); runCurrent()
        assertEquals(listOf("local" to item.transcriptionConfig.endpoint), f.requested)
        assertEquals(listOf("local"), f.saved)
        assertEquals(0, f.store.waitingCount())
        assertFalse(item.audioFile.exists())
    }

    @Test fun `LAN dictation keeps local corrections when its public text provider is unavailable`() = runTest {
        val f = Fixture(this)
        val item = f.item("word", "http://192.168.1.5:8000/v1").copy(
            ppPreferences = PostProcessingPreferences(
                enabled = true,
                provider = PostProcessingPreferences.PROVIDER_OPENAI,
                apiKey = "synthetic-pp-key",
                endpoint = "https://api.openai.com/v1/chat/completions"
            ),
            ppFix = true,
            replacementRules = TextDictionary.encodeRules(listOf(TextReplacementRules.Rule("word", "corrected")))
        )

        f.queue.enqueue(item); runCurrent()

        assertFalse(f.internet)
        assertEquals(listOf("word" to item.transcriptionConfig.endpoint), f.requested)
        assertEquals(listOf("corrected"), f.saved)
        assertTrue(f.store.recordings.value.isEmpty())
        assertEquals(0, f.store.waitingCount())
        assertFalse(item.audioFile.exists())
    }

    @Test fun `cloud dictation stays durable on an unvalidated Wi-Fi link`() = runTest {
        val f = Fixture(this)
        f.queue.enqueue(f.item("cloud", "https://api.groq.com/openai/v1")); runCurrent()
        assertTrue(f.requested.isEmpty())
        assertTrue(f.saved.isEmpty())
        assertEquals(1, f.store.waitingCount())
        assertTrue(File(f.store.recordings.value.single().audioPath).exists())
    }

    @Test fun `mixed parked providers retry only eligible original endpoints`() = runTest {
        val f = Fixture(this)
        f.link = false
        val cloud = f.item("cloud", "https://api.groq.com/openai/v1")
        val local = f.item("local", "http://192.168.1.5:8000/v1")
        f.queue.enqueue(cloud); f.queue.enqueue(local); runCurrent()
        assertEquals(2, f.store.waitingCount())
        f.link = true
        f.queue.onNetworkAvailable(); runCurrent()
        assertEquals(listOf("local" to local.transcriptionConfig.endpoint), f.requested)
        assertEquals(listOf("local"), f.saved)
        assertEquals(1, f.store.waitingCount())
        assertEquals(cloud.transcriptionConfig.endpoint, f.store.recordings.value.single().endpoint)
        f.queue.retryFailed(); runCurrent()
        assertEquals(1, f.requested.size)
        assertEquals(1, f.store.waitingCount())
        f.internet = true
        f.queue.onNetworkAvailable(); runCurrent()
        assertEquals(listOf("local" to local.transcriptionConfig.endpoint, "cloud" to cloud.transcriptionConfig.endpoint), f.requested)
        assertEquals(listOf("local", "cloud"), f.saved)
        assertTrue(f.store.recordings.value.isEmpty())
    }

    @Test fun `retry timer can restore a LAN recording without an internet callback`() = runTest {
        val f = Fixture(this)
        f.link = false
        f.queue.enqueue(f.item("local", "http://192.168.1.5:8000/v1")); runCurrent()
        assertEquals(1, f.store.waitingCount())
        f.link = true
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("local"), f.saved)
        assertTrue(f.store.recordings.value.isEmpty())
        assertFalse(f.internet)
    }

    @Test fun `bootstrap retries loopback even with no network to emit onAvailable`() = runTest {
        val f = Fixture(this)
        f.link = false
        val item = f.item("phone-server", "http://127.0.0.1:8000/v1")
        f.store.park(item, item.audioFile, ParkedRecording.State.WAITING_NETWORK, 1)
        f.queue.bootstrap(); runCurrent()
        assertEquals(listOf("phone-server"), f.saved)
        assertTrue(f.store.recordings.value.isEmpty())
        assertFalse(f.internet)
    }

    @Test fun `cancelling during endpoint resolution cannot later send or deliver the recording`() = runTest {
        val f = Fixture(this)
        val resolved = CompletableDeferred<Unit>()
        f.resolve = { resolved.await(); listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5))) }
        val item = f.item("cancelled", "http://speech.example.org:8000/v1")
        f.queue.enqueue(item); runCurrent()
        assertTrue(f.queue.cancelLatest()); runCurrent()
        resolved.complete(Unit); runCurrent()
        assertTrue(f.requested.isEmpty())
        assertTrue(f.saved.isEmpty())
        assertTrue(f.store.recordings.value.isEmpty())
        assertFalse(item.audioFile.exists())
    }
}
