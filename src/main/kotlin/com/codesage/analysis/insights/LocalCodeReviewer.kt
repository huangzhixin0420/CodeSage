package com.codesage.analysis.insights

import com.codesage.shared.utils.Logger

/**
 * T5.4 修复：本地代码审查引擎
 *
 * **目标**：替代 prompt-only 的 `builtin_code_review` Skill。
 *
 * **设计选择**（保持零新增依赖）：
 * 1. 规则以 Kotlin object 形式内置（不引入 YAML loader 复杂度）
 * 2. 每条规则包含 id, severity, regex 模式, 建议
 * 3. 提供 [LocalCodeReviewer.review] 入口：对一段源码运行所有规则
 * 4. 输出 [ReviewFinding] 列表（行号、严重度、规则 id、消息、建议）
 *
 * **规则类别**（来自 TARGETED_OPTIMIZATION_PLAN.md T5.4）：
 * - Security: 硬编码密码、SQL 注入、XSS、命令注入
 * - Performance: N+1 查询、循环 IO、不必要的对象创建
 * - Style: 长方法、长参数列表、命名约定
 * - Correctness: 空指针风险、资源未关闭
 *
 * **不实现的能力**（保持范围聚焦）：
 * - 类型检查（需要 PSI）
 * - 控制流分析（需要 CFG）
 * - 跨文件分析（需要 symbol index）
 */
object LocalCodeReviewer {

    private val logger = Logger.getLogger<LocalCodeReviewer>()

    /**
     * 审查一段源码
     *
     * @param source 源码
     * @param language 语言（"kotlin" / "java" / "javascript" / "python" / ...）
     * @param filePath 源文件路径（用于报告）
     * @return [ReviewFinding] 列表（按行号 + 规则 id 排序）
     */
    fun review(source: String, language: String = "kotlin", filePath: String = "<unknown>"): List<ReviewFinding> {
        val findings = mutableListOf<ReviewFinding>()
        val activeRules = rules.filter { it.appliesTo(language) }

        for (rule in activeRules) {
            try {
                val matches = rule.pattern.findAll(source)
                for (match in matches) {
                    val lineNumber = source.lineNumberAt(match.range.first)
                    val lineContent = source.lineAt(lineNumber)
                    findings.add(
                        ReviewFinding(
                            ruleId = rule.id,
                            severity = rule.severity,
                            category = rule.category,
                            lineNumber = lineNumber,
                            lineContent = lineContent,
                            message = rule.message,
                            suggestion = rule.suggestion,
                            filePath = filePath
                        )
                    )
                }
            } catch (e: Exception) {
                logger.warn("[LocalCodeReviewer] Rule ${rule.id} failed: ${e.message}")
            }
        }

        return findings.sortedWith(compareBy({ it.lineNumber }, { it.ruleId }))
    }

    /**
     * 审查并按严重度汇总
     */
    fun reviewAndSummarize(source: String, language: String = "kotlin", filePath: String = "<unknown>"): ReviewSummary {
        val findings = review(source, language, filePath)
        return ReviewSummary(
            totalFindings = findings.size,
            bySeverity = findings.groupingBy { it.severity }.eachCount(),
            byCategory = findings.groupingBy { it.category }.eachCount(),
            findings = findings
        )
    }

    // === 内置规则 ===

