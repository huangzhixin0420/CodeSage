package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentResult
import com.codesage.agent.core.AgentSession
import com.codesage.model.dto.Message
import com.codesage.model.dto.Tool
import com.codesage.agent.planner.Task
import com.codesage.agent.planner.TaskStatus
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.collections.filterIsInstance

/**
 * Agent角色枚举
 */
enum class AgentRole {
    PLANNER,    // 任务规划者
    CODER,      // 代码编写者
    REVIEWER,   // 代码审查者
    TESTER,     // 测试工程师
    RESEARCHER  // 调研员
}

/**
 * Agent能力定义
 */
data class Capability(
    val name: String,
    val description: String,
    val toolAccess: List<String> = emptyList()
)

/**
 * Agent定义
 */
interface Agent {
    val name: String
    val role: AgentRole
    val capabilities: List<Capability>
    val systemPrompt: String

    suspend fun process(task: Task): AgentResult
}

/**
 * 基础Agent实现
 */
abstract class BaseAgent(
    override val name: String,
    override val role: AgentRole,
    override val capabilities: List<Capability>,
    override val systemPrompt: String,
    protected val agentCore: AgentCore
) : Agent {

    protected val logger = Logger.getLogger(this::class.java)

    override suspend fun process(task: Task): AgentResult {
        logger.info("[$name] Processing task: ${task.description}")
        // Use chatWithTools for sub-agents so they can use IDE tools
        val resultBuilder = StringBuilder()
        var errorMessage: String? = null
        agentCore.chatWithTools(task.description).collect { event ->
            when (event) {
                is com.codesage.agent.core.AgentStreamEvent.TextDelta -> resultBuilder.append(event.delta)
                is com.codesage.agent.core.AgentStreamEvent.Error -> {
                    errorMessage = event.message
                }

                else -> { /* ignore other events for result aggregation */
                }
            }
        }
        return if (errorMessage != null) {
            AgentResult.Failure(errorMessage, AgentSession(id = "sub_agent_${role.name}"))
        } else {
            AgentResult.Success(
                com.codesage.model.dto.Message.assistantMessage(resultBuilder.toString()),
                AgentSession(id = "sub_agent_${role.name}")
            )
        }
    }
}

/**
 * Planner Agent - 任务分解
 */
class PlannerAgent(agentCore: AgentCore) : BaseAgent(
    name = "Planner",
    role = AgentRole.PLANNER,
    capabilities = listOf(
        Capability("task_decomposition", "分解复杂任务为子任务"),
        Capability("priority_assignment", "分配任务优先级"),
        Capability("dependency_analysis", "分析任务依赖关系"),
        Capability("structured_planning", "输出结构化可执行计划")
    ),
    systemPrompt = """
        You are a Task Planner specialized in breaking down complex tasks into structured, executable plans.

        Your responsibilities:
        1. Analyze the user's request thoroughly
        2. Break it into clear, actionable steps
        3. Identify dependencies between steps (after, before, depends_on)
        4. Mark parallelizable steps explicitly
        5. Set appropriate priorities (LOW, MEDIUM, HIGH, CRITICAL)
        6. Output a structured plan in YAML format that can be directly parsed and executed

        Output format (YAML):
        ```yaml
        plan:
          steps:
            - id: step_1
              description: "Clear description of what to do"
              priority: HIGH
              dependencies: []
              parallel_with: []
              estimated_duration_ms: 30000
            - id: step_2
              description: "Next step description"
              priority: MEDIUM
              dependencies: [step_1]
              parallel_with: []
              estimated_duration_ms: 60000
            - id: step_3
              description: "A step that can run in parallel with step_2"
              priority: MEDIUM
              dependencies: [step_1]
              parallel_with: [step_2]
              estimated_duration_ms: 45000
        ```

        Rules:
        - Every step MUST have a unique id in format "step_N"
        - dependencies list the ids of steps that must complete BEFORE this step starts
        - parallel_with lists the ids of steps that can run concurrently with this step
        - estimated_duration_ms is your best estimate of execution time in milliseconds
        - Use clear, actionable language in descriptions
        - Ensure the dependency graph forms a valid DAG (no cycles)
        - If a step depends on multiple steps, list ALL of them in dependencies
    """.trimIndent(),
    agentCore = agentCore
)

/**
 * Coder Agent - 代码编写
 */
