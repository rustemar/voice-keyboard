package com.tyraen.voicekeyboard.feature.postprocessing

import org.junit.Assert.assertEquals
import org.junit.Test

class PostProcessingArtifactStripperTest {

    @Test fun `plain text passes through unchanged`() {
        val input = "Hello world."
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `blank text passes through unchanged`() {
        assertEquals("", PostProcessingArtifactStripper.strip(""))
        assertEquals("   ", PostProcessingArtifactStripper.strip("   "))
    }

    @Test fun `strips trailing italic Russian Примечание block — the reported case`() {
        val input = "Бампни, Дискорд, бой на очередной maintenance цикл по Мунат.\n\n" +
            "---\n\n" +
            "*(Примечание: слово «мейнтонанс» оставлено в исходном написании, так как " +
            "инструкции запрещают перефразировать текст. Если вы хотите написать " +
            "«maintenance» латиницей — уточните.)*"
        assertEquals(
            "Бампни, Дискорд, бой на очередной maintenance цикл по Мунат.",
            PostProcessingArtifactStripper.strip(input)
        )
    }

    @Test fun `strips trailing italic English Note block`() {
        val input = "Hello world.\n\n*(Note: kept verbatim per instructions.)*"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips trailing plain parenthesized Note`() {
        val input = "Hello world.\n\n(Note: foo bar)"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips trailing horizontal rule alone`() {
        val input = "Hello world.\n\n---"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips trailing asterisk separator`() {
        val input = "Hello world.\n\n***"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips trailing underscore separator`() {
        val input = "Hello world.\n\n___"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips bare keyword line at the end`() {
        val input = "Hello world.\n\nNote: I kept it as-is."
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips italic single-asterisk meta line`() {
        val input = "Hello world.\n\n*Note: kept verbatim*"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips multiline parenthesized note block`() {
        val input = "Hello world.\n\n*(Примечание: foo\nbar\nbaz.)*"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips combined separator plus note block in one pass loop`() {
        val input = "Hello world.\n\n---\n\n*(Note: foo)*\n\n---"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `does not strip parenthesized aside in the middle of text`() {
        val input = "He said (note this) and walked away."
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `does not strip three dashes inside a sentence`() {
        val input = "He said --- and stopped."
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `does not strip a legitimate trailing parenthesized phrase`() {
        val input = "Hello (world)"
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `does not strip text that happens to contain Note colon mid-sentence`() {
        // "Note:" appears mid-paragraph, not as a trailing block — must survive.
        val input = "Take note: this is the rule. Then keep going."
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `does not strip a Note keyword on the same line as content`() {
        // No blank-line separator between body and "Note:" => not a trailing meta block.
        val input = "Hello world.\nNote: still on the next line directly."
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `keeps body when only the meta block exists with no content before`() {
        // Conservative: nothing precedes the meta block, leave it.
        val input = "*(Note: foo)*"
        assertEquals(input, PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips Disclaimer block`() {
        val input = "Hello world.\n\n*(Disclaimer: I am an AI.)*"
        assertEquals("Hello world.", PostProcessingArtifactStripper.strip(input))
    }

    @Test fun `strips Замечание block`() {
        val input = "Привет.\n\n*(Замечание: оставлено как есть.)*"
        assertEquals("Привет.", PostProcessingArtifactStripper.strip(input))
    }
}
