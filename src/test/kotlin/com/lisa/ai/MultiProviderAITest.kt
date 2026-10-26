package com.lisa.ai

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MultiProviderAITest {

    private class FakeProvider(
        override val name: String,
        private val answer: String,
        private val shouldFail: () -> Boolean = { false },
    ) : AIProvider {
        override val model = "test-model"
        var calls = 0

        override suspend fun generate(
            messages: List<ChatMessage>,
            systemPrompt: String,
        ): AIResponse {
            calls++
            if (shouldFail()) throw AIProviderException("Temporary failure")
            return AIResponse(answer, name, model)
        }
    }

    @Test
    fun fallsBackToNextProviderWhenFirstFails() = runBlocking {
        val first = FakeProvider("First", "unused") { true }
        val second = FakeProvider("Second", "working")

        val router = MultiProviderAI(listOf(first, second))
        val response = router.generate(
            listOf(ChatMessage("user", "Hello")),
            "Test",
        )

        assertEquals("working", response.text)
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun reportsFailureWhenAllProvidersFail() = runBlocking {
        val first = FakeProvider("First", "unused") { true }
        val second = FakeProvider("Second", "unused") { true }

        val router = MultiProviderAI(
            listOf(first, second),
            cooldownMillis = 60_000,
        )

        assertFailsWith<AIProviderException> {
            router.generate(listOf(ChatMessage("user", "Hello")), "Test")
        }
        assertEquals(1, first.calls)
        assertEquals(1, second.calls)
    }

    @Test
    fun retriesProviderAfterCooldownExpires() = runBlocking {
        var now = 1_000L
        var firstShouldFail = true
        var secondShouldFail = false

        val first = FakeProvider("First", "recovered") { firstShouldFail }
        val second = FakeProvider("Second", "backup") { secondShouldFail }

        val router = MultiProviderAI(
            providers = listOf(first, second),
            cooldownMillis = 100,
            clockMillis = { now },
        )

        val initial = router.generate(
            listOf(ChatMessage("user", "Hello")),
            "Test",
        )
        assertEquals("backup", initial.text)

        firstShouldFail = false
        secondShouldFail = true
        now += 101

        val recovered = router.generate(
            listOf(ChatMessage("user", "Hello again")),
            "Test",
        )

        assertEquals("recovered", recovered.text)
        assertEquals(2, first.calls)
    }

    @Test
    fun rejectsAnEmptyProviderList() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            MultiProviderAI(emptyList())
        }
    }
}
