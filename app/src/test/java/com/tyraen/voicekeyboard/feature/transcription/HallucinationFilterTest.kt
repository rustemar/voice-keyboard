package com.tyraen.voicekeyboard.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class HallucinationFilterTest {

    @Test fun `keeps real speech unchanged`() {
        assertEquals("Hello world", HallucinationFilter.clean("Hello world"))
    }

    @Test fun `trims surrounding whitespace`() {
        assertEquals("Hello", HallucinationFilter.clean("  Hello  "))
    }

    @Test fun `empty input returns empty`() {
        assertEquals("", HallucinationFilter.clean(""))
        assertEquals("", HallucinationFilter.clean("   "))
    }

    @Test fun `punctuation-only input returns empty`() {
        assertEquals("", HallucinationFilter.clean("..."))
        assertEquals("", HallucinationFilter.clean(". , !"))
        assertEquals("", HallucinationFilter.clean("—"))
    }

    @Test fun `known phantom phrases are dropped on long-enough recordings`() {
        val long = 5_000L
        assertEquals("", HallucinationFilter.clean("Thank you", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("thanks for watching", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Subscribe!", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("продолжение следует.", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Спасибо.", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Спасибо за просмотр", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Vielen Dank.", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Merci.", recordingDurationMs = long))
    }

    @Test fun `phantom phrases survive on short recordings`() {
        // A real "Спасибо" / "Thank you" is ~0.5–1 s. Below the threshold
        // we trust the transcription; above it, it's almost certainly a
        // hallucination from silence.
        val short = 800L
        assertEquals("Спасибо.", HallucinationFilter.clean("Спасибо.", recordingDurationMs = short))
        assertEquals("Thank you", HallucinationFilter.clean("Thank you", recordingDurationMs = short))
        assertEquals("Vielen Dank.", HallucinationFilter.clean("Vielen Dank.", recordingDurationMs = short))
    }

    @Test fun `unknown duration falls back to filtering phantom phrases`() {
        // Caller didn't measure duration (legacy path) — preserve the old
        // safe behavior so we don't ship leaks to users on the legacy path.
        assertEquals("", HallucinationFilter.clean("Thank you"))
        assertEquals("", HallucinationFilter.clean("Спасибо."))
    }

    @Test fun `phantom phrase detection is case-insensitive and tolerant of trailing punctuation`() {
        val long = 5_000L
        assertEquals("", HallucinationFilter.clean("THANK YOU", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Thank you!", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("СПАСИБО!", recordingDurationMs = long))
    }

    @Test fun `phrases containing phantom words but longer survive`() {
        val long = 5_000L
        assertEquals(
            "Thank you for the gift",
            HallucinationFilter.clean("Thank you for the gift", recordingDurationMs = long)
        )
        assertEquals(
            "I want to subscribe to the channel",
            HallucinationFilter.clean("I want to subscribe to the channel", recordingDurationMs = long)
        )
        assertEquals(
            "Спасибо большое за помощь",
            HallucinationFilter.clean("Спасибо большое за помощь", recordingDurationMs = long)
        )
    }

    @Test fun `subtitle credit lines are dropped regardless of duration`() {
        val short = 500L
        assertEquals("", HallucinationFilter.clean("Субтитры создавал DimaTorzok", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Субтитры подогнал DimaTorzok", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Субтитры создавал Дмитрий Z.", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Субтитры сделал корректор: Иван", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Subtitles by Anonymous", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Sous-titres réalisés par la communauté d'Amara.org"))
        assertEquals("", HallucinationFilter.clean("Untertitelung im Auftrag des ZDF, 2018"))
    }

    @Test fun `legitimate sentences that mention subtitles are kept`() {
        // "Subtitles" / "Субтитры" CAN appear in real dictation — only the
        // credit-line shape (anchored at start) is filtered. A sentence
        // about subtitles in the middle survives.
        assertEquals(
            "Включи субтитры пожалуйста",
            HallucinationFilter.clean("Включи субтитры пожалуйста")
        )
        assertEquals(
            "I disabled subtitles in the player",
            HallucinationFilter.clean("I disabled subtitles in the player")
        )
    }

    @Test fun `prompt echoes are dropped`() {
        // Whisper occasionally regurgitates a slice of its own style prompt
        // when given silence. The filter compares the trimmed transcription
        // against the prompt as a substring.
        val prompt = "Hello, how are you? I'm doing well. This is a properly formatted sentence, with punctuation and capitalization."
        assertEquals("", HallucinationFilter.clean("I'm doing well.", prompt = prompt))
        assertEquals("", HallucinationFilter.clean("This is a properly formatted sentence.", prompt = prompt))
        assertEquals("", HallucinationFilter.clean("Hello, how are you?", prompt = prompt))
    }

    @Test fun `prompt echo check is case-insensitive`() {
        val prompt = "Привет, как дела? У меня всё хорошо. Это правильно отформатированное предложение."
        assertEquals("", HallucinationFilter.clean("Это правильно.", prompt = prompt))
        assertEquals("", HallucinationFilter.clean("ПРИВЕТ, КАК ДЕЛА?", prompt = prompt))
    }

    @Test fun `prompt echo check ignores empty prompt and short text`() {
        // No prompt → don't false-positive on anything.
        assertEquals("Hello", HallucinationFilter.clean("Hello", prompt = ""))
        // Very short text shouldn't be flagged as echo even if it appears
        // in the prompt — too easy to get false positives on common words.
        assertEquals("Hi", HallucinationFilter.clean("Hi", prompt = "Hi everyone, hi there"))
    }

    @Test fun `text not in prompt survives`() {
        val prompt = "Hello, how are you? I'm doing well."
        assertEquals(
            "I went to the store",
            HallucinationFilter.clean("I went to the store", prompt = prompt)
        )
    }
}
