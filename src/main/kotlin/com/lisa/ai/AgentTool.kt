package com.lisa.ai

/**
 * Standard interface for capabilities that Lisa's agent can invoke.
 * Tools must validate their own inputs and report errors clearly.
 */
interface AgentTool {
    val name: String
    val description: String

    /**
     * JSON Schema describing this tool's arguments.
     * The default permits arbitrary object properties for older tools.
     */
    val parametersSchema: kotlinx.serialization.json.JsonObject
        get() = kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put("properties", kotlinx.serialization.json.buildJsonObject {})
            put("additionalProperties", kotlinx.serialization.json.JsonPrimitive(true))
        }

    suspend fun execute(arguments: Map<String, String>): ToolResult
}

data class ToolResult(
    val success: Boolean,
    val output: String,
    val error: String? = null
) {
    companion object {
        fun success(output: String) = ToolResult(
            success = true,
            output = output
        )

        fun failure(error: String) = ToolResult(
            success = false,
            output = "",
            error = error
        )
    }
}
