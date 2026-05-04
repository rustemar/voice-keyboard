package com.tyraen.voicekeyboard.feature.transcription

object HallucinationFilter {

    /** Whisper produces these on silence regardless of recording length.
     *  All of them are also valid one-word user dictations, so we drop them
     *  only when the recording is longer than [SHORT_RECORDING_THRESHOLD_MS]
     *  — short clips might be a real "thank you" / "спасибо". */
    private val phantomPhrases = setOf(
        "продолжение следует",
        "спасибо",
        "спасибо за просмотр",
        "спасибо за внимание",
        "thank you",
        "thanks for watching",
        "thanks for listening",
        "subscribe",
        "like and subscribe",
        "please subscribe",
        "subtitles by",
        "vielen dank",
        "merci",
        "merci d'avoir regardé",
        "sous-titres",
        "untertitel",
        "you"
    )

    /** Subtitle-credit pattern that no real speaker ever dictates. Matches
     *  things like "Субтитры создавал DimaTorzok", "Субтитры подогнал …",
     *  "Субтитры сделал корректор: Дмитрий Z.", "Subtitles by …",
     *  "Sous-titres réalisés par …", "Untertitel im Auftrag des ZDF, …".
     *  Always filtered, regardless of recording length. */
    private val subtitleCreditRegex = Regex(
        "^\\s*(субтитры|субтитрирование|субтитрирование и редактирование выполнены|" +
            "subtitles?|closed captions?|sous-titres?|sous-titrage|" +
            "untertitel(?:ung)?)\\b.*",
        RegexOption.IGNORE_CASE
    )

    /** A recording shorter than this is probably real speech. Above this
     *  threshold, single-word "phantom phrases" are dropped. */
    private const val SHORT_RECORDING_THRESHOLD_MS = 2_000L

    fun clean(rawText: String, prompt: String = "", recordingDurationMs: Long = 0L): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.all { it in ".,;:!?…·•–—-~​ " }) return ""

        if (subtitleCreditRegex.matches(trimmed)) return ""

        if (isPromptEcho(trimmed, prompt)) return ""

        // Phantom phrases are real words too — only filter when we're
        // confident the user wasn't actually speaking.
        if (recordingDurationMs == 0L || recordingDurationMs > SHORT_RECORDING_THRESHOLD_MS) {
            val normalized = trimmed.trimEnd('.', ',', '!', '?', '…', ' ').lowercase()
            if (normalized in phantomPhrases) return ""
        }

        return trimmed
    }

    /** True when the transcription is just an echo of the style/vocabulary
     *  prompt we sent to Whisper. Whisper sometimes regurgitates a slice of
     *  its own prompt under low-confidence conditions (silence + busy
     *  prompt). The check is conservative: we only flag echoes of at least
     *  three characters that appear verbatim as a substring of the prompt. */
    private fun isPromptEcho(text: String, prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val normalizedText = text.trimEnd('.', ',', '!', '?', '…', ' ').lowercase()
        if (normalizedText.length < 3) return false
        val normalizedPrompt = prompt.lowercase()
        return normalizedText in normalizedPrompt
    }
}
