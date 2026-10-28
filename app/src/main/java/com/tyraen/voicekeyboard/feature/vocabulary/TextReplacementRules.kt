package com.tyraen.voicekeyboard.feature.vocabulary

import java.util.ArrayDeque

/** User-authored, literal replacements. No rule text is interpreted as a regular expression. */
object TextReplacementRules {
    // These are local resource limits, independent of the speech model's prompt/token budget.
    const val MAX_CHARS = 100_000
    const val MAX_RULES = 5_000
    const val MAX_SOURCE_CHARS = 512
    const val MAX_TARGET_CHARS = 2_048

    data class Rule(val source: String, val target: String)

    enum class ErrorKind {
        INPUT_TOO_LONG,
        TOO_MANY_RULES,
        MISSING_SEPARATOR,
        EMPTY_SOURCE,
        EMPTY_TARGET,
        SOURCE_TOO_LONG,
        TARGET_TOO_LONG,
        DUPLICATE_SOURCE,
        INVALID_CHARACTERS
    }

    /** A zero line refers to the whole definition; ordinary errors use one-based line numbers. */
    data class ParseError(val line: Int, val kind: ErrorKind)

    data class ParseResult(val rules: List<Rule>, val errors: List<ParseError>) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    data class ReplacementResult(val text: String, val targets: List<String>)

    /**
     * Optional bulk-entry syntax: one `source => target` or `source → target` per line.
     * The first delimiter separates the fields; later delimiters are literal target text.
     * Empty lines are ignored. An invalid definition never exposes a partially usable rule set.
     */
    fun parse(raw: String): ParseResult {
        if (raw.length > MAX_CHARS) {
            return ParseResult(emptyList(), listOf(ParseError(0, ErrorKind.INPUT_TOO_LONG)))
        }
        val rules = ArrayList<Rule>()
        val lines = ArrayList<Int>()
        val errors = ArrayList<ParseError>()
        raw.lineSequence().forEachIndexed { index, line ->
            if (line.isBlank()) return@forEachIndexed
            val ascii = line.indexOf("=>")
            val arrow = line.indexOf('→')
            val delimiter = when {
                ascii < 0 -> arrow
                arrow < 0 -> ascii
                else -> minOf(ascii, arrow)
            }
            if (delimiter < 0) {
                errors.add(ParseError(index + 1, ErrorKind.MISSING_SEPARATOR))
                return@forEachIndexed
            }
            val delimiterLength = if (line.startsWith("=>", delimiter)) 2 else 1
            rules.add(Rule(line.substring(0, delimiter).trim(), line.substring(delimiter + delimiterLength).trim()))
            lines.add(index + 1)
        }
        errors.addAll(validateInternal(rules, lines))
        return ParseResult(if (errors.isEmpty()) rules.toList() else emptyList(), errors.sortedBy { it.line })
    }

    /** Validate structured UI/storage values without serializing them through the bulk syntax. */
    fun validate(rules: List<Rule>): List<ParseError> = validateInternal(rules, null)

    private fun validateInternal(rules: List<Rule>, lines: List<Int>?): List<ParseError> {
        val errors = ArrayList<ParseError>()
        val sources = HashSet<String>()
        var size = 0L
        rules.forEachIndexed { index, rule ->
            val line = lines?.get(index) ?: index + 1
            size += rule.source.length.toLong() + rule.target.length + 4L
            if (index >= MAX_RULES) {
                if (index == MAX_RULES) errors.add(ParseError(line, ErrorKind.TOO_MANY_RULES))
                return@forEachIndexed
            }
            when {
                rule.source.isBlank() -> errors.add(ParseError(line, ErrorKind.EMPTY_SOURCE))
                rule.source.length > MAX_SOURCE_CHARS -> errors.add(ParseError(line, ErrorKind.SOURCE_TOO_LONG))
                containsInvalidCharacters(rule.source) -> errors.add(ParseError(line, ErrorKind.INVALID_CHARACTERS))
                !sources.add(folded(rule.source)) -> errors.add(ParseError(line, ErrorKind.DUPLICATE_SOURCE))
            }
            when {
                rule.target.isBlank() -> errors.add(ParseError(line, ErrorKind.EMPTY_TARGET))
                rule.target.length > MAX_TARGET_CHARS -> errors.add(ParseError(line, ErrorKind.TARGET_TOO_LONG))
                containsInvalidCharacters(rule.target) -> errors.add(ParseError(line, ErrorKind.INVALID_CHARACTERS))
            }
        }
        if (size > MAX_CHARS) errors.add(ParseError(0, ErrorKind.INPUT_TOO_LONG))
        return errors
    }

    fun compile(rules: List<Rule>): Matcher {
        val snapshot = rules.toList()
        require(validate(snapshot).isEmpty()) { "Invalid text replacement rules" }
        return Matcher(snapshot)
    }

