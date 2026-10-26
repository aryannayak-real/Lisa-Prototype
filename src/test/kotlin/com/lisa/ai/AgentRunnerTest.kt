package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRunnerTest {

    private class CalculatorRequestModel : AgentModel {
        override val name = "Offline Agent Test"

        private var requestedTool = false

        override suspend fun nextTurn(
            messages: List<ChatMessage>,
            systemPrompt: String,
            availableTools: List<AgentTool>
        ): AgentTurn {
            if (!requestedTool) {
                requestedTool = true
                assertTrue(availableTools.any { it.name == "calculator" })
                return AgentTurn.ToolCall(
                    id = "call-1",
                    name = "calculator",
                    arguments = mapOf("expression" to "12 * (3 + 2)")
                )
            }

            val toolResult = messages.last()
            assertEquals("tool", toolResult.role)
            assertEquals("call-1", toolResult.toolCallId)
            assertTrue(toolResult.content.orEmpty().contains("60.0"))
            return AgentTurn.FinalAnswer("The answer is 60.")
        }
    }

    @Test
    fun agentCallsCalculatorAndUsesItsResult() = runBlocking {
        val runner = AgentRunner(
            model = CalculatorRequestModel(),
            tools = ToolRegistry(listOf(CalculatorTool()))
        )

        val result = runner.run("Calculate 12 times the sum of 3 and 2.")

        assertTrue(result.completed)
        assertEquals("The answer is 60.", result.answer)
        assertEquals(1, result.toolCalls)
        assertEquals(2, result.turns)
    }

    @Test
    fun agentStopsAtPerRunTurnLimit() = runBlocking {
        val repeatingModel = object : AgentModel {
            override val name = "Repeating Test Model"

            override suspend fun nextTurn(
                messages: List<ChatMessage>,
                systemPrompt: String,
                availableTools: List<AgentTool>
            ) = AgentTurn.ToolCall(
                id = "repeat",
                name = "calculator",
                arguments = mapOf("expression" to "1 + 1")
            )
        }

        val result = AgentRunner(
            model = repeatingModel,
            tools = ToolRegistry(listOf(CalculatorTool())),
            maxTurns = 2
        ).run("Test the safety limit.")

        assertFalse(result.completed)
        assertEquals(2, result.turns)
        assertEquals(2, result.toolCalls)
    }


    @Test
    fun agentExecutesMultipleToolCallsAndReturnsBothResults() = runBlocking {
        var modelTurn = 0

        val batchModel = object : AgentModel {
            override val name = "Batch Tool Test Model"

            override suspend fun nextTurn(
                messages: List<ChatMessage>,
                systemPrompt: String,
                availableTools: List<AgentTool>
            ): AgentTurn {
                modelTurn++
                if (modelTurn == 1) {
                    return AgentTurn.ToolCalls(
                        listOf(
                            AgentTurn.ToolCall(
                                id = "call-a",
                                name = "calculator",
                                arguments = mapOf("expression" to "2 + 3")
                            ),
                            AgentTurn.ToolCall(
                                id = "call-b",
                                name = "calculator",
                                arguments = mapOf("expression" to "7 * 6")
                            )
                        )
                    )
                }

                val toolMessages = messages.filter { it.role == "tool" }
                assertEquals(listOf("call-a", "call-b"), toolMessages.map { it.toolCallId })
                assertTrue(toolMessages[0].content.orEmpty().contains("5.0"))
                assertTrue(toolMessages[1].content.orEmpty().contains("42.0"))
                return AgentTurn.FinalAnswer("Both calculations are complete.")
            }
        }

        val result = AgentRunner(
            model = batchModel,
            tools = ToolRegistry(listOf(CalculatorTool()))
        ).run("Calculate 2 + 3 and 7 * 6.")

        assertTrue(result.completed)
        assertEquals("Both calculations are complete.", result.answer)
        assertEquals(2, result.toolCalls)
        assertEquals(2, result.turns)
    }

}
