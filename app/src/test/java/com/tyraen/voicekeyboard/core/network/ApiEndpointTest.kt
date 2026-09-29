package com.tyraen.voicekeyboard.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointTest {

    private val chat = "/chat/completions"

    @Test fun `a base url ending in v1 gets the request path`() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", ApiEndpoint.complete("https://openrouter.ai/api/v1", chat))
        assertEquals("https://api.groq.com/openai/v1/chat/completions", ApiEndpoint.complete("https://api.groq.com/openai/v1", chat))
    }

    @Test fun `a full url is left alone`() {
        assertEquals("https://api.openai.com/v1/chat/completions", ApiEndpoint.complete("https://api.openai.com/v1/chat/completions", chat))
        assertEquals("https://proxy.example/custom/route", ApiEndpoint.complete("https://proxy.example/custom/route", chat))
    }

    @Test fun `trailing slashes and whitespace are dropped`() {
        assertEquals("https://openrouter.ai/api/v1/chat/completions", ApiEndpoint.complete("  https://openrouter.ai/api/v1/  ", chat))
        assertEquals("https://api.openai.com/v1/chat/completions", ApiEndpoint.complete("https://api.openai.com/v1/chat/completions/", chat))
    }

    @Test fun `a missing scheme becomes https`() {
        assertEquals(
            "https://api.mistral.ai/v1/audio/transcriptions",
            ApiEndpoint.complete("api.mistral.ai/v1/audio/transcriptions", "/audio/transcriptions")
        )
        assertEquals("https://api.mistral.ai/v1/audio/transcriptions", ApiEndpoint.complete("api.mistral.ai/v1", "/audio/transcriptions"))
        assertEquals("http://localhost:11434/v1/chat/completions", ApiEndpoint.complete("http://localhost:11434/v1", chat))
    }

    @Test fun `a bare host or a query string is not guessed at`() {
        assertEquals("https://openrouter.ai", ApiEndpoint.complete("https://openrouter.ai", chat))
        assertEquals("https://proxy.example/v1?key=abc", ApiEndpoint.complete("https://proxy.example/v1?key=abc", chat))
    }

    @Test fun `completion is idempotent`() {
        val once = ApiEndpoint.complete("openrouter.ai/api/v1/", chat)
        assertEquals(once, ApiEndpoint.complete(once, chat))
    }

    @Test fun `blank input stays blank so callers can fall back to their default`() {
        assertEquals("", ApiEndpoint.complete("", chat))
        assertEquals("", ApiEndpoint.complete("   ", chat))
    }

    @Test fun `only a plain http address counts as unencrypted`() {
        assertTrue(ApiEndpoint.isUnencrypted("http://192.168.1.50:8000/v1/audio/transcriptions"))
        assertTrue(ApiEndpoint.isUnencrypted("  HTTP://nas.local:11434/v1 "))
        assertFalse(ApiEndpoint.isUnencrypted("https://api.groq.com/openai/v1/audio/transcriptions"))
        assertFalse(ApiEndpoint.isUnencrypted(""))
        // A scheme-less address is completed to https, so it is not flagged while being typed.
        assertFalse(ApiEndpoint.isUnencrypted("api.mistral.ai/v1"))
    }

    @Test fun `a server on the phone itself is not flagged`() {
        assertFalse(ApiEndpoint.isUnencrypted("http://localhost:8080/v1"))
        assertFalse(ApiEndpoint.isUnencrypted("http://127.0.0.1:8000/v1/audio/transcriptions"))
        assertTrue(ApiEndpoint.isUnencrypted("http://10.0.2.2:8765/v1"))
    }

    @Test fun `host and path are split without port credentials or query`() {
        assertEquals("openrouter.ai", ApiEndpoint.hostOf("https://OpenRouter.ai/api/v1"))
        assertEquals("localhost", ApiEndpoint.hostOf("http://user:pw@localhost:11434/v1?x=1"))
        assertEquals("", ApiEndpoint.hostOf("no scheme here"))
        assertEquals("/api/v1", ApiEndpoint.pathOf("https://openrouter.ai/api/v1?x=1#frag"))
        assertEquals("", ApiEndpoint.pathOf("https://openrouter.ai"))
    }
}
