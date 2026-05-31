package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import com.codesage.agent.planner.Task
import com.codesage.agent.planner.TaskStatus
import com.codesage.shared.utils.Logger

/**
 * Kanban 看板任务
 */
data class KanbanTask(
    val id: String,
    val description: String,
    val toolset: String = "dev",
    val status: KanbanStatus = KanbanStatus.BACKLOG,
    val assignedWorker: String? = null,
    val result: String? = null,
    val blocker: String? = null
)

/**
 * Kanban 任务状态
 */
enum class KanbanStatus {
    BACKLOG,      // 待办
    IN_PROGRESS,  // 进行中
    REVIEW,       // 审核中
    DONE,         // 完成
    BLOCKED       // 阻塞
}

/**
 * Kanban Orchestrator
 *
 * 只做调度，不执行具体工作。
 * 参考 Hermes 的 kanban_orchestrator 设计。
 */
class KanbanOrchestrator(
    private val agentCore: AgentCore,
    private val subAgentExecutor: SubAgentExecutor
) {

    private val logger = Logger.getLogger<KanbanOrchestrator>()
    private val todoStore = mutableListOf<KanbanTask>()
    private val taskCounter = java.util.concurrent.atomic.AtomicInteger(0)

    val systemPrompt = """
        You are a Kanban Orchestrator. Your rules:
        1. NEVER do the work yourself — only delegate to workers
        2. Maintain a todo list of all pending tasks
        3. Track worker progress via delegate_task results
        4. Reconcile and hand off between workers
        5. Own the task lifecycle: create → assign → verify → close
        6. When all tasks are done, summarize results for the user
    """.trimIndent()

    /**
     * 创建新任务
     */
    fun createTask(description: String, toolset: String = "dev"): KanbanTask {
        val task = KanbanTask(
            id = "kb_${taskCounter.incrementAndGet()}",
            description = description,
            toolset = toolset
        )
        todoStore.add(task)
        logger.info("[Kanban] Created task ${task.id}: ${description.take(50)}")
        return task
    }

    /**
     * 将复杂任务分解为 Kanban 任务列表
     */
    fun decomposeToKanban(taskDescription: String): List<KanbanTask> {
        // 简单的启发式分解（后续可接入 LLM）
        val steps = taskDescription.split(Regex("""(?=[,，;；])|(?<=[,，;；])"""))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length > 5 }

        return if (steps.size > 1) {
            steps.map { createTask(it) }
        } else {
            listOf(createTask(taskDescription))
        }
    }

    /**
     * 分配任务给 Worker
     */
    fun assignTask(taskId: String, workerId: String): Boolean {
        val index = todoStore.indexOfFirst { it.id == taskId }
        if (index == -1) return false

        todoStore[index] = todoStore[index].copy(
            assignedWorker = workerId,
            status = KanbanStatus.IN_PROGRESS
        )
        logger.info("[Kanban] Assigned $taskId to $workerId")
        return true
    }

    /**
     * 更新任务状态
     */
    fun updateTaskStatus(
        taskId: String,
        status: KanbanStatus,
        result: String? = null,
        blocker: String? = null
    ): Boolean {
        val index = todoStore.indexOfFirst { it.id == taskId }
        if (index == -1) return false

        todoStore[index] = todoStore[index].copy(
            status = status,
            result = result ?: todoStore[index].result,
            blocker = blocker ?: todoStore[index].blocker
        )
        logger.info("[Kanban] Updated $taskId to $status")
        return true
    }

    /**
     * 获取所有任务
     */
    fun getAllTasks(): List<KanbanTask> = todoStore.toList()

    /**
     * 获取待办任务
     */
    fun getBacklog(): List<KanbanTask> = todoStore.filter { it.status == KanbanStatus.BACKLOG }

    /**
     * 获取进行中任务
     */
    fun getInProgress(): List<KanbanTask> = todoStore.filter { it.status == KanbanStatus.IN_PROGRESS }

    /**
     * 获取已完成任务
     */
    fun getDone(): List<KanbanTask> = todoStore.filter { it.status == KanbanStatus.DONE }

    /**
     * 获取阻塞任务
     */
    fun getBlocked(): List<KanbanTask> = todoStore.filter { it.status == KanbanStatus.BLOCKED }

    /**
     * 检查是否全部完成
     */
    fun isAllDone(): Boolean = todoStore.isNotEmpty() && todoStore.all { it.status == KanbanStatus.DONE }

    /**
     * 生成当前看板状态的文本描述
     */
    fun renderBoard(): String {
        return buildString {
            appendLine("## Kanban Board")
            appendLine()

            KanbanStatus.values().forEach { status ->
                val tasks = todoStore.filter { it.status == status }
                appendLine("### ${status.name} (${tasks.size})")
                tasks.forEach { task ->
                    appendLine("- [${task.id}] ${task.description.take(60)}")
                    if (task.assignedWorker != null) {
                        appendLine("  → Assigned: ${task.assignedWorker}")
                    }
                    if (task.blocker != null) {
                        appendLine("  ⚠️ Blocker: ${task.blocker}")
                    }
                }
                appendLine()
            }
        }
    }

    /**
     * 汇总所有已完成任务的结果
     */
    fun summarizeResults(): String {
        val doneTasks = getDone()
        if (doneTasks.isEmpty()) return "No tasks completed yet."

        return buildString {
            appendLine("## Task Execution Summary")
            appendLine()
            doneTasks.forEach { task ->
                appendLine("### ${task.id}")
                appendLine("**Description:** ${task.description}")
                appendLine("**Result:** ${task.result ?: "No output"}")
                appendLine()
            }
        }
    }

    /**
     * 清空看板
     */
    fun clearBoard() {
        todoStore.clear()
        taskCounter.set(0)
    }
}
