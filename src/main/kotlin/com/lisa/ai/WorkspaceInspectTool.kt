package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Read-only project overview for files inside Lisa-Workspace.
 */
class WorkspaceInspectTool(
    workspace: Path,
    private val maxFiles: Int = 1500,
    private val maxOutputChars: Int = 12000
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "inspect_workspace_project"
    override val description =
        "Inspect a project read-only. Identify likely project types, instructions, README files, build manifests, and source entry points inside Lisa-Workspace. Skips generated and hidden directories."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("path", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Optional relative project directory; defaults to workspace root."))
            })
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    private val ignoredDirectories = setOf(
        ".git", ".gradle", ".idea", ".kotlin", "build",
        "node_modules", "dist", "target", "vendor", ".venv", "venv"
    )

    private val importantNames = setOf(
        "AGENTS.md", "CLAUDE.md", "README", "README.md", "README.txt",
        "settings.gradle", "settings.gradle.kts", "build.gradle",
        "build.gradle.kts", "gradlew", "pom.xml", "package.json",
        "tsconfig.json", "vite.config.ts", "vite.config.js",
        "pyproject.toml", "requirements.txt", "setup.py",
        "Cargo.toml", "go.mod", "Makefile", "AndroidManifest.xml"
    )

    init {
        require(maxFiles in 1..10000)
        require(maxOutputChars in 1000..50000)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val relative = arguments["path"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "."
        if (relative.contains('\u0000')) {
            return ToolResult.failure("Invalid workspace path.")
        }

        val start = root.resolve(relative).normalize()
        if (!start.startsWith(root)) {
            return ToolResult.failure("Path is outside the selected workspace.")
        }
        if (!hasSafeComponents(start)) {
            return ToolResult.failure("Symbolic links are not allowed in workspace paths.")
        }
        if (!Files.isDirectory(start, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.failure("Directory not found: $relative")
        }

        return try {
            val important = sortedSetOf<String>()
            val sourceFiles = sortedSetOf<String>()
            val types = sortedSetOf<String>()
            val pending = ArrayDeque<Path>()
            pending.add(start)
            var scanned = 0
            var truncated = false

            while (pending.isNotEmpty() && !truncated) {
                val directory = pending.removeLast()
                val children = Files.list(directory).use { stream ->
                    stream.sorted(compareBy<Path> { it.fileName.toString().lowercase() }).toList()
                }

                for (entry in children) {
                    if (Files.isSymbolicLink(entry)) continue
                    val name = entry.fileName.toString()

                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                        if (!name.startsWith(".") && name !in ignoredDirectories) {
                            pending.add(entry)
                        }
                        continue
                    }
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue

                    scanned++
                    if (scanned > maxFiles) {
                        scanned--
                        truncated = true
                        break
                    }

                    val rel = start.relativize(entry).toString()
                        .replace(entry.fileSystem.separator, "/")

                    if (name in importantNames || name.startsWith("README.")) {
                        important.add(rel)
                    }

                    when {
                        name.startsWith("build.gradle") || name.startsWith("settings.gradle") ->
                            types.add("Gradle project")
                        name.endsWith(".kt") || name.endsWith(".kts") -> {
                            types.add("Kotlin/JVM or Kotlin project")
                            if (name == "Main.kt" || name.endsWith("Application.kt")) {
                                sourceFiles.add(rel)
                            }
                        }
                        name.endsWith(".java") -> {
                            types.add("Java project")
                            if (name == "Main.java") sourceFiles.add(rel)
                        }
                        name.endsWith(".tsx") || name.endsWith(".ts") ||
                            name.endsWith(".jsx") || name.endsWith(".js") -> {
                            types.add("JavaScript/TypeScript project")
                            if (name in setOf("main.ts", "main.tsx", "index.ts", "index.tsx", "App.tsx", "App.jsx")) {
                                sourceFiles.add(rel)
                            }
                        }
                        name.endsWith(".py") -> {
                            types.add("Python project")
                            if (name in setOf("main.py", "app.py", "__main__.py")) {
                                sourceFiles.add(rel)
                            }
                        }
                        name == "AndroidManifest.xml" -> types.add("Android application")
                        name == "package.json" -> types.add("Node.js project")
                        name == "pyproject.toml" || name == "requirements.txt" -> types.add("Python project")
                        name == "pom.xml" -> types.add("Maven project")
                        name == "Cargo.toml" -> types.add("Rust project")
                        name == "go.mod" -> types.add("Go project")
                    }
                }
            }

            val report = buildString {
                appendLine("Read-only project inspection: $relative")
                appendLine("Files scanned: $scanned${if (truncated) " (scan limit reached)" else ""}")
                appendLine()
                appendLine("Likely project types:")
                if (types.isEmpty()) appendLine("- Not enough evidence to identify a project type.")
                else types.forEach { appendLine("- $it") }

                appendLine()
                appendLine("Important project files:")
                if (important.isEmpty()) appendLine("- None found.")
                else important.take(100).forEach { appendLine("- $it") }

                appendLine()
                appendLine("Likely source entry points:")
                if (sourceFiles.isEmpty()) appendLine("- None recognized by filename.")
                else sourceFiles.take(100).forEach { appendLine("- $it") }

                appendLine()
                append("This inspection did not modify any files.")
            }.take(maxOutputChars)

            ToolResult.success(report)
        } catch (error: Exception) {
            ToolResult.failure("Unable to inspect project: ${error.message ?: "unknown error"}")
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
