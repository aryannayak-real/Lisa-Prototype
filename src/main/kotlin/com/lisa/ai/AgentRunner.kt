package com.lisa.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

data class AgentRunResult(
    val answer: String,
    val toolCalls: Int,
    val turns: Int,
    val completed: Boolean,
    val messages: List<ChatMessage> = emptyList(),
)

/**
 * Coordinates model turns and tool execution for one agent run.
 * The caller may supply existing conversation history.
 */
class AgentRunner(
    private val model: AgentModel,
    private val tools: ToolRegistry,
    private val systemPrompt: String = AgentEngine.DEFAULT_SYSTEM_PROMPT,
    private val maxTurns: Int = 12,
) {
    init {
        require(maxTurns > 0) { "maxTurns must be positive." }
    }

    suspend fun run(userRequest: String): AgentRunResult {
        require(userRequest.isNotBlank()) { "Request cannot be empty." }
        return run(
            initialMessages = listOf(
                ChatMessage(role = "user", content = userRequest.trim()),
            ),
        )
    }

    suspend fun run(initialMessages: List<ChatMessage>): AgentRunResult {
        require(initialMessages.isNotEmpty()) { "At least one message is required." }
        require(initialMessages.last().role == "user") {
            "The latest message must be a user message."
        }

        val messages = initialMessages.toMutableList()
        var toolCalls = 0

        repeat(maxTurns) { index ->
            when (
                val turn = model.nextTurn(
                    messages = messages.toList(),
                    systemPrompt = systemPrompt,
                    availableTools = tools.listTools(),
                )
            ) {
                is AgentTurn.FinalAnswer -> {
                    messages.add(ChatMessage(role = "assistant", content = turn.text))
                    return AgentRunResult(
                        answer = turn.text,
                        toolCalls = toolCalls,
                        turns = index + 1,
                        completed = true,
                        messages = messages.toList(),
                    )
                }

                is AgentTurn.ToolCalls -> {
                    messages.add(
                        ChatMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = turn.calls.map(::toChatToolCall),
                        ),
                    )

                    for (call in turn.calls) {
                        val result = tools.execute(call.name, call.arguments)
                        toolCalls++
                        messages.add(
                            ChatMessage(
                                role = "tool",
                                content = if (result.success) {
                                    result.output
                                } else {
                                    "Tool failed: ${result.error ?: "Unknown error"}"
                                },
                                toolCallId = call.id,
                            ),
                        )
                    }
                }

                is AgentTurn.ToolCall -> {
                    messages.add(
                        ChatMessage(
                            role = "assistant",
                            content = null,
                            toolCalls = listOf(toChatToolCall(turn)),
                        ),
                    )

                    val result = tools.execute(turn.name, turn.arguments)
                    toolCalls++
                    messages.add(
                        ChatMessage(
                            role = "tool",
                            content = if (result.success) {
                                result.output
                            } else {
                                "Tool failed: ${result.error ?: "Unknown error"}"
                            },
                            toolCallId = turn.id,
                        ),
                    )
                }
            }
        }

        return AgentRunResult(
            answer = "Lisa reached the per-run limit of $maxTurns model turns. You can continue with a follow-up request.",
            toolCalls = toolCalls,
            turns = maxTurns,
            completed = false,
            messages = messages.toList(),
        )
    }

    private fun toChatToolCall(call: AgentTurn.ToolCall): ChatToolCall {
        val arguments = buildJsonObject {
            call.arguments.forEach { (key, value) ->
                put(key, JsonPrimitive(value))
            }
        }
        return ChatToolCall(
            id = call.id,
            name = call.name,
            argumentsJson = Json.encodeToString(JsonObject.serializer(), arguments),
        )
    }
}
