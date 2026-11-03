package com.tyraen.voicekeyboard.core.ui

import android.text.Layout
import android.graphics.text.LineBreaker
import android.widget.TextView

/** Programmatically created keyboard actions use the same wrapping policy as settings. */
fun TextView.useReadableActionLabel() {
    setSingleLine(false)
    maxLines = Int.MAX_VALUE
    ellipsize = null
    breakStrategy = LineBreaker.BREAK_STRATEGY_BALANCED
    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
}