    private val rules: List<ReviewRule> = listOf(
        // === Security ===
        ReviewRule(
            id = "security.hardcoded-password",
            severity = Severity.HIGH,
            category = Category.SECURITY,
            message = "Hardcoded password detected",
            suggestion = "Use environment variables or a secure secrets manager. " +
                    "Never commit passwords to source control.",
            pattern = Regex(
                """(?i)(password|passwd|pwd|secret|api[_-]?key|token)\s*=\s*['"][^'"]{3,}['"]"""
            )
        ),
        ReviewRule(
            id = "security.sql-injection",
            severity = Severity.CRITICAL,
            category = Category.SECURITY,
            message = "Possible SQL injection: string concatenation in query",
            suggestion = "Use parameterized queries (PreparedStatement) or an ORM. " +
                    "Avoid concatenating user input into SQL strings.",
            pattern = Regex(
                """(?i)(executeQuery|executeUpdate|execute\s*\(|rawQuery)\s*\(\s*['"].*?\+"""
            )
        ),
        ReviewRule(
            id = "security.command-injection",
            severity = Severity.HIGH,
            category = Category.SECURITY,
            message = "Possible command injection: Runtime.exec or ProcessBuilder with user input",
            suggestion = "Validate and sanitize all inputs. Avoid passing user-controlled " +
                    "strings to shell commands. Use a safe API like ProcessBuilder with explicit argv.",
            pattern = Regex(
                """(Runtime\.getRuntime\(\)\.exec|ProcessBuilder\s*\(.*?\+)"""
            )
        ),
        ReviewRule(
            id = "security.weak-crypto",
            severity = Severity.MEDIUM,
            category = Category.SECURITY,
            message = "Weak cryptographic algorithm detected",
            suggestion = "Use modern algorithms: SHA-256/SHA-3 for hashing, AES-GCM for encryption, " +
                    "bcrypt/scrypt/Argon2 for passwords. Avoid MD5 and SHA-1.",
            pattern = Regex(
                """(?i)(MessageDigest\.getInstance\s*\(\s*['"](MD5|SHA-1|SHA1)['"]|Cipher\.getInstance\s*\(\s*['"](DES|RC4)['"])"""
            )
        ),
        ReviewRule(
            id = "security.xss-risk",
            severity = Severity.MEDIUM,
            category = Category.SECURITY,
            message = "Possible XSS: unescaped user input in HTML output",
            suggestion = "Escape all user-controlled data before inserting into HTML. " +
                    "Use a templating engine that auto-escapes, or apply OWASP Java Encoder.",
            pattern = Regex(
                """(?i)(innerHTML|outerHTML)\s*=\s*['"].*?\+"""
            )
        ),

        // === Performance ===
        ReviewRule(
            id = "performance.n-plus-one",
            severity = Severity.MEDIUM,
            category = Category.PERFORMANCE,
            message = "Possible N+1 query: loop body calls a query/find method",
            suggestion = "Use eager loading (JOIN FETCH) or batch queries. " +
                    "Avoid calling findByX in a loop over the result set.",
            pattern = Regex(
                """(for|while|\.forEach|\.map)\s*\(.*?[\s\S]{0,200}?(findBy|findAll|getBy|query|select|executeQuery)"""
            )
        ),
        ReviewRule(
            id = "performance.loop-io",
            severity = Severity.MEDIUM,
            category = Category.PERFORMANCE,
            message = "I/O operation inside a loop (likely N round-trips)",
            suggestion = "Batch the I/O outside the loop, or use streaming APIs. " +
                    "Even a network call inside a 100-iteration loop is a 100x slowdown.",
            pattern = Regex(
                """(for|while|\.forEach|\.map)\s*\([\s\S]{0,300}?(httpClient|fetch|read|write|send|request)"""
            )
        ),
        ReviewRule(
            id = "performance.string-concat-loop",
            severity = Severity.LOW,
            category = Category.PERFORMANCE,
            message = "String concatenation in a loop: O(n²) behavior",
            suggestion = "Use StringBuilder, StringBuffer, or a joinToString() / collect pattern.",
            pattern = Regex(
                """(for|while)\s*\(.*?\)\s*\{[\s\S]{0,200}?(\w+)\s*\+=\s*"""
            )
        ),

        // === Style / Maintainability ===
        ReviewRule(
            id = "style.long-method",
            severity = Severity.LOW,
            category = Category.STYLE,
            message = "Method body exceeds 50 lines (consider refactoring)",
            suggestion = "Extract helper methods. Long methods are hard to test and understand. " +
                    "Aim for < 30 lines per method.",
            pattern = Regex(
                """\b(fun|function|def|public|private|protected|static)\s+\w+\s*\([^\)]*\)\s*[\{\:][\s\S]{1500,}?(?=\n\s*\n|\Z)"""
            ),
            lineRange = 1..1  // Pattern doesn't really map to a single line
        ),
        ReviewRule(
            id = "style.long-parameter-list",
            severity = Severity.LOW,
            category = Category.STYLE,
            message = "Function with > 5 parameters (consider parameter object)",
            suggestion = "Group related parameters into a data class / config object. " +
                    "Improves readability and evolvability.",
            pattern = Regex(
                """\b(fun|function|def)\s+\w+\s*\(([^,\)]+,){5,}[^)]*\)"""
            )
        ),
        ReviewRule(
            id = "style.todo-comment",
            severity = Severity.INFO,
            category = Category.STYLE,
            message = "TODO/FIXME comment found",
            suggestion = "Track in your issue tracker. Don't leave TODOs in committed code indefinitely.",
            pattern = Regex(
                """(?i)(//|#|/\*).*?\b(TODO|FIXME|XXX|HACK)\b"""
            )
        ),

        // === Correctness ===
        ReviewRule(
            id = "correctness.system-out",
            severity = Severity.LOW,
            category = Category.STYLE,
            message = "Use of System.out / console.log (use a logger instead)",
            suggestion = "Use the project's logger (e.g., Logger.getLogger()). " +
                    "Production code should never print to stdout directly.",
            pattern = Regex(
                """(System\.out\.print|console\.log)"""
            )
        ),
        ReviewRule(
            id = "correctness.empty-catch",
            severity = Severity.MEDIUM,
            category = Category.CORRECTNESS,
            message = "Empty catch block: errors silently swallowed",
            suggestion = "At minimum, log the exception. If the error is truly ignorable, " +
                    "add a comment explaining why.",
            pattern = Regex(
                """catch\s*\([^)]+\)\s*\{\s*\}"""
            )
        ),
        ReviewRule(
            id = "correctness.equals-string-literal",
            severity = Severity.LOW,
            category = Category.CORRECTNESS,
            message = "String comparison with == (use .equals() for content equality)",
            suggestion = "Use string.equals(other) for content comparison. " +
                    "== compares references in Java/Kotlin. In Kotlin == is structural but " +
                    "in Java you must use .equals().",
            pattern = Regex(
                """(\w+|\"[^\"]+\")\s*==\s*(\w+|\"[^\"]+\")\s*\)"""
            )
        )
    )

