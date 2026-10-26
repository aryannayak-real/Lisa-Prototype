package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class WorkspaceWriteToolTest {
    @Test
    fun createsNewFile() = runBlocking {
        val root = Files.createTempDirectory("lisa-write-test")
        try {
            val result = WorkspaceWriteTool(root).execute(
                mapOf("path" to "hello.txt", "content" to "Hello Lisa")
            )
            assertTrue(result.success)
            assertEquals("Hello Lisa", root.resolve("hello.txt").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun refusesToOverwriteExistingFile() = runBlocking {
        val root = Files.createTempDirectory("lisa-write-test")
        try {
            root.resolve("hello.txt").toFile().writeText("Original")
            val result = WorkspaceWriteTool(root).execute(
                mapOf("path" to "hello.txt", "content" to "Replacement")
            )
            assertFalse(result.success)
            assertEquals("Original", root.resolve("hello.txt").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-write-test")
        try {
            val result = WorkspaceWriteTool(root).execute(
                mapOf("path" to "../outside.txt", "content" to "No")
            )
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
