package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceCommandToolTest {
    @Test
    fun runsPwdInsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-command-test")
        try {
            val result = WorkspaceCommandTool(root).execute(
                mapOf("command" to "pwd", "args" to "[]")
            )
            assertTrue(result.success)
            assertTrue(result.output.contains(root.toAbsolutePath().normalize().toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsShellAndUnknownCommands() = runBlocking {
        val root = Files.createTempDirectory("lisa-command-test")
        try {
            for (command in listOf("sh", "find", "gradle", "rm")) {
                val result = WorkspaceCommandTool(root).execute(
                    mapOf("command" to command, "args" to "[]")
                )
                assertFalse(result.success, "Should reject $command")
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsPathsOutsideWorkspace() = runBlocking {
        val root = Files.createTempDirectory("lisa-command-test")
        try {
            val result = WorkspaceCommandTool(root).execute(
                mapOf("command" to "cat", "args" to "[\"../outside.txt\"]")
            )
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun grepFindsLiteralTextInWorkspaceFile() = runBlocking {
        val root = Files.createTempDirectory("lisa-command-test")
        try {
            Files.writeString(root.resolve("sample.txt"), "hello Lisa\n")
            val result = WorkspaceCommandTool(root).execute(
                mapOf("command" to "grep", "args" to "[\"hello\", \"sample.txt\"]")
            )
            assertTrue(result.success)
            assertTrue(result.output.contains("hello Lisa"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsMalformedArguments() = runBlocking {
        val root = Files.createTempDirectory("lisa-command-test")
        try {
            val result = WorkspaceCommandTool(root).execute(
                mapOf("command" to "ls", "args" to "not-json")
            )
            assertFalse(result.success)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
