package com.codesage.skill.builtin

import com.codesage.skill.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI 辅助编程 Skill 生态
 * 为 Agent 提供更高层次的代码理解和生成能力
 */

// region 代码解释 Skill

class CodeExplanationSkill : Skill {
    override val id = "builtin_code_explanation"
    override val name = "Code Explanation"
    override val description = "解释代码的功能、逻辑和设计意图。支持多种编程语言。"
    override val version = "1.0.0"
    override val category = SkillCategory.AI_INTEGRATION
    override val tags = setOf("ai", "explanation", "code", "documentation")
    override val inputSchema = mapOf(
        "code" to mapOf("type" to "string", "description" to "要解释的代码片段"),
        "language" to mapOf("type" to "string", "description" to "编程语言（可选，如 kotlin, java, python）"),
        "detail_level" to mapOf(
            "type" to "string",
            "description" to "详细程度: brief/detailed/deep",
            "enum" to listOf("brief", "detailed", "deep")
        )
    )
    override val outputSchema = mapOf(
        "explanation" to mapOf("type" to "string", "description" to "代码解释"),
        "key_points" to mapOf("type" to "array", "description" to "关键要点列表")
    )

    private val logger = Logger.getLogger<CodeExplanationSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        val code = input.getString("code") ?: return SkillResult.Failure("Missing 'code' parameter")
        val language = input.getString("language") ?: "auto"
        val detailLevel = input.getString("detail_level") ?: "detailed"

        // 提取关键信息供后续 LLM 调用使用（Skill 本身不直接调用 LLM，而是格式化输入）
        val analysis = analyzeCodeStructure(code, language)

        return SkillResult.Success(
            mapOf(
                "explanation_prompt" to buildExplanationPrompt(code, language, detailLevel),
                "code_analysis" to analysis,
                "language" to language,
                "detail_level" to detailLevel,
                "line_count" to code.lines().size
            )
        )
    }

    private fun analyzeCodeStructure(code: String, language: String): Map<String, Any> {
        val lines = code.lines()
        return mapOf(
            "total_lines" to lines.size,
            "non_empty_lines" to lines.count { it.trim().isNotEmpty() },
            "comment_lines" to lines.count {
                it.trim().startsWith("//") || it.trim().startsWith("#") || it.trim().startsWith("/*")
            },
            "detected_language" to if (language == "auto") detectLanguage(code) else language,
            "has_classes" to code.contains(Regex("""class\s+\w+""")),
            "has_functions" to code.contains(Regex("""(fun|def|function|void|int|String)\s+\w+\s*\("""))
        )
    }

    private fun detectLanguage(code: String): String {
        return when {
            code.contains("fun ") && code.contains(": ") -> "kotlin"
            code.contains("public class") || code.contains("private class") -> "java"
            code.contains("def ") && code.contains(":") -> "python"
            code.contains("function ") || code.contains("const ") || code.contains("let ") -> "javascript"
            code.contains("interface ") && code.contains("{") -> "typescript"
            code.contains("package main") && code.contains("func ") -> "go"
            code.contains("fn ") || code.contains("impl ") -> "rust"
            else -> "unknown"
        }
    }

    private fun buildExplanationPrompt(code: String, language: String, detailLevel: String): String {
        val levelDesc = when (detailLevel) {
            "brief" -> "简要解释这段代码的功能（1-2句话）"
            "detailed" -> "详细解释这段代码的功能、逻辑流程和关键设计决策"
            "deep" -> "深入解释这段代码的功能、算法复杂度、潜在问题和改进建议"
            else -> "解释这段代码的功能"
        }
        return "$levelDesc\n\n```${if (language == "auto") "" else language}\n$code\n```"
    }
}

// endregion

// region 重构建议 Skill

