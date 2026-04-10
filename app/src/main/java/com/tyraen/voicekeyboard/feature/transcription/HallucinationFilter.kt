package com.tyraen.voicekeyboard.feature.transcription

object HallucinationFilter {

    private val phantomPhrases = setOf(
        "продолжение следует",
        "thank you",
        "thanks for watching",
        "thanks for listening",
        "subscribe",
        "like and subscribe",
        "please subscribe",
        "subtitles by",
        "sous-titres",
        "untertitel",
        "you"
    )

    fun clean(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.all { it in ".,;:!?…·•–—-~\u200B " }) return ""

        val normalized = trimmed.trimEnd('.', ',', '!', '?', '…', ' ').lowercase()
        if (normalized in phantomPhrases) return ""

        return trimmed
    }
}
