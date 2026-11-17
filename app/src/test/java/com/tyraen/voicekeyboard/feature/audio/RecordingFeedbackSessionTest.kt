package com.tyraen.voicekeyboard.feature.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingFeedbackSessionTest {
    @Test fun successfulCaptureEmitsOnePairDespiteRepeatedCleanup() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession({ events += "start" }, { events += "stop" })
        session.started(true)
        session.started(true)
        session.stopped() // Finalize or cancel closes the microphone.
        session.stopped() // Later network failure/owner release must remain silent.
        assertEquals(listOf("start", "stop"), events)
    }

    @Test fun failedStartHasNoStopSound() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession({ events += "start" }, { events += "stop" })
        session.stopped()
        session.stopped()
        assertEquals(emptyList<String>(), events)
    }

    @Test fun unknownOrDisabledSettingRemainsSilentUntilNextCapture() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession({ events += "start" }, { events += "stop" })
        session.started(false)
        session.started(true) // A delayed load must not announce an already-running capture.
        session.stopped()
        assertEquals(emptyList<String>(), events)
        session.started(true)
        session.stopped()
        assertEquals(listOf("start", "stop"), events)
    }

    @Test fun playbackFailureCannotBreakRecordingOrDuplicateCleanup() {
        var starts = 0
        var stops = 0
        val session = RecordingFeedbackSession(
            { starts++; throw IllegalStateException("Unavailable audio output") },
            { stops++; throw IllegalStateException("Unavailable audio output") }
        )
        session.started(true)
        session.stopped()
        session.stopped()
        session.started(true)
        session.stopped()
        assertEquals(2, starts)
        assertEquals(2, stops)
    }

    @Test fun allFeedbackToggleCombinationsKeepOutputsIndependentAndOrdered() {
        for (sound in listOf(false, true)) {
            for (haptic in listOf(false, true)) {
                val events = mutableListOf<String>()
                val session = RecordingFeedbackSession(
                    { events += "start" }, { events += "stop" }, { events += "haptic" }
                )
                session.started(sound, haptic)
                session.started(sound, haptic)
                session.stopped()
                session.stopped()
                val expected = buildList {
                    if (sound) add("start")
                    if (haptic) add("haptic")
                    if (sound) add("stop")
                    if (haptic) add("haptic")
                }
                assertEquals("sound=$sound haptic=$haptic", expected, events)
            }
        }
    }

    @Test fun choicesRemainSnapshottedUntilNextCapture() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession(
            { events += "start" }, { events += "stop" }, { events += "haptic" }
        )
        session.started(true, false)
        session.started(false, true) // An asynchronous preference reload is not a new start.
        session.stopped()
        assertEquals(listOf("start", "stop"), events)

        session.started(false, true)
        session.started(true, false)
        session.stopped()
        assertEquals(listOf("start", "stop", "haptic", "haptic"), events)
    }

    @Test fun soundFailureDoesNotSuppressHapticAtEitherEvent() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession(
            { events += "start"; throw IllegalStateException("Unavailable audio output") },
            { events += "stop"; throw IllegalStateException("Unavailable audio output") },
            { events += "haptic" }
        )
        session.started(true, true)
        session.stopped()
        session.stopped()
        assertEquals(listOf("start", "haptic", "stop", "haptic"), events)
    }

    @Test fun hapticFailureDoesNotSuppressStopSoundOrNextCapture() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession(
            { events += "start" },
            { events += "stop" },
            { events += "haptic"; throw IllegalStateException("Unavailable haptic output") }
        )
        repeat(2) {
            session.started(true, true)
            session.stopped()
            session.stopped()
        }
        assertEquals(listOf("start", "haptic", "stop", "haptic",
            "start", "haptic", "stop", "haptic"), events)
    }

    @Test fun cleanupBeforeSuccessfulStartCannotEmitHaptic() {
        val events = mutableListOf<String>()
        val session = RecordingFeedbackSession(
            { events += "start" }, { events += "stop" }, { events += "haptic" }
        )
        session.stopped()
        assertEquals(emptyList<String>(), events)
        session.started(false, false)
        session.started(true, true)
        session.stopped()
        assertEquals(emptyList<String>(), events)
    }
}
