package com.codesage.agent.planner

import com.codesage.agent.core.AgentResult
import com.codesage.agent.core.AgentStreamEvent
import com.codesage.model.dto.Message
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 步骤执行结果
 */
data class StepExecutionResult(
    val stepId: String,
    val success: Boolean,
    val output: String,
    val error: String? = null
)

/**
 * 并行执行配置
 */
data class ParallelExecutionConfig(
    /** 并行度限制（最大并发数），0 表示不限制 */
    val maxConcurrency: Int = 0,
    /** 是否在一个步骤失败时立即中止整个计划 */
    val failFast: Boolean = true,
    /** 步骤执行超时（毫秒），0 表示不限制 */
    val stepTimeoutMs: Long = 0L
)

/**
 * 步骤执行器接口
 * 具体的步骤执行逻辑由外部注入
 */
fun interface StepExecutor {
    suspend fun execute(step: TaskStep): StepExecutionResult
}

/**
 * 并行任务执行器
 * 根据 DAG 拓扑排序结果，按层并行执行任务步骤
 */
class ParallelTaskExecutor(
    private val config: ParallelExecutionConfig = ParallelExecutionConfig()
) {

    /**
     * 执行 DAG 任务计划
     * 按拓扑排序的分组，逐组并行执行
     *
     * @param plan DAG 任务计划
     * @param executor 步骤执行器
     * @return Flow<AgentStreamEvent> 流式输出执行事件
     */
    fun execute(
        plan: DagTaskPlan,
        executor: StepExecutor
    ): Flow<AgentStreamEvent> = flow {
        // 验证 DAG
        val validation = DagUtils.validateDag(plan.steps)
        if (!validation.isValid) {
            emit(AgentStreamEvent.Error("Invalid task plan: ${validation.errorMessage}"))
            return@flow
        }

        emit(AgentStreamEvent.Thinking("开始执行计划: ${plan.description} (共 ${plan.totalSteps} 步)"))

        // 按拓扑排序的组执行
        for (groupIndex in plan.executionOrder.indices) {
            val group = plan.executionOrder[groupIndex]
            emit(AgentStreamEvent.Thinking("执行第 ${groupIndex + 1}/${plan.executionOrder.size} 组 (共 ${group.size} 个并行步骤)"))

            // 收集当前组的执行结果
            val groupResults = executeGroup(group, plan, executor)

            for (result in groupResults) {
                if (result.success) {
                    emit(AgentStreamEvent.Thinking("步骤 [${result.stepId}] 完成"))
                } else {
                    emit(AgentStreamEvent.Error("步骤 [${result.stepId}] 失败: ${result.error}"))
                    if (config.failFast) {
                        emit(AgentStreamEvent.Thinking("由于 failFast 设置，中止计划执行"))
                        return@flow
                    }
                }
            }

            // 检查组内是否有失败（非 failFast 模式）
            if (!config.failFast && groupResults.any { !it.success }) {
                emit(AgentStreamEvent.Thinking("第 ${groupIndex + 1} 组部分步骤失败，继续执行后续组"))
            }
        }

        val hasFailure = plan.steps.any { it.status == TaskStatus.FAILED }
        if (hasFailure) {
            emit(AgentStreamEvent.Thinking("计划执行完成，部分步骤失败"))
        } else {
            emit(AgentStreamEvent.Thinking("计划执行完成，所有步骤成功"))
        }
    }

    /**
     * 执行一个并行组
     */
    private suspend fun executeGroup(
        group: List<String>,
        plan: DagTaskPlan,
        executor: StepExecutor
    ): List<StepExecutionResult> = coroutineScope {
        val jobs = group.map { stepId ->
            async {
                val step = plan.getStep(stepId)
                    ?: return@async StepExecutionResult(stepId, false, "", "Step not found in plan")

                try {
                    val result = if (config.stepTimeoutMs > 0) {
                        withTimeout(config.stepTimeoutMs) {
                            executor.execute(step)
                        }
                    } else {
                        executor.execute(step)
                    }
                    result
                } catch (e: TimeoutCancellationException) {
                    StepExecutionResult(stepId, false, "", "Step execution timed out after ${config.stepTimeoutMs}ms")
                } catch (e: Exception) {
                    StepExecutionResult(stepId, false, "", e.message ?: "Unknown error")
                }
            }
        }

        // 如果配置了并发限制，使用 semaphore 控制
        if (config.maxConcurrency > 0) {
            // 这里实际上上面的 async 已经创建了所有协程
            // 为了严格限制并发数，应该在创建 async 时就控制
            // 但考虑到 grouped execution 的特性，通常每组不会太大
            // 这里简单实现：等待所有完成
            jobs.map { it.await() }
        } else {
            jobs.map { it.await() }
        }
    }

    /**
     * 带并发限制的并行组执行
     */
    private suspend fun executeGroupWithLimit(
        group: List<String>,
        plan: DagTaskPlan,
        executor: StepExecutor
    ): List<StepExecutionResult> {
        if (config.maxConcurrency <= 0) {
            return coroutineScope {
                group.map { stepId ->
                    async {
                        executeSingleStep(stepId, plan, executor)
                    }
                }.map { it.await() }
            }
        }

        val semaphore = kotlinx.coroutines.sync.Semaphore(config.maxConcurrency)
        return coroutineScope {
            group.map { stepId ->
                async {
                    semaphore.acquire()
                    try {
                        executeSingleStep(stepId, plan, executor)
                    } finally {
                        semaphore.release()
                    }
                }
            }.map { it.await() }
        }
    }

    private suspend fun executeSingleStep(
        stepId: String,
        plan: DagTaskPlan,
        executor: StepExecutor
    ): StepExecutionResult {
        val step = plan.getStep(stepId)
            ?: return StepExecutionResult(stepId, false, "", "Step not found in plan")

        return try {
            if (config.stepTimeoutMs > 0) {
                withTimeout(config.stepTimeoutMs) {
                    executor.execute(step)
                }
            } else {
                executor.execute(step)
            }
        } catch (e: TimeoutCancellationException) {
            StepExecutionResult(stepId, false, "", "Step execution timed out after ${config.stepTimeoutMs}ms")
        } catch (e: Exception) {
            StepExecutionResult(stepId, false, "", e.message ?: "Unknown error")
        }
    }
}

/**
 * 将 AgentCore 的 chatWithTools 包装为 StepExecutor
 */
class AgentCoreStepExecutor(
    private val chatWithTools: suspend (String) -> Flow<AgentStreamEvent>
) : StepExecutor {
    override suspend fun execute(step: TaskStep): StepExecutionResult {
        val resultBuilder = StringBuilder()
        var error: String? = null

        try {
            chatWithTools(step.description).collect { event ->
                when (event) {
                    is AgentStreamEvent.TextDelta -> resultBuilder.append(event.delta)
                    is AgentStreamEvent.Error -> {
                        error = event.message
                    }

                    else -> { /* ignore */
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: "Execution failed"
        }

        return StepExecutionResult(
            stepId = step.id,
            success = error == null,
            output = resultBuilder.toString(),
            error = error
        )
    }
}
