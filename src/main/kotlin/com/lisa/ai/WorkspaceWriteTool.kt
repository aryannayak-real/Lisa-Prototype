package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Creates a new UTF-8 text file inside the selected workspace without overwriting existing files.
 */
class WorkspaceWriteTool(
    workspace: Path,
    private val maxBytes: Int = 256 * 1024
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "create_workspace_file"
    override val description =
        "Create a new UTF-8 text file inside the selected project workspace. Never overwrites an existing file. Provide a relative path and the complete file content."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Relative path for the new file inside the workspace."))
            })
            put("content", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Complete UTF-8 text content of the new file."))
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("content"))
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
        val content = arguments["content"]
            ?: return ToolResult.failure("File content is required.")

        if (relative.isEmpty()) {
            return ToolResult.failure("A relative file path is required.")
        }

        return try {
            val requested = root.resolve(relative).normalize()

            if (requested == root || !requested.startsWith(root)) {
                return ToolResult.failure("Path is outside the selected workspace or is not a file path.")
            }

            var current = root
            for (component in root.relativize(requested)) {
                current = current.resolve(component)
                if (Files.isSymbolicLink(current)) {
                    return ToolResult.failure("Symbolic links are not allowed.")
                }
            }

            val parent = requested.parent
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failure("Parent directory does not exist: ${parent ?: relative}")
            }

            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > maxBytes) {
                return ToolResult.failure(
                    "Content is too large (${bytes.size} bytes). Limit: $maxBytes bytes."
                )
            }

            Files.write(
                requested,
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            )

            ToolResult.success("Created file: $relative (${bytes.size} bytes).")
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            ToolResult.failure("File already exists; it was not overwritten: $relative")
        } catch (error: Exception) {
            ToolResult.failure(
                "Unable to create workspace file: ${error.message ?: "unknown error"}"
            )
        }
    }
}
