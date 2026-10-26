package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Replaces one exact text occurrence in an existing workspace file.
 */
class WorkspaceEditTool(
    workspace: Path,
    private val maxBytes: Int = 256 * 1024
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "edit_workspace_file"
    override val description =
        "Edit a UTF-8 text file inside the selected workspace by replacing exact existing text. The old text must occur exactly once; otherwise no changes are made. Provide a relative path, exact old text, and replacement text."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Relative path of the existing file inside the workspace."))
            })
            put("old_text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Exact text to find. It must occur exactly once."))
            })
            put("new_text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Replacement text."))
            })
            put("expected_original", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional full original file content. Reject the edit if the file differs from this content."))
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("path"))
            add(JsonPrimitive("old_text"))
            add(JsonPrimitive("new_text"))
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
        val oldText = arguments["old_text"]
            ?: return ToolResult.failure("The exact old_text is required.")
        val newText = arguments["new_text"]
            ?: return ToolResult.failure("The new_text replacement is required.")

        if (relative.isEmpty()) {
            return ToolResult.failure("A relative file path is required.")
        }
        if (oldText.isEmpty()) {
            return ToolResult.failure("old_text cannot be empty.")
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

            if (!Files.isRegularFile(requested, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failure("File not found or not a regular file: $relative")
            }

            val originalBytes = Files.readAllBytes(requested)
            if (originalBytes.size > maxBytes) {
                return ToolResult.failure("File exceeds the edit limit of $maxBytes bytes.")
            }

            val original = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(originalBytes))
                .toString()

            val expectedOriginal = arguments["expected_original"]
            if (expectedOriginal != null && expectedOriginal != original) {
                return ToolResult.failure("File changed since preview; edit rejected. Preview again before applying.")
            }

            val first = original.indexOf(oldText)
            if (first < 0) {
                return ToolResult.failure("Exact old_text was not found; file was not changed.")
            }
            if (original.indexOf(oldText, first + oldText.length) >= 0) {
                return ToolResult.failure("old_text occurs more than once; file was not changed.")
            }

            val updated = original.replaceRange(first, first + oldText.length, newText)
            val updatedBytes = updated.toByteArray(StandardCharsets.UTF_8)
            if (updatedBytes.size > maxBytes) {
                return ToolResult.failure("Edited file would exceed the limit of $maxBytes bytes.")
            }

            // Create a temporary sibling, then atomically replace the file where supported.
            val temp = Files.createTempFile(requested.parent, ".lisa-edit-", ".tmp")
            try {
                Files.write(
                    temp,
                    updatedBytes,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
                )
                try {
                    Files.move(
                        temp,
                        requested,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(
                        temp,
                        requested,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                Files.deleteIfExists(temp)
            }

            ToolResult.success("Edited file: $relative (${updatedBytes.size} bytes).")
        } catch (error: Exception) {
            ToolResult.failure(
                "Unable to edit workspace file: ${error.message ?: "unknown error"}"
            )
        }
    }
}
