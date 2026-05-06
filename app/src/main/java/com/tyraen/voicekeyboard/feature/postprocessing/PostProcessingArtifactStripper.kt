package com.tyraen.voicekeyboard.feature.postprocessing

/**
 * Defensive cleanup for trailing meta-commentary that LLMs occasionally append
 * despite the GUARD prompt. Strips well-known shapes from the end of the text;
 * leaves anything that doesn't match alone.
 */
object PostProcessingArtifactStripper {

    private val KEYWORDS = "Примечание|примечание|Note|note|Замечание|замечание|" +
        "Комментарий|комментарий|Comment|comment|Footnote|footnote|Caveat|caveat|" +
        "Disclaimer|disclaimer"

    private val PATTERNS: List<Regex> = listOf(
        // *(Note: ...)*  /  (Note: ...)  — multiline body allowed.
        Regex("""\n\s*\*?\(\s*(?:$KEYWORDS)\s*[:：][\s\S]*?\)\*?\s*$"""),
        // *Note: ...*  /  _Note: ..._  — single-line italic.
        Regex("""\n\s*[*_](?:$KEYWORDS)\s*[:：][^\n]*?[*_]\s*$"""),
        // Bare line at the end starting with a keyword: "Note: ..." or "Примечание: ...",
        // separated from the body by a blank line.
        Regex("""\n\s*\n\s*(?:$KEYWORDS)\s*[:：][^\n]*\s*$"""),
        // Trailing horizontal-rule line (---, ***, ___).
        Regex("""\n\s*[-*_]{3,}\s*$"""),
    )

    fun strip(text: String): String {
        if (text.isBlank()) return text
        var s = text
        // Bounded loop: each iteration must remove something or we exit.
        // 16 is generous; real outputs converge in 1–3 passes.
        repeat(16) {
            val before = s
            val trimmed = s.trimEnd()
            if (trimmed != s) s = trimmed
            for (p in PATTERNS) {
                val replaced = p.replace(s, "")
                if (replaced != s) s = replaced
            }
            if (s == before) return s
        }
        return s
    }
}
