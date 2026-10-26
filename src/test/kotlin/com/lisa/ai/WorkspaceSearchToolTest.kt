package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class WorkspaceSearchToolTest {
    @Test
    fun findsTextInWorkspaceFiles() = runBlocking {
        val root = Files.createTempDirectory("lisa-search-test")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/Main.kt").writeText("class LisaAssistant")
            root.resolve("README.md").writeText("hello world")

            val result = WorkspaceSearchTool(root)
                .execute(mapOf("query" to "lisaassistant"))

            assertTrue(result.success)
            assertTrue(result.output.contains("src/Main.kt:1"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-search-test")
        try {
            val result = WorkspaceSearchTool(root)
                .execute(mapOf("query" to "hello", "path" to "../outside"))
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("outside"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun requiresQuery() = runBlocking {
        val root = Files.createTempDirectory("lisa-search-test")
        try {
            val result = WorkspaceSearchTool(root).execute(emptyMap())
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("query"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun skipsGeneratedDirectories() = runBlocking {
        val root = Files.createTempDirectory("lisa-search-test")
        try {
            root.resolve("build").createDirectories()
            root.resolve("build/Generated.kt").writeText("SPECIAL_MARKER")
            root.resolve("Main.kt").writeText("ordinary code")

            val result = WorkspaceSearchTool(root)
                .execute(mapOf("query" to "SPECIAL_MARKER"))

            assertTrue(result.success)
            assertTrue(result.output.contains("No matches found"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
