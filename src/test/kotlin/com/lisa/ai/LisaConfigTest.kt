package com.lisa.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class LisaConfigTest {
    @Test
    fun missingEndpointReturnsNoConfiguration() {
        assertNull(
            LisaConfig.fromEnvironment(
                mapOf("LISA_AI_MODEL" to "example-model")
            )
        )
    }

    @Test
    fun readsProviderSettingsFromEnvironment() {
        val config = LisaConfig.fromEnvironment(
            mapOf(
                "LISA_AI_PROVIDER" to "Example Provider",
                "LISA_AI_BASE_URL" to "https://example.com/v1",
                "LISA_AI_MODEL" to "example-model",
                "LISA_AI_API_KEY" to "test-secret"
            )
        )

        assertNotNull(config)
        assertEquals("Example Provider", config.providerName)
        assertEquals("https://example.com/v1", config.baseUrl)
        assertEquals("example-model", config.model)
        assertEquals("test-secret", config.apiKey)
    }

    @Test
    fun apiKeyIsOptional() {
        val config = LisaConfig.fromEnvironment(
            mapOf(
                "LISA_AI_BASE_URL" to "https://example.com/v1",
                "LISA_AI_MODEL" to "example-model"
            )
        )

        assertNotNull(config)
        assertNull(config.apiKey)
        assertEquals("Configured AI Provider", config.providerName)
    }

    @Test
    fun blankRequiredSettingsReturnNoConfiguration() {
        assertNull(
            LisaConfig.fromEnvironment(
                mapOf(
                    "LISA_AI_BASE_URL" to "  ",
                    "LISA_AI_MODEL" to "example-model"
                )
            )
        )

        assertNull(
            LisaConfig.fromEnvironment(
                mapOf(
                    "LISA_AI_BASE_URL" to "https://example.com/v1",
                    "LISA_AI_MODEL" to " "
                )
            )
        )
    }
}
