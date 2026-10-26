package com.lisa.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lisa's conversation engine with optional persistent conversation storage
 * and optional agent-tool execution.
 */
class AgentEngine(
    private val provider: AIProvider,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxHistoryMessages: Int = 40,
    private val conversationStore: ConversationStore? = null,
    private val tools: ToolRegistry = ToolRegistry(),
    private val maxAgentTurns: Int = 12,
) : AutoCloseable {
    private val mutex = Mutex()
    private val history = mutableListOf<ChatMessage>()
    private var initialized = false

    private suspend fun initialize() {
        if (initialized) return
        val saved = conversationStore?.load().orEmpty()
        history.clear()
        history.addAll(saved)
        trimHistory()
        initialized = true
    }

    suspend fun chat(userText: String): AIResponse = mutex.withLock {
        initialize()

        val input = userText.trim()
        require(input.isNotEmpty()) { "Message cannot be empty." }

        val previousHistory = history.toList()
        history.add(ChatMessage(role = "user", content = input))
        trimHistory()

        try {
            val response = if (tools.listTools().isNotEmpty()) {
                val agentModel = provider as? AgentModel
                    ?: throw AIProviderException(
                        "The configured provider does not support agent tools.",
                    )

                val result = AgentRunner(
                    model = agentModel,
                    tools = tools,
                    systemPrompt = systemPrompt,
                    maxTurns = maxAgentTurns,
                ).run(history.toList())

                history.clear()
                history.addAll(result.messages)
                AIResponse(
                    text = result.answer,
                    provider = provider.name,
                    model = provider.model,
                )
            } else {
                val generated = provider.generate(
                    messages = history.toList(),
                    systemPrompt = systemPrompt,
                )
                history.add(ChatMessage(role = "assistant", content = generated.text))
                generated
            }

            trimHistory()
            conversationStore?.save(history.toList())
            response
        } catch (error: Exception) {
            history.clear()
            history.addAll(previousHistory)
            throw error
        }
    }

    suspend fun getHistory(): List<ChatMessage> = mutex.withLock {
        initialize()
        history.toList()
    }

    suspend fun clearHistory() = mutex.withLock {
        initialize()
        history.clear()
        conversationStore?.clear()
    }

    private fun trimHistory() {
        while (history.size > maxHistoryMessages) {
            history.removeAt(0)
        }
    }

    override fun close() {
        (provider as? AutoCloseable)?.close()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """
            You are Lisa, a helpful AI companion and coding assistant.
            Use the entire conversation history, including the user's latest request and earlier details.
            Do not ask the user to repeat information already provided or clearly available in the conversation.
            When the user requests a workspace action and the required details are present, use the appropriate tools
            and execute the steps in the requested order. Do not merely describe or invent tool results.
            When the user provides an existing project as a ZIP, import it into Lisa-Workspace using workspace_import_zip.
            Use the ZIP's absolute path and a simple, new destination folder name. Never overwrite an existing project.
            After importing, inspect the imported project with inspect_workspace_project and follow its project instructions.
            Then perform the requested work directly: read relevant files, make focused edits, and run available tests.
            Do not stop at describing a plan when the requested action can be completed with available tools.
            Before substantial coding work, inspect the workspace project with inspect_workspace_project.
            Read relevant project instructions and documentation, then inspect the specific source files before planning edits.
            Follow the project's existing language, architecture, and conventions; avoid unrelated rewrites.
            After code changes, run relevant tests when available and report their actual results.
            If a required detail is genuinely missing, ask only for that detail.
            Treat tool output as the source of truth. Report success only after the tool confirms it.
            For file edits, inspect the current file content before planning a replacement.
            For preview requests, call preview_workspace_edit and never apply the edit; report its actual output.
            When applying an edit after a preview, use the exact original file content as expected_original
            so the edit is rejected if the file has changed since it was inspected. If the content is unavailable,
            read the file first; never invent expected_original. A preview is not permission to apply an edit.
            Treat file modifications as separate actions and apply them only when the user has requested the change.
            Be honest about uncertainty, explain things clearly, and help the user complete tasks safely.
            Never claim to have performed an action that you did not actually perform.
        """
    }
}
