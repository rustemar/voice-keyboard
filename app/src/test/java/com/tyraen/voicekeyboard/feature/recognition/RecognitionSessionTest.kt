package com.tyraen.voicekeyboard.feature.recognition

import com.tyraen.voicekeyboard.feature.transcription.SpeechToTextClient
import com.tyraen.voicekeyboard.feature.transcription.TranscriptionConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.SocketTimeoutException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecognitionSessionTest {
    @get:Rule val files = TemporaryFolder()

    private inner class Fixture(
        val test: TestScope,
        limits: RecognitionSession.Limits = RecognitionSession.Limits()
    ) {
        val file = files.newFile().apply { writeText("synthetic audio") }
        val events = ArrayList<String>()
        val errors = ArrayList<RecognitionSession.Error>()
        val requests = ArrayList<TranscriptionConfig>()
        var captureDurationMs = 800L
        var finishUsable = true
        var canStart = true
        var startFailure: Exception? = null
        var readyAction: () -> Unit = {}
        var endAction: () -> Unit = {}
        var amplitude: (Int) -> Unit = {}
        var microphoneHeld = false
        var aborts = 0
        var calls = 0
        var transcribe: suspend () -> Result<String> = { Result.success(" recognized words ") }
        val capture = object : RecognitionSession.Capture {
            override fun start(onAmplitude: (Int) -> Unit): Boolean {
                startFailure?.let { throw it }
                amplitude = onAmplitude
                microphoneHeld = canStart
                return canStart
            }
            override fun finish(): RecognitionSession.CapturedAudio? {
                microphoneHeld = false
                return if (finishUsable) RecognitionSession.CapturedAudio(file, captureDurationMs) else {
                    file.delete()
                    null
                }
            }
            override fun abort() {
                aborts++
                if (microphoneHeld) file.delete()
                microphoneHeld = false
            }
        }
        val session = RecognitionSession(
            capture,
            object : SpeechToTextClient {
                override suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String> {
                    assertFalse("Microphone must close before uploading", microphoneHeld)
                    assertEquals(file, audioFile)
                    calls++
                    requests += config
                    return transcribe()
                }
                override suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File) = Result.success("OK")
            },
            test.backgroundScope,
            object : RecognitionSession.Callbacks {
                override fun onReady() { events += "ready"; readyAction() }
                override fun onBeginningOfSpeech() { events += "begin" }
                override fun onRmsChanged(value: Float) { assertTrue(value.isFinite()); events += "rms" }
                override fun onEndOfSpeech() { events += "end"; endAction() }
                override fun onResults(text: String) { events += "result:$text" }
                override fun onError(error: RecognitionSession.Error) { errors += error; events += "error" }
            },
            { test.testScheduler.currentTime }, limits
        )
        val config = TranscriptionConfig("test-key", "https://test.invalid/audio/transcriptions", "test-model", "ru", "")
        fun start() = session.start(config)
    }

    @Test fun `explicit stop uploads quiet speech once with request language and duration`() = runTest {
        val f = Fixture(this)
        assertTrue(f.start())
        f.amplitude(20)
        f.session.stop(); f.session.stop()
        runCurrent()
        assertEquals(listOf("ready", "rms", "end", "result:recognized words"), f.events)
        assertEquals(1, f.calls)
        assertEquals(f.config.copy(recordingDurationMs = 800), f.requests.single())
        assertTrue(f.errors.isEmpty())
        assertFalse(f.microphoneHeld)
        assertFalse(f.file.exists())
        assertFalse(f.session.isActive)
        assertFalse(f.session.start(f.config))
    }

    @Test fun `voice followed by silence stops automatically`() = runTest {
        val f = Fixture(this)
        f.start(); f.amplitude(3000)
        advanceTimeBy(150); f.amplitude(2500)
        advanceTimeBy(150); f.amplitude(0)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(1, f.events.count { it == "begin" })
        assertEquals(1, f.events.count { it == "end" })
        assertEquals(1, f.calls)
        assertFalse(f.microphoneHeld)
        assertFalse(f.file.exists())
    }

    @Test fun `initial silence ends with speech timeout and never uploads`() = runTest {
        val f = Fixture(this)
        f.start()
        repeat(5) { f.amplitude(0); advanceTimeBy(1_000) }
        runCurrent()
        assertEquals(listOf(RecognitionSession.Error.SPEECH_TIMEOUT), f.errors)
        assertEquals(0, f.calls)
        assertFalse(f.microphoneHeld)
        assertFalse(f.file.exists())
        assertEquals(0, f.events.count { it == "end" })
    }

    @Test fun `continuous speech has a hard capture ceiling`() = runTest {
        val f = Fixture(this, RecognitionSession.Limits(maximumCaptureMs = 2_000))
        f.start()
        repeat(14) { f.amplitude(3000); advanceTimeBy(150) }
        runCurrent()
        assertEquals(1, f.calls)
        assertEquals(1, f.events.count { it.startsWith("result:") })
        assertFalse(f.microphoneHeld)
    }

    @Test fun `one short answer in a single amplitude sample is still recognized`() = runTest {
        val f = Fixture(this)
        f.start(); f.amplitude(3000); f.amplitude(0)
        advanceTimeBy(1_200); runCurrent()
        assertEquals(1, f.events.count { it == "begin" })
        assertTrue(f.errors.isEmpty())
        assertEquals(1, f.calls)
        assertFalse(f.microphoneHeld)
    }

    @Test fun `minimum listening hint delays silence completion rather than setting maximum`() = runTest {
        val f = Fixture(this, RecognitionSession.Limits(minimumListeningMs = 3_000))
        f.start(); f.amplitude(3000); f.amplitude(3000)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(f.session.isRecording)
        assertEquals(0, f.calls)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, f.calls)
    }

    @Test fun `cancel recording is silent idempotent and removes owned audio`() = runTest {
        val f = Fixture(this)
        f.start(); f.session.cancel(); f.session.cancel(); f.session.stop()
        advanceTimeBy(50_000); runCurrent()
        f.amplitude(3000)
        assertEquals(listOf("ready"), f.events)
        assertEquals(0, f.calls)
        assertFalse(f.microphoneHeld)
        assertFalse(f.file.exists())
        assertEquals(1, f.aborts)
        assertFalse(f.session.isActive)
    }

    @Test fun `cancel before dispatched upload starts deletes file and never calls backend`() = runTest {
        val f = Fixture(this)
        f.start(); f.session.stop(); f.session.cancel()
        runCurrent()
        assertEquals(0, f.calls)
        assertEquals(listOf("ready", "end"), f.events)
        assertFalse(f.file.exists())
    }

    @Test fun `cancel upload suppresses noncooperative late success and leaves no retry`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.transcribe = { withContext(NonCancellable) { gate.await() }; Result.success("late secret") }
        f.start(); f.session.stop(); runCurrent()
        assertEquals(1, f.calls)
        f.session.cancel(); gate.complete(Unit); runCurrent()
        assertEquals(listOf("ready", "end"), f.events)
        assertFalse(f.file.exists())
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `client death in ready or end callback releases microphone and file`() = runTest {
        for (dieAtReady in listOf(true, false)) {
            val f = Fixture(this)
            if (dieAtReady) f.readyAction = { throw IllegalStateException("client died") }
            else f.endAction = { throw IllegalStateException("client died") }
            f.start(); f.session.stop(); runCurrent()
            assertFalse(f.microphoneHeld)
            assertFalse(f.file.exists())
            assertEquals(0, f.calls)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun `client cancellation from ready callback cannot start an orphan monitor`() = runTest {
        val f = Fixture(this)
        f.readyAction = { f.session.cancel() }
        f.start(); advanceTimeBy(50_000); runCurrent()
        assertEquals(listOf("ready"), f.events)
        assertFalse(f.microphoneHeld)
        assertEquals(0, f.calls)
    }

    @Test fun `request deadline cancels backend and cleans audio with one timeout error`() = runTest {
        val f = Fixture(this, RecognitionSession.Limits(maximumRequestMs = 1_000))
        var canceled = false
        f.transcribe = { try { awaitCancellation() } finally { canceled = true } }
        f.start(); f.session.stop(); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertTrue(canceled)
        assertEquals(listOf(RecognitionSession.Error.NETWORK_TIMEOUT), f.errors)
        assertFalse(f.file.exists())
        assertFalse(f.session.isActive)
        f.session.stop(); f.session.cancel()
        assertEquals(1, f.errors.size)
    }

    @Test fun `backend failures finish once without retry or transcript fallback`() = runTest {
        val failures = listOf(
            SocketTimeoutException() to RecognitionSession.Error.NETWORK_TIMEOUT,
            java.net.UnknownHostException() to RecognitionSession.Error.NETWORK,
            Exception("API error 401: bad key") to RecognitionSession.Error.SERVER,
            Exception("API error 429: limit") to RecognitionSession.Error.TOO_MANY_REQUESTS,
            Exception("API error 400: unsupported language") to RecognitionSession.Error.LANGUAGE_NOT_SUPPORTED
        )
        for ((error, expected) in failures) {
            val f = Fixture(this)
            f.transcribe = { Result.failure(error) }
            f.start(); f.session.stop(); runCurrent()
            assertEquals(listOf(expected), f.errors)
            assertEquals(1, f.calls)
            assertFalse(f.file.exists())
            assertFalse(f.session.isActive)
        }
    }

    @Test fun `empty recognition or unusable capture returns no match and removes file`() = runTest {
        for (captureMode in listOf("blank", "short", "failed", "empty")) {
            val f = Fixture(this)
            when (captureMode) {
                "blank" -> f.transcribe = { Result.success("  ") }
                "short" -> f.captureDurationMs = 100
                "failed" -> f.finishUsable = false
                "empty" -> f.file.writeText("")
            }
            f.start(); f.session.stop(); runCurrent()
            assertEquals(listOf(RecognitionSession.Error.NO_MATCH), f.errors)
            assertEquals(if (captureMode == "blank") 1 else 0, f.calls)
            assertFalse(f.file.exists())
        }
    }

    @Test fun `provider cancellation still terminates a live request without late output`() = runTest {
        for (throwError in listOf(true, false)) {
            val f = Fixture(this)
            f.transcribe = {
                if (throwError) throw CancellationException("provider stopped")
                Result.failure(CancellationException("provider stopped"))
            }
            f.start(); f.session.stop(); runCurrent()
            assertEquals(listOf(RecognitionSession.Error.CLIENT), f.errors)
            assertFalse(f.file.exists())
            assertEquals(0, f.events.count { it.startsWith("result:") })
        }
    }

    @Test fun `failed start never reports ready and releases capture`() = runTest {
        for ((failure, expected) in listOf(
            SecurityException() to RecognitionSession.Error.INSUFFICIENT_PERMISSIONS,
            IllegalStateException() to RecognitionSession.Error.AUDIO,
            null to RecognitionSession.Error.AUDIO
        )) {
            val f = Fixture(this)
            f.startFailure = failure
            f.canStart = false
            assertFalse(f.start())
            assertEquals(listOf(expected), f.errors)
            assertEquals(listOf("error"), f.events)
            assertEquals(1, f.aborts)
        }
    }

    @Test fun `cancel before start stays silent and cannot later start microphone`() = runTest {
        val f = Fixture(this)
        f.session.cancel()
        assertFalse(f.start())
        f.session.stop(); runCurrent()
        assertTrue(f.events.isEmpty())
        assertFalse(f.microphoneHeld)
    }

    @Test fun `stop before start reports client error once instead of starting later`() = runTest {
        val f = Fixture(this)
        f.session.stop(); f.session.stop()
        assertFalse(f.start())
        assertEquals(listOf(RecognitionSession.Error.CLIENT), f.errors)
        assertFalse(f.microphoneHeld)
    }

    @Test fun `missing key does not start recorder or request`() = runTest {
        val f = Fixture(this)
        assertFalse(f.session.start(f.config.copy(apiKey = "")))
        assertEquals(listOf(RecognitionSession.Error.CLIENT), f.errors)
        assertFalse(f.microphoneHeld)
        assertEquals(0, f.calls)
    }
}
