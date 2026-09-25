package com.tyraen.voicekeyboard.feature.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransientErrorTest {

    private fun transient(message: String?) = ProcessingQueue.isTransientError(Exception(message))

    @Test fun `rate limit and request timeout are retried`() {
        assertTrue(transient("API error 429: Rate limit reached for model whisper-large-v3-turbo"))
        assertTrue(transient("API error 408: Request Timeout"))
    }

    @Test fun `a rate limit that links to billing is still retried`() {
        assertTrue(transient("API error 429: {\"error\":{\"message\":\"Rate limit reached for model whisper-large-v3-turbo. Please try again in 7.5s. Need more tokens? Upgrade to Dev Tier today at https://console.groq.com/settings/billing\",\"code\":\"rate_limit_exceeded\"}}"))
    }

    @Test fun `a 429 for exhausted credit needs attention`() {
        assertFalse(transient("API error 429: {\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}"))
        assertFalse(transient("API error 429: {\"error\":{\"code\":\"credit_balance_exhausted\"}}"))
        assertFalse(transient("API error 429: {\"error\":{\"code\":\"organization_spend_limit_exceeded\"}}"))
    }

    @Test fun `other client errors need attention`() {
        assertFalse(transient("API error 401: Invalid API Key"))
        assertFalse(transient("API error 400: bad request"))
        assertFalse(transient("API error 404: model not found"))
    }

    @Test fun `server errors are retried`() {
        assertTrue(transient("API error 500: internal error"))
        assertTrue(transient("API error 503: service unavailable"))
    }

    @Test fun `network failures are retried`() {
        assertTrue(ProcessingQueue.isTransientError(java.net.SocketTimeoutException()))
        assertTrue(ProcessingQueue.isTransientError(java.net.UnknownHostException("api.groq.com")))
        assertTrue(transient("Failed to connect to /10.0.2.2:8765"))
    }

    @Test fun `no error is not transient, an error without a message is`() {
        assertFalse(ProcessingQueue.isTransientError(null))
        assertTrue(transient(null))
    }
}
