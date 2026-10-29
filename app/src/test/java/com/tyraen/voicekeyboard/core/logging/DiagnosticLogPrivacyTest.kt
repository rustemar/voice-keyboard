package com.tyraen.voicekeyboard.core.logging

import com.tyraen.voicekeyboard.feature.postprocessing.PostProcessingClient
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogPrivacyTest {
    @get:Rule val temporary = TemporaryFolder()

    // Exercise the real file-writing path without adding an Android mocking dependency.
    private val targetField = DiagnosticLog::class.java.getDeclaredField("target").apply {
        isAccessible = true
    }
    private var previousTarget: File? = null
    private lateinit var logFile: File

    @Before
    fun useTemporaryLog() {
        previousTarget = targetField.get(null) as File?
        logFile = temporary.newFile("app_log.txt")
        targetField.set(null, logFile)
    }

    @After
    fun restoreLogTarget() {
        targetField.set(null, previousTarget)
    }

    @Test
    fun `failure log excludes exception messages nested causes and stack traces`() {
        val transcript = "PRIVATE_DICTATION_SENTINEL"
        val apiKey = "sk-PRIVATE_KEY_SENTINEL"
        val endpoint = "https://private-endpoint.invalid/transcribe?token=PRIVATE_TOKEN"
        val nestedSecret = "PRIVATE_NESTED_CAUSE_SENTINEL"
        val stackSecret = "PRIVATE_STACK_SENTINEL"
        val error = IOException(
            "$transcript $apiKey $endpoint",
            IllegalArgumentException(nestedSecret)
        ).apply {
            stackTrace = arrayOf(StackTraceElement(stackSecret, "request", "Private.kt", 42))
        }

        DiagnosticLog.recordFailure("Queue", "Transcription failed", error)

        val exportedContents = logFile.readText()
        assertTrue(exportedContents.contains("[Queue] ERROR Transcription failed: IOException"))
        for (secret in listOf(transcript, apiKey, endpoint, nestedSecret, stackSecret)) {
            assertFalse("Log exposed $secret", exportedContents.contains(secret))
        }
    }

    @Test
    fun `provider error bodies cannot enter the log through ApiException`() {
        val responseBody = """{"error":{"message":"PRIVATE_SELECTION_SENTINEL sk-PRIVATE_KEY_SENTINEL"}}"""

        DiagnosticLog.recordFailure(
            "Queue", "Post-processing failed, using raw text",
            PostProcessingClient.ApiException(400, responseBody)
        )

        val exportedContents = logFile.readText()
        assertTrue(exportedContents.contains("Post-processing failed, using raw text: ApiException"))
        assertFalse(exportedContents.contains(responseBody))
        assertFalse(exportedContents.contains("PRIVATE_SELECTION_SENTINEL"))
        assertFalse(exportedContents.contains("sk-PRIVATE_KEY_SENTINEL"))
    }

    @Test
    fun `fixed diagnostic context remains available without an exception`() {
        DiagnosticLog.recordFailure("PostProcessing", "Failed: HTTP 400")

        assertTrue(logFile.readText().contains("[PostProcessing] ERROR Failed: HTTP 400"))
    }
}
