package com.codesage.agent.planner

/**
 * DAG 任务步骤定义
 * 增强版 SubTask，支持完整的 DAG 依赖图声明和并行标记
 */
data class TaskStep(
    val id: String,
    val description: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val priority: TaskPriority = TaskPriority.MEDIUM,
    val dependencies: List<String> = emptyList(),
    /** 标记可与哪些步骤并行执行（显式并行组标记） */
    val parallelWith: List<String> = emptyList(),
    val result: Any? = null,
    val error: String? = null,
    /** 预计执行时间（毫秒），用于关键路径计算 */
    val estimatedDurationMs: Long = 0L
)

/**
 * DAG 任务规划结果
 * 支持完整的依赖图、拓扑排序和并行执行组
 */
data class DagTaskPlan(
    val taskId: String,
    val description: String,
    val steps: List<TaskStep>,
    /** 拓扑排序后的并行执行组，每组内步骤无依赖可并行 */
    val executionOrder: List<List<String>>,
    val estimatedSteps: Int,
    val totalSteps: Int,
    /** 关键路径步骤 ID 列表 */
    val criticalPath: List<String> = emptyList()
) {
    /**
     * 获取步骤的依赖图邻接表表示
     */
    fun toAdjacencyList(): Map<String, List<String>> {
        return steps.associate { it.id to it.dependencies }
    }

    /**
     * 查找指定步骤的直接后继步骤
     */
    fun getDependents(stepId: String): List<String> {
        return steps.filter { it.dependencies.contains(stepId) }.map { it.id }
    }

    /**
     * 获取指定步骤
     */
    fun getStep(stepId: String): TaskStep? = steps.find { it.id == stepId }

    /**
     * 检查所有步骤是否已完成
     */
    fun isCompleted(): Boolean = steps.all { it.status == TaskStatus.COMPLETED }

    /**
     * 检查是否有步骤失败
     */
    fun hasFailed(): Boolean = steps.any { it.status == TaskStatus.FAILED }
}

/**
 * DAG 工具类
 * 提供拓扑排序、循环依赖检测、关键路径计算
 */
object DagUtils {

    /**
     * 对步骤列表进行拓扑排序，返回可并行执行的分组
     *
     * @param steps 任务步骤列表
     * @return 按执行顺序排列的并行组，每组内步骤可并行执行
     * @throws CircularDependencyException 当检测到循环依赖时抛出
     */
    fun topologicalSort(steps: List<TaskStep>): List<List<String>> {
        if (steps.isEmpty()) return emptyList()

        val adjacencyList = steps.associate { it.id to it.dependencies.toMutableList() }
        val inDegree = steps.associate { step ->
            step.id to step.dependencies.size
        }.toMutableMap()

        // Kahn 算法
        val result = mutableListOf<List<String>>()
        val remaining = steps.map { it.id }.toMutableSet()

        while (remaining.isNotEmpty()) {
            // 找到所有入度为 0 的节点
            val zeroInDegree = remaining.filter { inDegree[it] == 0 }

            if (zeroInDegree.isEmpty()) {
                // 存在循环依赖
                val cycle = detectCycle(adjacencyList, remaining)
                throw CircularDependencyException(
                    "Circular dependency detected involving steps: ${cycle.joinToString(" -> ")}"
                )
            }

            result.add(zeroInDegree)
            remaining.removeAll(zeroInDegree)

            // 更新入度
            for (node in zeroInDegree) {
                for (step in steps) {
                    if (step.dependencies.contains(node)) {
                        inDegree[step.id] = (inDegree[step.id] ?: 0) - 1
                    }
                }
            }
        }

        return result
    }

    /**
     * 检测循环依赖并返回循环路径
     */
    fun detectCycle(adjacencyList: Map<String, List<String>>, nodes: Set<String> = adjacencyList.keys): List<String> {
        val visited = mutableSetOf<String>()
        val recStack = mutableSetOf<String>()
        val path = mutableListOf<String>()

        for (node in nodes) {
            if (node !in visited) {
                val cycle = dfsFindCycle(node, adjacencyList, visited, recStack, path)
                if (cycle != null) return cycle
            }
        }
        return emptyList()
    }

