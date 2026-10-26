package com.lisa.ai

/**
 * Runtime configuration for Lisa's AI provider.
 *
 * Credentials are read from environment variables and are never hard-coded.
 */
data class LisaConfig(
    val providerName: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String?
) {
    companion object {
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv()
        ): LisaConfig? {
            val baseUrl = environment["LISA_AI_BASE_URL"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val model = environment["LISA_AI_MODEL"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val apiKey = environment["LISA_AI_API_KEY"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val providerName = environment["LISA_AI_PROVIDER"]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: "Configured AI Provider"

            return LisaConfig(
                providerName = providerName,
                baseUrl = baseUrl,
                model = model,
                apiKey = apiKey
            )
        }
    }

    fun createProvider(): AIProvider =
        CurlOpenAICompatibleProvider(
            name = providerName,
            model = model,
            baseUrl = baseUrl,
            apiKey = apiKey
        )
}
