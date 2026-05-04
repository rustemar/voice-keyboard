package com.tyraen.voicekeyboard.feature.transcription

object HallucinationFilter {

    /** Phrases that are *only* hallucinations — nobody dictates these.
     *  Filtered regardless of recording length. */
    private val alwaysFilterPhrases = setOf(
        "продолжение следует",
        "спасибо за просмотр",
        "спасибо за внимание",
        "thanks for watching",
        "thanks for listening",
        "subscribe",
        "like and subscribe",
        "please subscribe",
        "subtitles by",
        "merci d'avoir regardé",
        "sous-titres",
        "untertitel"
    )

    /** Real one-word words that Whisper also emits on silence. We can only
     *  drop them when the recording is too long for that one word to be
     *  legit — see [SHORT_RECORDING_THRESHOLD_MS]. */
    private val durationGatedPhrases = setOf(
        "спасибо",
        "thank you",
        "vielen dank",
        "merci",
        "you"
    )

    /** Subtitle-credit patterns that no real speaker dictates. Two regexes:
     *  one anchored at the start of the string for the canonical
     *  "Субтитры [verb] X" / "Subtitles by X" / "Sous-titres réalisés par X"
     *  shape, and a second that fires whenever the text contains the
     *  "subtitle" stem together with "корректор" / "редактор" — a known
     *  Whisper hallucination shape "Редактор субтитров X Корректор Y" that
     *  appears verbatim when the prompt biases toward names. */
    private val subtitleCreditRegex = Regex(
        "^\\s*(субтитры|субтитрирование|субтитрирование и редактирование выполнены|" +
            "редактор\\s+субтитров|корректор[:.]?\\s+|" +
            "subtitles?|closed captions?|sous-titres?|sous-titrage|" +
            "untertitel(?:ung)?)\\b.*",
        RegexOption.IGNORE_CASE
    )

    /** Whisper sometimes emits "Редактор субтитров А.Семкин Корректор
     *  А.Егорова" wholesale on silence + busy prompt. A real sentence
     *  containing both "корректор" and a subtitle stem is essentially
     *  unheard of in dictation, so we treat the co-occurrence as a
     *  credit line. */
    private val subtitleCreditCooccurrenceRegex = Regex(
        ".*\\bсубтитр\\w*.*\\b(корректор|редактор)\\b.*|" +
            ".*\\b(корректор|редактор)\\b.*\\bсубтитр\\w*.*",
        RegexOption.IGNORE_CASE
    )

    private val tokenSplitter = Regex("[\\s,;.!?…]+")

    /** A real "Спасибо" / "Thank you" is ~0.5–1 s. Anything longer with
     *  just that one word is almost certainly a hallucination. */
    private const val SHORT_RECORDING_THRESHOLD_MS = 1_500L

    /** Need at least this many distinct tokens before we consider a
     *  vocab-echo. One- or two-word answers (a contact's name) must
     *  always pass through; three or more is where dictation ambiguity
     *  starts. */
    private const val VOCAB_ECHO_MIN_TOKENS = 3

    /** For exactly 3 tokens, require *all* of them to be vocab matches
     *  before flagging — three names like "Альфия Аусу Рустем" might be
     *  a real list dictation (Whisper just garbled "Алсу" → "Аусу") and
     *  must pass when even one token is genuinely out of vocabulary. */
    private const val VOCAB_ECHO_THRESHOLD_3 = 1.0

    /** Four or more tokens: 70 % overlap is enough. Real dictation rarely
     *  hits this density of vocab tokens, while Whisper hallucinations
     *  routinely produce 5–7 names with one or two out-of-vocab garbles. */
    private const val VOCAB_ECHO_THRESHOLD_LONG = 0.7

    fun clean(
        rawText: String,
        prompt: String = "",
        vocabulary: String = "",
        recordingDurationMs: Long = 0L
    ): String {
        // The `prompt` parameter is reserved for future use; an earlier
        // substring-based prompt-echo detector caused too many false
        // positives on legitimate one-word dictation ("Привет" is a
        // substring of "Привет, как дела?"), so it was removed.
        @Suppress("UNUSED_PARAMETER") prompt

        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.all { it in ".,;:!?…·•–—-~​ " }) return ""

        if (subtitleCreditRegex.matches(trimmed)) return ""
        if (subtitleCreditCooccurrenceRegex.matches(trimmed)) return ""

        val normalized = trimmed.trimEnd('.', ',', '!', '?', '…', ' ').lowercase()
        if (normalized in alwaysFilterPhrases) return ""

        val isLong = recordingDurationMs == 0L || recordingDurationMs > SHORT_RECORDING_THRESHOLD_MS
        if (isLong && normalized in durationGatedPhrases) return ""

        if (isVocabEcho(trimmed, vocabulary)) return ""

        return trimmed
    }

    /** True when Whisper appears to have regurgitated the vocabulary bias
     *  list as transcription. Triggers when the transcription has at
     *  least three tokens and at least 70 % of them appear in the user's
     *  vocabulary. No duration gate — a real human cannot physically
     *  dictate three names faster than Whisper can hallucinate them, so
     *  the token count alone is the signal.
     *
     *  Imperfect overlap is on purpose — Whisper often jumbles the list
     *  ("Рустем" → "Рустема", duplicates "Азик, Азик", trailing junk),
     *  which would defeat strict-substring matching. */
    private fun isVocabEcho(text: String, vocabulary: String): Boolean {
        if (vocabulary.isBlank()) return false

        val vocabTokens = vocabulary
            .split('\n', ',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (vocabTokens.isEmpty()) return false

        val tokens = text.split(tokenSplitter)
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }
        if (tokens.size < VOCAB_ECHO_MIN_TOKENS) return false

        val hits = tokens.count { token ->
            // Whisper sometimes inflects: "Рустем" → "Рустема". Treat a
            // token as a hit if it equals or is contained in any vocab
            // term, or vice versa.
            vocabTokens.any { v -> v == token || token.startsWith(v) || v.startsWith(token) }
        }
        val ratio = hits.toDouble() / tokens.size
        val threshold = if (tokens.size == 3) VOCAB_ECHO_THRESHOLD_3 else VOCAB_ECHO_THRESHOLD_LONG
        return ratio >= threshold
    }
}
