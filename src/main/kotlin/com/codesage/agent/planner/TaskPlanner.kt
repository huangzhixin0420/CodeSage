package com.codesage.agent.planner

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.model.dto.Message
import kotlinx.coroutines.flow.Flow

/**
 * 任务状态
 */
enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 任务优先级
 */
enum class TaskPriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * 子任务定义（向后兼容）
 * @see TaskStep 作为增强版替代
 */
data class SubTask(
    val id: String,
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val dependencies: List<String> = emptyList(),
    val result: Any? = null,
    val error: String? = null
)

/**
 * 任务定义（向后兼容）
 */
data class Task(
    val id: String,
    val description: String,
    val goal: String,
    val subTasks: MutableList<SubTask> = mutableListOf(),
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) {
    fun addSubTask(subTask: SubTask) {
        subTasks.add(subTask)
    }

    fun updateSubTaskStatus(subTaskId: String, status: TaskStatus) {
        subTasks.find { it.id == subTaskId }?.let {
            val index = subTasks.indexOf(it)
            subTasks[index] = it.copy(status = status)
        }
    }

    fun isCompleted(): Boolean = subTasks.all { it.status == TaskStatus.COMPLETED }

    fun hasFailed(): Boolean = subTasks.any { it.status == TaskStatus.FAILED }
}

/**
 * 任务规划结果（向后兼容）
 * @see DagTaskPlan 作为增强版替代
 */
data class TaskPlan(
    val task: Task,
    val executionOrder: List<List<String>>,
    val estimatedSteps: Int,
    val totalSubTasks: Int
)

/**
 * 任务规划器
 * 支持 DAG 依赖图、自然语言依赖分析、人机协作审批和并行执行
 */
