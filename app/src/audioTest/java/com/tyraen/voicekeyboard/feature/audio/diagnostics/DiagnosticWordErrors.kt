package com.tyraen.voicekeyboard.feature.audio.diagnostics

import java.text.Normalizer
import java.util.Locale

/** Word edit distance, ignoring case/punctuation. Digits and written numbers remain distinct. */
object DiagnosticWordErrors {
    data class Score(val referenceWords: Int, val errors: Int) {
        val percent: Double get() = errors * 100.0 / referenceWords
    }

    fun compare(reference: String, hypothesis: String): Score? {
        fun words(value: String) = Regex("[\\p{L}\\p{N}]+")
            .findAll(Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
            .map { it.value }.toList()
        val expected = words(reference)
        val actual = words(hypothesis)
        if (expected.isEmpty()) return null
        require(expected.size <= 2_000 && actual.size <= 4_000) { "Transcript too long for word comparison" }
        var previous = IntArray(actual.size + 1) { it }
        for (i in expected.indices) {
            val current = IntArray(actual.size + 1)
            current[0] = i + 1
            for (j in actual.indices) current[j + 1] = minOf(
                previous[j + 1] + 1, current[j] + 1,
                previous[j] + if (expected[i] == actual[j]) 0 else 1
            )
            previous = current
        }
        return Score(expected.size, previous.last())
    }
}
