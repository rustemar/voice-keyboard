package com.tyraen.voicekeyboard.feature.update

import okhttp3.OkHttpClient
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
        // "1.7.2-rc1" → numeric parts are [1, 7, 2], treated as 1.7.2
        assertFalse(checker.isVersionNewer("1.7.2-rc1", "1.7.2"))
    }
}
