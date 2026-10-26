package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.stream.Collectors

class WorkspaceTreeTool(
    workspace: Path,
    private val maxEntries: Int = 300,
    private val maxDepth: Int = 8
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "workspace_project_tree"
    override val description =
        "Show a bounded recursive tree of project files and directories inside Lisa-Workspace. Provide an optional relative directory path."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional relative directory path; use . for the workspace root."))
            })
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    private val ignoredDirectories = setOf(
        ".git", ".gradle", ".idea", ".kotlin", "build",
        "node_modules", "dist", "target", "vendor", ".venv", "venv"
    )

    init {
        require(maxEntries in 1..2000)
        require(maxDepth in 1..20)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val relative = arguments["path"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "."
        val requested = root.resolve(relative).normalize()

        if (!requested.startsWith(root)) {
            return ToolResult.failure("Path is outside the selected workspace.")
        }

        if (!hasSafeComponents(requested)) {
            return ToolResult.failure("Symbolic links are not allowed in workspace paths.")
        }

        if (!Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.failure("Directory not found: $relative")
        }

        return try {
            val lines = mutableListOf<String>()
            var visited = 0
            var truncated = false

            fun walk(directory: Path, depth: Int, prefix: String) {
                if (truncated || depth > maxDepth) return

                val children = Files.list(directory).use { stream ->
                    stream
                        .filter { !Files.isSymbolicLink(it) }
                        .filter {
                            !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) ||
                                it.fileName.toString() !in ignoredDirectories
                        }
                        .sorted(compareBy<Path> { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                            .thenBy { it.fileName.toString().lowercase() })
                        .collect(Collectors.toList())
                }

                for ((index, child) in children.withIndex()) {
                    if (visited >= maxEntries) {
                        truncated = true
                        return
                    }

                    visited++
                    val isDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
                    val last = index == children.lastIndex
                    val branch = if (last) "└── " else "├── "
                    val label = child.fileName.toString() + if (isDirectory) "/" else ""
                    lines.add(prefix + branch + label)

                    if (isDirectory && depth < maxDepth) {
                        walk(child, depth + 1, prefix + if (last) "    " else "│   ")
                    }
                    if (truncated) return
                }
            }

            lines.add(requested.fileName?.toString() ?: root.toString())
            walk(requested, 1, "")

            val footer = buildString {
                if (truncated) append("\n[Tree truncated at $maxEntries entries.]")
                if (visited > 0) append("\nEntries shown: $visited")
            }

            ToolResult.success(
                "Project tree for: $relative\n" +
                    if (lines.size == 1) "(empty directory)" else lines.joinToString("\n") +
                    footer
            )
        } catch (error: Exception) {
            ToolResult.failure("Unable to build project tree: ${error.message ?: "unknown error"}")
        }
    }

    private fun hasSafeComponents(path: Path): Boolean {
        var current = root
        for (part in root.relativize(path)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return false
        }
        return true
    }
}
