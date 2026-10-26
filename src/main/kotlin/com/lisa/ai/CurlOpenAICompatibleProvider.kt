package com.lisa.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** OpenAI-compatible provider using Termux curl for network access. */
class CurlOpenAICompatibleProvider(
    override val name: String,
    override val model: String,
    private val baseUrl: String,
    private val apiKey: String? = null,
) : AIProvider, AgentModel {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun generate(
        messages: List<ChatMessage>,
        systemPrompt: String,
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
            outputTokens = response.usage?.completionTokens,
        )
    }

    override suspend fun nextTurn(
        messages: List<ChatMessage>,
        systemPrompt: String,
        availableTools: List<AgentTool>,
    ): AgentTurn {
        val response = request(messages, systemPrompt, availableTools)
        val message = response.choices.firstOrNull()?.message
            ?: throw AIProviderException("The provider returned no choices.")

        val calls = message.toolCalls.orEmpty().map { call ->
            val arguments = try {
                json.parseToJsonElement(call.function.arguments).jsonObject
                    .mapValues { (_, value) -> value.toLisaArgumentString() }
            } catch (e: Exception) {
                throw AIProviderException(
                    "The model returned invalid arguments for tool '${call.function.name}'.",
                    e,
                )
            }
            AgentTurn.ToolCall(call.id, call.function.name, arguments)
        }

        if (calls.isNotEmpty()) {
            return if (calls.size == 1) calls.first() else AgentTurn.ToolCalls(calls)
        }

        return AgentTurn.FinalAnswer(
            message.content
                ?: throw AIProviderException("The provider returned neither text nor a tool call.")
        )
    }

    private fun request(
        messages: List<ChatMessage>,
        systemPrompt: String,
        availableTools: List<AgentTool>,
    ): ApiResponse {
        if (messages.isEmpty()) {
            throw AIProviderException("At least one chat message is required.")
        }

        val apiMessages = buildList {
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
                                    arguments = call.argumentsJson,
                                ),
                            )
                        },
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
                    parameters = tool.parametersSchema,
                ),
            )
        }

        val payload = ApiRequest(
            model = model,
            messages = apiMessages,
            tools = definitions.takeIf { it.isNotEmpty() },
            toolChoice = if (definitions.isNotEmpty()) "auto" else null,
        )

        val requestFile = try {
            java.nio.file.Files.createTempFile("lisa-request-", ".json").also {
                java.nio.file.Files.writeString(it, json.encodeToString(payload))
            }
        } catch (e: Exception) {
            throw AIProviderException("Could not prepare the API request: ${e.message}", e)
        }

        try {
            val command = listOf(
                "curl",
                "--ipv6",
                "--silent",
                "--show-error",
                "--write-out",
                "\n%{http_code}",
                "--header",
                "Content-Type: application/json",
                "--data-binary",
                "@$requestFile",
                "--config",
                "-",
                baseUrl.trimEnd('/') + "/chat/completions",
            )

            val process = try {
                ProcessBuilder(command).start()
            } catch (e: Exception) {
                throw AIProviderException(
                    "Could not start curl. Ensure curl is installed in Termux.",
                    e,
                )
            }

            // Send the API key through curl's standard input, not its command-line arguments.
            val config = buildString {
                if (!apiKey.isNullOrBlank()) {
                    val safeKey = apiKey
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                    append("header = \"Authorization: Bearer ")
                        .append(safeKey)
                        .append("\"\n")
                }
            }

            process.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(config)
            }

            val output = process.inputStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val errorOutput = process.errorStream.bufferedReader(Charsets.UTF_8).use {
                it.readText()
            }
            val exitCode = process.waitFor()

            val marker = output.lastIndexOf('\n')
            val body = if (marker >= 0) output.substring(0, marker) else ""
            val status = if (marker >= 0) {
                output.substring(marker + 1).trim().toIntOrNull() ?: 0
            } else {
                0
            }

            if (exitCode != 0 || status == 0) {
                throw AIProviderException(
                    "Request to '$name' failed: " +
                        (errorOutput.ifBlank { body }).take(500)
                )
            }

            if (status !in 200..299) {
                throw AIProviderException(
                    "Request to '$name' failed with HTTP $status: ${body.take(1000)}"
                )
            }

            return json.decodeFromString<ApiResponse>(body)
        } catch (e: AIProviderException) {
            throw e
        } catch (e: Exception) {
            throw AIProviderException(
                "Request to '$name' failed: ${e.message ?: e::class.simpleName}",
                e,
            )
        } finally {
            runCatching {
                java.nio.file.Files.deleteIfExists(requestFile)
            }
        }
    }

    private fun JsonElement.toLisaArgumentString(): String = when (this) {
        is JsonPrimitive -> contentOrNull ?: toString()
        is JsonObject -> json.encodeToString(this)
        else -> json.encodeToString(this)
    }

    @Serializable
    private data class ApiRequest(
        val model: String,
        val messages: List<ApiMessage>,
        val tools: List<ApiTool>? = null,
        @SerialName("tool_choice") val toolChoice: String? = null,
    )

    @Serializable
    private data class ApiMessage(
        val role: String,
        val content: String? = null,
        @SerialName("tool_call_id") val toolCallId: String? = null,
        @SerialName("tool_calls") val toolCalls: List<ApiToolCall>? = null,
    )

    @Serializable
    private data class ApiToolCall(
        val id: String,
        val type: String,
        val function: ApiFunctionCall,
    )

    @Serializable
    private data class ApiFunctionCall(
        val name: String,
        val arguments: String,
    )

    @Serializable
    private data class ApiTool(
        val type: String,
        val function: ApiFunctionDefinition,
    )

    @Serializable
    private data class ApiFunctionDefinition(
        val name: String,
        val description: String,
        val parameters: JsonObject,
    )

    @Serializable
    private data class ApiResponse(
        val choices: List<ApiChoice> = emptyList(),
        val model: String = "",
        val usage: ApiUsage? = null,
    )

    @Serializable
    private data class ApiChoice(
        val message: ApiResponseMessage,
    )

    @Serializable
    private data class ApiResponseMessage(
        val content: String? = null,
        @SerialName("tool_calls")
        val toolCalls: List<ApiResponseToolCall>? = null,
    )

    @Serializable
    private data class ApiResponseToolCall(
        val id: String,
        val type: String = "function",
        val function: ApiResponseFunction,
    )

    @Serializable
    private data class ApiResponseFunction(
        val name: String,
        val arguments: String,
    )

    @Serializable
    private data class ApiUsage(
        @SerialName("prompt_tokens") val promptTokens: Int? = null,
        @SerialName("completion_tokens") val completionTokens: Int? = null,
    )
}
