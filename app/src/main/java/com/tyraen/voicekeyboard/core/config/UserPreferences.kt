package com.tyraen.voicekeyboard.core.config

import com.tyraen.voicekeyboard.core.locale.TranscriptionLocale

data class UserPreferences(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    /** Comma-separated Whisper language codes the user dictates in, e.g. "ru, en". */
    val languages: String,
    /** The code currently selected on the keyboard. Always one of [languageCodes]. */
    val activeLanguage: String,
    val autoRecord: Boolean,
    val addTrailingSpace: Boolean,
    val prompt: String,
    val singleWordStripPunctuation: Boolean = false,
    /** Hand back to the keyboard that opened this one once a dictation has been typed. */
    val returnToPreviousKeyboard: Boolean = false
) {
    val languageCodes: List<String> get() = TranscriptionLocale.parseCodes(languages)

    /**
     * The code sent to Whisper. Falls back to the first configured language when the stored
     * active code was removed from the list (edited in settings) — and to blank when the user
     * configured no language at all, which makes Whisper auto-detect.
     */
    val effectiveLanguage: String
        get() {
            val codes = languageCodes
            return when {
                codes.isEmpty() -> ""
                activeLanguage in codes -> activeLanguage
                else -> codes.first()
            }
        }
}
