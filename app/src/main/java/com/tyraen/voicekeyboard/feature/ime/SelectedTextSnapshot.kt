package com.tyraen.voicekeyboard.feature.ime

/** A replacement is valid only while the exact original selection and its neighbours survive. */
data class SelectedTextSnapshot(
    val editorSession: Long,
    val start: Int,
    val end: Int,
    val text: String,
    val before: String,
    val after: String
) {
    init {
        require(start >= 0 && end >= start)
        require(end - start == text.length)
    }

    fun matches(current: SelectedTextSnapshot?): Boolean = current == this

    fun afterReplacement(replacement: String): SelectedTextSnapshot = copy(
        end = start + replacement.length,
        text = replacement
    )
}