class TaskPlanner(
    private val dependencyAnalyzer: TaskDependencyAnalyzer = TaskDependencyAnalyzer(),
    private val approvalController: PlanApprovalController = PlanApprovalController(),
    private val parallelExecutor: ParallelTaskExecutor = ParallelTaskExecutor()
) {

    private val taskIdGenerator = TaskIdGenerator()

    /**
     * 创建新任务
     */
    fun createTask(description: String, goal: String, priority: TaskPriority = TaskPriority.MEDIUM): Task {
        return Task(
            id = taskIdGenerator.generate(),
            description = description,
            goal = goal,
            priority = priority
        )
    }

    /**
     * 分解任务为 DAG 计划（增强版）
     * 支持从自然语言自动识别依赖关系
     */
    fun decomposeToDagPlan(task: Task, context: List<Message> = emptyList()): DagTaskPlan {
        val steps = dependencyAnalyzer.parseSteps(task.description)

        // 如果自然语言拆分只有一个步骤，尝试使用上下文或更智能的拆分
        val finalSteps = if (steps.size <= 1 && task.description.length > 50) {
            // 对于较长的描述，尝试按句子拆分
            val sentenceSteps = splitBySentences(task.description)
            if (sentenceSteps.size > 1) {
                sentenceSteps.mapIndexed { index, desc ->
                    TaskStep(
                        id = taskIdGenerator.generateSubTaskId(index),
                        description = desc
                    )
                }
            } else steps
        } else steps

        // 计算拓扑排序
        val executionOrder = try {
            DagUtils.topologicalSort(finalSteps)
        } catch (e: CircularDependencyException) {
            // 如果检测到循环依赖，回退到顺序执行
            listOf(finalSteps.map { it.id })
        }

        // 计算关键路径
        val criticalPath = DagUtils.calculateCriticalPath(finalSteps, executionOrder)

        return DagTaskPlan(
            taskId = task.id,
            description = task.description,
            steps = finalSteps,
            executionOrder = executionOrder,
            estimatedSteps = executionOrder.size,
            totalSteps = finalSteps.size,
            criticalPath = criticalPath
        )
    }

    /**
     * 从结构化数据（如 LLM 输出）构建 DAG 计划
     */
    fun buildDagPlanFromStructured(
        taskId: String,
        description: String,
        structuredSteps: List<StructuredStep>
    ): DagTaskPlan {
        val steps = dependencyAnalyzer.parseStructuredSteps(structuredSteps)

        // 验证并拓扑排序
        val validation = DagUtils.validateDag(steps)
        if (!validation.isValid) {
            throw IllegalArgumentException("Invalid structured plan: ${validation.errorMessage}")
        }

        val executionOrder = DagUtils.topologicalSort(steps)
        val criticalPath = DagUtils.calculateCriticalPath(steps, executionOrder)

        return DagTaskPlan(
            taskId = taskId,
            description = description,
            steps = steps,
            executionOrder = executionOrder,
            estimatedSteps = executionOrder.size,
            totalSteps = steps.size,
            criticalPath = criticalPath
        )
    }

    /**
     * 向后兼容：分解任务为旧版 TaskPlan
     */
    fun decomposeTask(task: Task, context: List<Message>): TaskPlan {
        val dagPlan = decomposeToDagPlan(task, context)

        // 同步到 Task 的 subTasks（向后兼容）
        dagPlan.steps.forEachIndexed { index, step ->
            task.addSubTask(
                SubTask(
                    id = step.id,
                    description = step.description,
                    priority = step.priority,
                    dependencies = step.dependencies
                )
            )
        }

        return TaskPlan(
            task = task,
            executionOrder = dagPlan.executionOrder,
            estimatedSteps = dagPlan.estimatedSteps,
            totalSubTasks = dagPlan.totalSteps
        )
    }

    /**
     * 请求用户审批计划
     * 返回 Flow，包含 PlanGenerated 和最终的审批结果事件
     */
    fun requestPlanApproval(plan: DagTaskPlan): Flow<AgentStreamEvent> {
        return approvalController.requestApproval(plan)
    }

    /**
     * 批准计划（供 UI/外部调用）
     */
    suspend fun approvePlan(planId: String): Boolean {
        return approvalController.approve(planId)
    }

    /**
     * 修改计划（供 UI/外部调用）
     */
    suspend fun modifyPlan(planId: String, modifiedPlan: DagTaskPlan): Boolean {
        return approvalController.modify(planId, modifiedPlan)
    }

    /**
     * 拒绝计划（供 UI/外部调用）
     */
    suspend fun rejectPlan(planId: String, reason: String): Boolean {
        return approvalController.reject(planId, reason)
    }

    /**
     * 执行 DAG 计划（并行）
     */
    fun executeDagPlan(
        plan: DagTaskPlan,
        executor: StepExecutor
    ): Flow<AgentStreamEvent> {
        return parallelExecutor.execute(plan, executor)
    }

    /**
     * 验证任务计划
     */
    fun validatePlan(plan: DagTaskPlan): DagValidationResult {
        return DagUtils.validateDag(plan.steps)
    }

    /**
     * 检测循环依赖
     */
    fun detectCircularDependencies(steps: List<TaskStep>): List<String> {
        val adjacencyList = steps.associate { it.id to it.dependencies }
        return DagUtils.detectCycle(adjacencyList)
    }

    /**
     * 验证任务是否可执行（向后兼容）
     */
    fun canExecute(task: Task): Boolean {
        return task.subTasks.all { subTask ->
            subTask.dependencies.all { depId ->
                task.subTasks.find { it.id == depId }?.status == TaskStatus.COMPLETED
            }
        }
    }

    /**
     * 获取下一个可执行的任务（向后兼容）
     */
    fun getNextExecutableTasks(task: Task): List<SubTask> {
        return task.subTasks.filter { subTask ->
            subTask.status == TaskStatus.PENDING &&
                    subTask.dependencies.all { depId ->
                        task.subTasks.find { it.id == depId }?.status == TaskStatus.COMPLETED
                    }
        }
    }

    /**
     * 按句子拆分描述
     */
    private fun splitBySentences(description: String): List<String> {
        return description.split(SENTENCE_ENDER_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    companion object {
        // T0.8 修复（CodeReview High #13）：缓存正则表达式实例
        // Kotlin 的 Regex 是 thread-safe 且内部使用 Pattern.CASE_INSENSITIVE 等标志位；
        // 之前 splitBySentences 每次调用都重新创建 Pattern 对象，对长描述文本存在 O(n) 额外开销。
        // 改为 companion 缓存后，整个进程内只有一份 Pattern 实例。
        private val SENTENCE_ENDER_REGEX = Regex("(?<=[.!?。！？])\\s+")
    }
}

/**
 * 任务ID生成器
 */
class TaskIdGenerator {
    private val counter = java.util.concurrent.atomic.AtomicInteger(0)

    fun generate(): String = "task_${System.currentTimeMillis()}_${counter.incrementAndGet()}"

    fun generateSubTaskId(index: Int): String = "subtask_${index}"
}
