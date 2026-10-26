package com.lisa.ai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Chat Completions-compatible provider.
 * Supports ordinary chat and structured function/tool calls.
 * Supply credentials through configuration; never hard-code API keys.
 */
class OpenAICompatibleProvider(
    override val name: String,
    override val model: String,
    private val baseUrl: String,
    private val apiKey: String? = null,
    private val engine: HttpClientEngine? = null
) : AIProvider, AgentModel, AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(engine ?: CIO.create()) {
        install(ContentNegotiation) {
            json(this@OpenAICompatibleProvider.json)
        }
    }

    override suspend fun generate(
        messages: List<ChatMessage>,
        systemPrompt: String
    ): AIResponse {
        val response = request(messages, systemPrompt, emptyList())
        val message = response.choices.firstOrNull()?.message
            ?: throw AIProviderException("The provider returned no choices.")

        val text = message.content
            ?: throw AIProviderException("The provider returned no response text.")

        return AIResponse(
            text = text,
            provider = name,
            model = response.model.ifBlank { model },
            inputTokens = response.usage?.promptTokens,
            outputTokens = response.usage?.completionTokens
        )
    }

    override suspend fun nextTurn(
        messages: List<ChatMessage>,
        systemPrompt: String,
        availableTools: List<AgentTool>
    ): AgentTurn {
        val response = request(messages, systemPrompt, availableTools)
        val message = response.choices.firstOrNull()?.message
            ?: throw AIProviderException("The provider returned no choices.")

        val apiToolCalls = message.toolCalls.orEmpty()
        if (apiToolCalls.isNotEmpty()) {
            val calls = apiToolCalls.map { toolCall ->
                val args = try {
                    json.parseToJsonElement(toolCall.function.arguments).jsonObject
                        .mapValues { (_, value) -> value.toLisaArgumentString() }
                } catch (e: Exception) {
                    throw AIProviderException(
                        "The model returned invalid arguments for tool '${toolCall.function.name}'.",
                        e
                    )
                }

                AgentTurn.ToolCall(
                    id = toolCall.id,
                    name = toolCall.function.name,
                    arguments = args
                )
            }

            return if (calls.size == 1) calls.first() else AgentTurn.ToolCalls(calls)
        }

        return AgentTurn.FinalAnswer(
            message.content
                ?: throw AIProviderException("The provider returned neither text nor a tool call.")
        )
    }

    private suspend fun request(
        messages: List<ChatMessage>,
        systemPrompt: String,
        availableTools: List<AgentTool>
    ): ApiResponse {
        if (messages.isEmpty()) {
            throw AIProviderException("At least one chat message is required.")
        }

        val requestMessages = buildList {
            if (systemPrompt.isNotBlank()) {
                add(ApiMessage(role = "system", content = systemPrompt))
            }

            messages.forEach { message ->
                add(
                    ApiMessage(
                        role = message.role,
                        content = message.content,
                        toolCallId = message.toolCallId,
                        toolCalls = message.toolCalls?.map { call ->
                            ApiToolCall(
                                id = call.id,
                                type = "function",
                                function = ApiFunctionCall(
                                    name = call.name,
                                    arguments = call.argumentsJson
                                )
                            )
                        }
                    )
                )
            }
        }

        val definitions = availableTools.map { tool ->
            ApiTool(
                type = "function",
                function = ApiFunctionDefinition(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parametersSchema
                )
            )
        }

        val request = ApiRequest(
            model = model,
            messages = requestMessages,
            tools = definitions.takeIf { it.isNotEmpty() },
            toolChoice = if (definitions.isNotEmpty()) "auto" else null
        )

        try {
            val httpResponse = client.post(baseUrl.trimEnd('/') + "/chat/completions") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                if (!apiKey.isNullOrBlank()) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                }
                setBody(request)
            }

            if (httpResponse.status.value !in 200..299) {
                val details = httpResponse.bodyAsText().take(1000)
                throw AIProviderException(
                    "Request to '$name' failed with HTTP ${httpResponse.status.value} " +
                        "(${httpResponse.status.description}): $details"
                )
            }

            return httpResponse.body()
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            throw AIProviderException(
                "Request to '$name' failed: ${e.message ?: e::class.simpleName}",
                e
            )
        }
    }

    override fun close() {
        client.close()
    }

    private fun JsonElement.toLisaArgumentString(): String =
        when (this) {
            is JsonPrimitive -> contentOrNull ?: toString()
            is JsonObject -> json.encodeToString(this)
            else -> json.encodeToString(this)
        }

    @Serializable
    private data class ApiRequest(
        val model: String,
        val messages: List<ApiMessage>,
        val tools: List<ApiTool>? = null,
        @SerialName("tool_choice")
        val toolChoice: String? = null
    )

    @Serializable
    private data class ApiMessage(
        val role: String,
        val content: String? = null,
        @SerialName("tool_call_id")
        val toolCallId: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ApiToolCall>? = null
    )

    @Serializable
    private data class ApiToolCall(
        val id: String,
        val type: String,
        val function: ApiFunctionCall
    )

    @Serializable
    private data class ApiFunctionCall(
        val name: String,
        val arguments: String
    )

    @Serializable
    private data class ApiTool(
        val type: String,
        val function: ApiFunctionDefinition
    )

    @Serializable
    private data class ApiFunctionDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject
    )

    @Serializable
    private data class ApiResponse(
        val choices: List<ApiChoice> = emptyList(),
        val model: String = "",
        val usage: ApiUsage? = null
    )

    @Serializable
    private data class ApiChoice(
        val message: ApiResponseMessage
    )

    @Serializable
    private data class ApiResponseMessage(
        val content: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ApiResponseToolCall>? = null
    )

    @Serializable
    private data class ApiResponseToolCall(
        val id: String,
        val type: String = "function",
        val function: ApiResponseFunction
    )

    @Serializable
    private data class ApiResponseFunction(
        val name: String,
        val arguments: String
    )

    @Serializable
    private data class ApiUsage(
        @SerialName("prompt_tokens")
        val promptTokens: Int? = null,
        @SerialName("completion_tokens")
        val completionTokens: Int? = null
    )
}
