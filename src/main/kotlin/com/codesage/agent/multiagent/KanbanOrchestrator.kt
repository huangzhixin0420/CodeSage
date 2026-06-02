package com.codesage.agent.multiagent

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.SubAgentExecutor
import com.codesage.agent.planner.Task
import com.codesage.agent.planner.TaskStatus
import com.codesage.shared.utils.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
     *
     * T4.5 修复：使用 LLM 分解（可选）。如果未配置 [LLMTaskDecomposer]，回退到启发式 split。
     *
     * 阻塞于 LLM 调用的同步等待：为了保持现有 API 不变为同步函数，LLM 调用在内部
     * 用 [kotlinx.coroutines.runBlocking] 包裹。这在 IDE 插件的 main thread 中是可接受的
     * （Kanban 分解是用户主动触发的操作，不在热路径）。
     */
    fun decomposeToKanban(
        taskDescription: String,
        llmDecomposer: LLMTaskDecomposer? = null
    ): List<KanbanTask> {
        // 优先使用 LLM 分解
        if (llmDecomposer != null) {
            try {
                val decomposed = kotlinx.coroutines.runBlocking {
                    llmDecomposer.decompose(taskDescription)
                }
                if (decomposed.isNotEmpty()) {
                    logger.info("[Kanban] LLM decomposition produced ${decomposed.size} tasks")
                    return decomposed.map { decomposed ->
                        createTask(decomposed.description, decomposed.toolset)
                    }
                }
            } catch (e: Exception) {
                logger.warn("[Kanban] LLM decomposition failed: ${e.message}, falling back to heuristic")
            }
        }

        // Heuristic fallback
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

/**
 * T4.5 修复：LLM 驱动的 Kanban 任务分解器
 *
 * **目标**：用 LLM 替换启发式 split，把复杂需求分解为 5–20 个可执行 KanbanTask。
 *
 * **设计**：
 * 1. 接受一个 LLM 调用回调（`LlmInvoker`），由调用方注入
 * 2. LLM 返回 JSON `{"tasks": [{"description": "...", "toolset": "dev|test|research", "estimated_minutes": 5}]}`
 * 3. 解析失败时降级到空列表（caller 回退到启发式）
 * 4. 24h 结果缓存（按 description hash）
 */
class LLMTaskDecomposer(
    private val invoker: LlmInvoker,
    private val config: Config = Config()
) {
    private val logger = Logger.getLogger<LLMTaskDecomposer>()
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 分解任务为子任务列表
     */
    suspend fun decompose(taskDescription: String): List<DecomposedTask> {
        if (taskDescription.isBlank()) return emptyList()

        // 1. 检查缓存
        val cacheKey = hashDescription(taskDescription)
        val cached = cache[cacheKey]
        if (cached != null && !cached.isExpired()) {
            logger.debug("[LLMTaskDecomposer] Cache hit for '$taskDescription'")
            return cached.tasks
        }

        // 2. 调用 LLM
        val prompt = buildPrompt(taskDescription)
        val response = try {
            invoker.invoke(prompt)
        } catch (e: Exception) {
            logger.warn("[LLMTaskDecomposer] LLM call failed: ${e.message}")
            return emptyList()
        }

        // 3. 解析响应
        val tasks = parseResponse(response, taskDescription)
        if (tasks.isNotEmpty()) {
            // 4. 缓存结果
            cache[cacheKey] = CacheEntry(tasks, System.currentTimeMillis() + config.cacheTtlMs)
        }
        return tasks
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.clear()
    }

    /**
     * 清除过期缓存条目
     */
    fun pruneExpired(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val iter = cache.entries.iterator()
        while (iter.hasNext()) {
            if (iter.next().value.expiresAtMs <= nowMs) {
                iter.remove()
                removed++
            }
        }
        return removed
    }

    private fun hashDescription(desc: String): String {
        val normalized = desc.trim().lowercase().replace(Regex("\\s+"), " ")
        return Integer.toHexString(normalized.hashCode())
    }

    private fun buildPrompt(taskDescription: String): String = buildString {
        appendLine("You are a task decomposition specialist. Given a user request, break it into a list of concrete, actionable Kanban tasks.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Generate between ${config.minTasks} and ${config.maxTasks} tasks")
        appendLine("- Each task description should be specific and actionable (start with a verb)")
        appendLine("- For each task, classify the toolset as one of: 'dev' (writing/modifying code), 'test' (writing/running tests), 'research' (information gathering), 'docs' (documentation)")
        appendLine("- Estimate duration in minutes (5-60)")
        appendLine("- Do NOT include a final 'summary' or 'finalize' step — the user does that")
        appendLine()
        appendLine("User request: \"$taskDescription\"")
        appendLine()
        appendLine("Respond with a single JSON object (no markdown fencing):")
        appendLine("""{"tasks": [{"description": "Step 1: ...", "toolset": "dev", "estimated_minutes": 15}, ...]}""")
    }

    private fun parseResponse(response: String, originalRequest: String): List<DecomposedTask> {
        // 提取 JSON（容忍 markdown 包裹）
        val jsonText = extractJson(response) ?: run {
            logger.debug("[LLMTaskDecomposer] No JSON in response: $response")
            return emptyList()
        }
        val obj = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            logger.debug("[LLMTaskDecomposer] Failed to parse JSON: $jsonText")
            return emptyList()
        }
        val tasksArray = obj["tasks"] as? JsonArray ?: return emptyList()
        val tasks = mutableListOf<DecomposedTask>()
        for (el in tasksArray) {
            val taskObj = el as? JsonObject ?: continue
            val desc = taskObj["description"]?.jsonPrimitive?.contentOrNull ?: continue
            val toolset = taskObj["toolset"]?.jsonPrimitive?.contentOrNull ?: "dev"
            val estMinutes = taskObj["estimated_minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
            tasks.add(DecomposedTask(desc.trim(), toolset, estMinutes))
        }
        // 限制数量
        return if (tasks.size > config.maxTasks) tasks.take(config.maxTasks) else tasks
    }

    private fun extractJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val end = trimmed.lastIndexOf("}")
            return if (end >= 0) trimmed.substring(0, end + 1) else null
        }
        val fenceStart = trimmed.indexOf("```")
        if (fenceStart >= 0) {
            val inner = trimmed.substring(fenceStart).removePrefix("```").removePrefix("json").trimStart()
            val fenceEnd = inner.indexOf("```")
            val content = if (fenceEnd >= 0) inner.substring(0, fenceEnd) else inner
            return content.trim()
        }
        return null
    }

    /**
     * LLM 分解出的单个任务
     */
    data class DecomposedTask(
        val description: String,
        val toolset: String,  // "dev" | "test" | "research" | "docs"
        val estimatedMinutes: Int = 15
    )

    data class Config(
        val minTasks: Int = 3,
        val maxTasks: Int = 20,
        val cacheTtlMs: Long = 24L * 60 * 60 * 1000L  // 24h
    )

    private data class CacheEntry(
        val tasks: List<DecomposedTask>,
        val expiresAtMs: Long
    ) {
        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAtMs <= now
    }
}
