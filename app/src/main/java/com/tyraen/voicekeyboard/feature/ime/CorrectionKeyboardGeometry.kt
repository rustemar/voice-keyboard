package com.tyraen.voicekeyboard.feature.ime

import kotlin.math.min
import kotlin.math.roundToInt

/** A letter occupies one fixed cell even when its row contains fewer keys. */
object CorrectionKeyboardGeometry {
    data class KeyBounds(val left: Int, val width: Int) {
        val right: Int get() = left + width
    }

    data class Row(val letterWidth: Int, val keys: List<KeyBounds>, val leading: Int, val trailing: Int)

    fun columns(rows: List<List<String>>, finalRowActions: Boolean): Int {
        require(rows.isNotEmpty() && rows.all { it.isNotEmpty() })
        return maxOf(rows.maxOf { it.size }, rows.last().size + if (finalRowActions) 2 else 0)
    }

    /** Side controls can be wider than letters, while all alphabet keys keep the same pitch. */
    fun sideUnits(columns: Int, lastRowLetters: Int): Float =
        min(1.5f, (columns - lastRowLetters) / 2f).coerceAtLeast(1f)

    fun row(width: Int, columns: Int, units: List<Float>, inset: Int, gap: Int): Row {
        require(width >= 0 && columns > 0 && inset >= 0 && gap >= 0)
        require(units.isNotEmpty() && units.all { it > 0f })
        require(units.sum() <= columns + 0.001f)
        val edge = min(inset, width / 4)
        val content = (width - edge * 2).coerceAtLeast(0)
        val spacing = min(gap, content / (columns * 4))
        val letterWidth = ((content - spacing * (columns - 1)) / columns).coerceAtLeast(0)
        val widths = units.map { ((letterWidth + spacing) * it - spacing).roundToInt().coerceAtLeast(0) }
        val occupied = widths.sum() + spacing * (widths.size - 1)
        var left = ((width - occupied) / 2).coerceAtLeast(0)
        val leading = left
        val keys = widths.map { keyWidth ->
            KeyBounds(left, keyWidth).also { left += keyWidth + spacing }
        }
        return Row(letterWidth, keys, leading, (width - keys.last().right).coerceAtLeast(0))
    }

    fun availableHeight(viewport: Int, constrained: Boolean, editorReserve: Int): Int {
        require(viewport > 0 && editorReserve >= 0)
        return if (constrained) (viewport - editorReserve).coerceAtLeast(1) else viewport
    }

    fun sheetHeight(
        header: Int, instruction: Int, keyRows: Int, keyHeight: Int, rowGap: Int,
        padding: Int, available: Int
    ): Int {
        require(header >= 0 && instruction >= 0 && keyRows > 0 && keyHeight > 0)
        require(rowGap >= 0 && padding >= 0 && available > 0)
        return (header + instruction + keyRows * (keyHeight + rowGap) + padding).coerceAtMost(available)
    }
}
