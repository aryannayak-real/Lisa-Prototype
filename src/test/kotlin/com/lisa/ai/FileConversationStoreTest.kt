package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileConversationStoreTest {

    @Test
    fun savesAndRestoresMessagesIncludingToolCalls() = runBlocking {
        val directory = Files.createTempDirectory("lisa-store-test")
        val file = directory.resolve("history.json")

        try {
            val store = FileConversationStore(file)
            val messages = listOf(
                ChatMessage("user", "Calculate 2 + 3"),
                ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(
                        ChatToolCall("call-1", "calculator", """{"expression":"2 + 3"}""")
                    )
                ),
                ChatMessage("tool", "5.0", toolCallId = "call-1"),
                ChatMessage("assistant", "The answer is 5.")
            )

            store.save(messages)

            assertTrue(Files.exists(file))
            assertEquals(messages, FileConversationStore(file).load())
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun clearRemovesSavedHistory() = runBlocking {
        val directory = Files.createTempDirectory("lisa-store-test")
        val file = directory.resolve("history.json")

        try {
            val store = FileConversationStore(file)
            store.save(listOf(ChatMessage("user", "Hello")))
            store.clear()

            assertFalse(Files.exists(file))
            assertTrue(store.load().isEmpty())
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun corruptedJsonProducesClearError() = runBlocking {
        val directory = Files.createTempDirectory("lisa-store-test")
        val file = directory.resolve("history.json")

        try {
            Files.writeString(file, "{not valid JSON")

            val error = assertFailsWith<IllegalStateException> {
                FileConversationStore(file).load()
            }

            assertTrue(error.message.orEmpty().contains("Could not read Lisa conversation history"))
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
