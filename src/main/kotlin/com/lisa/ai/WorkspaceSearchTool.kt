package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.ArrayDeque

class WorkspaceSearchTool(
    workspace: Path,
    private val maxFiles: Int = 2000,
    private val maxMatches: Int = 100,
    private val maxFileBytes: Long = 256 * 1024
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "search_workspace"
    override val description =
        "Search text files inside Lisa-Workspace for a literal, case-insensitive query. Skips generated and hidden directories."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("query", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Literal text to find in project files."))
            })
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional relative directory to search; defaults to workspace root."))
            })
        })
        put("required", buildJsonArray { add(JsonPrimitive("query")) })
        put("additionalProperties", JsonPrimitive(false))
    }

    private val ignoredDirectories = setOf(
        ".git", ".gradle", ".idea", ".kotlin", "build",
        "node_modules", "dist", "target", "vendor", ".venv", "venv"
    )

    init {
        require(maxFiles in 1..10000)
        require(maxMatches in 1..1000)
        require(maxFileBytes in 1..(10L * 1024 * 1024))
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val query = arguments["query"]?.trim().orEmpty()
        if (query.isEmpty()) return ToolResult.failure("A search query is required.")
        if (query.length > 500) {
            return ToolResult.failure("Search query is too long (maximum 500 characters).")
        }
        if (query.contains('\u0000')) return ToolResult.failure("Invalid search query.")

        val relative = arguments["path"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "."
        if (relative.contains('\u0000')) return ToolResult.failure("Invalid workspace path.")

        val start = root.resolve(relative).normalize()
        if (!start.startsWith(root)) {
            return ToolResult.failure("Path is outside the selected workspace.")
        }

        return try {
            if (!hasSafeComponents(start)) {
                return ToolResult.failure("Symbolic links are not allowed in workspace paths.")
            }
            if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
                return ToolResult.failure("Directory not found: $relative")
            }

            val matches = mutableListOf<String>()
            val pending = ArrayDeque<Path>()
            pending.add(start)
            var scannedFiles = 0
            var skippedLargeFiles = 0
            var truncated = false

            while (pending.isNotEmpty() && !truncated) {
                val directory = pending.removeLast()

                val children = Files.list(directory).use { stream ->
                    stream.sorted(compareBy<Path> { it.fileName.toString().lowercase() })
                        .toList()
                }

                for (entry in children) {
                    if (Files.isSymbolicLink(entry)) continue

                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        val name = entry.fileName.toString()
                        if (name.startsWith(".") || name in ignoredDirectories) continue
                        pending.add(entry)
                        continue
                    }

                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue

                    scannedFiles++
                    if (scannedFiles > maxFiles) {
                        scannedFiles--
                        truncated = true
                        break
                    }

                    if (Files.size(entry) > maxFileBytes) {
                        skippedLargeFiles++
                        continue
                    }

                    val lines = try {
                        Files.readAllLines(entry, Charsets.UTF_8)
                    } catch (_: Exception) {
                        continue
                    }

                    for ((index, line) in lines.withIndex()) {
                        if (line.contains(query, ignoreCase = true)) {
                            val relativeFile = start.relativize(entry)
                            matches.add("$relativeFile:${index + 1}: ${line.trim().take(300)}")
                            if (matches.size >= maxMatches) {
                                truncated = true
                                break
                            }
                        }
                    }
                    if (truncated) break
                }
            }

            val header =
                "Search: $query\nDirectory: $relative\nFiles scanned: $scannedFiles"
            val footer = buildString {
                if (skippedLargeFiles > 0) append("\nSkipped large files: $skippedLargeFiles")
                if (truncated) append("\nResults limited; refine the query or search path for more.")
            }

            ToolResult.success(
                header + "\n" +
                    (matches.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: "No matches found.") +
                    footer
            )
        } catch (error: Exception) {
            ToolResult.failure("Unable to search workspace: ${error.message ?: "unknown error"}")
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