    private fun dfsFindCycle(
        node: String,
        adjacencyList: Map<String, List<String>>,
        visited: MutableSet<String>,
        recStack: MutableSet<String>,
        path: MutableList<String>
    ): List<String>? {
        visited.add(node)
        recStack.add(node)
        path.add(node)

        for (neighbor in adjacencyList[node] ?: emptyList()) {
            if (neighbor !in visited) {
                val cycle = dfsFindCycle(neighbor, adjacencyList, visited, recStack, path)
                if (cycle != null) return cycle
            } else if (neighbor in recStack) {
                // 发现循环
                val cycleStart = path.indexOf(neighbor)
                return path.subList(cycleStart, path.size) + neighbor
            }
        }

        path.removeAt(path.size - 1)
        recStack.remove(node)
        return null
    }

    /**
     * 计算关键路径
     * 返回关键路径上的步骤 ID 列表（按顺序）
     *
     * @param steps 任务步骤列表
     * @param executionOrder 拓扑排序后的执行组
     */
    fun calculateCriticalPath(steps: List<TaskStep>, executionOrder: List<List<String>>): List<String> {
        if (steps.isEmpty()) return emptyList()

        val stepMap = steps.associateBy { it.id }
        val earliestStart = mutableMapOf<String, Long>()
        val earliestFinish = mutableMapOf<String, Long>()
        val latestStart = mutableMapOf<String, Long>()
        val latestFinish = mutableMapOf<String, Long>()

        // 前向遍历：计算最早开始/完成时间
        for (group in executionOrder) {
            for (stepId in group) {
                val step = stepMap[stepId] ?: continue
                val es = if (step.dependencies.isEmpty()) {
                    0L
                } else {
                    step.dependencies.maxOf { depId -> earliestFinish[depId] ?: 0L }
                }
                earliestStart[stepId] = es
                earliestFinish[stepId] = es + step.estimatedDurationMs
            }
        }

        val projectDuration = earliestFinish.values.maxOrNull() ?: 0L

        // 初始化最晚时间
        steps.forEach { latestFinish[it.id] = projectDuration }

        // 后向遍历：计算最晚开始/完成时间
        for (group in executionOrder.asReversed()) {
            for (stepId in group) {
                val step = stepMap[stepId] ?: continue
                val lf = if (latestFinish[stepId] == null) projectDuration else latestFinish[stepId]!!
                latestFinish[stepId] = lf
                latestStart[stepId] = lf - step.estimatedDurationMs

                // 更新依赖项的最晚完成时间
                for (depId in step.dependencies) {
                    val currentLf = latestFinish[depId] ?: projectDuration
                    val newLf = latestStart[stepId]!!
                    if (newLf < currentLf) {
                        latestFinish[depId] = newLf
                    }
                }
            }
        }

        // 关键路径：总浮动时间为 0 的步骤
        return steps.filter { step ->
            val es = earliestStart[step.id] ?: 0L
            val ls = latestStart[step.id] ?: 0L
            ls - es == 0L
        }.sortedBy { earliestStart[it.id] ?: 0L }.map { it.id }
    }

    /**
     * 验证 DAG 的有效性
     * @return 验证结果，包含是否有效和错误信息
     */
    fun validateDag(steps: List<TaskStep>): DagValidationResult {
        // 检查 ID 唯一性
        val ids = steps.map { it.id }
        if (ids.toSet().size != ids.size) {
            return DagValidationResult(false, "Duplicate step IDs found")
        }

        // 检查依赖是否存在
        val idSet = ids.toSet()
        for (step in steps) {
            for (dep in step.dependencies) {
                if (dep !in idSet) {
                    return DagValidationResult(false, "Step '${step.id}' depends on unknown step '$dep'")
                }
            }
            for (parallel in step.parallelWith) {
                if (parallel !in idSet) {
                    return DagValidationResult(false, "Step '${step.id}' references unknown parallel step '$parallel'")
                }
            }
        }

        // 检查循环依赖
        val adjacencyList = steps.associate { it.id to it.dependencies }
        val cycle = detectCycle(adjacencyList)
        if (cycle.isNotEmpty()) {
            return DagValidationResult(false, "Circular dependency detected: ${cycle.joinToString(" -> ")}")
        }

        return DagValidationResult(true)
    }
}

/**
 * DAG 验证结果
 */
data class DagValidationResult(
    val isValid: Boolean,
    val errorMessage: String? = null
)

/**
 * 循环依赖异常
 */
class CircularDependencyException(message: String) : RuntimeException(message)
