package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.memory.MemoryManager
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 子 Agent 执行结果
 */
data class SubAgentResult(
    val success: Boolean,
    val output: String,
    val sessionId: String,
    val iterationsUsed: Int,
    val toolsUsed: List<String>
)

/**
 * 子 Agent 执行器
 *
 * 参考 Hermes 的 delegate_task 工具设计：
 * - 创建新 AgentCore 实例（隔离的 context）
 * - 加载指定 toolset 的工具（而非全部工具）
 * - 独立 session 和 budget（继承父 agent 的 budget 比例）
 * - 通过 progress callback 实时汇报进度给父 agent
 *
 * 防止无限递归：每个 SubAgentExecutor 知道自己所在的嵌套深度（[depth]），
 * 超过 [MAX_RECURSION_DEPTH] 时拒绝继续 spawn。
 */
open class SubAgentExecutor(
    private val parentAgent: AgentCore,
    private val gateway: ModelGateway = ModelGateway.getInstance(),
    private val project: Project? = null,
    private val skillToolAdapter: SkillToolAdapter? = null,
    /**
     * 当前子 Agent 在嵌套树中的深度。
     * 0 = 顶层父 Agent；1 = 它直接 spawn 出的子 Agent；2 = 孙子；以此类推。
     */
    private val depth: Int = 0
) {

    private val logger = Logger.getLogger<SubAgentExecutor>()

    /**
     * 生成子 Agent 系统提示
     *
     * 子 Agent 的 prompt 由两部分组成：
     * 1. 父 Agent 的 system prompt（让子 Agent 继承项目上下文、工具说明、角色等）
     * 2. Sub-agent 专用 section（明确任务边界、隔离约束、深度限制）
     *
     * 注意：父 prompt 中的 "## Sub-Agent Delegation" 仍然存在，但子 Agent 在
     * 达到 [MAX_RECURSION_DEPTH] 时不应再继续 spawn。
     */
    private fun generateSubAgentPrompt(
        taskDescription: String,
        toolset: String,
        parentPrompt: String
    ): String = buildSubAgentPrompt(taskDescription, toolset, parentPrompt, depth)

    /**
     * Spawn 一个隔离的子 Agent 执行任务
     *
     * @param parentSessionId 父会话 ID
     * @param taskDescription 任务描述
     * @param toolset 工具集名称（dev, research, test, browser）
     * @param maxIterations 子 Agent 的迭代预算
     * @param contextFiles 子 Agent 需要访问的文件列表
     * @param progressCallback 进度回调（实时汇报给父 agent）
     * @param parentBudget 父 agent 的 budget（用于按比例继承）
     */
    open suspend fun spawn(
        parentSessionId: String,
        taskDescription: String,
        toolset: String = "dev",
        maxIterations: Int = 10,
        contextFiles: List<String> = emptyList(),
        progressCallback: suspend (String) -> Unit = {},
        parentBudget: TaskBudget? = null
    ): SubAgentResult {
        // 1. 递归深度检查
        if (depth >= MAX_RECURSION_DEPTH) {
            val msg =
                "Max sub-agent recursion depth ($MAX_RECURSION_DEPTH) reached at depth=$depth; refusing to spawn further sub-agents"
            logger.warn("[SubAgent] $msg")
            progressCallback("[SubAgent] $msg")
            return SubAgentResult(
                success = false,
                output = msg,
                sessionId = "depth_exceeded_${System.currentTimeMillis()}",
                iterationsUsed = 0,
                toolsUsed = emptyList()
            )
        }

        logger.info("[SubAgent] Spawning for task: ${taskDescription.take(80)} (depth=$depth)")

        val subSessionId = "sub_${parentSessionId}_${System.currentTimeMillis()}"

        // 2. 按 toolset 过滤工具集
        val subToolRegistry = createToolRegistryForToolset(toolset)
        val toolCount = subToolRegistry.getAllTools().size
        logger.info("[SubAgent] Toolset='$toolset' → $toolCount tools available")

        // 3. 计算子 Agent 预算配置（继承父 Agent 剩余预算比例）
        val subBudgetConfig = if (parentBudget != null) {
            val pluginConfig = PluginConfig.getInstance()
            val parentRemaining = parentBudget.remainingIterations()
            val subMaxIterations = (parentRemaining * pluginConfig.subAgentBudgetRatio).toInt()
                .coerceAtLeast(3)
                .coerceAtMost(maxIterations)
            logger.info("[SubAgent] Budget inherited from parent: parentRemaining=$parentRemaining, subMaxIterations=$subMaxIterations (ratio=${pluginConfig.subAgentBudgetRatio})")
            TaskBudget.BudgetConfig(
                maxIterations = subMaxIterations,
                maxTokens = parentBudget.config.maxTokens,
                maxDurationMs = parentBudget.config.maxDurationMs,
                enableIteration = parentBudget.config.enableIteration,
                enableToken = parentBudget.config.enableToken,
                enableTime = parentBudget.config.enableTime,
                warningThresholdPercent = parentBudget.config.warningThresholdPercent
            )
        } else {
            TaskBudget.BudgetConfig(maxIterations = maxIterations)
        }

        // 4. 基于父 prompt 构造子 Agent 的 prompt
        val parentPrompt = parentAgent.getSystemPrompt()
        val subSystemPrompt = generateSubAgentPrompt(taskDescription, toolset, parentPrompt)

        // 5. 创建子 AgentCore（注入过滤后的 registry 和子深度）
        val subAgent = AgentCore(
            gateway = gateway,
            project = project,
            skillToolAdapter = skillToolAdapter,
            toolRegistryOverride = subToolRegistry,
            subAgentDepth = depth + 1
        )

        // 配置子 Agent（传入自定义 prompt + 预算）
        subAgent.initialize(
            AgentConfig(
                defaultModel = parentAgent.getCurrentModel(),
                systemPrompt = subSystemPrompt,
                budgetConfig = subBudgetConfig
            )
        )

        // 6. 构建任务消息（包含上下文文件）
        // 读取上下文文件内容并注入到任务描述中
        val taskMessage = buildString {
            appendLine(taskDescription)
            if (contextFiles.isNotEmpty()) {
                appendLine()
                appendLine("## Context Files")
                contextFiles.forEach { filePath ->
                    appendLine()
                    appendLine("### $filePath")
                    try {
                        val content = readContextFile(filePath)
                        if (content != null) {
                            appendLine("```")
                            // 限制单个文件注入大小，避免超出上下文限制
                            appendLine(content.take(8000))
                            if (content.length > 8000) {
                                appendLine("...[truncated, total ${content.length} chars]")
                            }
                            appendLine("```")
                        } else {
                            appendLine("[File not found]")
                        }
                    } catch (e: Exception) {
                        appendLine("[Error reading file: ${e.message}]")
                    }
                }
            }
        }

        // 7. 执行对话循环
        val outputBuilder = StringBuilder()
        val toolsUsed = mutableSetOf<String>()
        var iterationsUsed = 0
        var success = true

        try {
            progressCallback("[SubAgent $subSessionId] Starting task (depth=$depth, toolset=$toolset)...")

            subAgent.chatWithTools(taskMessage).collect { event ->
                when (event) {
                    is AgentStreamEvent.TextDelta -> {
                        outputBuilder.append(event.delta)
                    }

                    is AgentStreamEvent.ToolCallStart -> {
                        toolsUsed.add(event.toolCall.name)
                        iterationsUsed++
                        progressCallback("[SubAgent] Using tool: ${event.toolCall.name}")
                    }

                    is AgentStreamEvent.ToolCallResult -> {
                        progressCallback("[SubAgent] Tool ${event.toolName} ${if (event.success) "completed" else "failed"}")
                    }

                    is AgentStreamEvent.Thinking -> {
                        progressCallback("[SubAgent] ${event.message}")
                    }

                    is AgentStreamEvent.SubAgentStart -> {
                        // 子 Agent 自己也想 spawn 会被 MAX_RECURSION_DEPTH 拦截
                        progressCallback("[SubAgent nested depth=${depth + 1}] Starting: ${event.taskDescription}")
                    }

                    is AgentStreamEvent.SubAgentProgress -> {
                        progressCallback("[SubAgent nested depth=${depth + 1}] ${event.message}")
                    }

                    is AgentStreamEvent.SubAgentComplete -> {
                        progressCallback("[SubAgent nested depth=${depth + 1}] Completed: ${if (event.success) "success" else "failed"}")
                    }

                    is AgentStreamEvent.BudgetStatus -> {
                        progressCallback("[SubAgent] Budget: ${event.status}, remaining=${event.remainingIterations}")
                    }

                    is AgentStreamEvent.BudgetExhausted -> {
                        success = false
                        outputBuilder.appendLine("\n[BUDGET EXHAUSTED] ${event.reason}")
                        progressCallback("[SubAgent] Budget exhausted: ${event.reason}")
                    }

                    is AgentStreamEvent.BudgetExtended -> {
                        progressCallback("[SubAgent] Budget extended: +${event.extraIterations} iterations")
                    }

                    is AgentStreamEvent.Error -> {
                        success = false
                        outputBuilder.appendLine("\n[ERROR] ${event.message}")
                        progressCallback("[SubAgent] Error: ${event.message}")
                    }

                    AgentStreamEvent.Done -> {
                        progressCallback("[SubAgent] Task completed")
                    }

                    else -> {
                        // 新事件类型默认处理：忽略
                    }
                }
            }
        } catch (e: Exception) {
            success = false
            logger.error("SubAgent execution failed", e)
            outputBuilder.appendLine("\n[EXCEPTION] ${e.message}")
        }

        val result = SubAgentResult(
            success = success,
            output = outputBuilder.toString(),
            sessionId = subSessionId,
            iterationsUsed = iterationsUsed,
            toolsUsed = toolsUsed.toList()
        )

        logger.info("[SubAgent] Completed. Success=$success, Iterations=$iterationsUsed, Tools=${toolsUsed}, Depth=$depth")
        return result
    }

    /**
     * 并行执行多个子 Agent 任务（带并发限制，默认最多 3 个同时运行）
     */
    suspend fun spawnParallel(
        parentSessionId: String,
        tasks: List<SubTaskConfig>,
        maxConcurrency: Int = 3,
        progressCallback: suspend (taskIndex: Int, progress: String) -> Unit = { _, _ -> }
    ): List<SubAgentResult> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
        tasks.mapIndexed { index, config ->
            async {
                semaphore.withPermit {
                    spawn(
                        parentSessionId = parentSessionId,
                        taskDescription = config.description,
                        toolset = config.toolset,
                        maxIterations = config.maxIterations,
                        contextFiles = config.contextFiles,
                        progressCallback = { progress -> progressCallback(index, progress) }
                    )
                }
            }
        }.awaitAll()
    }

    /**
     * 读取上下文文件内容
     */
    private fun readContextFile(path: String): String? {
        val base = project?.basePath
        val resolvedPath = if (base != null && !File(path).isAbsolute) {
            File(base, path).canonicalPath
        } else {
            path
        }
        return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(
            com.intellij.openapi.util.Computable {
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
                    ?: return@Computable null
                if (virtualFile.isDirectory) {
                    null
                } else {
                    String(virtualFile.contentsToByteArray(), StandardCharsets.UTF_8)
                }
            }
        )
    }

    /**
     * 为指定 toolset 创建过滤后的工具注册表
     *
     * 工具集分类（基于当前 IDE 工具命名）：
     * - dev: 全部 IDE 工具（默认，开放所有）
     * - research: 只读类（read_file, search_code, grep_code, semantic_search 等）
     * - test: 测试相关（read_file, run_tests, exec_shell, list_directory 等）
     * - browser: 网络/浏览器类（http_request, web_scraper, clipboard 等）
     * - 其它/未识别: 退化为 dev（保留所有）
     *
     * 委托（delegate_task）、记忆（memory_*）始终保留，便于子 Agent 汇报和复用上下文。
     */
    private fun createToolRegistryForToolset(toolset: String): ToolRegistry {
        val registry = ToolRegistry.createDefault(project)

        val allowedNames: Set<String>? = when (toolset) {
            "dev" -> null  // null = 保留全部
            "research" -> RESEARCH_TOOLS + ALWAYS_AVAILABLE
            "test" -> TEST_TOOLS + ALWAYS_AVAILABLE
            "browser" -> BROWSER_TOOLS + ALWAYS_AVAILABLE
            else -> null  // 未知 toolset 退化为 dev
        }

        if (allowedNames != null) {
            val toRemove = registry.getAllTools()
                .map { it.name }
                .filter { it !in allowedNames }
            toRemove.forEach { name ->
                registry.unregister(name)
            }
            logger.info("[SubAgent] Toolset='$toolset' filtered out ${toRemove.size} tools; remaining=${registry.getAllTools().size}")
        }

        return registry
    }

    /**
     * 子任务配置
     */
    data class SubTaskConfig(
        val description: String,
        val toolset: String = "dev",
        val maxIterations: Int = 10,
        val contextFiles: List<String> = emptyList()
    )

    companion object {
        /**
         * 子 Agent 最大递归深度。
         *
         * 顶层 Agent (depth=0) 可 spawn 子 Agent (depth=1)；
         * 子 Agent (depth=1) 在尝试 spawn 孙子 Agent 时被拒绝（>=MAX）。
         * 即：parent → sub → sub-sub 这一层就被拦截。
         */
        const val MAX_RECURSION_DEPTH: Int = 2

        /**
         * 纯函数版：构造子 Agent 的 system prompt（不依赖实例状态），便于测试。
         */
        @JvmStatic
        fun buildSubAgentPrompt(
            taskDescription: String,
            toolset: String,
            parentPrompt: String,
            depth: Int
        ): String {
            val subAgentSection = buildString {
                appendLine()
                appendLine("---")
                appendLine()
                appendLine("## Sub-Agent Context (depth=$depth)")
                appendLine()
                appendLine("You are a **specialized sub-agent** spawned by a parent agent. The parent has delegated the following task to you:")
                appendLine()
                appendLine("> $taskDescription")
                appendLine()
                appendLine("Your operating rules:")
                appendLine("1. **Stay focused**: ONLY work on the task above. Do not deviate to other concerns.")
                appendLine("2. **Tool boundary**: You have access to the `$toolset` toolset. Do not attempt tools that are not in your toolset.")
                appendLine("3. **Report concisely**: The parent agent will read your output verbatim. Be specific about what you did, what you found, and any blockers.")
                appendLine("4. **No new delegation**: Do not spawn further sub-agents. If you need help, complete what you can and report blockers to the parent.")
                appendLine("5. **No new tasks**: Do not create or schedule follow-up work; the parent owns the task lifecycle.")
                appendLine("6. **Toolset: `$toolset`** — if a tool you need is missing, escalate to the parent instead of improvising.")
            }
            return parentPrompt + subAgentSection
        }

        /** 在所有 toolset 中都保留的工具（汇报、记忆、委托） */
        private val ALWAYS_AVAILABLE: Set<String> = setOf(
            "delegate_task",
            "memory_search", "memory_add", "memory_update"
        )

        /** 只读类：调研 / 信息收集 / 静态分析 */
        private val RESEARCH_TOOLS: Set<String> = setOf(
            "read_file", "read_multiple_files", "list_directory", "find_file",
            "grep_code", "search_code", "get_file_info", "get_project_structure",
            "analyze_symbol", "find_usages", "get_inheritance_chain",
            "semantic_search", "get_file_summary", "get_project_stats", "symbol_search",
            "http_request", "web_scraper", "parse_json", "format_json",
            "encode_base64", "decode_base64", "hash_md5", "hash_sha256",
            "timestamp", "uuid", "generate_doc", "analyze_dependencies"
        )

        /** 测试相关：阅读 + 运行 + 报告 */
        private val TEST_TOOLS: Set<String> = setOf(
            "read_file", "read_multiple_files", "list_directory", "find_file",
            "grep_code", "search_code", "get_file_info", "get_project_structure",
            "run_tests", "exec_shell", "run_command",
            "get_file_summary", "analyze_symbol", "semantic_search",
            "parse_json", "format_json", "timestamp"
        )

        /** 浏览器 / 网络类 */
        private val BROWSER_TOOLS: Set<String> = setOf(
            "http_request", "web_scraper", "clipboard",
            "parse_json", "format_json", "encode_base64", "decode_base64",
            "hash_md5", "hash_sha256", "timestamp", "uuid"
        )
    }
}
