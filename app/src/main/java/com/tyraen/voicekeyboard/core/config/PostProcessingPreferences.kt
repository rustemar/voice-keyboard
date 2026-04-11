package com.tyraen.voicekeyboard.core.config

data class PostProcessingPreferences(
    val enabled: Boolean = false,
    val provider: String = PROVIDER_OPENAI,
    val apiKey: String = "",
    val endpoint: String = "",
    val model: String = "",
    val temperature: Float = DEFAULT_TEMPERATURE,
    val promptFix: String = "",
    val promptShorten: String = "",
    val promptEmoji: String = "",
    val promptSuffix: String = ""
) {
    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CLAUDE = "claude"

        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        const val DEFAULT_CLAUDE_ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val DEFAULT_CLAUDE_MODEL = "claude-haiku-4-5-20251001"

        const val DEFAULT_TEMPERATURE = 0.3f

        const val DEFAULT_PROMPT_FIX =
            "Fix ONLY punctuation and spelling errors. Remove filler/hesitation sounds (um, uh, ммм, э, euh, えーと). Do NOT rephrase, shorten, or rewrite the text in any other way. Keep every word the author used, including profanity."

        const val DEFAULT_PROMPT_SHORTEN =
            "Make the text more concise: remove repetitions, filler words, and unnecessary verbosity, but keep ALL key points, details, and arguments. Preserve the author's style and tone. Fix spelling and punctuation. Keep profanity unchanged."

        const val DEFAULT_PROMPT_EMOJI =
            "Add 1 relevant emoji after each sentence-ending mark (.!?). For obvious humor or sarcasm use 2-3 laughing emoji. Use only common everyday emoji. Do NOT change, rephrase, or shorten the text — only insert emoji."

        const val DEFAULT_PROMPT_SUFFIX = "Output ONLY the resulting text, no explanations."

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
    fun resolvedPromptFix(): String = promptFix.ifBlank { DEFAULT_PROMPT_FIX }
    fun resolvedPromptShorten(): String = promptShorten.ifBlank { DEFAULT_PROMPT_SHORTEN }
    fun resolvedPromptEmoji(): String = promptEmoji.ifBlank { DEFAULT_PROMPT_EMOJI }
    fun resolvedPromptSuffix(): String = promptSuffix.ifBlank { DEFAULT_PROMPT_SUFFIX }
    fun resolvedTemperature(): Float = temperature
}