    /**
     * Immutable Aho-Corasick matcher. Scanning is independent of the number of unrelated rules;
     * output-link traversal is bounded by MAX_SOURCE_CHARS. Original UTF-16 offsets are retained.
     *
     * Matching uses locale-independent simple Unicode case folding, without transliteration or
     * accent removal. Whole-phrase boundaries include letters, numbers, combining marks and
     * connectors. This deliberately does not guess word boundaries inside unspaced CJK text.
     */
    class Matcher internal constructor(private val rules: List<Rule>) {
        private class Node {
            val edges = HashMap<Int, Int>()
            var fallback = 0
            var terminal = -1
            var output = -1
        }

        private val nodes = ArrayList<Node>().apply { add(Node()) }
        private val lengths = IntArray(rules.size)

        init {
            rules.forEachIndexed { index, rule ->
                var state = 0
                var offset = 0
                while (offset < rule.source.length) {
                    val codePoint = rule.source.codePointAt(offset)
                    val key = fold(codePoint)
                    state = nodes[state].edges.getOrPut(key) {
                        nodes.add(Node())
                        nodes.lastIndex
                    }
                    lengths[index]++
                    offset += Character.charCount(codePoint)
                }
                nodes[state].terminal = index
            }
            val queue = ArrayDeque<Int>()
            nodes[0].edges.values.forEach { queue.addLast(it) }
            while (queue.isNotEmpty()) {
                val state = queue.removeFirst()
                nodes[state].edges.forEach { (key, next) ->
                    var fallback = nodes[state].fallback
                    while (fallback != 0 && key !in nodes[fallback].edges) fallback = nodes[fallback].fallback
                    nodes[next].fallback = nodes[fallback].edges[key] ?: 0
                    val suffix = nodes[next].fallback
                    nodes[next].output = if (nodes[suffix].terminal >= 0) suffix else nodes[suffix].output
                    queue.addLast(next)
                }
            }
        }

        fun apply(text: String): String = applyWithDetails(text).text

        /** Targets contain only replacements actually selected in the leftmost-longest pass. */
        fun applyWithDetails(text: String): ReplacementResult {
            if (text.isEmpty() || rules.isEmpty()) return ReplacementResult(text, emptyList())
            val count = text.codePointCount(0, text.length)
            val points = IntArray(count)
            val offsets = IntArray(count + 1)
            var offset = 0
            for (index in 0 until count) {
                offsets[index] = offset
                points[index] = text.codePointAt(offset)
                offset += Character.charCount(points[index])
            }
            offsets[count] = text.length

            val bestRule = IntArray(count) { -1 }
            val bestEnd = IntArray(count)
            var state = 0
            for (index in points.indices) {
                val key = fold(points[index])
                while (state != 0 && key !in nodes[state].edges) state = nodes[state].fallback
                state = nodes[state].edges[key] ?: 0
                if (index + 1 < count && isWordContinuation(points[index + 1])) continue
                var output = if (nodes[state].terminal >= 0) state else nodes[state].output
                while (output >= 0) {
                    val rule = nodes[output].terminal
                    val start = index + 1 - lengths[rule]
                    if ((start == 0 || !isWordContinuation(points[start - 1])) && index + 1 > bestEnd[start]) {
                        bestRule[start] = rule
                        bestEnd[start] = index + 1
                    }
                    output = nodes[output].output
                }
            }

            var result: StringBuilder? = null
            val targets = LinkedHashSet<String>()
            var index = 0
            var unchangedStart = 0
            while (index < count) {
                val rule = bestRule[index]
                if (rule < 0) {
                    index++
                    continue
                }
                if (result == null) result = StringBuilder(text.length)
                result.append(text, unchangedStart, offsets[index])
                val target = rules[rule].target
                result.append(target)
                targets.add(target)
                index = bestEnd[index]
                unchangedStart = offsets[index]
            }
            if (result == null) return ReplacementResult(text, emptyList())
            result.append(text, unchangedStart, text.length)
            return ReplacementResult(result.toString(), targets.toList())
        }
    }

    private fun fold(codePoint: Int): Int = Character.toLowerCase(Character.toUpperCase(codePoint))

    private fun folded(text: String): String = buildString {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            appendCodePoint(fold(codePoint))
            index += Character.charCount(codePoint)
        }
    }

    private fun containsInvalidCharacters(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                if (index + 1 >= text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index += 2
                continue
            }
            if (Character.isLowSurrogate(char)) return true
            val type = Character.getType(char)
            if (Character.isISOControl(char) || type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()) return true
            index++
        }
        return false
    }

    private fun isWordContinuation(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) || when (Character.getType(codePoint)) {
            Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
            Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(), Character.CONNECTOR_PUNCTUATION.toInt() -> true
            else -> codePoint == 0x200C || codePoint == 0x200D ||
                codePoint in 0x1F3FB..0x1F3FF || codePoint in 0x1F1E6..0x1F1FF ||
                codePoint in 0xE0020..0xE007F
        }
}
