package com.codesage.agent.core

import com.codesage.agent.context.ContextManager
import com.codesage.agent.memory.MemoryManager
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import com.codesage.persistence.ConversationPersistence
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
 * 单次 tool call 的"已完成"摘要（P2: 给取消场景用）。
 *
 * 仅携带"摘要级别"信息（工具名 + 关键参数 path / pattern / command + 结果长度 + 成功状态），
 * **不**带 content 全文 — 避免给父 LLM 注入大量 token。
 *
 * 父 LLM 看到这份列表后能知道：
 * - 子 Agent 改过 / 读过的文件
 * - 子 Agent 用过的工具谱
 * - 哪些成功了、哪些失败了
 */
data class ToolCallRecord(
    val name: String,
    /** 关键参数摘要（path / pattern / command 等），fallback 截断 200 字符 */
    val argSummary: String,
    /** 结果长度（字节） */
    val resultLength: Int,
    val success: Boolean
)

/**
 * 子 Agent 执行结果
 *
 * P2 扩展：当 [cancelled]=true 时，父 LLM 看到 [output] 里有 "Cancelled by user." marker，
 * 应停止 retry 并询问用户下一步（见 buildSubAgentPrompt 的 Cancellation Semantics）。
 * [completedToolCalls] 让父 LLM 知道子 Agent 在被取消前已经动过哪些文件 / 读过什么。
 */
