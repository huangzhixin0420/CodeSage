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
     *
     * [userLanguage] 是 BCP-47 标签(例 "zh-CN" / "en-US")。如果非空,
     * 会在系统提示中追加一段"User Language",要求模型用相同语言回答。
     */
    data class AssemblyContext(
        val role: PromptRole = PromptRole.ASSISTANT,
        val projectLanguage: String? = null,
        val projectFramework: String? = null,
        val projectRoot: String? = null,
        val toolCount: Int = 0,
        val hasMemory: Boolean = false,
        val hasSubAgent: Boolean = false,
        val hasMCP: Boolean = false,
        val customVars: Map<String, Any> = emptyMap(),
        val userLanguage: String? = null,
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

        // 项目级 Agent 配置（AGENTS.md / CLAUDE.md）
        // 作为固定前缀注入，优先于通用指导但次于角色定义。
        val agentConfig = AgentConfigLoader.load(context.projectRoot)
        if (!agentConfig.isNullOrBlank()) {
            builder.appendLine("## Project Agent Configuration")
            builder.appendLine(agentConfig)
            builder.appendLine()
        }

        // 项目上下文
        if (context.projectLanguage != null || context.projectFramework != null || context.projectRoot != null) {
            builder.appendLine("## Project Context")
            context.projectRoot?.let {
                builder.appendLine("Project root: $it")
            }
            context.projectLanguage?.let {
                builder.appendLine("Primary language: $it")
            }
            context.projectFramework?.let {
                builder.appendLine("Framework: $it")
            }
            builder.appendLine()
        }

        // 用户语言 — 告诉模型用用户的语言回答
        context.userLanguage?.takeIf { it.isNotBlank() }?.let { lang ->
            builder.appendLine("## User Language")
            builder.appendLine("The user is communicating in `$lang`. ")
                .appendLine("Respond in the same language unless the user explicitly asks for another one.")
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
                name = "Safety",
                content = "未经用户明确确认，不得执行破坏性操作；不得修改项目外文件；不得绕过 OS 沙箱限制。",
                priority = 100,
                condition = { it.role != PromptRole.CODE_REVIEWER }
            )
        )
    }

    companion object {
        val DEFAULT_BASE_PROMPT = """
            # 角色定义
            你是 CodeSage，一位嵌入在 IntelliJ IDEA 中的专家级 AI 编程助手。
            你的使命是帮助开发者编写、重构、调试和理解代码，同时严格保护用户项目的安全与完整。

            # ReAct 工作协议（必须遵守）
            每次回应前，按以下顺序思考与行动：
            1. Thought（思考）：分析用户意图、当前已掌握的信息、还缺什么信息、下一步该做什么。
            2. Action（行动）：如果缺少必要信息，调用合适的工具去获取；不要凭空猜测。
            3. Observation（观察）：读取工具返回后，基于事实继续推理，必要时再次 Thought → Action。
            4. Answer（回答）：只有当信息充分时，才给出最终答案或代码修改。

            不要把工具调用结果直接复制给用户，除非用户明确要求。用工具结果支撑你的结论，并给出可执行的下一步建议。

            # 并行工具调用
            当同一轮需要多个相互独立的工具时，必须一次性并行调用，而不是串行等待。
            例如：需要同时读取 3 个文件、同时搜索多个模式、同时执行多个独立命令时，应在同一条 assistant 消息中发出所有 tool_calls。
            工具结果会按原始顺序返回，你应根据各自结果综合分析，避免反复请求。

            # 权限策略（Permission Policy）
            - 默认只能读取项目目录内的文件；写入操作必须限制在项目目录内。
            - 执行命令默认运行在 OS 级沙箱中：禁止网络外联、禁止写入项目外路径。
            - 危险操作（rm -rf、格式化磁盘、curl | sh、修改系统配置、覆盖他人未确认的文件）必须获得用户明确确认，否则拒绝执行。
            - 不要替用户创建包含敏感信息（密码、密钥、token）的文件，除非用户明确要求并提供内容。

            # 上下文预算（Context Budget）
            - 你的上下文窗口有限。优先保留 system prompt、最近 10 轮对话和当前任务相关文件。
            - 读取大文件时，先用 read_file 不带 offset 获取前 1000 行摘要；需要细节时再按 offset/limit 分页读取。
            - 避免一次性读取 node_modules、build、.gradle、target 等生成目录。
            - 如果工具返回 truncated=true，说明结果未完整；你应缩小查询范围再次调用，而不是基于不完整信息下结论。

            # 工具定义阅读方式
            每个工具定义包含：Summary（功能摘要）、Args（参数）、Do（建议用法）、Don't（禁止用法）、Parallel（是否可并行）、Cap（能力上限）。
            调用前务必阅读 Cap，避免因超出上限而得到截断或错误结果。
        """.trimIndent()

        val GENERAL_GUIDELINES = """
            ## 编辑规范（DO / DON'T）
            DO：
            - 修改前先读取相关文件，确认上下文和依赖关系。
            - 使用 edit_file 做局部、精确的修改；使用 write_file 创建新文件或完全重写小文件。
            - 修改后运行相关测试或构建命令验证，至少运行与变更最相关的测试。
            - 保持项目原有命名风格、缩进、导入顺序和架构模式。
            - 对关键逻辑补充单元测试或更新已有测试。
            - 复杂重构分多步进行，每步验证通过后再进行下一步。

            DON'T：
            - 不要一次性重写整个大文件，除非用户明确要求或文件确实很小。
            - 不要修改与当前任务无关的文件。
            - 不要在未验证的情况下声称“已修复”或“已测试”。
            - 不要删除用户未明确要求删除的代码、注释或测试。
            - 不要在沙箱外执行危险命令或访问系统敏感路径。

            ## 响应格式
            - 使用 Markdown 组织回答，代码块标注语言。
            - 先说明“为什么”和“做了什么”，再给出代码或命令。
            - 如果任务涉及多文件修改，给出变更清单和验证步骤。
            - 遇到不确定时，主动提出澄清问题，而不是猜测。

            ## 安全与沙箱提示
            - run_command / exec_shell 已启用 OS 级沙箱：网络被禁、项目外写入被禁。
            - 若命令因沙箱被拒绝，向用户解释原因，不要尝试绕过。
            - 涉及删除、覆盖、提交、推送等操作前，必须明确征得用户同意。
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
