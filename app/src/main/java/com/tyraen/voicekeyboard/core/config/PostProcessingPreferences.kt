package com.tyraen.voicekeyboard.core.config

import com.tyraen.voicekeyboard.core.network.ApiEndpoint

data class PostProcessingPreferences(
    val enabled: Boolean = false,
    val provider: String = PROVIDER_CLAUDE,
    val apiKey: String = "",
    val endpoint: String = "",
    val model: String = "",
    val temperature: Float = DEFAULT_TEMPERATURE,
    val promptFix: String = "",
    val promptShorten: String = "",
    val promptEmoji: String = "",
    val promptSuffix: String = "",
    val translateLang: String = "en",
    val translateModel: String = "",
    val terminalVisible: Boolean = false,
    val contextEnabled: Boolean = false
) {
    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_CLAUDE = "claude"

        const val OPENAI_REQUEST_PATH = "/chat/completions"
        const val DEFAULT_OPENAI_ENDPOINT = "https://api.openai.com/v1$OPENAI_REQUEST_PATH"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"

        const val CLAUDE_REQUEST_PATH = "/messages"
        const val DEFAULT_CLAUDE_ENDPOINT = "https://api.anthropic.com/v1$CLAUDE_REQUEST_PATH"
        const val DEFAULT_CLAUDE_MODEL = "claude-sonnet-4-6"

        /**
         * Hosts that speak exactly one of the two request dialects. Used only to read a pasted
         * base URL, where the path says nothing yet; a full path always wins (see [providerFor]).
         */
        private val OPENAI_STYLE_HOSTS = setOf(
            "api.openai.com", "openrouter.ai", "api.groq.com", "api.mistral.ai",
            "api.together.xyz", "api.deepseek.com"
        )
        private const val ANTHROPIC_HOST = "api.anthropic.com"

        const val DEFAULT_OPENAI_TRANSLATE_MODEL = "gpt-4o"
        const val DEFAULT_CLAUDE_TRANSLATE_MODEL = "claude-sonnet-4-6"

        const val DEFAULT_TEMPERATURE = 0.3f

        const val DEFAULT_PROMPT_FIX =
            "Fix punctuation, spelling, and obvious transcription errors. " +
            "Remove filler and hesitation sounds (um, uh, like, you know, ммм, э, ну, типа, euh, えーと). " +
            "Remove false starts, stutters, and accidental repetitions (\"I— I think\", \"я хотел... я хотел сказать\"). " +
            "For self-corrections (\"wait no\", \"I meant\", \"scratch that\", \"не, я имел в виду\") keep only the corrected version. " +
            "Preserve the speaker's voice, tone, vocabulary, and intent. " +
            "Keep technical terms, proper nouns, names, and profanity exactly as spoken. " +
            "Do NOT rephrase, shorten, or restructure the text. " +
            "If the input is empty or only filler, output nothing."

        const val DEFAULT_PROMPT_SHORTEN =
            "Make the text more concise: remove repetitions, filler words, and unnecessary verbosity, " +
            "but keep ALL key points, details, and arguments. " +
            "Remove false starts, stutters, and accidental repetitions. " +
            "For self-corrections (\"wait no\", \"I meant\", \"scratch that\", \"не, я имел в виду\") keep only the corrected version. " +
            "Preserve the author's voice, tone, and intent. " +
            "Keep technical terms, proper nouns, names, and profanity exactly as spoken. " +
            "Fix spelling and punctuation. " +
            "If the input is empty or only filler, output nothing."

        const val DEFAULT_PROMPT_EMOJI =
            "Add relevant emoji to the text sparingly — at most 30% of sentences should get an emoji, but always at least one. " +
            "Place emoji after sentence-ending punctuation (.!?) where they feel most natural and expressive. " +
            "For obvious humor or sarcasm use 2-3 laughing emoji. " +
            "Use only common everyday emoji. Do NOT change, rephrase, or shorten the text — only insert emoji."

        const val DEFAULT_PROMPT_TERMINAL =
            "The user is dictating commands for a terminal/SSH session using voice in their native language. " +
            "Convert the dictated text into valid shell commands. " +
            "The user may either: (1) describe what they want in natural language (e.g. 'change directory to home' → 'cd ~', " +
            "'show files' → 'ls', 'find all log files' → 'find / -name \"*.log\"'), or " +
            "(2) dictate commands directly, using words like 'space', 'slash', 'dot', 'dash', 'tilde', 'pipe', 'flag' as literal separators " +
            "(e.g. 'ls space dash la' → 'ls -la'). " +
            "Interpret the intent and output ONLY the resulting shell command(s), one per line. " +
            "Do not add explanations, comments, or markdown formatting. Do not wrap in code blocks."

        const val DEFAULT_PROMPT_SUFFIX = "Output ONLY the resulting text, no explanations."

        /**
         * Default prompt texts from earlier releases. The settings screen used to persist the
         * prefilled default as a user override on Apply, so anyone who pressed Apply stayed on
         * the wording of that version forever. Stored prompts equal to any of these are treated
         * as "use the current default" (see [normalizePrompt]).
         */
        val LEGACY_DEFAULT_PROMPTS: Set<String> = setOf(
            "Fix ONLY punctuation and spelling errors. Remove filler/hesitation sounds (um, uh, ммм, э, euh, えーと). " +
                "Do NOT rephrase, shorten, or rewrite the text in any other way. Keep every word the author used, including profanity.",
            "Make the text more concise: remove repetitions, filler words, and unnecessary verbosity, but keep ALL key points, " +
                "details, and arguments. Preserve the author's style and tone. Fix spelling and punctuation. Keep profanity unchanged.",
            "Add 1 relevant emoji after each sentence-ending mark (.!?). For obvious humor or sarcasm use 2-3 laughing emoji. " +
                "Use only common everyday emoji. Do NOT change, rephrase, or shorten the text — only insert emoji."
        )

        /** A prompt that merely repeats a shipped default (current or past) is stored as blank. */
        fun normalizePrompt(value: String, currentDefault: String): String {
            val trimmed = value.trim()
            return if (trimmed == currentDefault || trimmed in LEGACY_DEFAULT_PROMPTS) "" else trimmed
        }

        fun defaultEndpoint(provider: String): String = when (provider) {
            PROVIDER_CLAUDE -> DEFAULT_CLAUDE_ENDPOINT
            else -> DEFAULT_OPENAI_ENDPOINT
        }

        /** Request path appended to a pasted base URL, per provider dialect. */
        fun defaultPath(provider: String): String = when (provider) {
            PROVIDER_CLAUDE -> CLAUDE_REQUEST_PATH
            else -> OPENAI_REQUEST_PATH
        }

        /**
         * Which provider dialect an address speaks, when the address makes it obvious; null
         * otherwise. The path is the strongest signal: only OpenAI-style APIs serve
         * `/chat/completions`, only Anthropic-style ones serve `/messages`. For a bare base URL
         * the host has to decide, and only hosts we know are consulted. The settings screen uses
         * this to flip the provider spinner when it contradicts the pasted address.
         */
        fun providerFor(endpoint: String): String? {
            val path = ApiEndpoint.pathOf(ApiEndpoint.complete(endpoint, ""))
            if (path.endsWith(OPENAI_REQUEST_PATH)) return PROVIDER_OPENAI
            if (path.endsWith(CLAUDE_REQUEST_PATH)) return PROVIDER_CLAUDE
            return when (val host = ApiEndpoint.hostOf(ApiEndpoint.complete(endpoint, ""))) {
                "" -> null
                ANTHROPIC_HOST -> PROVIDER_CLAUDE
                in OPENAI_STYLE_HOSTS -> PROVIDER_OPENAI
                else -> null
            }
        }

        fun defaultModel(provider: String): String = when (provider) {
            PROVIDER_CLAUDE -> DEFAULT_CLAUDE_MODEL
            else -> DEFAULT_OPENAI_MODEL
        }

        fun defaultTranslateModel(provider: String): String = when (provider) {
            PROVIDER_CLAUDE -> DEFAULT_CLAUDE_TRANSLATE_MODEL
            else -> DEFAULT_OPENAI_TRANSLATE_MODEL
        }
    }

    /** The stored address with a pasted base URL completed to the full request path. */
    fun resolvedEndpoint(): String =
        ApiEndpoint.complete(endpoint, defaultPath(provider)).ifBlank { defaultEndpoint(provider) }

    fun resolvedModel(): String = model.ifBlank { defaultModel(provider) }

    /**
     * Model for translate / rhyme: the explicit override, else a stronger default. That default
     * only exists on the provider's own API — on OpenRouter, Groq or a proxy "gpt-4o" is unknown
     * and translation would silently fall back to the untranslated text. There, reuse the model
     * the user configured instead of guessing.
     */
    fun resolvedTranslateModel(): String {
        if (translateModel.isNotBlank()) return translateModel
        val ownApi = endpoint.isBlank() ||
            ApiEndpoint.hostOf(resolvedEndpoint()) == ApiEndpoint.hostOf(defaultEndpoint(provider))
        return if (ownApi || model.isBlank()) defaultTranslateModel(provider) else model
    }
    /** The same preferences with default-repeating prompts blanked out. */
    fun withNormalizedPrompts(): PostProcessingPreferences = copy(
        promptFix = normalizePrompt(promptFix, DEFAULT_PROMPT_FIX),
        promptShorten = normalizePrompt(promptShorten, DEFAULT_PROMPT_SHORTEN),
        promptEmoji = normalizePrompt(promptEmoji, DEFAULT_PROMPT_EMOJI),
        promptSuffix = normalizePrompt(promptSuffix, DEFAULT_PROMPT_SUFFIX)
    )

    fun resolvedPromptFix(): String = promptFix.ifBlank { DEFAULT_PROMPT_FIX }
    fun resolvedPromptShorten(): String = promptShorten.ifBlank { DEFAULT_PROMPT_SHORTEN }
    fun resolvedPromptEmoji(): String = promptEmoji.ifBlank { DEFAULT_PROMPT_EMOJI }
    fun resolvedPromptTerminal(): String = DEFAULT_PROMPT_TERMINAL
    fun resolvedPromptSuffix(): String = promptSuffix.ifBlank { DEFAULT_PROMPT_SUFFIX }
    fun resolvedTemperature(): Float = temperature
}
