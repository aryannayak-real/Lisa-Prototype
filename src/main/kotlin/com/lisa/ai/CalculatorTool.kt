package com.lisa.ai

/**
 * Safe arithmetic tool.
 * Supports numbers, parentheses, +, -, *, /, and %.
 * Does not evaluate Kotlin, Java, or shell code.
 */
class CalculatorTool : AgentTool {
    override val name = "calculator"

    override val parametersSchema =
        kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put(
                "properties",
                kotlinx.serialization.json.buildJsonObject {
                    put(
                        "expression",
                        kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                            put(
                                "description",
                                kotlinx.serialization.json.JsonPrimitive(
                                    "Arithmetic expression using numbers, parentheses, +, -, *, /, and %."
                                )
                            )
                        }
                    )
                }
            )
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("expression"))
                }
            )
            put("additionalProperties", kotlinx.serialization.json.JsonPrimitive(false))
        }

    override val description =
        "Evaluate basic arithmetic expressions using numbers, parentheses, +, -, *, /, and %."

    override suspend fun execute(
        arguments: Map<String, String>
    ): ToolResult {
        val expression = arguments["expression"]?.trim()
            ?: return ToolResult.failure("Missing 'expression' argument.")

        if (expression.isEmpty()) {
            return ToolResult.failure("Expression cannot be empty.")
        }

        if (expression.length > 200) {
            return ToolResult.failure("Expression is too long.")
        }

        return try {
            val result = ArithmeticParser(expression).parse()
            if (!result.isFinite()) {
                ToolResult.failure("Result is not a finite number.")
            } else {
                ToolResult.success(result.toString())
            }
        } catch (error: IllegalArgumentException) {
            ToolResult.failure(error.message ?: "Invalid arithmetic expression.")
        }
    }
}

private class ArithmeticParser(private val input: String) {
    private var position = 0

    fun parse(): Double {
        val value = expression()
        skipSpaces()
        require(position == input.length) { "Unexpected input at position $position." }
        return value
    }

    private fun expression(): Double {
        var value = term()
        while (true) {
            skipSpaces()
            value = when {
                consume('+') -> value + term()
                consume('-') -> value - term()
                else -> return value
            }
        }
    }

    private fun term(): Double {
        var value = factor()
        while (true) {
            skipSpaces()
            value = when {
                consume('*') -> value * factor()
                consume('/') -> {
                    val divisor = factor()
                    require(divisor != 0.0) { "Division by zero." }
                    value / divisor
                }
                consume('%') -> {
                    val divisor = factor()
                    require(divisor != 0.0) { "Remainder by zero." }
                    value % divisor
                }
                else -> return value
            }
        }
    }

    private fun factor(): Double {
        skipSpaces()

        if (consume('+')) return factor()
        if (consume('-')) return -factor()

        if (consume('(')) {
            val value = expression()
            skipSpaces()
            require(consume(')')) { "Missing closing parenthesis." }
            return value
        }

        val start = position
        var dots = 0
        while (position < input.length) {
            val c = input[position]
            if (c.isDigit()) {
                position++
            } else if (c == '.') {
                dots++
                require(dots <= 1) { "Invalid number." }
                position++
            } else {
                break
            }
        }

        require(position > start) { "Expected a number at position $position." }
        return input.substring(start, position).toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid number.")
    }

    private fun consume(char: Char): Boolean {
        if (position < input.length && input[position] == char) {
            position++
            return true
        }
        return false
    }

    private fun skipSpaces() {
        while (position < input.length && input[position].isWhitespace()) {
            position++
        }
    }
}
