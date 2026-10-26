package com.lisa.ai

/**
 * Registers and exposes the tools available to Lisa's agent.
 * Registration rejects duplicate names to avoid ambiguous execution.
 */
class ToolRegistry(tools: List<AgentTool> = emptyList()) {

    private val registered = linkedMapOf<String, AgentTool>()

    init {
        tools.forEach(::register)
    }

    @Synchronized
    fun register(tool: AgentTool) {
        require(tool.name.isNotBlank()) { "Tool name cannot be blank." }
        require(tool.name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,63}"))) {
            "Invalid tool name: ${tool.name}"
        }
        require(tool.description.isNotBlank()) { "Tool description cannot be blank." }
        require(!registered.containsKey(tool.name)) {
            "A tool named '${tool.name}' is already registered."
        }
        registered[tool.name] = tool
    }

    @Synchronized
    fun listTools(): List<AgentTool> = registered.values.toList()

    @Synchronized
    fun describeTools(): String = registered.values.joinToString("\n") {
        "${it.name}: ${it.description}"
    }.ifBlank { "No tools are currently available." }

    suspend fun execute(
        name: String,
        arguments: Map<String, String>
    ): ToolResult {
        val tool = synchronized(this) { registered[name] }
            ?: return ToolResult.failure("Unknown tool: $name")

        return try {
            tool.execute(arguments)
        } catch (error: Exception) {
            ToolResult.failure(
                "Tool '$name' failed: ${error.message ?: error::class.simpleName}"
            )
        }
    }
}
