package com.lisa.ai

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores conversation history as JSON in a local file.
 * Writes through a temporary file to reduce the risk of partial saves.
 */
class FileConversationStore(
    private val file: Path
) : ConversationStore {

    private val mutex = Mutex()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    override suspend fun load(): List<ChatMessage> = mutex.withLock {
        if (!Files.exists(file)) return@withLock emptyList()

        try {
            val root = json.parseToJsonElement(Files.readString(file))
            val entries = root.jsonArray

            entries.map { element ->
                val obj = element.jsonObject
                val toolCalls = obj["toolCalls"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonArray
                    ?.map { callElement ->
                        val call = callElement.jsonObject
                        ChatToolCall(
                            id = call.getValue("id").jsonPrimitive.content,
                            name = call.getValue("name").jsonPrimitive.content,
                            argumentsJson = call.getValue("argumentsJson").jsonPrimitive.content
                        )
                    }

                ChatMessage(
                    role = obj.getValue("role").jsonPrimitive.content,
                    content = obj["content"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive?.content,
                    toolCallId = obj["toolCallId"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive?.content,
                    toolCalls = toolCalls
                )
            }
        } catch (error: Exception) {
            throw IllegalStateException(
                "Could not read Lisa conversation history from $file",
                error
            )
        }
    }

    override suspend fun save(messages: List<ChatMessage>): Unit = mutex.withLock {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)

        val root = JsonArray(messages.map { message ->
            buildJsonObject {
                put("role", message.role)
                put("content", message.content?.let(::JsonPrimitive) ?: JsonNull)
                put("toolCallId", message.toolCallId?.let(::JsonPrimitive) ?: JsonNull)
                put("toolCalls", message.toolCalls?.let { calls ->
                    JsonArray(calls.map { call ->
                        buildJsonObject {
                            put("id", call.id)
                            put("name", call.name)
                            put("argumentsJson", call.argumentsJson)
                        }
                    })
                } ?: JsonNull)
            }
        })

        val temporary = Files.createTempFile(parent, "lisa-history-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(root))
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        Files.deleteIfExists(file)
    }
}
