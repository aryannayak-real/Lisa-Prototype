package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class WorkspaceEditToolTest {
    @Test
    fun replacesExactTextOnce() = runBlocking {
        val root = Files.createTempDirectory("lisa-edit-test")
        try {
            root.resolve("hello.txt").writeText("Hello Lisa")
            val result = WorkspaceEditTool(root).execute(
                mapOf(
                    "path" to "hello.txt",
                    "old_text" to "Hello",
                    "new_text" to "Welcome"
                )
            )
            assertTrue(result.success)
            assertEquals("Welcome Lisa", root.resolve("hello.txt").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsTextThatIsNotFoundWithoutChangingFile() = runBlocking {
        val root = Files.createTempDirectory("lisa-edit-test")
        try {
            root.resolve("hello.txt").writeText("Hello Lisa")
            val result = WorkspaceEditTool(root).execute(
                mapOf(
                    "path" to "hello.txt",
                    "old_text" to "Missing",
                    "new_text" to "Welcome"
                )
            )
            assertFalse(result.success)
            assertEquals("Hello Lisa", root.resolve("hello.txt").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsRepeatedTextWithoutChangingFile() = runBlocking {
        val root = Files.createTempDirectory("lisa-edit-test")
        try {
            root.resolve("hello.txt").writeText("Lisa and Lisa")
            val result = WorkspaceEditTool(root).execute(
                mapOf(
                    "path" to "hello.txt",
                    "old_text" to "Lisa",
                    "new_text" to "LISA"
                )
            )
            assertFalse(result.success)
            assertEquals("Lisa and Lisa", root.resolve("hello.txt").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsEditWhenFileChangedSincePreview() = runBlocking {
        val root = Files.createTempDirectory("lisa-edit-test")
        try {
            root.resolve("hello.txt").writeText("Hello Lisa changed")
            val result = WorkspaceEditTool(root).execute(
                mapOf(
                    "path" to "hello.txt",
                    "old_text" to "Hello",
                    "new_text" to "Welcome",
                    "expected_original" to "Hello Lisa"
                )
            )
            assertFalse(result.success)
            assertTrue(result.error.orEmpty().contains("File changed since preview"))
            assertEquals("Hello Lisa changed", root.resolve("hello.txt").readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-edit-test")
        try {
            val result = WorkspaceEditTool(root).execute(
                mapOf(
                    "path" to "../outside.txt",
                    "old_text" to "Old",
                    "new_text" to "New"
                )
            )
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
