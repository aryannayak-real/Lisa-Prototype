package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class WorkspacePreviewEditTool(
    workspace: Path,
    private val maxBytes: Int = 256 * 1024
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "preview_workspace_edit"
    override val description =
        "For every new preview request, call this tool using the requested path, exact old_text, and new_text. Never substitute or claim a previous preview is a fresh execution. Preview an exact text replacement in a workspace file without changing it. The old text must occur exactly once."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Relative path of an existing workspace file."))
            })
            put("old_text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Exact text to find once."))
            })
            put("new_text", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Proposed replacement text."))
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
        require(maxBytes in 1..(1024 * 1024))
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val relative = arguments["path"]?.trim().orEmpty()
        val oldText = arguments["old_text"]
            ?: return ToolResult.failure("The exact old_text is required.")
        val newText = arguments["new_text"]
            ?: return ToolResult.failure("The proposed new_text is required.")

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

            val bytes = Files.readAllBytes(requested)
            if (bytes.size > maxBytes) {
                return ToolResult.failure("File exceeds the preview limit of $maxBytes bytes.")
            }

            val original = StandardCharsets.UTF_8.newDecoder()
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()

            val first = original.indexOf(oldText)
            if (first < 0) {
                return ToolResult.failure("Exact old_text was not found; file was not changed.")
            }
            if (original.indexOf(oldText, first + oldText.length) >= 0) {
                return ToolResult.failure("old_text occurs more than once; preview is ambiguous.")
            }

            val updated = original.replaceRange(first, first + oldText.length, newText)
            val updatedBytes = updated.toByteArray(StandardCharsets.UTF_8)
            if (updatedBytes.size > maxBytes) {
                return ToolResult.failure("Preview exceeds the limit of $maxBytes bytes.")
            }

            val oldLines = oldText.lines()
            val newLines = newText.lines()
            val diff = buildString {
                append("--- a/$relative\\n")
                append("+++ b/$relative\\n")
                oldLines.forEach { append("-").append(it).append("\\n") }
                newLines.forEach { append("+").append(it).append("\\n") }
            }

            ToolResult.success(
                "PREVIEW ONLY — file was not changed: $relative\\n" +
                    "--- UNIFIED-STYLE DIFF ---\\n" +
                    diff +
                    "--- SUMMARY ---\\n" +
                    "Original size: ${bytes.size} bytes\\n" +
                    "Proposed size: ${updatedBytes.size} bytes\\n" +
                    "Exact match count: 1\\n" +
                    "No changes have been written."
            )
        } catch (error: Exception) {
            ToolResult.failure("Unable to preview workspace edit: ${error.message ?: "unknown error"}")
        }
    }
}
