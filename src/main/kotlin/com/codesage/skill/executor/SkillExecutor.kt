package com.codesage.skill.executor

import com.codesage.skill.*
import com.codesage.skill.registry.SkillRegistry
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 技能执行器
 * 负责技能的加载和执行
 */
class SkillExecutor(
    private val registry: SkillRegistry = SkillRegistry.getInstance()
) {
    private val logger = Logger.getLogger<SkillExecutor>()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val runningTasks = ConcurrentHashMap<String, Job>()

    /**
     * 执行技能
     */
    suspend fun execute(
        skillId: String,
        input: SkillInput,
        context: ExecutionContext
    ): SkillResult {
        val skill = registry.get(skillId)
            ?: return SkillResult.Failure("Skill not found: $skillId")

        // 检查执行条件
        val canExecute = skill.canExecute(context)
        if (!canExecute.canExecute) {
            return SkillResult.Failure("Cannot execute skill: ${canExecute.reason}")
        }

        return try {
            logger.info("Executing skill: $skillId")
            val result = skill.execute(input, context)
            logger.info("Skill $skillId completed: ${result.isSuccess}")
            result
        } catch (e: CancellationException) {
            logger.warn("Skill $skillId cancelled")
            SkillResult.Failure("Execution cancelled", e)
        } catch (e: Exception) {
            logger.error("Skill $skillId failed", e)
            SkillResult.Failure(e.message ?: "Unknown error", e)
        }
    }

    /**
     * 异步执行技能
     */
    fun executeAsync(
        skillId: String,
        input: SkillInput,
        context: ExecutionContext,
        onComplete: ((SkillResult) -> Unit)? = null
    ): String {
        val taskId = "task_${System.currentTimeMillis()}_${runningTasks.size}"

        val job = scope.launch {
            try {
                val result = execute(skillId, input, context)
                onComplete?.invoke(result)
            } finally {
                runningTasks.remove(taskId)
            }
        }

        runningTasks[taskId] = job
        return taskId
    }

    /**
     * 批量执行技能
     */
    suspend fun executeBatch(
        requests: List<SkillExecutionRequest>
    ): List<SkillResult> = coroutineScope {
        requests.map { request ->
            async {
                execute(request.skillId, request.input, request.context)
            }
        }.map { it.await() }
    }

    /**
     * 取消执行中的任务
     */
    fun cancel(taskId: String): Boolean {
        val job = runningTasks.remove(taskId)
        return if (job != null) {
            job.cancel()
            runningTasks.remove(taskId)
            true
        } else {
            false
        }
    }

    /**
     * 取消所有任务
     */
    fun cancelAll() {
        runningTasks.values.forEach { it.cancel() }
        runningTasks.clear()
    }

    /**
     * 获取正在运行的任务数
     */
    fun getRunningCount(): Int = runningTasks.size

    /**
     * 关闭执行器
     */
    fun shutdown() {
        cancelAll()
        scope.cancel()
    }
}

/**
 * 技能执行请求
 */
data class SkillExecutionRequest(
    val skillId: String,
    val input: SkillInput,
    val context: ExecutionContext
)

/**
 * 结果聚合器
 */
class ResultAggregator {
    private val logger = Logger.getLogger<ResultAggregator>()

    /**
     * 聚合多个技能执行结果
     */
    fun aggregate(results: List<SkillResult>): AggregatedResult {
        val successes = results.filterIsInstance<SkillResult.Success>()
        val failures = results.filterIsInstance<SkillResult.Failure>()

        val allOutputs = successes.map { it.output }
        val mergedOutput = mergeOutputs(allOutputs)

        return AggregatedResult(
            totalCount = results.size,
            successCount = successes.size,
            failureCount = failures.size,
            outputs = mergedOutput,
            errors = failures.map { it.error }
        )
    }

    /**
     * 合并多个输出
     */
    private fun mergeOutputs(outputs: List<Map<String, Any>>): Map<String, Any> {
        return outputs.fold(emptyMap()) { acc, output ->
            acc + output
        }
    }
}

/**
 * 聚合结果
 */
data class AggregatedResult(
    val totalCount: Int,
    val successCount: Int,
    val failureCount: Int,
    val outputs: Map<String, Any>,
    val errors: List<String>
) {
    val isAllSuccess: Boolean get() = failureCount == 0
    val isAllFailed: Boolean get() = successCount == 0
    val hasErrors: Boolean get() = errors.isNotEmpty()
}
