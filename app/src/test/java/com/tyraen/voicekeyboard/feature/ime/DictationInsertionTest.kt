package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationInsertionTest {

    @Test fun `cancelled before-cursor read never attempts an acknowledged but ignored commit`() {
        var commits = 0
        val inserted = DictationInsertion.insert("kept result", true,
            readBefore = { null }, readAfter = { "" }, canCommit = { true },
            commit = { commits++; true })

        assertFalse(inserted)
        assertEquals(0, commits)
    }

    @Test fun `unavailable after-cursor context also preserves text without committing`() {
        var commits = 0
        val inserted = DictationInsertion.insert("kept result", true,
            readBefore = { "a" }, readAfter = { null }, canCommit = { true },
            commit = { commits++; true })

        assertFalse(inserted)
        assertEquals(0, commits)
    }

    @Test fun `empty cursor context is a valid empty field`() {
        val committed = mutableListOf<String>()
        val inserted = DictationInsertion.insert("first words", true,
            readBefore = { "" }, readAfter = { "" }, canCommit = { true },
            commit = { committed += it; true })

        assertTrue(inserted)
        assertEquals(listOf("first words "), committed)
    }

    @Test fun `session changed during a remote read cannot receive automatic or explicit insertion`() {
        for (changeDuringBefore in listOf(true, false)) {
            val capturedSession = 1L
            var currentSession = capturedSession
            var commits = 0
            val inserted = DictationInsertion.insert("original session result", true,
                readBefore = { if (changeDuringBefore) currentSession = 2L; "a" },
                readAfter = { if (!changeDuringBefore) currentSession = 2L; "" },
                canCommit = { currentSession == capturedSession },
                commit = { commits++; true })

            assertFalse(inserted)
            assertEquals(0, commits)
        }
    }

    @Test fun `replaced connection is rejected even when editor session still matches`() {
        val capturedConnection = Any()
        var currentConnection = capturedConnection
        var commits = 0
        val inserted = DictationInsertion.insert("kept result", false,
            readBefore = { "" },
            readAfter = { currentConnection = Any(); "" },
            canCommit = { currentConnection === capturedConnection },
            commit = { commits++; true })

        assertFalse(inserted)
        assertEquals(0, commits)
    }

    @Test fun `hidden or voice-blocked field discovered after reads prevents commit`() {
        var allowed = true
        var commits = 0
        val inserted = DictationInsertion.insert("kept result", true,
            readBefore = { "" }, readAfter = { allowed = false; "" },
            canCommit = { allowed }, commit = { commits++; true })

        assertFalse(inserted)
        assertEquals(0, commits)
    }

    @Test fun `already invalid target is not queried or committed`() {
        var calls = 0
        val inserted = DictationInsertion.insert("kept result", true,
            readBefore = { calls++; "" }, readAfter = { calls++; "" },
            canCommit = { false }, commit = { calls++; true })

        assertFalse(inserted)
        assertEquals(0, calls)
    }

    @Test fun `failed commit returns failure so the caller retains the result`() {
        var commits = 0
        val inserted = DictationInsertion.insert("kept result", true,
            readBefore = { "" }, readAfter = { "" }, canCommit = { true },
            commit = { commits++; false })

        assertFalse(inserted)
        assertEquals(1, commits)
    }

    @Test fun `successful insertion preserves neighbour padding and commits once`() {
        val committed = mutableListOf<String>()
        val inserted = DictationInsertion.insert("new words", true,
            readBefore = { "d" }, readAfter = { "." }, canCommit = { true },
            commit = { committed += it; true })

        assertTrue(inserted)
        assertEquals(listOf(" new words"), committed)
    }
}