class RefactoringSuggestionSkill : Skill {
    override val id = "builtin_refactoring_suggestion"
    override val name = "Refactoring Suggestion"
    override val description = "分析代码并提供重构建议：提取方法、重命名、简化条件、消除重复等。"
    override val version = "1.0.0"
    override val category = SkillCategory.AI_INTEGRATION
    override val tags = setOf("ai", "refactoring", "code-quality", "improvement")
    override val inputSchema = mapOf(
        "code" to mapOf("type" to "string", "description" to "要分析的代码片段"),
        "language" to mapOf("type" to "string", "description" to "编程语言"),
        "focus_areas" to mapOf(
            "type" to "array",
            "description" to "重点关注领域: readability/performance/safety/simplicity"
        )
    )
    override val outputSchema = mapOf(
        "suggestions" to mapOf("type" to "array", "description" to "重构建议列表"),
        "refactored_code" to mapOf("type" to "string", "description" to "重构后的代码示例")
    )

    private val logger = Logger.getLogger<RefactoringSuggestionSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        val code = input.getString("code") ?: return SkillResult.Failure("Missing 'code' parameter")
        val language = input.getString("language") ?: "auto"
        val focusAreas = input.getList("focus_areas")?.map { it.toString() } ?: listOf("readability", "simplicity")

        val metrics = calculateMetrics(code)
        val smells = detectCodeSmells(code)

        return SkillResult.Success(
            mapOf(
                "prompt" to buildRefactoringPrompt(code, language, focusAreas),
                "metrics" to metrics,
                "detected_smells" to smells,
                "focus_areas" to focusAreas,
                "suggestion_count" to smells.size
            )
        )
    }

    private fun calculateMetrics(code: String): Map<String, Any> {
        val lines = code.lines()
        return mapOf(
            "cyclomatic_complexity" to estimateComplexity(code),
            "method_length" to lines.size,
            "parameter_count" to countParameters(code),
            "nesting_depth" to maxNestingDepth(code)
        )
    }

    private fun estimateComplexity(code: String): Int {
        val patterns = listOf("if ", "while ", "for ", "when ", "switch", "catch ", "&&", "||", "?")
        return patterns.sumOf { code.split(it).size - 1 } + 1
    }

    private fun countParameters(code: String): Int {
        val match = Regex("""\(([^)]*)\)""").find(code)
        return match?.groupValues?.get(1)?.split(",")?.filter { it.trim().isNotEmpty() }?.size ?: 0
    }

    private fun maxNestingDepth(code: String): Int {
        var maxDepth = 0
        var currentDepth = 0
        code.forEach { c ->
            when (c) {
                '{', '(' -> currentDepth++
                '}', ')' -> currentDepth--
            }
            if (currentDepth > maxDepth) maxDepth = currentDepth
        }
        return maxDepth
    }

    private fun detectCodeSmells(code: String): List<Map<String, String>> {
        val smells = mutableListOf<Map<String, String>>()
        val lines = code.lines()

        // 方法过长
        if (lines.size > 30) {
            smells.add(
                mapOf(
                    "type" to "long_method",
                    "description" to "Method has ${lines.size} lines, consider extracting smaller methods"
                )
            )
        }

        // 嵌套过深
        if (maxNestingDepth(code) > 4) {
            smells.add(
                mapOf(
                    "type" to "deep_nesting",
                    "description" to "Deep nesting detected, consider early returns or extraction"
                )
            )
        }

        // 重复代码（简单检测：完全相同的行）
        val duplicates =
            lines.groupBy { it.trim() }.filter { it.value.size > 1 && it.key.isNotBlank() && it.key.length > 10 }
        if (duplicates.isNotEmpty()) {
            smells.add(
                mapOf(
                    "type" to "duplicate_code",
                    "description" to "Found ${duplicates.size} duplicated line patterns"
                )
            )
        }

        // 魔法数字
        val magicNumbers = Regex("""(?<!\w)(\d{2,})(?!\w)""").findAll(code).map { it.value }.toSet()
        if (magicNumbers.size > 3) {
            smells.add(
                mapOf(
                    "type" to "magic_numbers",
                    "description" to "Found ${magicNumbers.size} magic numbers, consider named constants"
                )
            )
        }

        // 注释比例过低
        val commentRatio =
            lines.count { it.trim().startsWith("//") || it.trim().startsWith("/*") }.toDouble() / lines.size
        if (commentRatio < 0.05 && lines.size > 10) {
            smells.add(
                mapOf(
                    "type" to "missing_comments",
                    "description" to "Low comment ratio (${"%.1f".format(commentRatio * 100)}%), consider adding documentation"
                )
            )
        }

        return smells
    }

    private fun buildRefactoringPrompt(code: String, language: String, focusAreas: List<String>): String {
        return "Analyze the following code and provide refactoring suggestions. " +
                "Focus areas: ${focusAreas.joinToString(", ")}.\n\n" +
                "```${if (language == "auto") "" else language}\n$code\n```"
    }
}

