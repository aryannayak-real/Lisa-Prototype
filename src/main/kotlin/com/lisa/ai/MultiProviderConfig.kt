package com.lisa.ai

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Loads multiple OpenAI-compatible providers from ~/.lisa/providers.properties.
 *
 * API keys stay in the private configuration file and are never logged.
 * Existing LISA_AI_* environment configuration remains supported.
 */
object MultiProviderConfig {

    fun load(
        file: Path = Path.of(
            System.getProperty("user.home"),
            ".lisa",
            "providers.properties"
        ),
        environment: Map<String, String> = System.getenv()
    ): List<AIProvider> {
        val providers = mutableListOf<AIProvider>()

        if (Files.isRegularFile(file)) {
            val properties = Properties()
            Files.newInputStream(file).use { properties.load(it) }

            val indexes = properties.stringPropertyNames()
                .mapNotNull { key ->
                    Regex("""provider\.(\d+)\.name""")
                        .matchEntire(key)
                        ?.groupValues?.get(1)?.toIntOrNull()
                }
                .distinct()
                .sorted()

            for (index in indexes) {
                val prefix = "provider.$index."
                val name = properties.getProperty(prefix + "name")
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: continue
                val baseUrl = properties.getProperty(prefix + "baseUrl")
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: continue
                val model = properties.getProperty(prefix + "model")
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: continue
                val apiKey = properties.getProperty(prefix + "apiKey")
                    ?.trim()?.takeIf { it.isNotEmpty() }

                providers += CurlOpenAICompatibleProvider(
                    name = name,
                    model = model,
                    baseUrl = baseUrl,
                    apiKey = apiKey
                )
            }
        }

        val environmentConfig = LisaConfig.fromEnvironment(environment)
        if (environmentConfig != null) {
            providers += environmentConfig.createProvider()
        }

        return providers.distinctBy { it.name to it.model }
    }
}
