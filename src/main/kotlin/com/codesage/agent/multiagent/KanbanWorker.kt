package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Kanban Worker
 *
 * 专注执行，不自作主张。
 * 参考 Hermes 的 kanban_worker 设计。
 */
class KanbanWorker(
    val workerId: String,
    private val agentCore: AgentCore,
    private val subAgentExecutor: SubAgentExecutor
) {

    private val logger = Logger.getLogger<KanbanWorker>()

    val systemPrompt = """
        You are a Kanban Worker. Your rules:
        1. ONLY execute tasks assigned by the orchestrator
        2. Report progress and blockers clearly
        3. Do NOT create new tasks — escalate to orchestrator
        4. Complete assigned task fully before returning
        5. If you need clarification, ask the orchestrator
        6. Be thorough but concise in your execution
    """.trimIndent()

    /**
     * 执行分配的任务
     */
    suspend fun executeTask(task: KanbanTask): KanbanTask {
        logger.info("[Worker $workerId] Starting task ${task.id}: ${task.description.take(50)}")

        return try {
            // 使用子 Agent 执行实际工作
            val result = subAgentExecutor.spawn(
                parentSessionId = agentCore.getCurrentSession()?.id ?: "unknown",
                taskDescription = task.description,
                toolset = task.toolset,
                maxIterations = 10,
                progressCallback = { progress ->
                    logger.info("[Worker $workerId] $progress")
                }
            )

            if (result.success) {
                task.copy(
                    status = KanbanStatus.DONE,
                    result = result.output,
                    assignedWorker = workerId
                )
            } else {
                task.copy(
                    status = KanbanStatus.BLOCKED,
                    result = result.output,
                    blocker = "Execution failed after ${result.iterationsUsed} iterations",
                    assignedWorker = workerId
                )
            }
        } catch (e: Exception) {
            logger.error("[Worker $workerId] Task ${task.id} failed", e)
            task.copy(
                status = KanbanStatus.BLOCKED,
                blocker = e.message ?: "Unknown error",
                assignedWorker = workerId
            )
        }
    }

    /**
     * 并行批量执行任务
     *
     * T0.5 修复（对应 CodeReview #10）：原实现是顺序 `tasks.map { executeTask(it) }`，
     * 对 6 个任务全部按顺序执行，总耗时为各项累加。并行后应受 maxConcurrency 限制。
     *
     * 进度回调：每个任务完成时调用 [onTaskProgress]（可选），回调接收
     * (taskIndex, result)。回调中的异常不会中断其他任务。
     */
    suspend fun executeTasks(
        tasks: List<KanbanTask>,
        maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
        onTaskProgress: (suspend (index: Int, result: KanbanTask) -> Unit)? = null
    ): List<KanbanTask> = coroutineScope {
        if (tasks.isEmpty()) return@coroutineScope emptyList()
        val effectiveConcurrency = maxConcurrency.coerceAtLeast(1)
        val semaphore = Semaphore(effectiveConcurrency)

        tasks.mapIndexed { index, task ->
            async {
                semaphore.withPermit {
                    val result = try {
                        executeTask(task)
                    } catch (e: Exception) {
                        logger.error("[Worker $workerId] Task ${task.id} threw unexpected exception", e)
                        task.copy(
                            status = KanbanStatus.BLOCKED,
                            blocker = "Unexpected error: ${e.message}",
                            assignedWorker = workerId
                        )
                    }
                    if (onTaskProgress != null) {
                        try {
                            onTaskProgress(index, result)
                        } catch (e: Exception) {
                            logger.warn("[Worker $workerId] Progress callback failed for task ${task.id}", e)
                        }
                    }
                    result
                }
            }
        }.awaitAll()
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 3
    }
}
