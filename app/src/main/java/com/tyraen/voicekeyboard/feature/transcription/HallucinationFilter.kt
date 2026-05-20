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

    /** Shape of the *start* of a hallucinated credit / end-card sentence.
     *  Tighter than [subtitleCreditRegex] — requires a production verb
     *  after the subtitle stem so that legitimate sentences that happen
     *  to start with "Субтитры" (e.g. "Субтитры были на английском")
     *  survive when they appear as the trailing sentence after real
     *  speech. Used by [creditSentenceRegex]. */
    private const val CREDIT_OPENER =
        "(?:" +
            // RU: "Субтитры / Субтитрирование <production verb>"
            "(?:редактор\\s+)?(?:субтитры?|субтитрирование)\\s+" +
            "(?:созда(?:ва)?л[аио]?|сделал[аио]?|подогнал[аио]?|подготовил[аио]?|" +
            "выполнил[аио]?|редактировал[аио]?|корректировал[аио]?|переводил[аио]?|" +
            "перев(?:ё|е)л[аио]?|написал[аио]?|оформил[аио]?|прислал[аио]?)\\b" +
            // RU: "Корректор: X", "Редактор субтитров X"
            "|корректор\\b\\s*[:.]?\\s*\\S+" +
            "|редактор\\s+(?:субтитров|титров)\\s+\\S+" +
            // EN
            "|subtitles?\\s+(?:by|created\\s+by|provided\\s+by|edited\\s+by|made\\s+by)\\b" +
            "|closed\\s+captions?\\s+by\\b" +
            // FR
            "|sous-titres?\\s+(?:réalisés?\\s+par|de|par|créés?\\s+par)\\b" +
            "|sous-titrage\\b" +
            // DE
            "|untertitel(?:ung)?\\b" +
            // End-card boilerplate (full known hallucination phrases).
            "|продолжение\\s+следует\\b" +
            "|спасибо\\s+за\\s+(?:просмотр|внимание)\\b" +
            "|thanks?\\s+for\\s+(?:watching|listening)\\b" +
            "|(?:please\\s+|like\\s+and\\s+)?subscribe\\b" +
            "|merci\\s+d'avoir\\s+regard[ée]\\b" +
            ")"

    /** A whole sentence shaped like a credit line. Used to trim trailing
     *  credit sentences appended after legitimate transcription
     *  ("...изучить. Субтитры создавал DimaTorzok"). */
    private val creditSentenceRegex = Regex(
        "^\\s*$CREDIT_OPENER[^\\n]*\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** Splits at `.!?…` + whitespace, but NOT after a single capital letter
     *  followed by a period — that's an initial ("И. Иванов", "А.Сёмкин"),
     *  not a sentence boundary. Initials show up inside credit
     *  hallucinations ("Субтитры подготовил И. Иванов") and a naive split
     *  would break the credit into two fragments and miss the tail. */
    private val sentenceBoundary = Regex("(?<=[.!?…])(?<![А-ЯЁA-Z]\\.)\\s+")

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

        // Strip trailing credit-shape sentences before running the full-line
        // checks: Whisper sometimes appends "Субтитры создавал X" / "Sous-
        // titres réalisés par X" *after* legitimate speech, so the start-
        // anchored regexes below won't catch it.
        val stripped = stripTrailingCreditTail(trimmed)
        if (stripped.isEmpty()) return ""
        if (stripped.all { it in ".,;:!?…·•–—-~​ " }) return ""

        if (subtitleCreditRegex.matches(stripped)) return ""
        if (subtitleCreditCooccurrenceRegex.matches(stripped)) return ""

        val normalized = stripped.trimEnd('.', ',', '!', '?', '…', ' ').lowercase()
        if (normalized in alwaysFilterPhrases) return ""

        val isLong = recordingDurationMs == 0L || recordingDurationMs > SHORT_RECORDING_THRESHOLD_MS
        if (isLong && normalized in durationGatedPhrases) return ""

        if (isVocabEcho(stripped, vocabulary)) return ""

        return stripped
    }

    /** Removes trailing sentences shaped like Whisper end-card credits.
     *  Iterates from the end so chained credits ("...изучить. Субтитры
     *  создавал X. Спасибо за просмотр.") collapse in one pass. The
     *  per-sentence regex requires a production verb after the subtitle
     *  stem, so a real "...И субтитры были на английском." survives. */
    private fun stripTrailingCreditTail(text: String): String {
        val sentences = text.split(sentenceBoundary).toMutableList()
        var changed = false
        while (sentences.isNotEmpty() &&
            creditSentenceRegex.matches(sentences.last().trim())
        ) {
            sentences.removeAt(sentences.size - 1)
            changed = true
        }
        if (!changed) return text
        return sentences.joinToString(" ").trimEnd()
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