data class SubAgentResult(
    val success: Boolean,
    val output: String,
    val sessionId: String,
    val iterationsUsed: Int,
    val toolsUsed: List<String>,
    /** P2: 用户是否在执行途中 cancel 了子 Agent */
    val cancelled: Boolean = false,
    /** P2: 取消前已完成的 tool call 摘要（cancelled=true 时才有意义） */
    val completedToolCalls: List<ToolCallRecord> = emptyList(),
    /** 6.6.3: worktree 隔离模式下产生的原始 diff */
    val worktreeDiff: String? = null,
    /** 6.6.3: worktree 隔离模式下产生的结构化 diff */
    val worktreeChanges: JsonObject? = null
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
 * 超过 [maxDepth] 时拒绝继续 spawn。
 *
 * @property maxDepth 本次子 Agent 允许的最大递归深度（默认 [DEFAULT_MAX_RECURSION_DEPTH]）。
 *                   可由调用方按任务指定，有效范围为 [1, 5]。
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
    private val depth: Int = 0,
    /**
     * 6.10.2: 本次子 Agent 允许的最大递归深度，默认 2。
     * 允许按任务在 [1, 5] 范围内覆盖。
     */
    private val maxDepth: Int = DEFAULT_MAX_RECURSION_DEPTH
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
        toolset: String,
        allowedTools: List<String>,
        deniedTools: List<String>,
        availableToolNames: List<String>
    ): String = buildSubAgentPrompt(
        taskDescription = taskDescription,
        toolset = toolset,
        depth = depth,
        maxDepth = maxDepth,
        allowedTools = allowedTools,
        deniedTools = deniedTools,
        availableToolNames = availableToolNames
    )

    /**
     * Spawn 一个隔离的子 Agent 执行任务
     *
     * @param parentSessionId 父会话 ID
     * @param taskDescription 任务描述
     * @param toolset 工具集名称（coder/explorer/verifier/webfetcher 或旧别名 dev/research/test/browser）
     * @param contextFiles 子 Agent 需要访问的文件列表
     * @param progressCallback 进度回调（实时汇报给父 agent）
     * @param maxDepth 6.10.2: 本次任务允许的最大递归深度，范围 [1, 5]，默认 [DEFAULT_MAX_RECURSION_DEPTH]。
     *                 若越界则直接返回错误，不创建子 Agent。
     * @param allowedTools 6.10.3: 显式白名单，非空时子 Agent 只能使用列表中的工具。
     * @param deniedTools 6.10.3: 黑名单，优先于 allowedTools。若显式包含 `delegate_task`，
     *                    则返回错误（子 Agent 被禁止再委托）。
     */
    open suspend fun spawn(
        parentSessionId: String,
        taskDescription: String,
        toolset: String = "dev",
        contextFiles: List<String> = emptyList(),
        progressCallback: suspend (String) -> Unit = {},
        /**
         * P2: 父协程的 Job 句柄。子 Agent 用来：
         * - 父 cancel 时立刻感知（结构化并发）
         * - catch CancellationException 后构造 cancelled summary 回灌父 LLM
         * 可选：老调用方传 null 即可（行为不变）。
         */
        parentJob: Job? = null,
        /**
         * 外部预先生成的 subSessionId（用于在 [EnhancedAgentLoop.executeDelegateTask]
         * 里把 SubAgentStart / SubAgentProgress / SubAgentComplete 三个事件的
         * sessionId 统一成一个串 — 修复 UI EventRouter 因为 sessionId 漂移导致
         * task / toolset / elapsedMs 全为空的 bug）。null = 内部自生成（保持
         * [spawnParallel] 等老调用方行为不变）。
         */
        subSessionIdOverride: String? = null,
        /**
         * 6.6.3: 是否在独立 git worktree 中运行子 Agent。为 true 时会在主项目
         * 仓库外创建 worktree，子 Agent 的 project basePath 指向 worktree，
         * 执行完成后自动收集 worktree diff 作为结果的一部分并清理 worktree。
         */
        isolatedWorktree: Boolean = false,
        /**
         * 6.10.2: 本次任务允许的最大递归深度，范围 [1, 5]，默认 [DEFAULT_MAX_RECURSION_DEPTH]。
         */
        maxDepth: Int = this.maxDepth,
        /**
         * 6.10.3: 显式工具白名单。
         */
        allowedTools: List<String> = emptyList(),
        /**
         * 6.10.3: 显式工具黑名单。
         */
        deniedTools: List<String> = emptyList(),
    ): SubAgentResult {
        // 0. maxDepth 范围校验
        if (maxDepth < 1 || maxDepth > 5) {
            val msg = "Invalid max_depth=$maxDepth; must be between 1 and 5 inclusive"
            logger.warn("[SubAgent] $msg")
            progressCallback("[SubAgent] $msg")
            return SubAgentResult(
                success = false,
                output = msg,
                sessionId = "invalid_max_depth_${System.currentTimeMillis()}",
                iterationsUsed = 0,
                toolsUsed = emptyList()
            )
        }

        // 1. 递归深度检查
        if (depth >= maxDepth) {
            val msg =
                "Max sub-agent recursion depth ($maxDepth) reached at depth=$depth; refusing to spawn further sub-agents"
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

        logger.info("[SubAgent] Spawning for task: ${taskDescription.take(80)} (depth=$depth, maxDepth=$maxDepth)")

        val subSessionId = subSessionIdOverride
            ?: "sub_${parentSessionId}_${System.currentTimeMillis()}"
        if (subSessionIdOverride != null) {
            logger.info("[SubAgent] using caller-supplied subSessionId=$subSessionId (events will share this id)")
        }

        // 2. 按 toolset + allow/deny 过滤工具集
        val toolFilterResult = createToolRegistryForToolset(
            toolset = toolset,
            projectOverride = project,
            allowedTools = allowedTools,
            deniedTools = deniedTools
        )
        if (toolFilterResult.isFailure) {
            val msg = toolFilterResult.exceptionOrNull()?.message
                ?: "Toolset filter failed"
            logger.warn("[SubAgent] $msg")
            progressCallback("[SubAgent] $msg")
            return SubAgentResult(
                success = false,
                output = msg,
                sessionId = subSessionId,
                iterationsUsed = 0,
                toolsUsed = emptyList()
            )
        }
        val subToolRegistry = toolFilterResult.getOrThrow()
        val availableToolNames = subToolRegistry.getAllTools().map { it.name }
        val toolCount = availableToolNames.size
        logger.info("[SubAgent] Toolset='$toolset' → $toolCount tools available (allowed=$allowedTools, denied=$deniedTools)")

        // 3. 6.6.3: 可选 worktree 隔离
        var worktreeInfo: WorktreeIsolation.WorktreeInfo? = null
        val effectiveProject = if (isolatedWorktree) {
            val repoRoot = project?.basePath
            if (repoRoot == null) {
                val msg = "isolated_worktree=true requires a project with basePath"
                logger.warn("[SubAgent] $msg")
                progressCallback("[SubAgent] $msg")
                return SubAgentResult(
                    success = false,
                    output = msg,
                    sessionId = subSessionId,
                    iterationsUsed = 0,
                    toolsUsed = emptyList()
                )
            }
            worktreeInfo = WorktreeIsolation.createWorktree(repoRoot, subSessionId)
            ProjectProxy.create(project, worktreeInfo.worktreePath)
        } else {
            project
        }

        // 4. 构造子 Agent 的独立 system prompt（不再继承主 Agent 的 prompt）
        val subSystemPrompt = generateSubAgentPrompt(
            taskDescription = taskDescription,
            toolset = toolset,
            allowedTools = allowedTools,
            deniedTools = deniedTools,
            availableToolNames = availableToolNames
        )
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
                project = effectiveProject,
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
                            val content = readContextFile(filePath, effectiveProject)
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
            // outputBuilder = 全量文本（仅用于 fallback 诊断，UI 不展示）
            // currentTurnText = 最后一个 turn 的文本（tool call 之间的内容），
            //                    循环结束后持有"最终 turn" = 子 Agent 的摘要
            // completedToolCalls = P2: 记录每个 tool call 的摘要（用于取消时回灌父 LLM）
            val outputBuilder = StringBuilder()
            var currentTurnText = StringBuilder()
            val toolsUsed = mutableSetOf<String>()
            val completedToolCalls = mutableListOf<ToolCallRecord>()

            /** 记录 in-flight tool 的关键参数（ToolCallStart 时抽，到 ToolCallResult 时合并） */
            val inflightToolArgs = mutableMapOf<String, String>()
            var iterationsUsed = 0
            var success = true
            var cancelled = false

            try {
                progressCallback("[SubAgent $subSessionId] Starting task (depth=$depth, toolset=$toolset)...")

                subAgent.chatWithTools(taskMessage).collect { event ->
                    when (event) {
                        is AgentStreamEvent.TextDelta -> {
                            currentTurnText.append(event.delta)
                            outputBuilder.append(event.delta)
                        }

                        is AgentStreamEvent.ToolCallStart -> {
                            // 新一轮 tool call 开始 = 之前的文本是"中间思考"，
                            // 清空 currentTurnText 让它只持有从现在起的最终 turn 文本
                            currentTurnText = StringBuilder()
                            toolsUsed.add(event.toolCall.name)
                            iterationsUsed++
                            // P2: 提前抽关键参数到 inflight map（ToolCallResult 时合并）
                            val summary = extractToolCallArgSummary(
                                event.toolCall.name,
                                event.toolCall.arguments
                            )
                            inflightToolArgs[event.toolCall.id] = summary
                            progressCallback("[SubAgent] Using tool: ${event.toolCall.name}")
                        }

                        is AgentStreamEvent.ToolCallResult -> {
                            // P2: tool 调用完成，记录到 completedToolCalls 供取消时回灌
                            val argSummary = inflightToolArgs.remove(event.toolCallId) ?: ""
                            completedToolCalls.add(
                                ToolCallRecord(
                                    name = event.toolName,
                                    argSummary = argSummary,
                                    resultLength = event.result.length,
                                    success = event.success
                                )
                            )
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
            } catch (e: CancellationException) {
                // P2: 用户 cancel 了父协程 — 立即停止当前 turn，**不**重抛，
                // 把已完成的 tool calls + 最后一段文本组成 cancelled summary，
                // 让父 LLM 知道"子 agent 改过什么 / 读了什么"，
                // 但同时让父 LLM 别自动 retry（见 buildSubAgentPrompt）。
                cancelled = true
                success = false
                logger.info(
                    "[SubAgent] Cancelled by parent job. " +
                            "Completed tool calls=${completedToolCalls.size}, " +
                            "iterationsUsed=$iterationsUsed"
                )
                // 注意：不 appendLine，避免破坏 extractCancelledSummary 的解析
            } catch (e: Exception) {
                success = false
                logger.error("SubAgent execution failed", e)
                outputBuilder.appendLine("\n[EXCEPTION] ${e.message}")
            }

            // 最终 turn = currentTurnText（循环结束时没新 tool call，持有的就是最后一段）
            // 兜底逻辑委托给纯函数 extractFinalTurnSummary，便于单测
            val output = if (cancelled) {
                extractCancelledSummary(
                    lastAssistantText = currentTurnText.toString(),
                    allText = outputBuilder.toString(),
                    completedToolCalls = completedToolCalls,
                    logger = logger
                )
            } else {
                extractFinalTurnSummary(
                    finalTurnText = currentTurnText.toString(),
                    allText = outputBuilder.toString(),
                    iterationsUsed = iterationsUsed,
                    logger = logger
                )
            }

            // 6.6.3: worktree 隔离模式下收集 diff
            val worktreeDiff = worktreeInfo?.let {
                try {
                    WorktreeIsolation.collectDiff(it)
                } catch (e: Exception) {
                    logger.warn("[SubAgent] Failed to collect worktree diff: ${e.message}")
                    null
                }
            }

            val result = SubAgentResult(
                success = success,
                output = output,
                sessionId = subSessionId,
                iterationsUsed = iterationsUsed,
                toolsUsed = toolsUsed.toList(),
                cancelled = cancelled,
                completedToolCalls = completedToolCalls.toList(),
                worktreeDiff = worktreeDiff?.rawDiff,
                worktreeChanges = worktreeDiff?.structuredDiff
            )

            logger.info(
                "[SubAgent] Completed. Success=$success, Tools=${toolsUsed}, Depth=$depth, " +
                        "worktree=${worktreeInfo != null}, hasDiff=${worktreeDiff?.rawDiff?.isNotBlank() == true}"
            )
            return result
        } finally {
            // 2026-06 修复: 之前直接 deleteRecursively() 会与子 Agent 的异步 saveSession 抢目录 —
            // 子 Agent 触发的 saveSession 是扔进 ioExecutor 队列的异步任务, 跑到这里时
            // tmp dir 已被删, 写 .tmp 文件就会 FileNotFoundException (整个对话丢失)。
            //
            // 修复策略:
            //   1. 先 awaitInFlightWrites() 等异步写入完成 (5s 超时)
            //   2. 再 shutdown() 拒收新任务
            //   3. 最后 deleteRecursively() 清目录
            //
            // writeAtomically 自身也有 mkdirs 兜底, 所以即便超时也只是日志告警, 不会丢数据。
            try {
                val drained = subPersistence.awaitInFlightWrites(timeoutMs = 5000L)
                if (drained > 0) {
                    logger.info("[SubAgent] Drained $drained in-flight persistence writes before cleanup")
                }
                subPersistence.shutdown()
            } catch (e: Exception) {
                logger.warn("[SubAgent] Error draining persistence before cleanup: ${e.message}")
            }
            try {
                val deleted = subPersistenceDir.deleteRecursively()
                if (!deleted) {
                    logger.warn("[SubAgent] Failed to delete tmp persistence dir: $subPersistenceDir")
                }
            } catch (e: Exception) {
                logger.warn("[SubAgent] Error cleaning up tmp persistence dir: ${e.message}")
            }

            // 6.6.3: 清理 worktree（在 persistence 清理之后，确保子 Agent 写入已落盘）
            worktreeInfo?.let {
                try {
                    WorktreeIsolation.cleanup(it)
                } catch (e: Exception) {
                    logger.warn("[SubAgent] Error cleaning up worktree: ${e.message}")
                }
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
                        contextFiles = config.contextFiles,
                        progressCallback = { progress -> progressCallback(index, progress) },
                        isolatedWorktree = config.isolatedWorktree,
                        maxDepth = config.maxDepth,
                        allowedTools = config.allowedTools,
                        deniedTools = config.deniedTools
                    )
                }
            }
        }.awaitAll()
    }

    /**
     * 读取上下文文件内容
     */
    private fun readContextFile(path: String, projectOverride: Project? = null): String? {
        val base = projectOverride?.basePath ?: project?.basePath
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
     * 工具集命名（**P3 重构**：意图导向，对齐 Claude Code Task tool）：
     * - coder:    写代码 / 改文件（之前的 dev）
     * - explorer: 调研 / 搜索 / 阅读（之前的 research）
     * - verifier: 跑测试 / 执行命令 / 验证（之前的 test）
     * - webfetcher: 网络 / 浏览器（之前的 browser）
     *
     * 旧名 alias（**保留**：老 LLM prompt / 用户习惯用旧名不立即破坏）：
     * - dev / research / test / browser
     * - 用 WARN 日志引导迁移到新名
     *
     * 6.10.3 扩展：
     * - 先按 [toolset] 做原有过滤；
     * - 若 [allowedTools] 非空，再取交集；
     * - 最后移除 [deniedTools] 中的工具；
     * - 若过滤后工具集为空，返回 [Result.failure]；
     * - 若 [deniedTools] 显式包含 `delegate_task`，返回 [Result.failure]
     *   （子 Agent 被禁止再委托）。
     *
     * 委托（delegate_task）在 toolset 过滤阶段始终保留，便于子 Agent 汇报；
     * 记忆（memory_*）工具由 [AgentCore.initialize] 后续注入，不在本注册表内。
     *
     * @return 过滤后的 [ToolRegistry] 或包含错误信息的 [Result.failure]。
     */
    private fun createToolRegistryForToolset(
        toolset: String,
        projectOverride: Project? = null,
        allowedTools: List<String> = emptyList(),
        deniedTools: List<String> = emptyList()
    ): Result<ToolRegistry> {
        val resolved = resolveToolsetAlias(toolset)
        val registry = ToolRegistry.createDefault(projectOverride ?: project)

        val baseAllowedNames: Set<String>? = when (resolved) {
            "coder" -> null  // 全部 IDE 工具（默认，开放所有）
            "explorer" -> RESEARCH_TOOLS + ALWAYS_AVAILABLE
            "verifier" -> TEST_TOOLS + ALWAYS_AVAILABLE
            "webfetcher" -> BROWSER_TOOLS + ALWAYS_AVAILABLE
            else -> null  // 未知 toolset 退化为 coder（保留所有）
        }

        if (baseAllowedNames != null) {
            val toRemove = registry.getAllTools()
                .map { it.name }
                .filter { it !in baseAllowedNames }
            toRemove.forEach { name ->
                registry.unregister(name)
            }
            logger.info("[SubAgent] Toolset='$toolset' (resolved='$resolved') filtered out ${toRemove.size} tools; remaining=${registry.getAllTools().size}")
        }

        // 6.10.3: allowed_tools 白名单再取交集。
        // 始终保留 ALWAYS_AVAILABLE（delegate_task / memory_*），保证子 Agent 能汇报和继续委托，
        // 除非后续 denied_tools 显式拒绝它们。
        if (allowedTools.isNotEmpty()) {
            val allowedSet = allowedTools.toSet() + ALWAYS_AVAILABLE
            val toRemove = registry.getAllTools()
                .map { it.name }
                .filter { it !in allowedSet }
            toRemove.forEach { name -> registry.unregister(name) }
            logger.info("[SubAgent] allowed_tools intersection removed ${toRemove.size} tools; remaining=${registry.getAllTools().size}")
        }

        // 6.10.3: denied_tools 黑名单最后应用
        if (deniedTools.isNotEmpty()) {
            val deniedSet = deniedTools.toSet()
            val toRemove = registry.getAllTools()
                .map { it.name }
                .filter { it in deniedSet }
            toRemove.forEach { name -> registry.unregister(name) }
            logger.info("[SubAgent] denied_tools removed ${toRemove.size} tools; remaining=${registry.getAllTools().size}")

            if ("delegate_task" in deniedSet) {
                return Result.failure(
                    IllegalArgumentException(
                        "子 Agent 被禁止再委托：denied_tools 显式包含 delegate_task"
                    )
                )
            }
        }

        if (registry.getAllTools().isEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "Toolset filter resulted in an empty toolset (toolset='$toolset', allowedTools=$allowedTools, deniedTools=$deniedTools)"
                )
            )
        }

        return Result.success(registry)
    }

    /**
     * 旧名 alias 解析 + 迁移 WARN。
     *
     * - `coder / explorer / verifier / webfetcher` → 原样返回
     * - `dev → coder`, `research → explorer`, `test → verifier`, `browser → webfetcher`
     *   → 触发 WARN 日志引导迁移
     * - 其它 → 原样返回（让外层 `else -> null` 退化为 coder）
     */
    private fun resolveToolsetAlias(toolset: String): String {
        val newName = TOOLSET_ALIAS[toolset]
        return if (newName != null) {
            logger.warn(
                "[SubAgent] Toolset alias deprecated: '$toolset' → '$newName'. " +
                        "Update your delegate_task call to use the new name."
            )
            newName
        } else {
            toolset
        }
    }

    /**
     * 子任务配置
     *
     * @property description 任务描述
     * @property toolset 工具集名称
     * @property contextFiles 子 Agent 需要访问的文件列表
     * @property isolatedWorktree 6.6.3: 是否在独立 git worktree 中运行
     * @property maxDepth 6.10.2: 最大递归深度，默认 [DEFAULT_MAX_RECURSION_DEPTH]
     * @property allowedTools 6.10.3: 显式工具白名单
     * @property deniedTools 6.10.3: 显式工具黑名单
     */
    data class SubTaskConfig(
        val description: String,
        val toolset: String = "dev",
        val contextFiles: List<String> = emptyList(),
        /** 6.6.3: 是否在独立 git worktree 中运行 */
        val isolatedWorktree: Boolean = false,
        /** 6.10.2: 本次任务允许的最大递归深度 */
        val maxDepth: Int = DEFAULT_MAX_RECURSION_DEPTH,
        /** 6.10.3: 显式工具白名单 */
        val allowedTools: List<String> = emptyList(),
        /** 6.10.3: 显式工具黑名单 */
        val deniedTools: List<String> = emptyList()
    )

    companion object {
        /**
         * 子 Agent 默认最大递归深度。
         *
         * 顶层 Agent (depth=0) 可 spawn 子 Agent (depth=1)；
         * 子 Agent (depth=1) 在尝试 spawn 孙子 Agent 时被拒绝（>=默认深度）。
         * 即：parent → sub → sub-sub 这一层就被拦截。
         *
         * 可通过 [SubAgentExecutor.maxDepth] 或 [SubTaskConfig.maxDepth] / `delegate_task.max_depth`
         * 在 [1, 5] 范围内按任务覆盖。
         */
        const val DEFAULT_MAX_RECURSION_DEPTH: Int = 2

        /**
         * 纯函数版：提取子 Agent 的"最终 turn"输出文本。
         *
         * 语义：
         * - 子 Agent 的 loop 会 emit 多个 turn 的 text（中间思考 + 最终摘要）
         * - 父 LLM 只看最终 turn（见 buildSubAgentPrompt 的 Final-Turn Output Contract）
         * - 本函数把 [finalTurnText]（最后一个 turn 的累积文本）作为首选
         *   兜底：当 finalTurnText 为空时（子 agent 没写摘要，最后一个 turn 又调了 tool），
         *   退化到 [allText]（所有 turn 的全量文本）+ warn 日志
         *   再兜底：都没内容时返回 "(sub-agent produced no output)"
         *
         * 抽成纯函数（不依赖实例）方便单测覆盖各种事件序列。
         */
        @JvmStatic
        fun extractFinalTurnSummary(
            finalTurnText: String,
            allText: String,
            iterationsUsed: Int,
            logger: com.intellij.openapi.diagnostic.Logger
        ): String = when {
            finalTurnText.isNotBlank() -> finalTurnText
            allText.isNotBlank() -> {
                logger.warn(
                    "[SubAgent] final turn was empty after ${iterationsUsed} iterations; " +
                            "falling back to all accumulated text (length=${allText.length}). " +
                            "Sub-agent did not honor the Final-Turn Output Contract."
                )
                allText
            }

            else -> "(sub-agent produced no output)"
        }

        /**
         * P2 纯函数：从 tool call 的 arguments JSON 抽出"摘要级别"的关键参数。
         *
         * 按 tool 名走白名单字段（path / pattern / command / query / url），
         * 这样父 LLM 看到 "Cancelled by user. Partial work completed" 后能
         * 直接知道"子 agent 改过 / 读过哪些文件 / 跑过哪些命令"。
         *
         * @param toolName 工具名（e.g. "read_file", "write_file"）
         * @param argumentsJson 工具调用的 arguments JSON 字符串
         * @return 摘要字符串（"path: /Users/leo/foo.kt" 这种形式），无关键字段时
         *         截断 argumentsJson 前 200 字符
         */
        @JvmStatic
        fun extractToolCallArgSummary(toolName: String, argumentsJson: String): String {
            val keyFields = when (toolName) {
                "read_file", "write_file", "edit_file", "delete_file", "find_file",
                "get_file_info", "get_file_summary", "find_usages", "analyze_symbol",
                "get_inheritance_chain" -> listOf("path", "file_path")

                "grep_code", "search_code", "semantic_search", "symbol_search" -> listOf(
                    "pattern", "query", "path", "directory"
                )

                "run_command", "run_tests", "exec_shell" -> listOf("command", "cmd", "shell_command")
                "http_request", "web_scraper" -> listOf("url", "uri")
                "list_directory" -> listOf("path", "directory")
                "read_multiple_files" -> listOf("paths", "files")
                else -> emptyList()
            }

            if (keyFields.isEmpty() || argumentsJson.isBlank()) {
                return argumentsJson.take(200)
            }

            return try {
                val element = kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson)
                val obj = element as? kotlinx.serialization.json.JsonObject
                val parts = mutableListOf<String>()
                if (obj != null) {
                    for (field in keyFields) {
                        val v = obj[field] ?: continue
                        val s = when (v) {
                            is kotlinx.serialization.json.JsonPrimitive -> v.content
                            else -> v.toString()
                        }
                        if (s.isNotBlank()) {
                            parts.add("$field: $s")
                        }
                    }
                }
                if (parts.isEmpty()) {
                    argumentsJson.take(200)
                } else {
                    parts.joinToString(", ")
                }
            } catch (e: Exception) {
                argumentsJson.take(200)
            }
        }

        /**
         * P2 纯函数：构造"用户取消了子 agent"场景下的回灌文本。
         *
         * **关键设计**：第一行必须是 "Cancelled by user." marker —
         * 父 LLM 的 prompt 里有明确的"看到 marker 别自动 retry"指令
         * （见 buildSubAgentPrompt 的 Cancellation Semantics 段）。
         *
         * 输出格式：
         * ```
         * Cancelled by user. Partial work completed:
         *
         * **Tool calls completed** (3):
         * - ✓ `read_file` (1240B): /Users/leo/Code/foo.kt
         * - ✓ `write_file` (23B): /Users/leo/Code/bar.kt
         * - ✗ `run_command` (0B): command: ls -la
         *
         * **Last assistant text**:
         * I refactored AuthService. Need to also update tests.
         * ```
         *
         * @param lastAssistantText 子 agent 最后一个 turn 的纯文本（中间思考 / 最终摘要）
         * @param allText 全量文本（兜底 — 当 lastAssistantText 为空时退化）
         * @param completedToolCalls 已完成的 tool call 列表
         * @param logger 日志
         */
        @JvmStatic
        fun extractCancelledSummary(
            lastAssistantText: String,
            allText: String,
            completedToolCalls: List<ToolCallRecord>,
            logger: com.intellij.openapi.diagnostic.Logger
        ): String = buildString {
            appendLine("Cancelled by user. Partial work completed:")
            appendLine()

            // 段 1：tool calls 摘要
            if (completedToolCalls.isNotEmpty()) {
                appendLine("**Tool calls completed** (${completedToolCalls.size}):")
                completedToolCalls.forEach { tc ->
                    val mark = if (tc.success) "✓" else "✗"
                    val summary = if (tc.argSummary.isBlank()) "" else ": ${tc.argSummary}"
                    appendLine("- $mark `${tc.name}` (${tc.resultLength}B)$summary")
                }
                appendLine()
            } else {
                logger.info("[SubAgent] cancelled before any tool call completed")
                appendLine("**Tool calls completed** (0)")
                appendLine()
            }

            // 段 2：最后一段 assistant 文本（兜底用 allText）
            val text = lastAssistantText.ifBlank { allText }
            if (text.isNotBlank()) {
                appendLine("**Last assistant text**:")
                appendLine(text.take(2000))
            }
        }.trimEnd()

        /**
         * 纯函数版：构造子 Agent 的 system prompt（不依赖实例状态），便于测试。
         *
         * v2 重构：**不再继承主 Agent 的 prompt**。子 Agent 拥有自己的最小化
         * system prompt（~1.2KB），专注于"我是谁、要做什么、有什么工具、输出什么"。
         *
         * 6.10.2/6.10.3 扩展：支持动态 [maxDepth]、白名单 [allowedTools]、黑名单 [deniedTools]
         * 以及实际可用工具名 [availableToolNames] 的注入。
         *
         * @param taskDescription 任务描述
         * @param toolset 工具集名称
         * @param depth 当前深度
         * @param maxDepth 允许的最大递归深度
         * @param allowedTools 显式白名单（空表示未限制）
         * @param deniedTools 显式黑名单（空表示未限制）
         * @param availableToolNames 实际可用工具名列表，用于提示模型
         */
        @JvmStatic
        fun buildSubAgentPrompt(
            taskDescription: String,
            toolset: String,
            depth: Int,
            maxDepth: Int = DEFAULT_MAX_RECURSION_DEPTH,
            allowedTools: List<String> = emptyList(),
            deniedTools: List<String> = emptyList(),
            availableToolNames: List<String> = emptyList()
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
            if (availableToolNames.isNotEmpty()) {
                appendLine()
                appendLine("Available tools: ${availableToolNames.sorted().joinToString(", ")}")
            }
            if (allowedTools.isNotEmpty()) {
                appendLine()
                appendLine("Allowed tools (whitelist): ${allowedTools.sorted().joinToString(", ")}")
            }
            if (deniedTools.isNotEmpty()) {
                appendLine()
                appendLine("Denied tools (blacklist): ${deniedTools.sorted().joinToString(", ")}")
            }
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
            appendLine("## Final-Turn Output Contract")
            appendLine()
            appendLine("When you have finished all work and have no more tool calls to make, your FINAL assistant turn (the last text you emit before the loop ends) must contain ONLY a concise plain-text summary using this structure:")
            appendLine()
            appendLine("  **Result**: <one-sentence summary of what you did>")
            appendLine("  **Files**: <comma-separated paths created/modified, or \"none\">")
            appendLine("  **Blockers**: <issues, or \"none\">")
            appendLine()
            appendLine("Hard rules for the final turn:")
            appendLine("- Plain text only. Do NOT include JSON, YAML, or fenced code blocks.")
            appendLine("- Do NOT call any tools in the final turn.")
            appendLine("- Do NOT add follow-up suggestions, next steps, or 'I can also do X'.")
            appendLine("- Do NOT repeat earlier intermediate reasoning.")
            appendLine()
            appendLine("Earlier turns (between tool calls) may contain whatever you need to think aloud. The parent agent only reads your FINAL turn.")
            appendLine()
            appendLine("## Cancellation Semantics (For Parent Agent)")
            appendLine()
            appendLine("If your tool result starts with 'Cancelled by user. Partial work completed:', this means a USER cancelled the sub-agent — do NOT automatically re-spawn. Acknowledge the cancel, summarize what was done so far, and ask the user how to proceed.")
            appendLine()
            appendLine("## Recursion")
            appendLine()
            appendLine("You are at depth=$depth of max=$maxDepth. Further delegation is not allowed.")
        }.trimEnd()

        /**
         * 旧 toolset 名 → 新 toolset 名 alias 表（P3 重命名）。
         * 详见 createToolRegistryForToolset 的 docstring。
         */
        private val TOOLSET_ALIAS: Map<String, String> = mapOf(
            "dev" to "coder",
            "research" to "explorer",
            "test" to "verifier",
            "browser" to "webfetcher"
        )

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
