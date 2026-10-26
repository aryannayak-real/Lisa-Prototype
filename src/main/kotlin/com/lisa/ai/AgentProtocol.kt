package com.lisa.ai

/**
 * Normalized response format used in Lisa's agent loop.
 */
sealed interface AgentTurn {
    data class FinalAnswer(
        val text: String
    ) : AgentTurn

    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: Map<String, String>
    ) : AgentTurn

    data class ToolCalls(
        val calls: List<ToolCall>
    ) : AgentTurn {
        init {
            require(calls.isNotEmpty()) { "ToolCalls must contain at least one call." }
        }
    }
}

/**
 * Provider-neutral description of a tool made available to a model.
 */
data class ToolDefinition(
    val name: String,
    val description: String
)

/**
 * Adapter contract for models that can select tools or return a final answer.
 */
interface AgentModel {
    val name: String

    suspend fun nextTurn(
        messages: List<ChatMessage>,
        systemPrompt: String,
        availableTools: List<AgentTool>
    ): AgentTurn
}
