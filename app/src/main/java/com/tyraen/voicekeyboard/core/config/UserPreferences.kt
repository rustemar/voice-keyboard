package com.tyraen.voicekeyboard.core.config

data class UserPreferences(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val language: String,
    val autoRecord: Boolean,
    val addTrailingSpace: Boolean,
    val prompt: String
)
