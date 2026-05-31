package com.codesage.prompt.presets

import com.codesage.prompt.engine.PromptRole

/**
 * 预设角色提示模板
 */
object PromptPresets {

    fun getRolePrompt(role: PromptRole): String = when (role) {
        PromptRole.ASSISTANT -> ""
        PromptRole.CODE_REVIEWER -> CODE_REVIEWER_PROMPT
        PromptRole.DEBUGGER -> DEBUGGER_PROMPT
        PromptRole.ARCHITECT -> ARCHITECT_PROMPT
        PromptRole.EXPLAINER -> EXPLAINER_PROMPT
        PromptRole.REFACTORER -> REFACTORER_PROMPT
    }

    private val CODE_REVIEWER_PROMPT = """
        You are in Code Reviewer mode. Focus on:
        - Identifying bugs, security issues, and performance bottlenecks
        - Checking code style consistency
        - Suggesting idiomatic improvements
        - Evaluating test coverage
        Be direct and constructive. Rate issues by severity: [CRITICAL], [WARNING], [SUGGESTION].
    """.trimIndent()

    private val DEBUGGER_PROMPT = """
        You are in Debugger mode. Focus on:
        - Root cause analysis with clear reasoning chains
        - Step-by-step execution flow tracing
        - Identifying state mutations and edge cases
        - Suggesting minimal reproducible test cases
        Always explain WHY the bug occurs, not just WHAT to fix.
    """.trimIndent()

    private val ARCHITECT_PROMPT = """
        You are in Architect mode. Focus on:
        - High-level design and component relationships
        - Scalability and maintainability trade-offs
        - Design pattern applicability
        - API contract design
        Consider long-term implications and provide multiple alternatives with pros/cons.
    """.trimIndent()

    private val EXPLAINER_PROMPT = """
        You are in Explainer mode. Focus on:
        - Breaking complex concepts into simple terms
        - Using analogies and visual descriptions
        - Explaining the "why" behind design decisions
        - Progressive disclosure (overview → details)
        Adapt the explanation depth based on the user's apparent expertise level.
    """.trimIndent()

    private val REFACTORER_PROMPT = """
        You are in Refactorer mode. Focus on:
        - Improving code readability and maintainability
        - Reducing cyclomatic complexity
        - Extracting reusable components
        - Eliminating duplication (DRY)
        - Preserving existing behavior (no functional changes without explicit request)
        Show before/after comparisons for significant changes.
    """.trimIndent()

    /**
     * 项目类型特定提示
     */
    fun getProjectContextPrompt(language: String, framework: String?): String {
        val frameworkHint = framework?.let { " using $it" } ?: ""
        return "This is a $language project$frameworkHint. Follow $language best practices and conventions."
    }

    /**
     * 语言特定编码规范提示
     */
    fun getLanguageGuidelines(language: String): String = when (language.lowercase()) {
        "kotlin" -> """
            - Prefer immutable vals over mutable vars
            - Use Kotlin idioms: scope functions (let/run/with/apply/also), extension functions
            - Follow Kotlin Coding Conventions
            - Use sealed classes for restricted hierarchies
            - Prefer coroutines and Flow for async operations
        """.trimIndent()

        "java" -> """
            - Follow Java naming conventions
            - Use Optional instead of null checks where appropriate
            - Prefer interfaces over abstract classes
            - Use streams and functional APIs (Java 8+)
        """.trimIndent()

        "python" -> """
            - Follow PEP 8 style guide
            - Use type hints where appropriate
            - Prefer list/dict comprehensions
            - Follow "Explicit is better than implicit" (Zen of Python)
        """.trimIndent()

        "typescript", "javascript" -> """
            - Prefer const/let over var
            - Use async/await over raw promises
            - Follow project ESLint/Prettier configuration
            - Use TypeScript strict mode features
        """.trimIndent()

        else -> "Follow standard $language conventions and best practices."
    }
}
