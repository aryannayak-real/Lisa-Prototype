package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Lists files within one explicitly selected workspace.
 * Symbolic links are not followed.
 */
class WorkspaceListTool(
    workspace: Path,
    private val maxEntries: Int = 200
) : AgentTool {
    private val root: Path = workspace.toAbsolutePath().normalize()

    override val name = "list_workspace"
    override val description =
        "List files and directories inside the selected project workspace. Provide an optional relative path."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Relative directory path; use . for the workspace root."))
            })
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    init {
        require(maxEntries in 1..1000) { "maxEntries must be between 1 and 1000." }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace must be an existing directory."
        }
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val relative = arguments["path"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "."

        return try {
            val requested = root.resolve(relative).normalize()
            if (!requested.startsWith(root)) {
                return ToolResult.failure("Path is outside the selected workspace.")
            }
            if (Files.isSymbolicLink(requested)) {
                return ToolResult.failure("Symbolic links are not allowed.")
            }
            if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failure("Directory not found: $relative")
            }

            val entries = Files.list(requested).use { stream ->
                stream
                    .filter { !Files.isSymbolicLink(it) }
                    .sorted(compareBy<Path> { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .thenBy { it.fileName.toString().lowercase() })
                    .limit((maxEntries + 1).toLong())
                    .toList()
            }

            val truncated = entries.size > maxEntries
            val shown = entries.take(maxEntries)
            val lines = shown.map { entry ->
                val kind = if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) "[DIR] " else "[FILE] "
                "$kind${entry.fileName}"
            }

            val header = "Workspace: $root\nDirectory: $relative"
            val footer = if (truncated) "\nResults limited to $maxEntries entries." else ""
            ToolResult.success(
                if (lines.isEmpty()) "$header\n(empty directory)"
                else "$header\n${lines.joinToString("\n")}$footer"
            )
        } catch (error: Exception) {
            ToolResult.failure("Unable to list workspace directory: ${error.message ?: "unknown error"}")
        }
    }
}
