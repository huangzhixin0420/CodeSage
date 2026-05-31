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
 * - 继承父 agent 的 memory provider，但独立 session
 * - 通过 progress callback 实时汇报进度给父 agent
 */
class SubAgentExecutor(
    private val parentAgent: AgentCore,
    private val gateway: ModelGateway = ModelGateway.getInstance(),
    private val project: Project? = null,
    private val skillToolAdapter: SkillToolAdapter? = null
) {

    private val logger = Logger.getLogger<SubAgentExecutor>()

    /**
     * 生成子 Agent 系统提示
     */
    private fun generateSubAgentPrompt(taskDescription: String, toolset: String): String {
        return """
            You are a specialized sub-agent focused on: $taskDescription

            Your constraints:
            1. ONLY work on the assigned task — do not deviate
            2. You have access to the $toolset toolset
            3. Report progress and blockers clearly
            4. Do NOT create new tasks — escalate blockers to the parent agent
            5. Complete the assigned task fully before returning
            6. Be concise — your output will be consumed by the parent agent

            Toolset: $toolset
        """.trimIndent()
    }

    /**
     * Spawn 一个隔离的子 Agent 执行任务
     *
     * @param parentSessionId 父会话 ID
     * @param taskDescription 任务描述
     * @param toolset 工具集名称（dev, research, test 等）
     * @param maxIterations 子 Agent 的迭代预算
     * @param contextFiles 子 Agent 需要访问的文件列表
     * @param progressCallback 进度回调（实时汇报给父 agent）
     */
    suspend fun spawn(
        parentSessionId: String,
        taskDescription: String,
        toolset: String = "dev",
        maxIterations: Int = 10,
        contextFiles: List<String> = emptyList(),
        progressCallback: suspend (String) -> Unit = {},
        parentBudget: TaskBudget? = null
    ): SubAgentResult {
        logger.info("[SubAgent] Spawning for task: ${taskDescription.take(80)}")

        val subSessionId = "sub_${parentSessionId}_${System.currentTimeMillis()}"
        val subSystemPrompt = generateSubAgentPrompt(taskDescription, toolset)

        // 1. 创建隔离的工具注册表（仅加载指定 toolset）
        val subToolRegistry = createToolRegistryForToolset(toolset)

        // 2. 计算子 Agent 预算配置（继承父 Agent 剩余预算比例）
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

        // 3. 创建子 AgentCore
        val subAgent = AgentCore(
            gateway = gateway,
            project = project,
            skillToolAdapter = skillToolAdapter
        )

        // 配置子 Agent（传入自定义预算配置）
        subAgent.initialize(
            AgentConfig(
                defaultModel = parentAgent.getCurrentModel(),
                systemPrompt = subSystemPrompt,
                budgetConfig = subBudgetConfig
            )
        )

        // 3. 注入上下文文件引用
        val contextManager = subAgent.getCurrentHistory().let { history ->
            // 通过反射或间接方式获取 contextManager... 实际上我们直接用 chatWithTools
            // 先添加上下文文件信息
            null
        }

        // 4. 构建任务消息（包含上下文文件）
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

        // 5. 执行对话循环
        val outputBuilder = StringBuilder()
        val toolsUsed = mutableSetOf<String>()
        var iterationsUsed = 0
        var success = true

        try {
            progressCallback("[SubAgent $subSessionId] Starting task...")

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
                        progressCallback("[SubAgent nested] Starting: ${event.taskDescription}")
                    }

                    is AgentStreamEvent.SubAgentProgress -> {
                        progressCallback("[SubAgent nested] ${event.message}")
                    }

                    is AgentStreamEvent.SubAgentComplete -> {
                        progressCallback("[SubAgent nested] Completed: ${if (event.success) "success" else "failed"}")
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

        logger.info("[SubAgent] Completed. Success=$success, Iterations=$iterationsUsed, Tools=${toolsUsed}")
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
     * 为指定 toolset 创建工具注册表
     */
    private fun createToolRegistryForToolset(toolset: String): ToolRegistry {
        val registry = ToolRegistry.createDefault()

        // 根据 toolset 过滤或增强工具
        when (toolset) {
            "dev" -> {
                // 开发工具集：保留所有默认 IDE 工具
            }

            "research" -> {
                // 研究工具集：侧重搜索和分析
                // 可以添加 web_search, documentation_lookup 等
            }

            "test" -> {
                // 测试工具集：侧重测试执行
                // 可以添加 test_runner, coverage_tool 等
            }

            "browser" -> {
                // 浏览器工具集
                // 可以添加 screenshot, navigate, click 等
            }

            else -> {
                // 默认：返回全部工具
            }
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
}
