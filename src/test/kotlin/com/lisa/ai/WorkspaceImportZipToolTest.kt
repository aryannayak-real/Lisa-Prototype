package com.lisa.ai

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceImportZipToolTest {

    private fun createZip(path: java.nio.file.Path, entries: List<Pair<String, String>>) {
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test
    fun importsProjectZipIntoNewFolder() = runBlocking {
        val root = Files.createTempDirectory("lisa-import-root")
        val zip = Files.createTempFile("lisa-project", ".zip")
        try {
            createZip(zip, listOf(
                "README.md" to "Example project",
                "src/Main.kt" to "fun main() {}"
            ))

            val result = WorkspaceImportZipTool(root).execute(mapOf(
                "zipPath" to zip.toString(),
                "destination" to "ExampleProject"
            ))

            assertTrue(result.success, result.error ?: result.output)
            assertEquals("Example project", Files.readString(root.resolve("ExampleProject/README.md")))
            assertEquals("fun main() {}", Files.readString(root.resolve("ExampleProject/src/Main.kt")))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(zip)
        }
    }

    @Test
    fun rejectsZipPathTraversalAndCleansPartialImport() = runBlocking {
        val root = Files.createTempDirectory("lisa-import-root")
        val zip = Files.createTempFile("lisa-traversal", ".zip")
        try {
            createZip(zip, listOf(
                "safe.txt" to "temporary",
                "../outside.txt" to "must not escape"
            ))

            val result = WorkspaceImportZipTool(root).execute(mapOf(
                "zipPath" to zip.toString(),
                "destination" to "UnsafeProject"
            ))

            assertFalse(result.success)
            assertFalse(Files.exists(root.parent.resolve("outside.txt")))
            assertFalse(Files.exists(root.resolve("UnsafeProject")))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(zip)
        }
    }

    @Test
    fun refusesToOverwriteExistingDestination() = runBlocking {
        val root = Files.createTempDirectory("lisa-import-root")
        val zip = Files.createTempFile("lisa-project", ".zip")
        try {
            val destination = Files.createDirectory(root.resolve("ExistingProject"))
            Files.writeString(destination.resolve("keep.txt"), "preserve me")
            createZip(zip, listOf("keep.txt" to "replace me"))

            val result = WorkspaceImportZipTool(root).execute(mapOf(
                "zipPath" to zip.toString(),
                "destination" to "ExistingProject"
            ))

            assertFalse(result.success)
            assertEquals("preserve me", Files.readString(destination.resolve("keep.txt")))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(zip)
        }
    }

    @Test
    fun cleansUpWhenExtractionFails() = runBlocking {
        val root = Files.createTempDirectory("lisa-import-root")
        val zip = Files.createTempFile("lisa-large-entry", ".zip")
        try {
            createZip(zip, listOf(
                "first.txt" to "created first",
                "large.txt" to "this entry exceeds the test limit"
            ))

            val result = WorkspaceImportZipTool(
                root,
                maxEntries = 10,
                maxEntryBytes = 8,
                maxTotalBytes = 32
            ).execute(mapOf(
                "zipPath" to zip.toString(),
                "destination" to "PartialProject"
            ))

            assertFalse(result.success)
            assertFalse(Files.exists(root.resolve("PartialProject")))
        } finally {
            root.toFile().deleteRecursively()
            Files.deleteIfExists(zip)
        }
    }
}
