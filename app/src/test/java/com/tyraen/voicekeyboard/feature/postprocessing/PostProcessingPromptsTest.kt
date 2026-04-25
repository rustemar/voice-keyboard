package com.tyraen.voicekeyboard.feature.postprocessing

import com.tyraen.voicekeyboard.core.config.PostProcessingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostProcessingPromptsTest {

    private val prefs = PostProcessingPreferences()

    @Test fun `every prompt embeds the guard against the model replying to user text`() {
        val build = PostProcessingPrompts.build(fix = true, shorten = false, emoji = false, text = "test", prefs = prefs)
        assertTrue(build.systemInstruction.contains("NEVER reply"))

        val translate = PostProcessingPrompts.buildTranslate("test", "en")
        assertTrue(translate.systemInstruction.contains("NEVER reply"))

        val rhyme = PostProcessingPrompts.buildRhyme("test")
        assertTrue(rhyme.systemInstruction.contains("NEVER reply"))

        val terminal = PostProcessingPrompts.buildTerminal("test", prefs)
        assertTrue(terminal.systemInstruction.contains("NEVER reply"))
    }

    @Test fun `user text is passed through verbatim, not embedded into system instruction`() {
        val build = PostProcessingPrompts.build(fix = true, shorten = false, emoji = false, text = "raw input", prefs = prefs)
        assertEquals("raw input", build.userText)
        assertFalse(build.systemInstruction.contains("raw input"))
    }

    @Test fun `shorten takes precedence over fix when both are set`() {
        val both = PostProcessingPrompts.build(fix = true, shorten = true, emoji = false, text = "x", prefs = prefs)
        assertTrue(both.systemInstruction.contains(PostProcessingPreferences.DEFAULT_PROMPT_SHORTEN))
        assertFalse(both.systemInstruction.contains(PostProcessingPreferences.DEFAULT_PROMPT_FIX))
    }

    @Test fun `emoji prompt is appended on top of fix or shorten`() {
        val combined = PostProcessingPrompts.build(fix = true, shorten = false, emoji = true, text = "x", prefs = prefs)
        assertTrue(combined.systemInstruction.contains(PostProcessingPreferences.DEFAULT_PROMPT_FIX))
        assertTrue(combined.systemInstruction.contains(PostProcessingPreferences.DEFAULT_PROMPT_EMOJI))
    }

    @Test fun `translate target language is included by name when known`() {
        val ru = PostProcessingPrompts.buildTranslate("hello", "ru")
        assertTrue(ru.systemInstruction.contains("ru"))
    }

    @Test fun `translate falls back to language code when name is unknown`() {
        val xx = PostProcessingPrompts.buildTranslate("hello", "xx")
        assertTrue(xx.systemInstruction.contains("xx"))
    }

    @Test fun `hasAnyMode reflects the input flags`() {
        assertFalse(PostProcessingPrompts.hasAnyMode(false, false, false))
        assertTrue(PostProcessingPrompts.hasAnyMode(true, false, false))
        assertTrue(PostProcessingPrompts.hasAnyMode(false, true, false))
        assertTrue(PostProcessingPrompts.hasAnyMode(false, false, true))
    }
}
