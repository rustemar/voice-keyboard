package com.tyraen.voicekeyboard.feature.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class HallucinationFilterTest {

    private val vocab = "Ильдус\nАльфия\nАлсу\nКадрия\nАхмадеев\nРустем\nВасилий\nИльшат"

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

    @Test fun `always-filter phrases are dropped regardless of duration`() {
        // Real log evidence: "Продолжение следует..." came in at 1719 ms
        // and used to leak through the duration gate. These phrases nobody
        // dictates — drop them unconditionally.
        assertEquals("", HallucinationFilter.clean("Продолжение следует...", recordingDurationMs = 500))
        assertEquals("", HallucinationFilter.clean("Продолжение следует...", recordingDurationMs = 1719))
        assertEquals("", HallucinationFilter.clean("thanks for watching", recordingDurationMs = 500))
        assertEquals("", HallucinationFilter.clean("Subscribe!", recordingDurationMs = 500))
        assertEquals("", HallucinationFilter.clean("Спасибо за просмотр", recordingDurationMs = 500))
        assertEquals("", HallucinationFilter.clean("Спасибо за внимание.", recordingDurationMs = 500))
    }

    @Test fun `duration-gated phrases drop only on long recordings`() {
        val long = 5_000L
        assertEquals("", HallucinationFilter.clean("Thank you", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Спасибо.", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Vielen Dank.", recordingDurationMs = long))
        assertEquals("", HallucinationFilter.clean("Merci.", recordingDurationMs = long))
    }

    @Test fun `duration-gated phrases survive on short recordings`() {
        val short = 800L
        assertEquals("Спасибо.", HallucinationFilter.clean("Спасибо.", recordingDurationMs = short))
        assertEquals("Thank you", HallucinationFilter.clean("Thank you", recordingDurationMs = short))
        assertEquals("Vielen Dank.", HallucinationFilter.clean("Vielen Dank.", recordingDurationMs = short))
    }

    @Test fun `unknown duration treats as long for duration-gated phrases`() {
        // Legacy callers without a measured duration — preserve safe
        // filtering so we don't ship leaks on any path that doesn't yet
        // thread duration through.
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
            "Спасибо большое за помощь",
            HallucinationFilter.clean("Спасибо большое за помощь", recordingDurationMs = long)
        )
    }

    @Test fun `subtitle credit lines are dropped regardless of duration`() {
        val short = 500L
        assertEquals("", HallucinationFilter.clean("Субтитры создавал DimaTorzok", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Субтитры подогнал DimaTorzok", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Субтитры сделал корректор: Иван", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Subtitles by Anonymous", recordingDurationMs = short))
        assertEquals("", HallucinationFilter.clean("Sous-titres réalisés par la communauté"))
        assertEquals("", HallucinationFilter.clean("Untertitelung im Auftrag des ZDF, 2018"))
    }

    @Test fun `legitimate sentences mentioning subtitles are kept`() {
        assertEquals(
            "Включи субтитры пожалуйста",
            HallucinationFilter.clean("Включи субтитры пожалуйста")
        )
        assertEquals(
            "I disabled subtitles in the player",
            HallucinationFilter.clean("I disabled subtitles in the player")
        )
    }

    @Test fun `editor-and-subtitles co-occurrence shape is dropped`() {
        // Real Whisper output (probe_vocab.py with a prompt biased by names):
        // "Редактор субтитров А.Семкин Корректор А.Егорова" — stable
        // hallucination on silence + busy prompt.
        assertEquals(
            "",
            HallucinationFilter.clean("Редактор субтитров А.Семкин Корректор А.Егорова")
        )
        assertEquals(
            "",
            HallucinationFilter.clean("Субтитры подготовил корректор А. Егорова")
        )
        // Reverse order (corrector mentioned first, then subtitle stem)
        assertEquals(
            "",
            HallucinationFilter.clean("Корректор А.Егорова, субтитры")
        )
    }

    @Test fun `single words from the style prompt are NOT filtered as echoes`() {
        // Real bug: with prompt "Привет, как дела? У меня всё хорошо. ...",
        // saying just "Привет" used to be wiped because the substring-based
        // echo detector flagged it. The detector was removed; one-word
        // outputs that happen to appear in the prompt must survive.
        val ruPrompt = "Привет, как дела? У меня всё хорошо. Это правильно отформатированное предложение."
        assertEquals("Привет", HallucinationFilter.clean("Привет", prompt = ruPrompt))
        assertEquals("Привет!", HallucinationFilter.clean("Привет!", prompt = ruPrompt))
        assertEquals("Хорошо.", HallucinationFilter.clean("Хорошо.", prompt = ruPrompt))
        assertEquals("Дела", HallucinationFilter.clean("Дела", prompt = ruPrompt))

        val enPrompt = "Hello, how are you? I'm doing well."
        assertEquals("Hello", HallucinationFilter.clean("Hello", prompt = enPrompt))
        assertEquals("Well.", HallucinationFilter.clean("Well.", prompt = enPrompt))
    }

    @Test fun `vocab echo at 3+ tokens with full overlap is dropped`() {
        // Real log evidence: dur=3033ms, all tokens from vocab.
        val long = 3_000L
        assertEquals(
            "",
            HallucinationFilter.clean(
                "Альфия, Алсу, Кадрия, Ахмадеев, Рустем",
                vocabulary = vocab,
                recordingDurationMs = long
            )
        )
    }

    @Test fun `vocab echo handles whisper's inflections and duplicates`() {
        // Real log: "Альфия, Алсу, Кадрия, Ахмадеев, Рустем, Азик, Азик"
        // — Whisper added an out-of-vocab token "Азик" twice. 5/7 = 71 %
        // vocab → above 70 % threshold for 4+ token mode → dropped.
        val long = 1_700L
        assertEquals(
            "",
            HallucinationFilter.clean(
                "Альфия, Алсу, Кадрия, Ахмадеев, Рустем, Азик, Азик",
                vocabulary = vocab,
                recordingDurationMs = long
            )
        )
        // 3-token case: Whisper inflection "Рустем" → "Рустема" is a
        // prefix-match → all three tokens hit vocab → 100 % → dropped.
        assertEquals(
            "",
            HallucinationFilter.clean(
                "Ахмадеев, Рустема Ахмадеев",
                vocabulary = vocab,
                recordingDurationMs = long
            )
        )
    }

    @Test fun `three-token list with one out-of-vocab survives as legit dictation`() {
        // Real probe evidence: dictating "Альфия Алсу Рустем" — Whisper
        // garbles "Алсу" → "Аусу", which prefix-matches neither way. So
        // 2/3 = 67 % vocab hits — below the 100 % requirement for the
        // 3-token case. Must pass through (legitimate user dictation).
        // The same shape with all-vocab tokens (e.g. real list "Альфия,
        // Алсу, Рустем" with no garble) does get filtered, but on real
        // user devices Whisper's stochasticity reliably introduces a
        // garble in genuine dictation, so the false-positive risk is low.
        assertEquals(
            "Альфия, Аусу, Рустем",
            HallucinationFilter.clean(
                "Альфия, Аусу, Рустем",
                vocabulary = vocab,
                recordingDurationMs = 2_697
            )
        )
        // And: a 3-token sentence where two tokens are common words and
        // one matches a vocab name must always pass.
        assertEquals(
            "Альфия пришла домой",
            HallucinationFilter.clean(
                "Альфия пришла домой",
                vocabulary = vocab,
                recordingDurationMs = 3_000
            )
        )
    }

    @Test fun `single name dictation always passes through`() {
        // Real log: dictating one contact name is a primary use case.
        // Vocab-echo must NOT eat single-token outputs even though the
        // token is in the vocab.
        val short = 900L
        assertEquals("Альфия.", HallucinationFilter.clean("Альфия.", vocabulary = vocab, recordingDurationMs = short))
        assertEquals("Рустем", HallucinationFilter.clean("Рустем", vocabulary = vocab, recordingDurationMs = short))
        assertEquals("Василий", HallucinationFilter.clean("Василий", vocabulary = vocab, recordingDurationMs = 1500))
    }

    @Test fun `two-name dictation passes through`() {
        // "Василий, привет!" — real example, 2 tokens, only one in vocab.
        // "Рустем Ахмадеев" — both in vocab but only 2 tokens, must survive.
        val long = 2_000L
        assertEquals(
            "Василий, привет!",
            HallucinationFilter.clean("Василий, привет!", vocabulary = vocab, recordingDurationMs = long)
        )
        assertEquals(
            "Рустем Ахмадеев",
            HallucinationFilter.clean("Рустем Ахмадеев", vocabulary = vocab, recordingDurationMs = long)
        )
    }

    @Test fun `vocab echo fires regardless of recording duration`() {
        // Real evidence: 839 ms silent recording produced
        // "Ахмадеев, Рустем, Василий, Ильдус, Азик, Рома, Азик."
        // — 7 vocab tokens. A human physically cannot dictate seven
        // names in 0.8 s, so duration is not a useful gate here.
        assertEquals(
            "",
            HallucinationFilter.clean(
                "Ахмадеев, Рустем, Василий, Ильдус, Азик, Рома, Азик.",
                vocabulary = "$vocab\nАзик\nРома",
                recordingDurationMs = 839
            )
        )
        // 3 tokens, 100 % vocab match → also dropped on a short clip.
        assertEquals(
            "",
            HallucinationFilter.clean(
                "Альфия, Алсу, Кадрия",
                vocabulary = vocab,
                recordingDurationMs = 600
            )
        )
    }

    @Test fun `vocab echo ignores empty vocabulary`() {
        assertEquals(
            "Альфия, Алсу, Кадрия",
            HallucinationFilter.clean(
                "Альфия, Алсу, Кадрия",
                vocabulary = "",
                recordingDurationMs = 5_000L
            )
        )
    }

    @Test fun `mixed vocab and real speech survives below threshold`() {
        // 2 of 5 tokens are in vocab → 40 %, below 80 % threshold.
        val long = 5_000L
        assertEquals(
            "Альфия пришла, я думаю",
            HallucinationFilter.clean(
                "Альфия пришла, я думаю",
                vocabulary = vocab,
                recordingDurationMs = long
            )
        )
    }

    @Test fun `trailing subtitle credit after legitimate speech is stripped`() {
        // Real log evidence: a long legitimate dictation ended with
        // "Субтитры создавал DimaTorzok" appended by Whisper. The start-
        // anchored regex never fired because the whole text isn't a
        // credit — only the trailing sentence is. The tail-strip should
        // remove the credit and leave the real text intact.
        val text = "Я уже твёрдо намерен идти завтра или послезавтра. " +
            "Субтитры создавал DimaTorzok"
        assertEquals(
            "Я уже твёрдо намерен идти завтра или послезавтра.",
            HallucinationFilter.clean(text, recordingDurationMs = 60_000)
        )
    }

    @Test fun `trailing credit handles RU verb variants`() {
        val variants = listOf(
            "Привет. Субтитры создавал DimaTorzok",
            "Привет. Субтитры создал Иван",
            "Привет. Субтитры сделал корректор",
            "Привет. Субтитры подогнал DimaTorzok",
            "Привет. Субтитры подготовил И. Иванов",
            "Привет. Субтитры выполнил И. Иванов",
            "Привет. Субтитры перевёл DimaTorzok",
            "Привет. Субтитры перевел DimaTorzok",
            "Привет. Редактор субтитров А.Сёмкин",
            "Привет. Корректор: А.Егорова"
        )
        for (text in variants) {
            assertEquals(
                "expected tail-strip for: $text",
                "Привет.",
                HallucinationFilter.clean(text)
            )
        }
    }

    @Test fun `trailing credit handles non-russian variants`() {
        assertEquals(
            "Hello there.",
            HallucinationFilter.clean("Hello there. Subtitles by Anonymous")
        )
        assertEquals(
            "Bonjour.",
            HallucinationFilter.clean("Bonjour. Sous-titres réalisés par la communauté")
        )
        assertEquals(
            "Guten Tag.",
            HallucinationFilter.clean("Guten Tag. Untertitelung im Auftrag des ZDF, 2018")
        )
    }

    @Test fun `multiple trailing credit sentences are stripped in one pass`() {
        val text = "Я закончил мысль. Субтитры создавал DimaTorzok. Спасибо за просмотр."
        assertEquals(
            "Я закончил мысль.",
            HallucinationFilter.clean(text)
        )
    }

    @Test fun `trailing sentence starting with субтитры but not a credit survives`() {
        // The tail-strip requires a production verb after the subtitle stem,
        // so a legitimate sentence that happens to start with "Субтитры"
        // ("Субтитры были на английском") must pass through.
        assertEquals(
            "Я смотрел фильм. Субтитры были на английском.",
            HallucinationFilter.clean("Я смотрел фильм. Субтитры были на английском.")
        )
        assertEquals(
            "Включи субтитры пожалуйста.",
            HallucinationFilter.clean("Включи субтитры пожалуйста.")
        )
    }

    @Test fun `legitimate text continuing after the credit is preserved`() {
        // Defensive: if Whisper somehow puts a credit shape mid-text and
        // continues with more legitimate speech afterwards, we leave the
        // text alone — the tail-strip only fires when the credit IS the
        // trailing sentence(s).
        val text = "Привет. Субтитры создавал DimaTorzok. Это интересное наблюдение."
        assertEquals(text, HallucinationFilter.clean(text))
    }
}
