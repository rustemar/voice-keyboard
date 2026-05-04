package com.tyraen.voicekeyboard.feature.transcription

import java.io.File

data class TranscriptionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val language: String,
    val prompt: String
)

interface SpeechToTextClient {
    suspend fun transcribe(audioFile: File, config: TranscriptionConfig): Result<String>
    suspend fun validateCredentials(apiKey: String, endpoint: String, model: String, cacheDir: File): Result<String>
}

object WhisperPromptBuilder {

    /**
     * Combines the locale style hint with the user's custom vocabulary.
     * The vocabulary is stored newline-separated; emitted comma-separated so Whisper
     * sees a natural list it can use as bias context. Whisper caps `prompt` at ~224 tokens —
     * the UI enforces a 600-character limit upstream which keeps us under that for any locale.
     */
    fun build(stylePrompt: String, vocabularyRaw: String): String {
        val vocabPart = vocabularyRaw
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
        return when {
            stylePrompt.isBlank() && vocabPart.isEmpty() -> ""
            stylePrompt.isBlank() -> vocabPart
            vocabPart.isEmpty() -> stylePrompt
            else -> "$stylePrompt $vocabPart"
        }
    }
}