class CoderAgent(agentCore: AgentCore) : BaseAgent(
    name = "Coder",
    role = AgentRole.CODER,
    capabilities = listOf(
        Capability("code_generation", "生成代码"),
        Capability("code_modification", "修改现有代码"),
        Capability("refactoring", "代码重构")
    ),
    systemPrompt = """
        You are a Coder Agent specialized in writing high-quality code.
        Your responsibilities:
        1. Write clean, maintainable code
        2. Follow best practices and coding standards
        3. Add appropriate comments and documentation
        4. Consider edge cases and error handling
        Always explain your code and the decisions behind it.
    """.trimIndent(),
    agentCore = agentCore
)

/**
 * Reviewer Agent - 代码审查
 */
class ReviewerAgent(agentCore: AgentCore) : BaseAgent(
    name = "Reviewer",
    role = AgentRole.REVIEWER,
    capabilities = listOf(
        Capability("code_review", "代码审查"),
        Capability("bug_detection", "检测潜在问题"),
        Capability("optimization_suggestion", "优化建议")
    ),
    systemPrompt = """
        You are a Code Reviewer Agent specialized in reviewing code quality.
        Your responsibilities:
        1. Review code for correctness and efficiency
        2. Identify potential bugs and security issues
        3. Suggest improvements and best practices
        4. Verify adherence to coding standards
        Provide constructive and actionable feedback.
    """.trimIndent(),
    agentCore = agentCore
)

/**
 * Tester Agent - 测试工程
 */
class TesterAgent(agentCore: AgentCore) : BaseAgent(
    name = "Tester",
    role = AgentRole.TESTER,
    capabilities = listOf(
        Capability("test_generation", "生成测试用例"),
        Capability("test_execution", "执行测试"),
        Capability("coverage_analysis", "覆盖率分析")
    ),
    systemPrompt = """
        You are a Tester Agent specialized in software testing.
        Your responsibilities:
        1. Generate comprehensive test cases
        2. Cover normal and edge cases
        3. Write unit tests and integration tests
        4. Ensure good test coverage
        Focus on meaningful tests that catch real bugs.
    """.trimIndent(),
    agentCore = agentCore
)

/**
 * Researcher Agent - 调研员
 */
class ResearcherAgent(agentCore: AgentCore) : BaseAgent(
    name = "Researcher",
    role = AgentRole.RESEARCHER,
    capabilities = listOf(
        Capability("information_gathering", "信息收集"),
        Capability("technology_research", "技术调研"),
        Capability("documentation", "文档编写")
    ),
    systemPrompt = """
        You are a Researcher Agent specialized in gathering information.
        Your responsibilities:
        1. Research technologies and best practices
        2. Gather relevant documentation and examples
        3. Analyze and summarize findings
        4. Provide actionable recommendations
        Focus on accuracy and practical applicability.
    """.trimIndent(),
    agentCore = agentCore
)

/**
 * 多Agent协调器
 * 负责多Agent协作执行复杂任务
 */
