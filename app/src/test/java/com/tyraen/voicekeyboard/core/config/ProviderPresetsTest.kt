package com.tyraen.voicekeyboard.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {

    @Test fun `a preset is recognised by host regardless of path or scheme`() {
        assertEquals(0, ProviderPresets.indexOf(ProviderPresets.speechToText, "https://api.groq.com/openai/v1/audio/transcriptions"))
        assertEquals(2, ProviderPresets.indexOf(ProviderPresets.speechToText, "api.mistral.ai/v1"))
        assertEquals(3, ProviderPresets.indexOf(ProviderPresets.speechToText, "https://openrouter.ai/api/v1"))
        assertEquals(2, ProviderPresets.indexOf(ProviderPresets.postProcessing, "https://openrouter.ai/api/v1"))
    }

    @Test fun `custom and blank addresses map to no preset`() {
        assertEquals(-1, ProviderPresets.indexOf(ProviderPresets.speechToText, ""))
        assertEquals(-1, ProviderPresets.indexOf(ProviderPresets.speechToText, "https://whisper.example.org/v1/audio/transcriptions"))
        assertEquals(-1, ProviderPresets.indexOf(ProviderPresets.speechToText, "https://openrouter.ai.example.org/api/v1"))
    }

    @Test fun `OpenRouter speech preset uses a namespaced transcription model`() {
        val preset = ProviderPresets.speechToText.single { it.name == "OpenRouter" }
        assertEquals("openai/whisper-large-v3-turbo", preset.model)
        assertEquals(preset.model, ProviderPresets.openRouterSpeechModels.first().id)
        assertTrue(ProviderPresets.openRouterSpeechModels.all { it.name.isNotBlank() && it.id.contains('/') })
    }

    @Test fun `every preset address is a complete request path for its dialect`() {
        ProviderPresets.speechToText.forEach { assertTrue(it.name, it.endpoint.endsWith("/audio/transcriptions")) }
        ProviderPresets.postProcessing.forEach { p ->
            val expected = if (p.provider == PostProcessingPreferences.PROVIDER_CLAUDE) "/messages" else "/chat/completions"
            assertTrue(p.name, p.endpoint.endsWith(expected))
            assertEquals(p.name, p.provider, PostProcessingPreferences.providerFor(p.endpoint))
        }
    }
}
