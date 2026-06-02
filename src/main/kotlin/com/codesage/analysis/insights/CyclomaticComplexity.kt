package com.codesage.analysis.insights

/**
 * T5.3 修复：圈复杂度近似计算
 *
 * **算法**：节点数 + 1（控制流图中分支点 + 1）
 *
 * 适用于：Kotlin / Java / 通用类 C 风格的语言。
 * 不依赖外部库，纯字符串扫描。
 *
 * **为什么不引入外部 linter**：
 * 1. 保持零新增依赖
 * 2. 启发式近似已能满足"识别需要拆分的方法"
 * 3. 真正精确需要控制流图（CFG），开销大
 *
 * **返回的指标**：
 * - `complexity`: 圈复杂度（>= 1）
 * - `branchCount`: 分支数（if/when/for/while/case/catch）
 * - `lineCount`: 有效代码行（非空非注释）
 */
object CyclomaticComplexity {

    /**
     * 计算圈复杂度
     *
     * @param source 方法体（可以是完整方法、lambda、函数）
     * @param language 语言提示（影响关键词识别）
     * @return [ComplexityResult]
     */
    fun compute(source: String, language: String = "kotlin"): ComplexityResult {
        val branchKeywords = when (language.lowercase()) {
            "kotlin" -> KOTLIN_BRANCH_KEYWORDS
            "java", "scala", "groovy" -> JVM_BRANCH_KEYWORDS
            "javascript", "typescript" -> JS_BRANCH_KEYWORDS
            "python" -> PYTHON_BRANCH_KEYWORDS
            else -> UNIVERSAL_BRANCH_KEYWORDS
        }

        val cleanedSource = stripCommentsAndStrings(source)
        val branchCount = countBranches(cleanedSource, branchKeywords)
        val lineCount = countEffectiveLines(cleanedSource)
        val complexity = 1 + branchCount  // 圈复杂度 = 边 - 节点 + 2 = 分支数 + 1

        return ComplexityResult(
            complexity = complexity,
            branchCount = branchCount,
            lineCount = lineCount,
            language = language
        )
    }

    private fun stripCommentsAndStrings(source: String): String {
        // 简化：去掉行注释、块注释、字符串字面量
        var result = source
        // 去掉块注释
        result = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(result, " ")
        // 去掉行注释（// 和 #）
        result = Regex("//.*?$", RegexOption.MULTILINE).replace(result, " ")
        result = Regex("#.*?$", RegexOption.MULTILINE).replace(result, " ")
        // 去掉字符串字面量（双引号和单引号）
        result = Regex("\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"").replace(result, "\"\"")
        result = Regex("'[^'\\\\]*(?:\\\\.[^'\\\\]*)*'").replace(result, "''")
        return result
    }

    private fun countBranches(source: String, keywords: List<BranchKeyword>): Int {
        var count = 0
        for (kw in keywords) {
            // 用 word boundary 匹配，避免 "select" 匹配 "if"
            val pattern = if (kw.atomic) {
                Regex("\\b${Regex.escape(kw.word)}\\b")
            } else {
                // 非 atomic（如 "&&"）：用简单 contains
                Regex.escape(kw.word).toRegex()
            }
            val matches = pattern.findAll(source).count()
            // 逻辑操作符 && || 在字符串里出现次数可能多于分支数，但作为启发式足够
            count += if (kw.atomic) matches else matches
        }
        return count
    }

    private fun countEffectiveLines(source: String): Int {
        return source.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length > 1 }
            .count()
    }

    /**
     * 圈复杂度 + 元信息
     */
    data class ComplexityResult(
        val complexity: Int,
        val branchCount: Int,
        val lineCount: Int,
        val language: String
    ) {
        /**
         * 风险等级
         */
        fun riskLevel(): RiskLevel = when {
            complexity <= 5 -> RiskLevel.LOW
            complexity <= 10 -> RiskLevel.MODERATE
            complexity <= 20 -> RiskLevel.HIGH
            else -> RiskLevel.VERY_HIGH
        }
    }

    enum class RiskLevel { LOW, MODERATE, HIGH, VERY_HIGH }

    private data class BranchKeyword(val word: String, val atomic: Boolean = true)

    // Kotlin: if, when, for, while, do, catch, &&, ||, ?:
    private val KOTLIN_BRANCH_KEYWORDS = listOf(
        BranchKeyword("if"),
        BranchKeyword("when"),
        BranchKeyword("for"),
        BranchKeyword("while"),
        BranchKeyword("do"),
        BranchKeyword("catch"),
        BranchKeyword("&&", atomic = false),
        BranchKeyword("||", atomic = false)
    )

    // Java/Scala/Groovy: same as Kotlin minus some constructs
    private val JVM_BRANCH_KEYWORDS = listOf(
        BranchKeyword("if"),
        BranchKeyword("switch"),
        BranchKeyword("case"),
        BranchKeyword("for"),
        BranchKeyword("while"),
        BranchKeyword("do"),
        BranchKeyword("catch"),
        BranchKeyword("&&", atomic = false),
        BranchKeyword("||", atomic = false)
    )

    // JavaScript / TypeScript
    private val JS_BRANCH_KEYWORDS = listOf(
        BranchKeyword("if"),
        BranchKeyword("switch"),
        BranchKeyword("case"),
        BranchKeyword("for"),
        BranchKeyword("while"),
        BranchKeyword("do"),
        BranchKeyword("catch"),
        BranchKeyword("&&", atomic = false),
        BranchKeyword("||", atomic = false),
        BranchKeyword("?")
    )

    // Python
    private val PYTHON_BRANCH_KEYWORDS = listOf(
        BranchKeyword("if"),
        BranchKeyword("elif"),
        BranchKeyword("for"),
        BranchKeyword("while"),
        BranchKeyword("except"),
        BranchKeyword("and", atomic = false),
        BranchKeyword("or", atomic = false)
    )

    // 通用 fallback
    private val UNIVERSAL_BRANCH_KEYWORDS = JVM_BRANCH_KEYWORDS
}
