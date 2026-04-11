package com.tyraen.voicekeyboard.feature.postprocessing

object PostProcessingPrompts {

    private const val FIX =
        "Fix punctuation, spelling. Remove all filler/hesitation words in any language (e.g. um, uh, like, well, you know, ну, э, ммм, типа, короче, вот, как бы, значит, euh, also, halt, pues, bueno, えーと, 那个). Keep profanity unchanged."

    private const val SHORTEN =
        "Make the text more concise: remove repetitions, filler words, and unnecessary verbosity, but keep ALL key points, details, and arguments. Preserve the author's style and tone. Fix spelling and punctuation. Keep profanity unchanged."

    private const val EMOJI =
        "Add 1 relevant emoji after each sentence-ending mark (.!?). For obvious humor or sarcasm use 2-3 laughing emoji. Use only common everyday emoji."

    private const val OUTPUT = "Output ONLY the resulting text, no explanations."

    fun build(fix: Boolean, shorten: Boolean, emoji: Boolean, text: String): String {
        val parts = mutableListOf<String>()
        when {
            shorten -> parts.add(SHORTEN)
            fix -> parts.add(FIX)
        }
        if (emoji) parts.add(EMOJI)
        parts.add(OUTPUT)
        return parts.joinToString(" ") + "\n\n" + text
    }

    fun hasAnyMode(fix: Boolean, shorten: Boolean, emoji: Boolean): Boolean =
        fix || shorten || emoji
}