// endregion

// region 测试生成 Skill

class TestGenerationSkill : Skill {
    override val id = "builtin_test_generation"
    override val name = "Test Generation"
    override val description = "为给定代码生成单元测试。支持 JUnit、TestNG、pytest 等框架。"
    override val version = "1.0.0"
    override val category = SkillCategory.AI_INTEGRATION
    override val tags = setOf("ai", "testing", "unit-test", "code-generation")
    override val inputSchema = mapOf(
        "code" to mapOf("type" to "string", "description" to "要测试的代码片段或方法"),
        "language" to mapOf("type" to "string", "description" to "编程语言"),
        "framework" to mapOf(
            "type" to "string",
            "description" to "测试框架: junit/testng/pytest/jest",
            "enum" to listOf("junit", "testng", "pytest", "jest", "auto")
        ),
        "coverage_focus" to mapOf(
            "type" to "string",
            "description" to "覆盖重点: happy-path/edge-cases/error-handling/all",
            "enum" to listOf("happy-path", "edge-cases", "error-handling", "all")
        )
    )
    override val outputSchema = mapOf(
        "tests" to mapOf("type" to "string", "description" to "生成的测试代码"),
        "test_cases" to mapOf("type" to "array", "description" to "测试用例描述列表")
    )

    private val logger = Logger.getLogger<TestGenerationSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        val code = input.getString("code") ?: return SkillResult.Failure("Missing 'code' parameter")
        val language = input.getString("language") ?: "kotlin"
        val framework = input.getString("framework") ?: "auto"
        val coverage = input.getString("coverage_focus") ?: "all"

        val detectedFramework = if (framework == "auto") detectFramework(language) else framework
        val testCases = generateTestCaseDescriptions(code, coverage)

        return SkillResult.Success(
            mapOf(
                "prompt" to buildTestPrompt(code, language, detectedFramework, coverage),
                "framework" to detectedFramework,
                "language" to language,
                "coverage_focus" to coverage,
                "test_cases" to testCases,
                "estimated_tests" to testCases.size
            )
        )
    }

    private fun detectFramework(language: String): String {
        return when (language.lowercase()) {
            "kotlin", "java" -> "junit"
            "python" -> "pytest"
            "javascript", "typescript" -> "jest"
            else -> "junit"
        }
    }

    private fun generateTestCaseDescriptions(code: String, coverage: String): List<Map<String, String>> {
        val cases = mutableListOf<Map<String, String>>()

        if (coverage in listOf("happy-path", "all")) {
            cases.add(mapOf("type" to "happy_path", "description" to "Normal input with expected output"))
        }
        if (coverage in listOf("edge-cases", "all")) {
            cases.add(mapOf("type" to "null_input", "description" to "Null or empty input handling"))
            cases.add(mapOf("type" to "boundary", "description" to "Boundary values (min, max, empty collections)"))
            cases.add(mapOf("type" to "large_input", "description" to "Large input performance"))
        }
        if (coverage in listOf("error-handling", "all")) {
            cases.add(mapOf("type" to "exception", "description" to "Exception and error handling paths"))
            cases.add(mapOf("type" to "invalid_input", "description" to "Invalid input format or type"))
        }

        return cases
    }

    private fun buildTestPrompt(code: String, language: String, framework: String, coverage: String): String {
        return "Generate $framework unit tests for the following $language code. " +
                "Focus on $coverage coverage.\n\n" +
                "```$language\n$code\n```"
    }
}

