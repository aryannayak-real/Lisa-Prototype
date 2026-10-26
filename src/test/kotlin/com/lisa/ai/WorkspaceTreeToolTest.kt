package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceTreeToolTest {
    @Test
    fun listsNestedFilesAndDirectories() = runBlocking {
        val root = Files.createTempDirectory("lisa-tree-test")
        try {
            Files.createDirectories(root.resolve("src/main"))
            Files.writeString(root.resolve("src/main/App.kt"), "class App")
            Files.writeString(root.resolve("README.md"), "Lisa")

            val result = WorkspaceTreeTool(root).execute(mapOf("path" to "."))
            assertTrue(result.success)
            assertTrue(result.output.contains("src/"))
            assertTrue(result.output.contains("main/"))
            assertTrue(result.output.contains("App.kt"))
            assertTrue(result.output.contains("README.md"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun skipsGeneratedDirectories() = runBlocking {
        val root = Files.createTempDirectory("lisa-tree-test")
        try {
            Files.createDirectories(root.resolve("build/classes"))
            Files.writeString(root.resolve("build/classes/Generated.class"), "ignored")
            Files.writeString(root.resolve("Main.kt"), "class Main")

            val result = WorkspaceTreeTool(root).execute(mapOf("path" to "."))
            assertTrue(result.success)
            assertTrue(result.output.contains("Main.kt"))
            assertFalse(result.output.contains("Generated.class"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-tree-test")
        try {
            val result = WorkspaceTreeTool(root).execute(mapOf("path" to "../outside"))
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsSymbolicLinkDirectoryWhenSupported() = runBlocking {
        val root = Files.createTempDirectory("lisa-tree-test")
        val outside = Files.createTempDirectory("lisa-tree-outside")
        try {
            Files.writeString(outside.resolve("secret.txt"), "private")
            try {
                Files.createSymbolicLink(root.resolve("linked"), outside)
            } catch (_: Exception) {
                return@runBlocking
            }

            val result = WorkspaceTreeTool(root).execute(mapOf("path" to "linked"))
            assertFalse(result.success)
            assertFalse(result.output.contains("private"))
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
