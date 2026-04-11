package com.tyraen.voicekeyboard.feature.postprocessing

object PostProcessingPrompts {

    private const val FIX =
        "Fix ONLY punctuation and spelling errors. Remove filler/hesitation sounds (um, uh, ммм, э, euh, えーと). Do NOT rephrase, shorten, or rewrite the text in any other way. Keep every word the author used, including profanity."

    private const val SHORTEN =
        "Make the text more concise: remove repetitions, filler words, and unnecessary verbosity, but keep ALL key points, details, and arguments. Preserve the author's style and tone. Fix spelling and punctuation. Keep profanity unchanged."

    private const val EMOJI =
        "Add 1 relevant emoji after each sentence-ending mark (.!?). For obvious humor or sarcasm use 2-3 laughing emoji. Use only common everyday emoji. Do NOT change, rephrase, or shorten the text — only insert emoji."

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
