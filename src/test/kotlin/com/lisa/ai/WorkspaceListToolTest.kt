package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class WorkspaceListToolTest {
    @Test
    fun listsFilesAndDirectoriesInsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-workspace-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("README.md").writeText("hello")

            val result = WorkspaceListTool(root).execute(mapOf("path" to "."))

            assertTrue(result.success)
            assertTrue(result.output.contains("[DIR] src"))
            assertTrue(result.output.contains("[FILE] README.md"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-workspace-test")
        try {
            val result = WorkspaceListTool(root).execute(mapOf("path" to "../outside"))
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("outside"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsFilePathsInsteadOfDirectories() = runBlocking {
        val root = Files.createTempDirectory("lisa-workspace-test")
        try {
            root.resolve("file.txt").writeText("hello")
            val result = WorkspaceListTool(root).execute(mapOf("path" to "file.txt"))
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
