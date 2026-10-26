package com.lisa.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Routes requests across configured providers in priority order.
 *
 * Failed providers enter a cooldown before they can be tried again.
 * This class never executes agent tools; it only requests model responses.
 */
class MultiProviderAI(
    providers: List<AIProvider>,
    private val cooldownMillis: Long = 60_000,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AIProvider, AgentModel, AutoCloseable {

    private data class Entry(
        val provider: AIProvider,
        var unavailableUntil: Long = 0,
    )

    private val entries = providers.distinctBy { it.name to it.model }
        .map { Entry(it) }
    private val mutex = Mutex()
    private var lastSuccessfulIndex = 0

    init {
        require(entries.isNotEmpty()) { "At least one AI provider is required." }
        require(cooldownMillis >= 0) { "Cooldown cannot be negative." }
    }

    override val name: String = "Lisa Multi-Provider"
    override val model: String = "automatic"

    override suspend fun generate(
        messages: List<ChatMessage>,
        systemPrompt: String,
    ): AIResponse = withProvider { provider ->
        provider.generate(messages, systemPrompt)
    }

    override suspend fun nextTurn(
        messages: List<ChatMessage>,
        systemPrompt: String,
        availableTools: List<AgentTool>,
    ): AgentTurn = withProvider { provider ->
        val agentModel = provider as? AgentModel
            ?: throw AIProviderException(
                "Configured provider '${provider.name}' does not support agent tools."
            )
        agentModel.nextTurn(messages, systemPrompt, availableTools)
    }

    private suspend fun <T> withProvider(
        request: suspend (AIProvider) -> T,
    ): T = mutex.withLock {
        val now = clockMillis()
        val ordered = entries.indices
            .filter { entries[it].unavailableUntil <= now }
            .sortedBy { if (it == lastSuccessfulIndex) 0 else it + 1 }

        if (ordered.isEmpty()) {
            entries.minBy { it.unavailableUntil }.provider.let { provider ->
                throw AIProviderException(
                    "All AI providers are cooling down. Try again later."
                )
            }
        }

        var lastError: Exception? = null

        for (index in ordered) {
            val provider = entries[index].provider
            try {
                val result = request(provider)
                entries[index].unavailableUntil = 0
                lastSuccessfulIndex = index
                return@withLock result
            } catch (error: Exception) {
                lastError = error
                entries[index].unavailableUntil =
                    clockMillis() + cooldownMillis
            }
        }

        throw AIProviderException(
            "All available AI providers failed. " +
                (lastError?.message ?: "No further details are available."),
            lastError,
        )
    }

    override fun close() {
        entries.forEach { entry ->
            runCatching { (entry.provider as? AutoCloseable)?.close() }
        }
    }
}