class MultiAgentOrchestrator(
    private val agentCore: AgentCore
) {
    private val logger = Logger.getLogger<MultiAgentOrchestrator>()

    private val agents = mutableMapOf<AgentRole, Agent>()

    init {
        // 初始化各个Agent
        agents[AgentRole.PLANNER] = PlannerAgent(agentCore)
        agents[AgentRole.CODER] = CoderAgent(agentCore)
        agents[AgentRole.REVIEWER] = ReviewerAgent(agentCore)
        agents[AgentRole.TESTER] = TesterAgent(agentCore)
        agents[AgentRole.RESEARCHER] = ResearcherAgent(agentCore)
    }

    /**
     * 获取指定角色的Agent
     */
    fun getAgent(role: AgentRole): Agent? = agents[role]

    /**
     * 注册自定义Agent
     */
    fun registerAgent(agent: Agent) {
        agents[agent.role] = agent
    }

    /**
     * 执行任务 (使用多Agent协作)
     */
    suspend fun executeTask(task: Task): TaskExecutionResult {
        logger.info("Starting multi-agent execution for task: ${task.id}")

        val results = mutableMapOf<AgentRole, AgentResult>()

        // 1. Planner 分析并分解任务
        val planner = agents[AgentRole.PLANNER]
        if (planner != null) {
            val planningTask = Task(
                id = "planning_${task.id}",
                description = "分析并分解任务: ${task.description}",
                goal = task.goal
            )
            results[AgentRole.PLANNER] = planner.process(planningTask)
        }

        // 2. 根据任务类型确定参与的Agent
        val participants = determineParticipants(task)

        // 3. 并行协调执行
        coroutineScope {
            val deferredResults = participants.map { role ->
                async {
                    val agent = agents[role]
                    role to if (agent != null) {
                        try {
                            agent.process(task)
                        } catch (e: Exception) {
                            logger.error("[$role] execution failed", e)
                            AgentResult.Failure(
                                e.message ?: "Unknown error",
                                AgentSession(id = "error_session")
                            )
                        }
                    } else {
                        AgentResult.Failure(
                            "Agent not found for role: $role",
                            AgentSession(id = "error_session")
                        )
                    }
                }
            }
            deferredResults.forEach { deferred ->
                val (role, result) = deferred.await()
                results[role] = result
            }
        }

        // 4. 聚合结果
        return aggregateResults(task, results)
    }

    /**
     * 确定参与任务的Agent
     */
    private fun determineParticipants(task: Task): List<AgentRole> {
        val description = task.description.lowercase()
        val participants = mutableSetOf<AgentRole>()

        // 基于关键词选择Agent（使用独立if判断，支持多角色同时匹配）
        if (description.contains("review") || description.contains("审查")) {
            participants.add(AgentRole.REVIEWER)
        }
        if (description.contains("test") || description.contains("测试")) {
            participants.add(AgentRole.TESTER)
        }
        if (description.contains("research") || description.contains("调研") ||
            description.contains("investigate") || description.contains("analyze")
        ) {
            participants.add(AgentRole.RESEARCHER)
        }
        if (description.contains("code") || description.contains("write") ||
            description.contains("implement") || description.contains("refactor") ||
            description.contains("fix") || description.contains("开发") ||
            participants.isEmpty()
        ) {
            participants.add(AgentRole.CODER)
        }

        return participants.toList()
    }

    /**
     * 聚合多Agent结果
     */
    private fun aggregateResults(task: Task, results: Map<AgentRole, AgentResult>): TaskExecutionResult {
        val successResults = results.values.filterIsInstance<AgentResult.Success>()
        val failureResults = results.filterValues { it is AgentResult.Failure }

        val aggregatedContent = buildString {
            appendLine("## 任务执行结果")
            appendLine()

            for ((role, result) in results) {
                appendLine("### ${role.name}")
                when (result) {
                    is AgentResult.Success -> {
                        appendLine(result.message.content)
                    }

                    is AgentResult.Failure -> {
                        appendLine("❌ 失败: ${result.error}")
                    }

                    is AgentResult.ToolCalls -> {
                        appendLine("⚙️ 需要工具调用: ${result.toolCalls.size}个")
                    }
                }
                appendLine()
            }
        }

        return TaskExecutionResult(
            taskId = task.id,
            success = failureResults.isEmpty(),
            results = results,
            summary = aggregatedContent,
            completedAt = System.currentTimeMillis()
        )
    }

    /**
     * 并行执行多个Agent任务
     */
    suspend fun executeParallel(
        tasks: List<Task>,
        roles: List<AgentRole>
    ): Map<AgentRole, AgentResult> = coroutineScope {
        val results = mutableMapOf<AgentRole, AgentResult>()

        val deferredResults = roles.mapIndexed { index, role ->
            async {
                val agent = agents[role] ?: return@async role to AgentResult.Failure(
                    "Agent not found for role: $role",
                    AgentSession(id = "error_session")
                )
                val task = tasks.getOrNull(index) ?: tasks.firstOrNull() ?: return@async role to AgentResult.Failure(
                    "No task available for role: $role",
                    AgentSession(id = "error_session")
                )

                role to try {
                    agent.process(task)
                } catch (e: Exception) {
                    AgentResult.Failure(
                        e.message ?: "Unknown error",
                        AgentSession(id = "error_session")
                    )
                }
            }
        }

        deferredResults.forEach { deferred ->
            val (role, result) = deferred.await()
            results[role] = result
        }

        results
    }
}

/**
 * 任务执行结果
 */
data class TaskExecutionResult(
    val taskId: String,
    val success: Boolean,
    val results: Map<AgentRole, AgentResult>,
    val summary: String,
    val completedAt: Long
)
