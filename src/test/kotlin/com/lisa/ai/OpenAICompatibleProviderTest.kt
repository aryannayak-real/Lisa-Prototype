package com.lisa.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAICompatibleProviderTest {
    @Test
    fun parsesToolCallFromMockApiResponse() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = """
                    {
                      "id": "chatcmpl-test",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [{
                            "id": "call-123",
                            "type": "function",
                            "function": {
                              "name": "calculator",
                              "arguments": "{\"expression\":\"12 * (3 + 2)\"}"
                            }
                          }]
                        },
                        "finish_reason": "tool_calls"
                      }],
                      "model": "test-model",
                      "usage": {
                        "prompt_tokens": 5,
                        "completion_tokens": 7
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val provider = OpenAICompatibleProvider(
            name = "Mock",
            model = "test-model",
            baseUrl = "https://mock.example/v1",
            engine = mockEngine
        )

        try {
            val result = provider.nextTurn(
                messages = listOf(ChatMessage("user", "Calculate 12 * (3 + 2)")),
                systemPrompt = "Use tools when appropriate.",
                availableTools = listOf(CalculatorTool())
            )

            val call = assertIs<AgentTurn.ToolCall>(result)
            assertEquals("call-123", call.id)
            assertEquals("calculator", call.name)
            assertEquals("12 * (3 + 2)", call.arguments["expression"])
        } finally {
            provider.close()
        }
    }


    @Test
    fun reportsHttpRateLimitFailure() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = """{"error":{"message":"Rate limit exceeded"}}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val provider = OpenAICompatibleProvider(
            name = "Mock",
            model = "test-model",
            baseUrl = "https://mock.example/v1",
            engine = mockEngine
        )

        try {
            val error = kotlin.test.assertFailsWith<AIProviderException> {
                provider.generate(
                    messages = listOf(ChatMessage("user", "Hello")),
                    systemPrompt = "You are Lisa."
                )
            }
            kotlin.test.assertTrue(error.message.orEmpty().contains("429") ||
                error.message.orEmpty().contains("Too Many Requests"))
        } finally {
            provider.close()
        }
    }



    @Test
    fun parsesMultipleToolCallsFromMockApiResponse() = runBlocking {
        val mockEngine = MockEngine {
            respond(
                content = """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [
                            {
                              "id": "call-a",
                              "type": "function",
                              "function": {
                                "name": "calculator",
                                "arguments": "{\"expression\":\"2 + 3\"}"
                              }
                            },
                            {
                              "id": "call-b",
                              "type": "function",
                              "function": {
                                "name": "calculator",
                                "arguments": "{\"expression\":\"7 * 6\"}"
                              }
                            }
                          ]
                        },
                        "finish_reason": "tool_calls"
                      }],
                      "model": "test-model"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val provider = OpenAICompatibleProvider(
            name = "Mock",
            model = "test-model",
            baseUrl = "https://mock.example/v1",
            engine = mockEngine
        )

        try {
            val result = provider.nextTurn(
                messages = listOf(ChatMessage("user", "Calculate both expressions")),
                systemPrompt = "Use the calculator.",
                availableTools = listOf(CalculatorTool())
            )

            val batch = assertIs<AgentTurn.ToolCalls>(result)
            assertEquals(2, batch.calls.size)
            assertEquals(listOf("call-a", "call-b"), batch.calls.map { it.id })
            assertEquals(listOf("2 + 3", "7 * 6"), batch.calls.map { it.arguments["expression"] })
        } finally {
            provider.close()
        }
    }

}