// endregion

// region 代码审查 Skill

class CodeReviewSkill : Skill {
    override val id = "builtin_code_review"
    override val name = "Code Review"
    override val description = "执行代码审查，检查潜在问题、安全漏洞、性能瓶颈和风格问题。"
    override val version = "1.0.0"
    override val category = SkillCategory.AI_INTEGRATION
    override val tags = setOf("ai", "code-review", "security", "performance", "quality")
    override val inputSchema = mapOf(
        "code" to mapOf("type" to "string", "description" to "要审查的代码片段"),
        "language" to mapOf("type" to "string", "description" to "编程语言"),
        "focus" to mapOf(
            "type" to "string",
            "description" to "审查重点: security/performance/style/all",
            "enum" to listOf("security", "performance", "style", "all")
        )
    )
    override val outputSchema = mapOf(
        "issues" to mapOf("type" to "array", "description" to "发现的问题列表"),
        "severity" to mapOf("type" to "string", "description" to "严重级别汇总")
    )

    private val logger = Logger.getLogger<CodeReviewSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult = CanExecuteResult(true)

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        val code = input.getString("code") ?: return SkillResult.Failure("Missing 'code' parameter")
        val language = input.getString("language") ?: "auto"
        val focus = input.getString("focus") ?: "all"

        val securityIssues = if (focus in listOf("security", "all")) scanSecurityIssues(code) else emptyList()
        val performanceIssues = if (focus in listOf("performance", "all")) scanPerformanceIssues(code) else emptyList()
        val styleIssues = if (focus in listOf("style", "all")) scanStyleIssues(code) else emptyList()

        val allIssues = securityIssues + performanceIssues + styleIssues
        val severityCount = allIssues.groupingBy { it["severity"] ?: "info" }.eachCount()

        return SkillResult.Success(
            mapOf(
                "prompt" to buildReviewPrompt(code, language, focus),
                "security_issues" to securityIssues,
                "performance_issues" to performanceIssues,
                "style_issues" to styleIssues,
                "total_issues" to allIssues.size,
                "severity_summary" to severityCount,
                "language" to language
            )
        )
    }

    private fun scanSecurityIssues(code: String): List<Map<String, String>> {
        val issues = mutableListOf<Map<String, String>>()
        val lowerCode = code.lowercase()

        // SQL 注入
        if (lowerCode.contains("select") && lowerCode.contains("from") && lowerCode.contains("+") || lowerCode.contains(
                "\${"
            )
        ) {
            issues.add(
                mapOf(
                    "severity" to "critical",
                    "type" to "sql_injection",
                    "description" to "Potential SQL injection: string concatenation in SQL query"
                )
            )
        }

        // 硬编码密钥
        if (Regex("""(password|secret|token|key)\s*=\s*["'][^"']+["']""", RegexOption.IGNORE_CASE).find(code) != null) {
            issues.add(
                mapOf(
                    "severity" to "high",
                    "type" to "hardcoded_secret",
                    "description" to "Potential hardcoded secret or password"
                )
            )
        }

        // 不安全的反序列化
        if (lowerCode.contains("objectinputstream") || lowerCode.contains("deserialize")) {
            issues.add(
                mapOf(
                    "severity" to "high",
                    "type" to "insecure_deserialization",
                    "description" to "Insecure deserialization detected"
                )
            )
        }

        // 路径遍历
        if (lowerCode.contains("../") || lowerCode.contains("..\\")) {
            issues.add(
                mapOf(
                    "severity" to "high",
                    "type" to "path_traversal",
                    "description" to "Potential path traversal vulnerability"
                )
            )
        }

        return issues
    }

    private fun scanPerformanceIssues(code: String): List<Map<String, String>> {
        val issues = mutableListOf<Map<String, String>>()

        // 字符串拼接在循环中
        if (Regex("""for\s*\([^)]*\)\s*\{[^}]*\+\s*=""").find(code) != null) {
            issues.add(
                mapOf(
                    "severity" to "medium",
                    "type" to "string_concat_in_loop",
                    "description" to "String concatenation in loop, consider using StringBuilder"
                )
            )
        }

        // 不合适的集合类型
        if (code.contains("List<") && code.contains(".contains(") && !code.contains("Set<")) {
            issues.add(
                mapOf(
                    "severity" to "low",
                    "type" to "list_contains",
                    "description" to "Frequent contains() on List, consider using Set for O(1) lookup"
                )
            )
        }

        return issues
    }

    private fun scanStyleIssues(code: String): List<Map<String, String>> {
        val issues = mutableListOf<Map<String, String>>()
        val lines = code.lines()

        // 行过长
        lines.forEachIndexed { index, line ->
            if (line.length > 120) {
                issues.add(
                    mapOf(
                        "severity" to "low",
                        "type" to "long_line",
                        "description" to "Line ${index + 1} exceeds 120 characters (${line.length})"
                    )
                )
            }
        }

        // 方法过长
        if (lines.size > 50) {
            issues.add(
                mapOf(
                    "severity" to "medium",
                    "type" to "long_method",
                    "description" to "Method is ${lines.size} lines, consider splitting"
                )
            )
        }

        return issues
    }

    private fun buildReviewPrompt(code: String, language: String, focus: String): String {
        return "Perform a code review on the following ${if (language == "auto") "" else language} code. " +
                "Focus: $focus. Identify issues with severity levels.\n\n" +
                "```${if (language == "auto") "" else language}\n$code\n```"
    }
}

