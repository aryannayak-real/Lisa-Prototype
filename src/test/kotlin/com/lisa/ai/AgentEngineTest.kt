package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentEngineTest {
    private class FakeProvider(
        private val shouldFail: Boolean = false
    ) : AIProvider {
        override val name = "Lisa Test Provider"
        override val model = "test-model"

        override suspend fun generate(
            messages: List<ChatMessage>,
            systemPrompt: String
        ): AIResponse {
            if (shouldFail) throw AIProviderException("Simulated provider failure")
            return AIResponse(
                text = "Test reply: ${messages.lastOrNull()?.content.orEmpty()}",
                provider = name,
                model = model
            )
        }
    }

    @Test
    fun newEngineRestoresSavedConversation() = runBlocking {
        val directory = Files.createTempDirectory("lisa-engine-test")
        val file = directory.resolve("history.json")

        try {
            val store = FileConversationStore(file)

            AgentEngine(FakeProvider(), conversationStore = store).use { first ->
                first.chat("Hello")
                first.chat("Remember this")
            }

            AgentEngine(FakeProvider(), conversationStore = store).use { second ->
                val history = second.getHistory()
                assertEquals(4, history.size)
                assertEquals(
                    listOf("user", "assistant", "user", "assistant"),
                    history.map { it.role }
                )
                assertEquals("Hello", history[0].content)
                assertEquals("Remember this", history[2].content)
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun clearHistoryClearsMemoryAndDisk() = runBlocking {
        val directory = Files.createTempDirectory("lisa-engine-test")
        val file = directory.resolve("history.json")

        try {
            val store = FileConversationStore(file)

            AgentEngine(FakeProvider(), conversationStore = store).use { lisa ->
                lisa.chat("Keep this temporarily")
                assertTrue(Files.exists(file))

                lisa.clearHistory()

                assertTrue(lisa.getHistory().isEmpty())
                assertTrue(store.load().isEmpty())
                assertTrue(!Files.exists(file))
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun providerFailureRestoresPreviousHistory() = runBlocking {
        val directory = Files.createTempDirectory("lisa-engine-test")
        val file = directory.resolve("history.json")

        try {
            val store = FileConversationStore(file)

            AgentEngine(FakeProvider(), conversationStore = store).use { lisa ->
                lisa.chat("Successful message")
                val previousHistory = lisa.getHistory()

                val failingEngine = AgentEngine(
                    FakeProvider(shouldFail = true),
                    conversationStore = store
                )

                failingEngine.use {
                    assertFailsWith<AIProviderException> {
                        it.chat("This should fail")
                    }
                    assertEquals(previousHistory, it.getHistory())
                }
            }
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }
}
