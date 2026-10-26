package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalculatorToolTest {
    private val calculator = CalculatorTool()

    @Test
    fun evaluatesArithmeticCorrectly() = runBlocking {
        assertEquals("14.0", calculator.execute(
            mapOf("expression" to "2 + 3 * 4")
        ).output)

        assertEquals("20.0", calculator.execute(
            mapOf("expression" to "(2 + 3) * 4")
        ).output)

        assertEquals("2.0", calculator.execute(
            mapOf("expression" to "10 / 5")
        ).output)
    }

    @Test
    fun rejectsDivisionByZero() = runBlocking {
        val result = calculator.execute(mapOf("expression" to "5 / 0"))
        assertFalse(result.success)
        assertTrue(result.error.orEmpty().contains("zero"))
    }

    @Test
    fun rejectsCodeAndInvalidInput() = runBlocking {
        assertFalse(calculator.execute(
            mapOf("expression" to "abc")
        ).success)

        assertFalse(calculator.execute(
            mapOf("expression" to "1 + 2; delete")
        ).success)
    }
}
