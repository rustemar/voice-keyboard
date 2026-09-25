package com.tyraen.voicekeyboard.feature.update

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCheckerTest {

    private val checker = ReleaseChecker(OkHttpClient())

    @Test fun `newer patch version is detected`() {
        assertTrue(checker.isVersionNewer("1.7.3", "1.7.2"))
    }

    @Test fun `newer minor version is detected`() {
        assertTrue(checker.isVersionNewer("1.8.0", "1.7.9"))
    }

    @Test fun `newer major version is detected`() {
        assertTrue(checker.isVersionNewer("2.0.0", "1.99.99"))
    }

    @Test fun `same version is not newer`() {
        assertFalse(checker.isVersionNewer("1.7.2", "1.7.2"))
    }

    @Test fun `older version is not newer`() {
        assertFalse(checker.isVersionNewer("1.7.1", "1.7.2"))
        assertFalse(checker.isVersionNewer("1.6.99", "1.7.0"))
    }

    @Test fun `versions of different lengths compare correctly`() {
        assertTrue(checker.isVersionNewer("1.7.2.1", "1.7.2"))
        assertFalse(checker.isVersionNewer("1.7", "1.7.0"))
        assertFalse(checker.isVersionNewer("1.7.0", "1.7"))
    }

    @Test fun `non-numeric segments are ignored without crash`() {
        // "1.7.2-rc1" → the suffix after '-' is dropped, treated as 1.7.2
        assertFalse(checker.isVersionNewer("1.7.2-rc1", "1.7.2"))
    }

    @Test fun `double-digit parts compare as numbers, not text`() {
        assertTrue(checker.isVersionNewer("1.10.0", "1.9.3"))
        assertTrue(checker.compareVersions("1.9.10", "1.9.2") > 0)
    }

    @Test fun `newer releases sort newest first`() {
        val sorted = listOf("1.9.3", "1.10.0", "1.9.10").sortedWith { a, b -> checker.compareVersions(b, a) }
        assertEquals(listOf("1.10.0", "1.9.10", "1.9.3"), sorted)
    }

    @Test fun `older sections are cut from a release body`() {
        val body = "- New thing\r\n- Other thing\r\n\r\nv1.9.0 — Previous release\r\n\r\n- Old thing\r\n"
        assertEquals("- New thing\n- Other thing", checker.ownNotes(body))
    }

    @Test fun `a body with only its own notes is kept whole`() {
        assertEquals("- Only this\n- And this", checker.ownNotes("- Only this\n- And this\n"))
        assertEquals("", checker.ownNotes(""))
    }

    @Test fun `a version mentioned inside a note is not a heading`() {
        val body = "- Fixes a bug from v1.9.0 — thanks for the report\n- Another"
        assertEquals(body, checker.ownNotes(body))
    }

    @Test fun `a fork's build suffix does not shift the version`() {
        assertFalse(checker.isVersionNewer("1.9.1-jf.6", "1.9.1"))
        assertTrue(checker.isVersionNewer("1.9.2", "1.9.1-jf.6"))
    }
}
