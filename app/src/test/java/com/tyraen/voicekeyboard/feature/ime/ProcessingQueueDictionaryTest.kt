package com.tyraen.voicekeyboard.feature.ime

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import com.tyraen.voicekeyboard.feature.postprocessing.PromptParts
import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import com.tyraen.voicekeyboard.feature.vocabulary.TextDictionary
import com.tyraen.voicekeyboard.feature.vocabulary.TextReplacementRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessingQueueDictionaryTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun rules(vararg values: Pair<String, String>): String = TextDictionary.encodeRules(
        values.map { TextReplacementRules.Rule(it.first, it.second) }
    )

    private fun currentDictation(prompt: PromptParts): String =
        runCatching { JSONObject(prompt.userText).getString("current_dictation") }.getOrDefault(prompt.userText)

    private fun promptContains(prompt: PromptParts, text: String): Boolean =
        prompt.systemInstruction.contains(text) || prompt.userText.contains(text)

    private inner class Fixture(val test: TestScope, val dir: File = temporary.newFolder()) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val store = ParkedRecordingStore(File(dir, "parked"), dispatcher)
        val saved = PendingTranscriptStore(File(dir, "saved"), dispatcher)
        val prefs = PostProcessingPreferences(enabled = true, apiKey = "pp-test")
        var online = true
        var editorSession = 1L
        var acceptsInsertion = true
        private var sequence = 0L
        val inserted = ArrayList<String>()
        val speechRequests = ArrayList<TranscriptionConfig>()
        val ppRequests = ArrayList<PromptParts>()
        var transcribe: suspend (String) -> Result<String> = { Result.success(it) }
        var postProcess: suspend (PromptParts) -> Result<String> = { Result.success(currentDictation(it)) }
        val queue = ProcessingQueue(
            object : SpeechToTextClient {
                override suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String> {
                    speechRequests += config
                    return transcribe(audioFile.readText())
                }

                override suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File) =
                    Result.success("OK")
            },
            { prompt, _, _ -> ppRequests += prompt; postProcess(prompt) },
            store, { online }, { "speech-test" }, { prefs },
            { identity, text, trailing -> saved.save(identity.workId, text, trailing, identity.createdAt) },
            test.backgroundScope, { 0L }, { saved.isCompleted(it) }, { saved.acknowledgeAudioCleanup(it) }
        )
        val listener = object : ProcessingQueue.Listener {
            override fun onTextReady(text: String, addTrailingSpace: Boolean, editorSessionToken: Long): Boolean {
                if (editorSessionToken != editorSession || !acceptsInsertion) return false
                inserted += text
                return true
            }

            override fun onQueueCountChanged(count: Int) {}
            override fun onProcessingPhaseChanged(phase: ProcessingQueue.ProcessingPhase) {}
            override fun onFailedCountChanged(count: Int) {}
            override fun onError(message: String) {}
        }

        init { queue.bindListener(listener) }

        fun item(
            raw: String,
            mode: String = "",
            hints: String = "",
            expanded: String = "",
            replacements: String = ""
        ): ProcessingQueue.QueueItem {
            val id = "dictation-${++sequence}"
            return ProcessingQueue.QueueItem(
                audioFile = File(dir, "$id.ogg").apply { writeText(raw) },
                transcriptionConfig = TranscriptionConfig(
                    "speech-test", "https://test.invalid", "test", "en", "Formatting hint.", vocabulary = hints
                ),
                addTrailingSpace = true, singleWordStripPunctuation = false, vocabulary = hints,
                ppPreferences = if (mode.isEmpty()) null else prefs,
                ppFix = mode == "fix" || mode == "all", ppShorten = false, ppEmoji = false,
                ppRhyme = mode == "rhyme" || mode == "all", ppTranslate = mode == "translate" || mode == "all",
                ppTerminal = mode == "terminal", editorSessionToken = editorSession,
                workId = id, createdAt = sequence,
                postProcessingVocabulary = expanded, replacementRules = replacements
            )
        }
    }

    @Test fun `local corrections work without a text-processing provider or enabled mode`() = runTest {
        val f = Fixture(this)
        val item = f.item("Use gabbord now.", replacements = rules("gabbord" to "Gboard"))

        f.queue.enqueue(item); runCurrent()

        assertEquals(listOf("Use Gboard now."), f.inserted)
        assertTrue(f.ppRequests.isEmpty())
        assertFalse(item.audioFile.exists())
    }

    @Test fun `expanded vocabulary and rule definitions never change speech-provider hints`() = runTest {
        val f = Fixture(this)
        val expanded = (1..150).joinToString("\n") { "ExpandedTerm$it" }
        val item = f.item("Use gabbord now.", "fix", "LegacyTerm", expanded,
            rules("gabbord" to "Gboard", "unmatched-source" to "UnmatchedTarget"))
        assertTrue(expanded.length > 600)

        f.queue.enqueue(item); runCurrent()

        assertEquals(listOf(item.transcriptionConfig), f.speechRequests)
        assertEquals("LegacyTerm", f.speechRequests.single().vocabulary)
        assertEquals("Formatting hint.", f.speechRequests.single().prompt)
        val prompt = f.ppRequests.single()
        assertEquals("Use Gboard now.", currentDictation(prompt))
        for (term in listOf("LegacyTerm", "ExpandedTerm1", "ExpandedTerm150", "Gboard")) {
            assertTrue("Missing text-processing reference: $term", promptContains(prompt, term))
        }
        assertFalse(promptContains(prompt, "unmatched-source"))
        assertFalse(promptContains(prompt, "UnmatchedTarget"))
        assertEquals(listOf("Use Gboard now."), f.inserted)
    }

    @Test fun `failed text processing falls back to locally corrected raw dictation`() = runTest {
        val f = Fixture(this)
        f.postProcess = { Result.failure(IOException("Synthetic text-processing failure")) }

        f.queue.enqueue(f.item("alpha", "fix", replacements = rules("alpha" to "beta", "beta" to "gamma")))
        runCurrent()

        assertEquals("beta", currentDictation(f.ppRequests.single()))
        assertEquals(listOf("beta"), f.inserted)
    }

    @Test fun `empty text-processing output for a real sentence preserves local corrections`() = runTest {
        val f = Fixture(this)
        f.postProcess = { Result.success("") }

        f.queue.enqueue(f.item("Use gabbord for this complete sentence, please.", "fix",
            replacements = rules("gabbord" to "Gboard")))
        runCurrent()

        assertEquals(listOf("Use Gboard for this complete sentence, please."), f.inserted)
    }

    @Test fun `translation output is never passed through local replacement rules again`() = runTest {
        val f = Fixture(this)
        f.postProcess = { Result.success("beta translated") }

        f.queue.enqueue(f.item("alpha", "translate", replacements = rules("alpha" to "beta", "beta" to "gamma")))
        runCurrent()

        assertEquals("beta", currentDictation(f.ppRequests.single()))
        assertEquals(listOf("beta translated"), f.inserted)
    }

    @Test fun `combined processing stages reuse references without reapplying replacements`() = runTest {
        val f = Fixture(this)
        var calls = 0
        f.postProcess = { Result.success(listOf("beta fixed", "beta rhymed", "beta translated")[calls++]) }

        f.queue.enqueue(f.item("alpha", "all", "LegacyTerm", "ExpandedTerm",
            rules("alpha" to "beta", "beta" to "gamma")))
        runCurrent()

        assertEquals(listOf("beta", "beta fixed", "beta rhymed"), f.ppRequests.map(::currentDictation))
        for (prompt in f.ppRequests) {
            for (term in listOf("LegacyTerm", "ExpandedTerm", "beta")) assertTrue(promptContains(prompt, term))
        }
        assertEquals(listOf("beta translated"), f.inserted)
    }

    @Test fun `terminal code is not rewritten by local dictation corrections`() = runTest {
        val f = Fixture(this)
        f.postProcess = { Result.success("printf beta") }

        f.queue.enqueue(f.item("alpha", "terminal", replacements = rules("alpha" to "beta", "beta" to "gamma")))
        runCurrent()

        assertEquals("beta", currentDictation(f.ppRequests.single()))
        assertEquals(listOf("printf beta"), f.inserted)
    }

    @Test fun `a late result saved for another editor uses the same corrected text`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        f.transcribe = { release.await(); Result.success(it) }
        val item = f.item("alpha", replacements = rules("alpha" to "beta", "beta" to "gamma"))

        f.queue.enqueue(item); runCurrent()
        f.editorSession = 2L
        release.complete(Unit); runCurrent()

        assertTrue(f.inserted.isEmpty())
        assertEquals("beta", f.saved.transcripts.value.single().text)
        assertTrue(f.saved.transcripts.value.single().addTrailingSpace)
        assertFalse(item.audioFile.exists())
    }

    @Test fun `cancellation during text processing never saves a corrected raw fallback`() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        f.postProcess = { withContext(NonCancellable) { release.await() }; Result.success("beta") }
        val item = f.item("alpha", "fix", replacements = rules("alpha" to "beta"))

        f.queue.enqueue(item); runCurrent()
        assertEquals("beta", currentDictation(f.ppRequests.single()))
        f.queue.unbindListener(f.listener)
        assertTrue(f.queue.cancelLatest())
        release.complete(Unit); runCurrent()

        assertTrue(f.inserted.isEmpty())
        assertTrue(f.saved.transcripts.value.isEmpty())
        assertFalse(item.audioFile.exists())
        assertEquals(0, f.store.count.value)
    }

    @Test fun `parked retry after restart keeps capture-time rules and post-processing vocabulary`() = runTest {
        val original = Fixture(this)
        original.online = false
        val capturedRules = rules("gabbord" to "Gboard")
        val item = original.item("Use gabbord.", "fix", "CaptureHint", "CaptureOnlyTerm", capturedRules)
        original.queue.enqueue(item); runCurrent()
        assertEquals(1, original.store.count.value)

        val restarted = Fixture(this, original.dir)
        restarted.store.ensureLoaded()
        val restored = restarted.store.recordings.value.single().toQueueItem("rotated-speech-key", "rotated-pp-key")
        assertEquals("CaptureOnlyTerm", restored.postProcessingVocabulary)
        assertEquals(capturedRules, restored.replacementRules)
        assertEquals("CaptureHint", restored.transcriptionConfig.vocabulary)
        assertEquals("rotated-speech-key", restored.transcriptionConfig.apiKey)
        assertEquals("rotated-pp-key", restored.ppPreferences?.apiKey)

        restarted.queue.bootstrap(); runCurrent()

        assertTrue(restarted.inserted.isEmpty())
        assertEquals("Use Gboard.", restarted.saved.transcripts.value.single().text)
        val prompt = restarted.ppRequests.single()
        assertEquals("Use Gboard.", currentDictation(prompt))
        assertTrue(promptContains(prompt, "CaptureOnlyTerm"))
        assertEquals(0, restarted.store.count.value)
    }

    @Test fun `legacy parked recordings without dictionary metadata retain normal behavior`() = runTest {
        val f = Fixture(this)
        val item = f.item("Original dictation.", "fix", "LegacyHint")
        val parked = f.store.park(item, item.audioFile, ParkedRecording.State.WAITING_NETWORK, 1)!!
        val legacy = JSONObject(parked.toJson()).apply {
            remove("postProcessingVocabulary")
            remove("replacementRules")
        }
        val restored = ParkedRecording.fromJson(legacy.toString())!!.toQueueItem("speech-test", "pp-test")
        assertEquals("", restored.postProcessingVocabulary)
        assertEquals("", restored.replacementRules)

        f.queue.enqueue(restored); runCurrent()

        assertEquals("Original dictation.", f.saved.transcripts.value.single().text)
        assertEquals("Original dictation.", currentDictation(f.ppRequests.single()))
        assertTrue(promptContains(f.ppRequests.single(), "LegacyHint"))
    }
}
