package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CorrectionKeyboardGeometryTest {
    @Test fun `shorter alphabet rows never stretch their letters`() {
        for (width in listOf(280, 320, 360, 411, 600, 900, 1280)) {
            for (layout in QuickCorrectionLayouts.all) {
                val flank = layout.rows.size <= 3
                val columns = CorrectionKeyboardGeometry.columns(layout.rows, flank)
                val full = CorrectionKeyboardGeometry.row(width, columns, List(columns) { 1f }, 2, 4)
                for ((index, letters) in layout.rows.withIndex()) {
                    val final = flank && index == layout.rows.lastIndex
                    val side = CorrectionKeyboardGeometry.sideUnits(columns, letters.size)
                    val units = if (final) listOf(side) + List(letters.size) { 1f } + side
                        else List(letters.size) { 1f }
                    val row = CorrectionKeyboardGeometry.row(width, columns, units, 2, 4)
                    val characterKeys = if (final) row.keys.drop(1).dropLast(1) else row.keys
                    characterKeys.forEach { key ->
                        assertEquals("${layout.code} at $width", full.letterWidth, key.width)
                    }
                    assertTrue("${layout.code} is not centered", abs(row.leading - row.trailing) <= 1)
                    assertTrue(row.keys.first().left >= 0)
                    assertTrue(row.keys.last().right <= width)
                }
            }
        }
    }

    @Test fun `Russian third row shares first row letter pitch`() {
        val rows = QuickCorrectionLayouts.resolve("ru")!!.rows
        val columns = CorrectionKeyboardGeometry.columns(rows, true)
        assertEquals(11, columns)
        val first = CorrectionKeyboardGeometry.row(411, columns, List(rows[0].size) { 1f }, 2, 4)
        val third = CorrectionKeyboardGeometry.row(411, columns,
            listOf(1f) + List(rows[2].size) { 1f } + 1f, 2, 4)
        assertEquals(first.keys.map { it.width }.distinct(), third.keys.map { it.width }.distinct())
    }

    @Test fun `numeric row has ten equal keys and no excessive edge space`() {
        for (width in listOf(280, 320, 360, 411, 600, 900, 1280)) {
            val row = CorrectionKeyboardGeometry.row(width, 10, List(10) { 1f }, 2, 4)
            assertEquals(10, row.keys.size)
            assertEquals(1, row.keys.map { it.width }.distinct().size)
            assertTrue(row.leading <= 10)
            assertTrue(row.trailing <= 10)
        }
    }

    @Test fun `Hindi keeps its alphabet order in four equal readable rows`() {
        val layout = QuickCorrectionLayouts.resolve("hi")!!
        assertEquals(4, layout.rows.size)
        assertTrue(layout.rows.all { it.size == 11 })
        assertEquals("अआइईउऊऋएऐओऔकखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसह",
            layout.rows.flatten().joinToString(""))
    }

    @Test fun `sheet height follows current content instead of old instruction height`() {
        val ordinary = CorrectionKeyboardGeometry.sheetHeight(52, 0, 4, 48, 4, 58, 800)
        val instruction = CorrectionKeyboardGeometry.sheetHeight(52, 100, 4, 48, 4, 58, 800)
        val restored = CorrectionKeyboardGeometry.sheetHeight(52, 0, 4, 48, 4, 58, 800)
        assertEquals(318, ordinary)
        assertEquals(ordinary + 100, instruction)
        assertEquals(ordinary, restored)
        assertEquals(260, CorrectionKeyboardGeometry.sheetHeight(46, 0, 6, 42, 4, 52, 260))
    }

    @Test fun `footer controls fit alongside space at narrow widths`() {
        val units = listOf(1.5f, 1f, 3.75f, 1f, 1.25f, 1.5f)
        for (width in listOf(280, 320, 360, 411, 900)) {
            val row = CorrectionKeyboardGeometry.row(width, 10, units, 2, 4)
            assertTrue(row.keys.last().right <= width)
            assertTrue(row.keys[2].width > row.keys[0].width * 2)
            row.keys.zipWithNext().forEach { (first, second) -> assertTrue(first.right < second.left) }
        }
    }

    @Test fun `short windows retain a receiving editor viewport while portrait stays natural`() {
        assertEquals(291, CorrectionKeyboardGeometry.availableHeight(387, true, 96))
        assertEquals(800, CorrectionKeyboardGeometry.availableHeight(800, false, 96))
        assertEquals(1, CorrectionKeyboardGeometry.availableHeight(80, true, 96))
        val available = CorrectionKeyboardGeometry.availableHeight(387, true, 96)
        assertEquals(available, CorrectionKeyboardGeometry.sheetHeight(46, 0, 5, 42, 4, 52, available))
        assertEquals(318, CorrectionKeyboardGeometry.sheetHeight(52, 0, 4, 48, 4, 58, 800))
    }
}
