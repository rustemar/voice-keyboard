package com.tyraen.voicekeyboard.feature.vocabulary

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Local rules and LLM reference terms never become speech-recognition hints implicitly. */
object TextDictionary {
    data class Prepared(val text: String, val vocabulary: String)
    private data class Compiled(val encoded: String, val matcher: TextReplacementRules.Matcher)
    @Volatile private var cached: Compiled? = null

    fun encodeRules(rules: List<TextReplacementRules.Rule>): String {
        require(TextReplacementRules.validate(rules).isEmpty()) { "Invalid replacement rules" }
        return JSONArray().apply {
            rules.forEach { put(JSONObject().put("source", it.source).put("target", it.target)) }
        }.toString()
    }

    fun decodeRules(encoded: String): List<TextReplacementRules.Rule> {
        if (encoded.isBlank()) return emptyList()
        // JSON overhead is bounded too; malformed private storage must not cause unbounded work.
        if (encoded.length > 400_000) return emptyList()
        return runCatching {
            val array = JSONArray(encoded)
            require(array.length() <= TextReplacementRules.MAX_RULES)
            val rules = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TextReplacementRules.Rule(item.getString("source"), item.getString("target"))
            }
            rules.takeIf { TextReplacementRules.validate(it).isEmpty() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun normalizeVocabulary(raw: String): String = raw.lineSequence().map(String::trim)
        .filter(String::isNotEmpty).distinct().joinToString("\n")

    fun mergeVocabulary(vararg sources: String): String {
        val seen = HashSet<String>()
        return sources.asSequence().flatMap { it.lineSequence() }.map(String::trim)
            .filter { it.isNotEmpty() && seen.add(it.lowercase(Locale.ROOT)) }.joinToString("\n")
    }

    /** Run once on raw recognition, before LLM stages, never on translated or generated code. */
    fun prepare(text: String, recognitionHints: String, postProcessingVocabulary: String,
                replacementRules: String): Prepared {
        val matcher = cached?.takeIf { it.encoded == replacementRules }?.matcher
            ?: TextReplacementRules.compile(decodeRules(replacementRules)).also {
                cached = Compiled(replacementRules, it)
            }
        val result = matcher.applyWithDetails(text)
        // Only targets actually inserted into this dictation become extra LLM references.
        val references = mergeVocabulary(result.targets.joinToString("\n"),
            postProcessingVocabulary, recognitionHints)
        return Prepared(result.text, references)
    }
}
