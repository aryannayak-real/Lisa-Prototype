package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test
    fun discoversAndExecutesCalculator() = runBlocking {
        val registry = ToolRegistry(listOf(CalculatorTool()))

        assertEquals(1, registry.listTools().size)
        assertTrue(registry.describeTools().contains("calculator"))

        val result = registry.execute(
            "calculator",
            mapOf("expression" to "(10 + 2) * 3")
        )

        assertTrue(result.success)
        assertEquals("36.0", result.output)
    }

    @Test
    fun reportsUnknownTools() = runBlocking {
        val registry = ToolRegistry(listOf(CalculatorTool()))

        val result = registry.execute("unknown_tool", emptyMap())

        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("Unknown tool"))
    }

    @Test
    fun rejectsDuplicateToolNames() {
        val registry = ToolRegistry(listOf(CalculatorTool()))

        val error = runCatching {
            registry.register(CalculatorTool())
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }
}