    // === 辅助扩展 ===

    private fun String.lineNumberAt(offset: Int): Int {
        val newlinesBefore = this.substring(0, offset.coerceAtMost(this.length)).count { it == '\n' }
        return newlinesBefore + 1
    }

    private fun String.lineAt(lineNumber: Int): String {
        return this.lines().getOrNull(lineNumber - 1)?.trim() ?: ""
    }

    // === 数据类 ===

    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
    enum class Category { SECURITY, PERFORMANCE, STYLE, CORRECTNESS }

    data class ReviewFinding(
        val ruleId: String,
        val severity: Severity,
        val category: Category,
        val lineNumber: Int,
        val lineContent: String,
        val message: String,
        val suggestion: String,
        val filePath: String
    )

    data class ReviewSummary(
        val totalFindings: Int,
        val bySeverity: Map<Severity, Int>,
        val byCategory: Map<Category, Int>,
        val findings: List<ReviewFinding>
    ) {
        fun hasCriticalOrHigh(): Boolean = bySeverity[Severity.CRITICAL] ?: 0 > 0 ||
                bySeverity[Severity.HIGH] ?: 0 > 0
    }

    private data class ReviewRule(
        val id: String,
        val severity: Severity,
        val category: Category,
        val message: String,
        val suggestion: String,
        val pattern: Regex,
        val languages: Set<String> = setOf("kotlin", "java", "javascript", "python", "scala", "groovy", "typescript"),
        val lineRange: IntRange = IntRange.EMPTY
    ) {
        fun appliesTo(language: String): Boolean {
            val lang = language.lowercase()
            return languages.contains("all") || languages.any { it.equals(lang, ignoreCase = true) }
        }
    }
}
