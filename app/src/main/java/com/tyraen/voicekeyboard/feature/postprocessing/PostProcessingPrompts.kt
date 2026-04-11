package com.tyraen.voicekeyboard.feature.postprocessing

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences

object PostProcessingPrompts {

    fun build(fix: Boolean, shorten: Boolean, emoji: Boolean, text: String, prefs: PostProcessingPreferences): String {
        val parts = mutableListOf<String>()
        when {
            shorten -> parts.add(prefs.resolvedPromptShorten())
            fix -> parts.add(prefs.resolvedPromptFix())
        }
        if (emoji) parts.add(prefs.resolvedPromptEmoji())
        parts.add(prefs.resolvedPromptSuffix())
        return parts.joinToString(" ") + "\n\n" + text
    }

    fun hasAnyMode(fix: Boolean, shorten: Boolean, emoji: Boolean): Boolean =
        fix || shorten || emoji
}
