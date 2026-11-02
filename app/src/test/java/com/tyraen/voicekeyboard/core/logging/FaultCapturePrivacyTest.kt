package com.tyraen.voicekeyboard.core.logging

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaultCapturePrivacyTest {
    @Test
    fun `crash details retain exception types and locations without any messages`() {
        val transcript = "PRIVATE_DICTATION_SENTINEL"
        val responseBody = """{"error":"PRIVATE_RESPONSE_SENTINEL"}"""
        val apiKey = "sk-PRIVATE_KEY_SENTINEL"
        val error = IOException(transcript, IllegalStateException(responseBody)).apply {
            stackTrace = arrayOf(StackTraceElement("example.Capture", "finish", "Capture.kt", 42))
            addSuppressed(IllegalArgumentException(apiKey))
        }

        val report = FaultCapture.technicalStackTrace(error)

        assertTrue(report.contains("java.io.IOException"))
        assertTrue(report.contains("at example.Capture.finish(Capture.kt:42)"))
        assertTrue(report.contains("Caused by: java.lang.IllegalStateException"))
        assertTrue(report.contains("Suppressed: java.lang.IllegalArgumentException"))
        for (secret in listOf(transcript, responseBody, "PRIVATE_RESPONSE_SENTINEL", apiKey)) {
            assertFalse("Crash report exposed $secret", report.contains(secret))
        }
    }

    @Test
    fun `circular causes cannot loop while building a crash report`() {
        val first = IOException("PRIVATE_FIRST_SENTINEL")
        val second = IllegalStateException("PRIVATE_SECOND_SENTINEL", first)
        first.initCause(second)

        val report = FaultCapture.technicalStackTrace(first)

        assertTrue(report.contains("[circular reference: java.io.IOException]"))
        assertFalse(report.contains("PRIVATE_FIRST_SENTINEL"))
        assertFalse(report.contains("PRIVATE_SECOND_SENTINEL"))
        assertTrue(report.length < 50_000)
    }

    @Test
    fun `long cause chains have a bounded report`() {
        var error: Throwable = IOException("PRIVATE_ROOT_SENTINEL")
        repeat(100) {
            error = IOException("PRIVATE_NESTED_SENTINEL", error).apply { stackTrace = emptyArray() }
        }

        val report = FaultCapture.technicalStackTrace(error)

        assertTrue(report.contains("[additional exceptions omitted]"))
        assertTrue(report.lines().size <= 34)
        assertFalse(report.contains("PRIVATE_ROOT_SENTINEL"))
        assertFalse(report.contains("PRIVATE_NESTED_SENTINEL"))
    }
}
