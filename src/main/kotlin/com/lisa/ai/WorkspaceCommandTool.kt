package com.lisa.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class WorkspaceCommandTool(
    workspace: Path,
    private val timeoutSeconds: Long = 15,
    private val maxOutputBytes: Int = 32 * 1024
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "run_workspace_command"
    override val description =
        "Run a restricted read-only command in Lisa-Workspace. Supported: pwd, ls, cat, grep. Arguments must be supplied as a JSON array of strings. No shell, pipelines, redirection, arbitrary executables, or writes."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("command", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("pwd"))
                    add(JsonPrimitive("ls"))
                    add(JsonPrimitive("cat"))
                    add(JsonPrimitive("grep"))
                })
            })
            put("args", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("items", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
            })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("command"))
            add(JsonPrimitive("args"))
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    init {
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Workspace must be an existing directory."
        }
        require(timeoutSeconds in 1..60)
        require(maxOutputBytes in 1024..(256 * 1024))
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val command = arguments["command"]?.trim().orEmpty()
        val rawArgs = arguments["args"].orEmpty()

        val args = try {
            val array = Json.parseToJsonElement(rawArgs) as? JsonArray
                ?: return ToolResult.failure("args must be a JSON array of strings.")
            array.map {
                (it as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                    ?: return ToolResult.failure("Every argument must be a string.")
            }
        } catch (_: Exception) {
            return ToolResult.failure("args must be a valid JSON array of strings.")
        }

        val processArgs: List<String>
        val executable: String

        when (command) {
            "pwd" -> {
                if (args.isNotEmpty()) return ToolResult.failure("pwd takes no arguments.")
                executable = "/data/data/com.termux/files/usr/bin/pwd"
                processArgs = listOf(executable)
            }

            "ls" -> {
                if (args.size > 1) return ToolResult.failure("ls accepts at most one workspace-relative path.")
                val path = if (args.isEmpty()) root else resolveSafePath(args[0])
                    ?: return ToolResult.failure("Invalid or unsafe workspace path.")
                executable = "/data/data/com.termux/files/usr/bin/ls"
                processArgs = listOf(executable, "-la", path.toString())
            }

            "cat" -> {
                if (args.size != 1) return ToolResult.failure("cat requires exactly one workspace-relative file path.")
                val path = resolveSafePath(args[0])
                    ?: return ToolResult.failure("Invalid or unsafe workspace path.")
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return ToolResult.failure("The requested path is not a regular file.")
                }
                if (Files.size(path) > maxOutputBytes) {
                    return ToolResult.failure("File exceeds the output size limit.")
                }
                executable = "/data/data/com.termux/files/usr/bin/cat"
                processArgs = listOf(executable, path.toString())
            }

            "grep" -> {
                if (args.size != 2) {
                    return ToolResult.failure("grep requires a literal search term and one workspace-relative file path.")
                }
                val pattern = args[0]
                if (pattern.isEmpty() || pattern.startsWith("-") ||
                    pattern.contains('\u0000') || pattern.contains('\n') || pattern.contains('\r')) {
                    return ToolResult.failure("Invalid search term.")
                }
                val path = resolveSafePath(args[1])
                    ?: return ToolResult.failure("Invalid or unsafe workspace path.")
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    return ToolResult.failure("The requested path is not a regular file.")
                }
                if (Files.size(path) > maxOutputBytes * 8L) {
                    return ToolResult.failure("File is too large to search with this command.")
                }
                executable = "/data/data/com.termux/files/usr/bin/grep"
                processArgs = listOf(executable, "-n", "-F", "--", pattern, path.toString())
            }

            else -> return ToolResult.failure("Command not allowed. Use pwd, ls, cat, or grep.")
        }

        return try {
            val process = ProcessBuilder(processArgs)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()

            val captured = ByteArrayOutputStream()
            val reader = Thread {
                process.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(captured) {
                            val remaining = maxOutputBytes - captured.size()
                            if (remaining > 0) captured.write(buffer, 0, minOf(count, remaining))
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                reader.join(1000)
                return ToolResult.failure("Command timed out after $timeoutSeconds seconds.")
            }
            reader.join(1000)

            val output = synchronized(captured) {
                String(captured.toByteArray(), StandardCharsets.UTF_8)
            }
            val truncated = synchronized(captured) { captured.size() >= maxOutputBytes }
            val suffix = if (truncated) "\n[Output truncated at $maxOutputBytes bytes.]" else ""
            ToolResult.success(
                "Command: $command\nExit code: ${process.exitValue()}\n$output$suffix"
            )
        } catch (error: Exception) {
            ToolResult.failure("Unable to run command: ${error.message ?: "unknown error"}")
        }
    }

    private fun resolveSafePath(value: String): Path? {
        if (value.isBlank() || value.startsWith("-") ||
            value.contains('\u0000') || value.contains('\n') || value.contains('\r')) {
            return null
        }

        val candidate = root.resolve(value).normalize()
        if (!candidate.startsWith(root) || candidate == root) return null

        var current = root
        for (part in root.relativize(candidate)) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return null
        }

        return candidate
    }
}
