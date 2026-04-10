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
