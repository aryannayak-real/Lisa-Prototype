package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class WorkspaceReadToolTest {
    @Test
    fun readsTextFileInsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-read-test")
        try {
            root.resolve("Main.kt").writeText("fun main() = println(\"Lisa\")")

            val result = WorkspaceReadTool(root).execute(mapOf("path" to "Main.kt"))

            assertTrue(result.success)
            assertTrue(result.output.contains("fun main() = println(\"Lisa\")"))
            assertTrue(result.output.contains("BEGIN FILE"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-read-test")
        try {
            val result = WorkspaceReadTool(root).execute(mapOf("path" to "../outside.txt"))
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("outside"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMissingFiles() = runBlocking {
        val root = Files.createTempDirectory("lisa-read-test")
        try {
            val result = WorkspaceReadTool(root).execute(mapOf("path" to "missing.txt"))
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("File not found"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsFilesOverConfiguredLimit() = runBlocking {
        val root = Files.createTempDirectory("lisa-read-test")
        try {
            root.resolve("large.txt").writeText("1234567890")

            val result = WorkspaceReadTool(root, maxBytes = 5)
                .execute(mapOf("path" to "large.txt"))

            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("too large"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun requiresAPath() = runBlocking {
        val root = Files.createTempDirectory("lisa-read-test")
        try {
            val result = WorkspaceReadTool(root).execute(emptyMap())
            assertFalse(result.success)
            assertEquals("A relative file path is required.", result.error)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
