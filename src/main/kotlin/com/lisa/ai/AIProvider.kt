package com.lisa.ai

/**
 * Common interface for every AI model connected to Lisa.
 * Provider implementations will be added separately.
 */
interface AIProvider {
    val name: String
    val model: String

    suspend fun generate(
        messages: List<ChatMessage>,
        systemPrompt: String
    ): AIResponse
}

data class ChatMessage(
    val role: String,
    val content: String?,
    val toolCallId: String? = null,
    val toolCalls: List<ChatToolCall>? = null
)

data class ChatToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class AIResponse(
    val text: String,
    val provider: String,
    val model: String,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)

class AIProviderException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
