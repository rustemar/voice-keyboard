package com.tyraen.voicekeyboard.feature.recognition

import java.util.IllformedLocaleException
import java.util.Locale

/** Converts a client's language tag to the provider's primary language code. */
internal object RecognitionLanguage {
    /** Blank in both places keeps automatic language detection; null means an invalid tag. */
    fun resolve(requested: String?, fallback: String): String? {
        val tag = requested?.takeIf { it.isNotBlank() } ?: fallback
        if (tag.isBlank()) return ""
        return try {
            val language = Locale.Builder().setLanguageTag(tag.trim().replace('_', '-'))
                .build().language.lowercase(Locale.ROOT)
            when {
                language == "und" || !language.matches(Regex("[a-z]{2,3}")) -> null
                language == "iw" -> "he"
                language == "in" -> "id"
                language == "ji" -> "yi"
                else -> language
            }
        } catch (_: IllformedLocaleException) {
            null
        }
    }
}
