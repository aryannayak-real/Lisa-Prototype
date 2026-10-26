package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceInspectToolTest {
    @Test
    fun identifiesProjectFilesTypesAndEntryPoints() = runBlocking {
        val root = Files.createTempDirectory("lisa-inspect-test")
        try {
            Files.createDirectories(root.resolve("src"))
            Files.writeString(root.resolve("README.md"), "# Demo")
            Files.writeString(root.resolve("build.gradle.kts"), "plugins {}")
            Files.writeString(root.resolve("src/Main.kt"), "fun main() {}")

            val result = WorkspaceInspectTool(root).execute(mapOf("path" to "."))

            assertTrue(result.success)
            assertTrue(result.output.contains("Kotlin/JVM or Kotlin project"))
            assertTrue(result.output.contains("Gradle project"), result.output)
            assertTrue(result.output.contains("README.md"))
            assertTrue(result.output.contains("build.gradle.kts"))
            assertTrue(result.output.contains("src/Main.kt"))
            assertTrue(result.output.contains("did not modify any files"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun skipsGeneratedAndHiddenDirectories() = runBlocking {
        val root = Files.createTempDirectory("lisa-inspect-test")
        try {
            Files.createDirectories(root.resolve("build"))
            Files.createDirectories(root.resolve(".git"))
            Files.writeString(root.resolve("build/Generated.kt"), "class Generated")
            Files.writeString(root.resolve(".git/config"), "private")
            Files.writeString(root.resolve("Main.kt"), "fun main() {}")

            val result = WorkspaceInspectTool(root).execute(mapOf("path" to "."))

            assertTrue(result.success)
            assertTrue(result.output.contains("Main.kt"))
            assertFalse(result.output.contains("Generated.kt"))
            assertFalse(result.output.contains(".git/config"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-inspect-test")
        try {
            val result = WorkspaceInspectTool(root).execute(mapOf("path" to "../outside"))
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkDirectoryWhenSupported() = runBlocking {
        val root = Files.createTempDirectory("lisa-inspect-test")
        val outside = Files.createTempDirectory("lisa-inspect-outside")
        try {
            Files.writeString(outside.resolve("secret.txt"), "private")
            try {
                Files.createSymbolicLink(root.resolve("linked"), outside)
            } catch (_: Exception) {
                return@runBlocking
            }

            val result = WorkspaceInspectTool(root).execute(mapOf("path" to "linked"))
            assertFalse(result.success)
            assertFalse(result.output.contains("private"))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
