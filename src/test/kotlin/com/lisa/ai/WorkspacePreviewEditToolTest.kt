package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspacePreviewEditToolTest {
    @Test
    fun previewsReplacementWithoutChangingOriginal() = runBlocking {
        val root = Files.createTempDirectory("lisa-preview-test")
        try {
            val file = root.resolve("sample.txt")
            file.writeText("Hello old world")

            val result = WorkspacePreviewEditTool(root).execute(
                mapOf(
                    "path" to "sample.txt",
                    "old_text" to "old",
                    "new_text" to "new"
                )
            )

            assertTrue(result.success)
            assertTrue(result.output.contains("PREVIEW ONLY"))
            assertTrue(result.output.contains("--- a/sample.txt"))
            assertTrue(result.output.contains("+++ b/sample.txt"))
            assertTrue(result.output.contains("-old"))
            assertTrue(result.output.contains("+new"))
            assertTrue(result.output.contains("No changes have been written."))
            assertTrue(Files.readString(file) == "Hello old world")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsAmbiguousMatch() = runBlocking {
        val root = Files.createTempDirectory("lisa-preview-test")
        try {
            root.resolve("sample.txt").writeText("same same")

            val result = WorkspacePreviewEditTool(root).execute(
                mapOf(
                    "path" to "sample.txt",
                    "old_text" to "same",
                    "new_text" to "new"
                )
            )

            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("more than once"))
            assertTrue(Files.readString(root.resolve("sample.txt")) == "same same")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-preview-test")
        try {
            val result = WorkspacePreviewEditTool(root).execute(
                mapOf(
                    "path" to "../outside.txt",
                    "old_text" to "old",
                    "new_text" to "new"
                )
            )

            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("outside"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
