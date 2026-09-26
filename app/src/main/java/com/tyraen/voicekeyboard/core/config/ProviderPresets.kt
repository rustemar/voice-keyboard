package com.tyraen.voicekeyboard.core.config

import com.tyraen.voicekeyboard.core.network.ApiEndpoint

/**
 * A short list of providers the settings screens can fill in with one tap. Each preset only
 * prefills the editable address and model fields; base-URL completion and the dialect switch
 * keep working for anything not listed. Kept deliberately short: a long host table rots.
 */
object ProviderPresets {

    data class Preset(
        val name: String,
        val endpoint: String,
        /** Blank means "leave the field empty", i.e. the provider's built-in default model. */
        val model: String,
        /** Post-processing dialect; null for speech-to-text presets. */
        val provider: String? = null
    )

    val speechToText: List<Preset> = listOf(
        Preset("Groq", "https://api.groq.com/openai/v1/audio/transcriptions", "whisper-large-v3-turbo"),
        Preset("OpenAI", "https://api.openai.com/v1/audio/transcriptions", "whisper-1"),
        Preset("Mistral", "https://api.mistral.ai/v1/audio/transcriptions", "voxtral-mini-latest"),
    )

    val postProcessing: List<Preset> = listOf(
        Preset("Anthropic (Claude)", PostProcessingPreferences.DEFAULT_CLAUDE_ENDPOINT, "", PostProcessingPreferences.PROVIDER_CLAUDE),
        Preset("OpenAI", PostProcessingPreferences.DEFAULT_OPENAI_ENDPOINT, "", PostProcessingPreferences.PROVIDER_OPENAI),
        Preset("OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4o-mini", PostProcessingPreferences.PROVIDER_OPENAI),
        Preset("Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b", PostProcessingPreferences.PROVIDER_OPENAI),
        Preset("Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", PostProcessingPreferences.PROVIDER_OPENAI),
        Preset("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-flash", PostProcessingPreferences.PROVIDER_OPENAI),
    )

    /** Index of the preset served by the host of [endpoint]; -1 when the address is custom or blank. */
    fun indexOf(presets: List<Preset>, endpoint: String): Int {
        val host = ApiEndpoint.hostOf(ApiEndpoint.complete(endpoint, ""))
        if (host.isEmpty()) return -1
        return presets.indexOfFirst { ApiEndpoint.hostOf(it.endpoint) == host }
    }
}
