package com.lisa.ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiProviderConfigTest {

    @Test
    fun loadsMultipleProvidersFromPropertiesFile() {
        val directory = Files.createTempDirectory("lisa-providers-test")
        val file = directory.resolve("providers.properties")

        try {
            Files.writeString(
                file,
                """
                provider.1.name=Provider One
                provider.1.baseUrl=https://one.example/v1
                provider.1.model=model-one
                provider.1.apiKey=key-one

                provider.2.name=Provider Two
                provider.2.baseUrl=https://two.example/v1
                provider.2.model=model-two
                provider.2.apiKey=key-two
                """.trimIndent()
            )

            val providers = MultiProviderConfig.load(
                file = file,
                environment = emptyMap()
            )

            assertEquals(2, providers.size)
            assertEquals("Provider One", providers[0].name)
            assertEquals("Provider Two", providers[1].name)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun ignoresIncompleteProviderEntries() {
        val directory = Files.createTempDirectory("lisa-providers-test")
        val file = directory.resolve("providers.properties")

        try {
            Files.writeString(
                file,
                """
                provider.1.name=Incomplete
                provider.1.model=model-one

                provider.2.name=Complete
                provider.2.baseUrl=https://two.example/v1
                provider.2.model=model-two
                """.trimIndent()
            )

            val providers = MultiProviderConfig.load(
                file = file,
                environment = emptyMap()
            )

            assertEquals(1, providers.size)
            assertEquals("Complete", providers.single().name)
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun keepsEnvironmentProviderWhenNoPropertiesFileExists() {
        val directory = Files.createTempDirectory("lisa-providers-test")
        val missingFile = directory.resolve("missing.properties")

        try {
            val providers = MultiProviderConfig.load(
                file = missingFile,
                environment = mapOf(
                    "LISA_AI_PROVIDER" to "OpenRouter",
                    "LISA_AI_BASE_URL" to "https://openrouter.ai/api/v1",
                    "LISA_AI_MODEL" to "openrouter/free",
                    "LISA_AI_API_KEY" to "test-key"
                )
            )

            assertEquals(1, providers.size)
            assertEquals("OpenRouter", providers.single().name)
        } finally {
            Files.deleteIfExists(directory)
        }
    }
}
