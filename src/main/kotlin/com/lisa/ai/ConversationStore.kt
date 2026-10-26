package com.lisa.ai

/**
 * Stores Lisa's conversation history.
 *
 * Implementations can use local files or a database later.
 */
interface ConversationStore {
    suspend fun load(): List<ChatMessage>

    suspend fun save(messages: List<ChatMessage>)

    suspend fun clear()
}

/**
 * In-memory store for tests and temporary sessions.
 */
class InMemoryConversationStore(
    initialMessages: List<ChatMessage> = emptyList()
) : ConversationStore {
    private val messages = initialMessages.toMutableList()

    override suspend fun load(): List<ChatMessage> =
        messages.toList()

    override suspend fun save(messages: List<ChatMessage>) {
        this.messages.clear()
        this.messages.addAll(messages)
    }

    override suspend fun clear() {
        messages.clear()
    }
}
