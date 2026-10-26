package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Reads a bounded amount of text from a file inside the selected workspace.
 */
class WorkspaceReadTool(
    workspace: Path,
    private val maxBytes: Long = 64 * 1024
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "read_workspace_file"
    override val description =
        "Read a UTF-8 text file inside the selected project workspace. Provide its relative file path."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Relative path to the text file inside the workspace."))
            })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(JsonPrimitive("path"))
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    init {
        require(maxBytes in 1..(1024 * 1024)) {
            "maxBytes must be between 1 byte and 1 MiB."
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace must be an existing directory."
        }
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val relative = arguments["path"]?.trim().orEmpty()
        if (relative.isEmpty()) {
            return ToolResult.failure("A relative file path is required.")
        }

        return try {
            val requested = root.resolve(relative).normalize()

            if (!requested.startsWith(root)) {
                return ToolResult.failure("Path is outside the selected workspace.")
            }

            // Check every path component so a symlinked parent cannot escape the workspace.
            var current = root
            val components = root.relativize(requested)
            for (component in components) {
                current = current.resolve(component)
                if (Files.isSymbolicLink(current)) {
                    return ToolResult.failure("Symbolic links are not allowed.")
                }
            }

            if (!Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failure("File not found or not a regular file: $relative")
            }

            val size = Files.size(requested)
            if (size > maxBytes) {
                return ToolResult.failure(
                    "File is too large to read at once ($size bytes). Limit: $maxBytes bytes."
                )
            }

            val bytes = Files.readAllBytes(requested)
            if (bytes.size > maxBytes) {
                return ToolResult.failure("File grew beyond the read limit; try again.")
            }

            val content = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()

            ToolResult.success(
                "File: $relative\nSize: ${bytes.size} bytes\n--- BEGIN FILE ---\n$content\n--- END FILE ---"
            )
        } catch (error: Exception) {
            ToolResult.failure(
                "Unable to read workspace file: ${error.message ?: "unknown error"}"
            )
        }
    }
}
