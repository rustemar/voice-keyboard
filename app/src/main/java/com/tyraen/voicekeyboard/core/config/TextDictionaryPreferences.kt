package com.tyraen.voicekeyboard.core.config

/** Independent from the small prompt sent to a speech recognizer. */
data class TextDictionaryPreferences(
    val postProcessingVocabulary: String = "",
    val replacementRules: String = ""
) {
    companion object {
        const val MAX_POSTPROCESSING_CHARS = 20_000
    }
}