// endregion

// region 依赖分析 Skill

class DependencyAnalysisSkill : Skill {
    override val id = "builtin_dependency_analysis"
    override val name = "Dependency Analysis"
    override val description = "分析项目依赖关系，检测过时依赖、许可证冲突和安全漏洞。"
    override val version = "1.0.0"
    override val category = SkillCategory.CODE_SEARCH
    override val tags = setOf("dependency", "security", "analysis", "project")
    override val inputSchema = mapOf(
        "project_path" to mapOf("type" to "string", "description" to "项目路径"),
        "check_outdated" to mapOf("type" to "boolean", "description" to "检查过时依赖"),
        "check_vulnerabilities" to mapOf("type" to "boolean", "description" to "检查安全漏洞")
    )
    override val outputSchema = mapOf(
        "dependencies" to mapOf("type" to "array", "description" to "依赖列表"),
        "outdated" to mapOf("type" to "array", "description" to "过时依赖列表"),
        "vulnerabilities" to mapOf("type" to "array", "description" to "漏洞列表")
    )

    private val logger = Logger.getLogger<DependencyAnalysisSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return if (context.projectPath != null) CanExecuteResult(true) else CanExecuteResult(
            false,
            "Project path required"
        )
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        val projectPath = input.getString("project_path") ?: context.projectPath
        ?: return SkillResult.Failure("Project path not specified")
        val checkOutdated = input.getBoolean("check_outdated") ?: true
        val checkVulns = input.getBoolean("check_vulnerabilities") ?: false

        val projectDir = File(projectPath)
        val buildSystem = when {
            File(projectDir, "pom.xml").exists() -> "maven"
            File(projectDir, "build.gradle").exists() || File(projectDir, "build.gradle.kts").exists() -> "gradle"
            File(projectDir, "package.json").exists() -> "npm"
            File(projectDir, "requirements.txt").exists() -> "pip"
            else -> "unknown"
        }

        return SkillResult.Success(
            mapOf(
                "project_path" to projectPath,
                "build_system" to buildSystem,
                "check_outdated" to checkOutdated,
                "check_vulnerabilities" to checkVulns,
                "recommendation" to when (buildSystem) {
                    "maven" -> "Run 'mvn dependency:tree' and 'mvn versions:display-dependency-updates'"
                    "gradle" -> "Run './gradlew dependencies' and './gradlew dependencyUpdates'"
                    "npm" -> "Run 'npm audit' and 'npm outdated'"
                    else -> "Unknown build system"
                }
            )
        )
    }
}

// endregion
