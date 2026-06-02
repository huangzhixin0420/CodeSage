package com.codesage.analysis.insights

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T5.3 修复验证测试：圈复杂度近似
 */
class CyclomaticComplexityTest {

    @Test
    fun `simple function has complexity 1`() {
        val source = """
            fun add(a: Int, b: Int): Int {
                return a + b
            }
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "kotlin")
        assertEquals(1, result.complexity)
        assertEquals(0, result.branchCount)
        assertEquals(CyclomaticComplexity.RiskLevel.LOW, result.riskLevel())
    }

    @Test
    fun `function with single if has complexity 2`() {
        val source = """
            fun max(a: Int, b: Int): Int {
                if (a > b) {
                    return a
                } else {
                    return b
                }
            }
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "kotlin")
        assertEquals(2, result.complexity)
        assertEquals(1, result.branchCount)
    }

    @Test
    fun `function with when has higher complexity`() {
        val source = """
            fun classify(n: Int): String = when {
                n < 0 -> "negative"
                n == 0 -> "zero"
                n < 10 -> "small"
                n < 100 -> "medium"
                else -> "large"
            }
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "kotlin")
        // when (1) + 分支条件 — 启发式近似 ≥ 2 (when 至少 1 个分支)
        assertTrue(result.complexity >= 2, "expected complexity >= 2, got ${result.complexity}")
    }

    @Test
    fun `function with for while catch counts all branches`() {
        val source = """
            fun process(items: List<Int>) {
                try {
                    for (item in items) {
                        if (item > 0) {
                            println(item)
                        }
                    }
                    var i = 0
                    while (i < 10) {
                        i++
                    }
                } catch (e: Exception) {
                    println(e)
                }
            }
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "kotlin")
        // for (1) + if (1) + while (1) + catch (1) = 4 → complexity 5
        assertEquals(5, result.complexity, "expected complexity 5, got ${result.complexity}")
    }

    @Test
    fun `risk level escalates with complexity`() {
        val low = CyclomaticComplexity.compute("fun a() = 1", "kotlin")
        val moderate = CyclomaticComplexity.compute(buildSource(8), "kotlin")
        val high = CyclomaticComplexity.compute(buildSource(15), "kotlin")
        val veryHigh = CyclomaticComplexity.compute(buildSource(25), "kotlin")

        assertEquals(CyclomaticComplexity.RiskLevel.LOW, low.riskLevel())
        assertEquals(CyclomaticComplexity.RiskLevel.MODERATE, moderate.riskLevel())
        assertEquals(CyclomaticComplexity.RiskLevel.HIGH, high.riskLevel())
        assertEquals(CyclomaticComplexity.RiskLevel.VERY_HIGH, veryHigh.riskLevel())
    }

    @Test
    fun `comments and strings do not count as branches`() {
        // "if" 在字符串里
        val source = """
            fun greet() {
                val msg = "if you see this, it's not a branch"
                /* if (this is in a comment) */
                println(msg)
            }
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "kotlin")
        // 字符串和注释里的 if 不算分支
        assertEquals(1, result.complexity, "comments/strings should not count")
    }

    @Test
    fun `language-specific keywords work for python`() {
        val source = """
            def classify(n):
                if n < 0:
                    return "negative"
                elif n == 0:
                    return "zero"
                else:
                    return "positive"
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "python")
        // if (1) + elif (1) = 2 → complexity 3
        assertEquals(3, result.complexity)
    }

    @Test
    fun `lineCount reflects effective lines`() {
        val source = """
            fun foo() {
                // comment only
                val x = 1
                val y = 2
                return x + y
            }
        """.trimIndent()
        val result = CyclomaticComplexity.compute(source, "kotlin")
        // 至少 3 行有效代码
        assertTrue(result.lineCount >= 3, "expected at least 3 effective lines, got ${result.lineCount}")
    }

    private fun buildSource(branchCount: Int): String {
        // 生成 N 个 if 的代码
        val sb = StringBuilder("fun complex() {\n")
        for (i in 0 until branchCount) {
            sb.append("    if (x > $i) { y = $i }\n")
        }
        sb.append("}\n")
        return sb.toString()
    }
}
