package com.tyraen.voicekeyboard.core.config

data class PostProcessingPreferences(
    val enabled: Boolean = false,
    val provider: String = PROVIDER_OPENAI,
    val apiKey: String = "",
    val endpoint: String = "",
    val model: String = ""
) {
    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CLAUDE = "claude"

        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        const val DEFAULT_CLAUDE_ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-4-5-20251001"

        fun defaultEndpoint(provider: String): String = when (provider) {
            PROVIDER_CLAUDE -> DEFAULT_CLAUDE_ENDPOINT
            else -> DEFAULT_OPENAI_ENDPOINT
        }

        fun defaultModel(provider: String): String = when (provider) {
            PROVIDER_CLAUDE -> DEFAULT_CLAUDE_MODEL
            else -> DEFAULT_OPENAI_MODEL
        }
    }

    fun resolvedEndpoint(): String = endpoint.ifBlank { defaultEndpoint(provider) }
    fun resolvedModel(): String = model.ifBlank { defaultModel(provider) }
}
