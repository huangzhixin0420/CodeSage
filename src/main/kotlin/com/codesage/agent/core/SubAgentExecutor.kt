package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.memory.MemoryManager
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.persistence.ConversationPersistence
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
import java.nio.file.Files
import java.util.UUID

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
 * - 独立 session
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
     * 设计原则（v2）：**子 Agent 不再继承主 Agent 的 system prompt**。
     * 子 Agent 应该拥有完全独立的最小化 system prompt：
     * - 自己的角色定位
     * - 任务描述
     * - 可用工具集
     * - 操作规则（专注、汇报、不再委托、不创建新任务、范围外不动文件）
     * - 输出格式
     * - 递归深度限制
     *
     * 这样设计的好处：
     * 1. 避免主 Agent 的大 prompt（通常 50-200KB，含完整工具说明、IDE 上下文等）
     *    在多级 spawn 时累积膨胀，触发 LLM API 400 / 2013 错误。
     * 2. 子 Agent 是"专家"而非"通才"——主 Agent 知道的所有项目背景对子 Agent
     *    完成单点任务没有帮助，反而稀释信号噪声。
     * 3. 真正实现"父 Agent 只关心子 Agent 的结论，不在乎过程"。
     */
    private fun generateSubAgentPrompt(
        taskDescription: String,
        toolset: String
    ): String = buildSubAgentPrompt(taskDescription, toolset, depth)

    /**
     * Spawn 一个隔离的子 Agent 执行任务
     *
     * @param parentSessionId 父会话 ID
     * @param taskDescription 任务描述
     * @param toolset 工具集名称（dev, research, test, browser）
     * @param maxIterations 子 Agent 的迭代预算
     * @param contextFiles 子 Agent 需要访问的文件列表
     * @param progressCallback 进度回调（实时汇报给父 agent）
     */
    open suspend fun spawn(
        parentSessionId: String,
        taskDescription: String,
        toolset: String = "dev",
        maxIterations: Int = 10,
        contextFiles: List<String> = emptyList(),
        progressCallback: suspend (String) -> Unit = {},
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

        // 3. 构造子 Agent 的独立 system prompt（不再继承主 Agent 的 prompt）
        val subSystemPrompt = generateSubAgentPrompt(taskDescription, toolset)
        // 日志：记录子 Agent 独立 prompt 的体积。历史曾因继承主 Agent 大 prompt
        // 累积到 152KB+ 触发 MiniMax 2013 错误，refactor 后 prompt 应 < 2KB。
        logger.info(
            "[SubAgent] prompt size | " +
                    "subSystemPrompt=${subSystemPrompt.length}B " +
                    "(independent, no parent inheritance; toolset=$toolset, depth=$depth)"
        )

        // 4. 为子 Agent 创建独立的 tmp 持久化目录
        //    - 即便 skipRestore/skipAutoSave=true 让它永远不被读写，
        //      也作为防御层：万一未来 P0 修复失效，子 Agent 最多写到自己的 tmp
        //    - spawn 结束时在 finally 块 deleteRecursively()
        val subPersistenceDir: File = Files.createTempDirectory(
            "codesage_subagent_${UUID.randomUUID()}_"
        ).toFile()
        val subPersistence = ConversationPersistence(subPersistenceDir)
        var subAgent: AgentCore? = null
        try {
            // 4a. 创建子 AgentCore（注入独立 persistence + 过滤后的 registry + 子深度）
            subAgent = AgentCore(
                gateway = gateway,
                project = project,
                skillToolAdapter = skillToolAdapter,
                toolRegistryOverride = subToolRegistry,
                subAgentDepth = depth + 1,
                conversationPersistenceOverride = subPersistence
            )

            // 4b. 配置子 Agent（传入自定义 prompt + 跳过 restore/autoSave）
            subAgent.initialize(
                AgentConfig(
                    defaultModel = parentAgent.getCurrentModel(),
                    systemPrompt = subSystemPrompt
                ),
                skipRestore = true,
                skipAutoSave = true
            )

            // 5. 构建任务消息（包含上下文文件）
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

            // 6. 执行对话循环
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
                            progressCallback("[SubAgent nested depth=${depth + 1}] Starting: ${event.taskDescription}")
                        }

                        is AgentStreamEvent.SubAgentProgress -> {
                            progressCallback("[SubAgent nested depth=${depth + 1}] ${event.message}")
                        }

                        is AgentStreamEvent.SubAgentComplete -> {
                            progressCallback("[SubAgent nested depth=${depth + 1}] Completed: ${if (event.success) "success" else "failed"}")
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

            logger.info("[SubAgent] Completed. Success=$success, Tools=${toolsUsed}, Depth=$depth")
            return result
        } finally {
            // 不管成功 / 异常 / 早退，都清理子 Agent 的 tmp 持久化目录
            try {
                val deleted = subPersistenceDir.deleteRecursively()
                if (!deleted) {
                    logger.warn("[SubAgent] Failed to delete tmp persistence dir: $subPersistenceDir")
                }
            } catch (e: Exception) {
                logger.warn("[SubAgent] Error cleaning up tmp persistence dir: ${e.message}")
            }
        }
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
         *
         * v2 重构：**不再继承主 Agent 的 prompt**。子 Agent 拥有自己的最小化
         * system prompt（~1.2KB），专注于"我是谁、要做什么、有什么工具、输出什么"。
         */
        @JvmStatic
        fun buildSubAgentPrompt(
            taskDescription: String,
            toolset: String,
            depth: Int
        ): String = buildString {
            appendLine("You are a specialized sub-agent in the CodeSage multi-agent system.")
            appendLine()
            appendLine("## Task")
            appendLine()
            appendLine(taskDescription)
            appendLine()
            appendLine("## Tools")
            appendLine()
            appendLine("You have access to the `$toolset` toolset only. Tool names, descriptions, and parameter schemas are passed separately in the request. Do not attempt to use tools outside this set.")
            appendLine()
            appendLine("## Operating Rules")
            appendLine()
            appendLine("1. **Focus**: ONLY work on the task above. Do not deviate to other concerns.")
            appendLine("2. **Tools**: Use only tools in your `$toolset` toolset.")
            appendLine("3. **Report concisely**: When done, return a clear summary of what you did, what you found, and any blockers.")
            appendLine("4. **No delegation**: Do not spawn further sub-agents. If you need help, complete what you can and report blockers.")
            appendLine("5. **No new tasks**: Do not create or schedule follow-up work; the parent owns the task lifecycle.")
            appendLine("6. **No side effects outside scope**: Modify only files explicitly mentioned in the task.")
            appendLine()
            appendLine("## Output Format")
            appendLine()
            appendLine("Return a concise result describing:")
            appendLine("- What you accomplished")
            appendLine("- Any files you created or modified")
            appendLine("- Any blockers or issues (if any)")
            appendLine()
            appendLine("## Recursion")
            appendLine()
            appendLine("You are at depth=$depth of max=$MAX_RECURSION_DEPTH. Further delegation is not allowed.")
        }.trimEnd()

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
