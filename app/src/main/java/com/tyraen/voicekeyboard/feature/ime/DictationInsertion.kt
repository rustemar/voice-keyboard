package com.tyraen.voicekeyboard.feature.ime

/**
 * Reads the cursor context before committing a dictated chunk. A null context can mean that a
 * remote editor connection was cancelled or timed out; it must not be treated as an empty field.
 * The caller also revalidates its connection and editor session after the remote reads.
 */
internal object DictationInsertion {

    fun insert(
        text: String,
        addTrailingSpace: Boolean,
        readBefore: () -> CharSequence?,
        readAfter: () -> CharSequence?,
        canCommit: () -> Boolean,
        commit: (String) -> Boolean
    ): Boolean {
        if (!canCommit()) return false
        val before = readBefore()?.toString() ?: return false
        val after = readAfter()?.toString() ?: return false
        val pad = DictationSpacing.decide(before, after, addTrailingSpace)
        val out = buildString {
            if (pad.leading) append(' ')
            append(text)
            if (pad.trailing) append(' ')
        }
        // Remote reads can outlive the connection/session that was valid when delivery began.
        if (!canCommit()) return false
        return commit(out)
    }
}
