package com.codesage.prompt.engine

import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.Tool
import com.codesage.prompt.presets.PromptPresets
import com.codesage.shared.utils.Logger

/**
 * 提示组装器
 * 根据当前场景、工具列表、项目上下文动态组装系统提示
 */
class PromptAssembler(
    private val basePrompt: String = DEFAULT_BASE_PROMPT,
    private val toolRegistry: ToolRegistry? = null
) {
    private val logger = Logger.getLogger<PromptAssembler>()

    private val sections = mutableListOf<PromptSection>()

    /**
     * 提示段，带优先级用于排序
     */
    data class PromptSection(
        val name: String,
        val content: String,
        val priority: Int = 50,
        val condition: (AssemblyContext) -> Boolean = { true }
    )

    /**
     * 组装上下文
     */
    data class AssemblyContext(
        val role: PromptRole = PromptRole.ASSISTANT,
        val projectLanguage: String? = null,
        val projectFramework: String? = null,
        val toolCount: Int = 0,
        val hasMemory: Boolean = false,
        val hasSubAgent: Boolean = false,
        val hasMCP: Boolean = false,
        val customVars: Map<String, Any> = emptyMap()
    )

    init {
        // 注册默认段
        registerDefaultSections()
    }

    /**
     * 注册自定义段
     */
    fun registerSection(section: PromptSection): PromptAssembler {
        sections.add(section)
        return this
    }

    /**
     * 组装系统提示
     */
    fun assemble(context: AssemblyContext = AssemblyContext()): String {
        val builder = StringBuilder()

        // 基础角色定义
        builder.appendLine(basePrompt)
        builder.appendLine()

        // 角色特定提示
        val rolePrompt = PromptPresets.getRolePrompt(context.role)
        if (rolePrompt.isNotBlank()) {
            builder.appendLine(rolePrompt)
            builder.appendLine()
        }

        // 项目上下文
        if (context.projectLanguage != null) {
            builder.appendLine("## Project Context")
            builder.appendLine("Primary language: ${context.projectLanguage}")
            context.projectFramework?.let {
                builder.appendLine("Framework: $it")
            }
            builder.appendLine()
        }

        // 工具说明
        if (context.toolCount > 0) {
            builder.appendLine("## Available Tools")
            builder.appendLine("You have access to $context.toolCount tools.")
            builder.appendLine()
        }

        // 能力说明
        if (context.hasMemory) {
            builder.appendLine("## Memory")
            builder.appendLine("You can use memory_search/memory_add to recall past conversations and facts.")
            builder.appendLine()
        }

        if (context.hasSubAgent) {
            builder.appendLine("## Sub-Agent Delegation")
            builder.appendLine("Use delegate_task to spawn isolated sub-agents for parallel work.")
            builder.appendLine()
        }

        if (context.hasMCP) {
            builder.appendLine("## External Tools (MCP)")
            builder.appendLine("You can also use MCP-connected external tools.")
            builder.appendLine()
        }

        // 按优先级排序并渲染条件段
        val activeSections = sections
            .filter { it.condition(context) }
            .sortedBy { it.priority }

        activeSections.forEach { section ->
            builder.appendLine("## ${section.name}")
            builder.appendLine(section.content)
            builder.appendLine()
        }

        // 通用指导原则（始终放在最后）
        builder.appendLine("## Guidelines")
        builder.appendLine(GENERAL_GUIDELINES)

        return builder.toString().trim()
    }

    /**
     * 组装带工具schema的完整提示
     */
    fun assembleWithTools(
        tools: List<Tool>,
        context: AssemblyContext = AssemblyContext()
    ): String {
        val base = assemble(context.copy(toolCount = tools.size))
        if (tools.isEmpty()) return base

        val toolDescriptions = tools.joinToString("\n") { tool ->
            val required = tool.parameters.required.joinToString(", ")
            "- ${tool.name}: ${tool.description} (required: $required)"
        }

        return buildString {
            appendLine(base)
            appendLine()
            appendLine("## Tool Definitions")
            appendLine(toolDescriptions)
        }.trim()
    }

    private fun registerDefaultSections() {
        sections.add(
            PromptSection(
                name = "Response Format",
                content = "Use markdown for code blocks. When suggesting changes, explain the reasoning first.",
                priority = 90
            )
        )

        sections.add(
            PromptSection(
                name = "Safety",
                content = "Never execute destructive operations without explicit confirmation. Avoid modifying files outside the project.",
                priority = 100,
                condition = { it.role != PromptRole.CODE_REVIEWER }
            ))
    }

    companion object {
        val DEFAULT_BASE_PROMPT = """
            You are CodeSage, an expert AI coding assistant embedded in IntelliJ IDEA.
            You help developers write, refactor, debug, and understand code.
        """.trimIndent()

        val GENERAL_GUIDELINES = """
            - Be concise but thorough in explanations
            - Prefer showing code over describing it
            - When uncertain, ask clarifying questions
            - Always consider the project's existing patterns and style
            - Suggest tests when modifying critical logic
        """.trimIndent()
    }
}

/**
 * 预设角色
 */
enum class PromptRole {
    ASSISTANT,          // 通用助手
    CODE_REVIEWER,      // 代码审查
    DEBUGGER,           // 调试专家
    ARCHITECT,          // 架构师
    EXPLAINER,          // 讲解者
    REFACTORER          // 重构专家
}
