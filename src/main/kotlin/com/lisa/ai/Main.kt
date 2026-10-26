package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Lisa terminal chat.
 *
 * Uses a configured OpenAI-compatible provider when available.
 * Otherwise, starts in offline demo mode.
 */
private class DemoProvider : AIProvider {
    override val name = "Lisa Offline Demo"
    override val model = "demo-model"

    override suspend fun generate(
        messages: List<ChatMessage>,
        systemPrompt: String
    ): AIResponse {
        val lastMessage = messages.lastOrNull()?.content.orEmpty()
        return AIResponse(
            text = "I'm running in offline demo mode. I received: $lastMessage",
            provider = name,
            model = model
        )
    }
}

fun main() = runBlocking {
    val home = System.getProperty("user.home")
    val historyFile = Path.of(home, ".lisa", "conversation.json")
    val store = FileConversationStore(historyFile)
    val configuredProviders = MultiProviderConfig.load()
    val provider: AIProvider = when {
        configuredProviders.size == 1 -> configuredProviders.single()
        configuredProviders.size > 1 -> MultiProviderAI(configuredProviders)
        else -> DemoProvider()
    }

    val workspace = Path.of(home, "Lisa-Workspace")
    val tools = ToolRegistry(
        listOf(
            CalculatorTool(),
            WorkspaceListTool(workspace),
            WorkspaceReadTool(workspace),
            WorkspaceSearchTool(workspace),
            WorkspaceWriteTool(workspace),
            WorkspaceEditTool(workspace),
            WorkspacePreviewEditTool(workspace),
            WorkspaceCommandTool(workspace),
            WorkspaceTreeTool(workspace),
            WorkspaceInspectTool(workspace),
            WorkspaceImportZipTool(workspace)
        )
    )

    AgentEngine(
        provider = provider,
        conversationStore = store,
        tools = tools
    ).use { lisa ->
        println("=== Lisa AI Assistant ===")
        println("Provider: ${provider.name}")
        println("Model: ${provider.model}")
        println("History file: $historyFile")

        val restoredCount = lisa.getHistory().size
        println("Restored messages: $restoredCount")
        println("Type /exit to quit, /clear to clear saved history.")
        println()

        while (true) {
            print("You: ")
            System.out.flush()

            val input = readlnOrNull() ?: break
            val command = input.trim()

            when {
                command.equals("/exit", ignoreCase = true) -> break

                command.equals("/clear", ignoreCase = true) -> {
                    lisa.clearHistory()
                    println("Lisa: Conversation history cleared.")
                }

                command.isBlank() -> continue

                else -> {
                    try {
                        val response = lisa.chat(command)
                        println("Lisa: ${response.text}")
                        println()
                    } catch (error: Exception) {
                        System.err.println(
                            "Lisa: Request failed: ${error.message ?: error::class.simpleName}"
                        )
                        println()
                    }
                }
            }
        }

        println("Lisa session ended.")
    }
}
