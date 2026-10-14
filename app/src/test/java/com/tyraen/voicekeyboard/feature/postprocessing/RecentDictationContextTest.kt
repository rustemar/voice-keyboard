package com.tyraen.voicekeyboard.feature.postprocessing

import org.junit.Assert.*
import org.junit.Test

class RecentDictationContextTest {
    private var time = 0L
    private val context = RecentDictationContext { time }

    private fun start(): Long {
        context.setScope("chat.app")
        return context.captureToken()!!
    }

    @Test fun `unknown or private editor neither reads nor remembers context`() {
        assertNull(context.captureToken())
        context.remember(null, "private text")
        val token = start()
        assertTrue(context.snapshot(token).isEmpty())
        context.remember(token, "ordinary text")
        context.setScope(null)
        assertNull(context.captureToken())
        context.remember(token, "late private result")
        assertTrue(context.snapshot(start()).isEmpty())
    }

    @Test fun `history contains only the five latest completed messages in order`() {
        val token = start()
        for (i in 1..8) context.remember(token, "message $i")
        assertEquals((4..8).map { "message $it" }, context.snapshot(token))
    }

    @Test fun `each message is bounded so five long dictations cannot grow requests without limit`() {
        val token = start()
        repeat(7) { context.remember(token, "x".repeat(20_000)) }
        val history = context.snapshot(token)
        assertEquals(5, history.size)
        assertEquals(5_000, history.sumOf { it.length })
    }

    @Test fun `truncation preserves unicode surrogate pairs`() {
        val token = start()
        context.remember(token, "a".repeat(999) + "😀tail")
        assertEquals("a".repeat(999), context.snapshot(token).single())
    }

    @Test fun `blank outputs never displace useful history`() {
        val token = start()
        context.remember(token, "  useful text  ")
        repeat(6) { context.remember(token, " \n\t ") }
        assertEquals(listOf("useful text"), context.snapshot(token))
    }

    @Test fun `clearing invalidates in-flight reads and late completions`() {
        val old = start()
        context.remember(old, "old text")
        context.clear()
        context.remember(old, "late result")
        assertTrue(context.snapshot(old).isEmpty())
        val fresh = context.captureToken()!!
        assertNotEquals(old, fresh)
        assertTrue(context.snapshot(fresh).isEmpty())
        context.remember(fresh, "new text")
        assertEquals(listOf("new text"), context.snapshot(fresh))
    }

    @Test fun `switching away and back cannot revive another application's pending result`() {
        val old = start()
        context.remember(old, "chat text")
        context.setScope("work.app")
        val work = context.captureToken()!!
        context.remember(old, "late chat result")
        assertTrue(context.snapshot(work).isEmpty())
        context.remember(work, "work text")
        context.setScope("chat.app")
        context.remember(old, "even later chat result")
        assertTrue(context.snapshot(context.captureToken()).isEmpty())
    }

    @Test fun `restarting the same editor or rebuilding the input view keeps history`() {
        val token = start()
        context.remember(token, "message")
        context.setScope("chat.app")
        assertEquals(token, context.captureToken())
        assertEquals(listOf("message"), context.snapshot(token))
    }

    @Test fun `idle timeout expires history and invalidates already captured tokens`() {
        val token = start()
        context.remember(token, "old message")
        time = RecentDictationContext.IDLE_TIMEOUT_MS - 1
        assertEquals(listOf("old message"), context.snapshot(token))
        time++
        assertTrue(context.snapshot(token).isEmpty())
        assertNotEquals(token, context.captureToken())
        context.remember(token, "late output")
        assertTrue(context.snapshot(context.captureToken()).isEmpty())
    }

    @Test fun `late completion cannot refresh expired history even without a read`() {
        val token = start()
        context.remember(token, "old message")
        time = RecentDictationContext.IDLE_TIMEOUT_MS
        context.remember(token, "late output")
        assertTrue(context.snapshot(context.captureToken()).isEmpty())
    }

    @Test fun `completion refreshes idle timeout but taking a snapshot does not`() {
        val token = start()
        context.remember(token, "first")
        time = RecentDictationContext.IDLE_TIMEOUT_MS - 1
        context.remember(token, "second")
        time++
        assertEquals(listOf("first", "second"), context.snapshot(token))
        time += RecentDictationContext.IDLE_TIMEOUT_MS - 1
        assertTrue(context.snapshot(token).isEmpty())
    }

    @Test fun `a snapshot is detached from later completions and reset`() {
        val token = start()
        context.remember(token, "first")
        val snapshot = context.snapshot(token)
        context.remember(token, "second")
        context.clear()
        assertEquals(listOf("first"), snapshot)
    }
}
