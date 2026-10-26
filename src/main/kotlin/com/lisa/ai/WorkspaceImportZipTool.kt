package com.lisa.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

class WorkspaceImportZipTool(
    workspace: Path,
    private val maxEntries: Int = 5_000,
    private val maxEntryBytes: Long = 50L * 1024 * 1024,
    private val maxTotalBytes: Long = 250L * 1024 * 1024,
) : AgentTool {
    private val root = workspace.toAbsolutePath().normalize()

    override val name = "workspace_import_zip"
    override val description =
        "Import a ZIP project into a new directory inside Lisa-Workspace. " +
        "Provide the absolute path to the ZIP file and a simple destination folder name. " +
        "Never overwrites an existing destination."

    override val parametersSchema = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("zipPath", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Absolute path to the ZIP file to import."))
            })
            put("destination", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("New folder name inside Lisa-Workspace; letters, numbers, dot, underscore and hyphen only."))
            })
        })
        put("required", kotlinx.serialization.json.buildJsonArray {
            add(JsonPrimitive("zipPath"))
            add(JsonPrimitive("destination"))
        })
        put("additionalProperties", JsonPrimitive(false))
    }

    init {
        require(maxEntries in 1..20_000)
        require(maxEntryBytes > 0)
        require(maxTotalBytes >= maxEntryBytes)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(root))
    }

    override suspend fun execute(arguments: Map<String, String>): ToolResult {
        val zipText = arguments["zipPath"]?.trim().orEmpty()
        val destinationName = arguments["destination"]?.trim().orEmpty()

        if (zipText.isEmpty()) return ToolResult.failure("ZIP path is required.")
        if (!destinationName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) ||
            destinationName == "." || destinationName == ".."
        ) {
            return ToolResult.failure("Destination must be a simple folder name.")
        }

        val zip = try {
            Path.of(zipText).toAbsolutePath().normalize()
        } catch (_: Exception) {
            return ToolResult.failure("Invalid ZIP path.")
        }

        if (!Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(zip)
        ) {
            return ToolResult.failure("ZIP file not found or symbolic links are not allowed.")
        }
        if (!zip.fileName.toString().endsWith(".zip", ignoreCase = true)) {
            return ToolResult.failure("The selected file must have a .zip extension.")
        }

        val destination = root.resolve(destinationName).normalize()
        if (!destination.startsWith(root) || destination == root) {
            return ToolResult.failure("Destination is outside the selected workspace.")
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.failure("Destination already exists; nothing was overwritten.")
        }

        var createdDestination = false
        return try {
            Files.createDirectory(destination)
            createdDestination = true

            var entries = 0
            var totalBytes = 0L
            val buffer = ByteArray(16 * 1024)

            BufferedInputStream(Files.newInputStream(zip)).use { input ->
                ZipInputStream(input).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        entries++
                        if (entries > maxEntries) {
                            throw IllegalArgumentException("ZIP contains too many entries.")
                        }

                        val rawName = entry.name
                        if (rawName.isBlank() || rawName.startsWith("/") ||
                            rawName.startsWith("\\") ||
                            Regex("^[A-Za-z]:").containsMatchIn(rawName) ||
                            rawName.contains('\\') ||
                            rawName.split('/').any { it == ".." || it == "." }
                        ) {
                            throw IllegalArgumentException("Unsafe ZIP entry path rejected.")
                        }

                        val relative = Path.of(rawName).normalize()
                        if (relative.isAbsolute || relative.startsWith("..")) {
                            throw IllegalArgumentException("Unsafe ZIP entry path rejected.")
                        }

                        val output = destination.resolve(relative).normalize()
                        if (!output.startsWith(destination) || output == destination) {
                            throw IllegalArgumentException("ZIP entry escapes destination.")
                        }

                        if (entry.isDirectory || rawName.endsWith("/")) {
                            Files.createDirectories(output)
                        } else {
                            Files.createDirectories(output.parent)
                            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                                throw IllegalArgumentException("Duplicate ZIP entry rejected.")
                            }

                            var entryBytes = 0L
                            Files.newOutputStream(output).use { out ->
                                while (true) {
                                    val count = archive.read(buffer)
                                    if (count < 0) break
                                    entryBytes += count
                                    totalBytes += count

                                    if (entryBytes > maxEntryBytes) {
                                        throw IllegalArgumentException("A ZIP entry exceeds the size limit.")
                                    }
                                    if (totalBytes > maxTotalBytes) {
                                        throw IllegalArgumentException("ZIP contents exceed the total size limit.")
                                    }
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                        archive.closeEntry()
                    }
                }
            }

            ToolResult.success(
                "ZIP project imported into Lisa-Workspace/$destinationName. " +
                "Entries: $entries; extracted bytes: $totalBytes."
            )
        } catch (error: Exception) {
            if (createdDestination) {
                runCatching { deleteTree(destination) }
            }
            ToolResult.failure(
                "ZIP import failed: ${error.message ?: "unknown error"}. " +
                "The partial import was cleaned up where possible."
            )
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(path)
        ) {
            Files.list(path).use { children ->
                children.forEach { child -> deleteTree(child) }
            }
        }
        Files.deleteIfExists(path)
    }
}
