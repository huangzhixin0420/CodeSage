package com.codesage.agent.planner

import com.codesage.shared.utils.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T4.3 修复：Planner 输出结构化解析与验证
 *
 * **目标**：把 LLM 输出的"自由文本 + YAML/JSON 块"解析为 [DagTaskPlan]，并严格验证。
 *
 * **解析策略**（按优先级尝试）：
 * 1. YAML（LLM 倾向于输出 YAML）
 * 2. JSON（fallback）
 * 3. 自然语言句法切分（最终 fallback）
 *
 * **验证规则**：
 * - 所有 `dependencies` 必须引用已知 step id
 * - 不能有循环依赖
 * - step id 必须唯一
 * - `parallel_with` 引用必须存在
 *
 * **Markdown 容忍**：自动剥离 ` ```yaml ` 包裹、提取首个 YAML/JSON 块。
 */
object StructuredPlanParser {

    private val logger = Logger.getLogger<StructuredPlanParser>()
    private val yamlJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 LLM 输出为结构化步骤列表
     *
     * @return [ParseResult.Success] 含步骤；[ParseResult.Failure] 含错误信息
     */
    fun parse(rawOutput: String, taskId: String = "unknown"): ParseResult {
        if (rawOutput.isBlank()) {
            return ParseResult.Failure("Empty input")
        }

        // 1. 尝试从 markdown 包裹中提取代码块
        val extracted = extractCodeBlock(rawOutput)
        if (extracted == null) {
            // 没有任何代码块：尝试自然语言 fallback
            val steps = tryFallbackParse(rawOutput, taskId)
            return if (steps.isNotEmpty()) {
                ParseResult.Success(steps, strategy = ParseStrategy.NATURAL_LANGUAGE)
            } else {
                ParseResult.Failure("No YAML/JSON/natural language steps found")
            }
        }

        // 2. 尝试 YAML 解析（区分"解析错误"和"验证错误"）
        when (val yamlResult = tryParseStructured(extracted, ParseStrategy.YAML)) {
            is ParseResult.Success -> return yamlResult
            is ParseResult.Failure -> {
                if (yamlResult.parseAttempted) {
                    // 解析成功但验证失败 → 直接返回，不 fallback
                    return yamlResult
                }
                // 解析失败 → 继续尝试 JSON
            }
        }

        // 3. 尝试 JSON 解析
        when (val jsonResult = tryParseStructured(extracted, ParseStrategy.JSON)) {
            is ParseResult.Success -> return jsonResult
            is ParseResult.Failure -> {
                if (jsonResult.parseAttempted) {
                    return jsonResult
                }
            }
        }

        // 4. 最终 fallback 到自然语言
        val steps = tryFallbackParse(rawOutput, taskId)
        return if (steps.isNotEmpty()) {
            ParseResult.Success(steps, strategy = ParseStrategy.NATURAL_LANGUAGE)
        } else {
            ParseResult.Failure("All parsing strategies failed. Last YAML error: extracted text was not valid YAML/JSON")
        }
    }

    /**
     * 尝试用指定策略（YAML/JSON）解析
     * @return ParseResult，Failure 上额外标记是否真正尝试了结构化解析
     */
    private fun tryParseStructured(text: String, strategy: ParseStrategy): ParseResult {
        return try {
            val steps = when (strategy) {
                ParseStrategy.YAML -> parseYamlPlan(text)
                ParseStrategy.JSON -> parseJsonSteps(text)
                ParseStrategy.NATURAL_LANGUAGE -> emptyList()
            }
            if (steps.isEmpty()) {
                return ParseResult.Failure("No steps parsed", parseAttempted = false)
            }
            validateSteps(steps)?.let {
                // 解析成功但验证失败 — 标记 parseAttempted=true
                return it.copy(parseAttempted = true)
            }
            ParseResult.Success(steps, strategy = strategy)
        } catch (e: Exception) {
            logger.warn("[StructuredPlanParser] ${strategy} parse error: ${e.message}")
            ParseResult.Failure("${strategy} parse error: ${e.message}", parseAttempted = false)
        }
    }

    private fun parseJsonSteps(jsonText: String): List<TaskStep> {
        val obj = yamlJson.parseToJsonElement(jsonText).jsonObject
        val stepsArray = obj["steps"] as? JsonArray
            ?: obj["plan"]?.jsonObject?.get("steps") as? JsonArray
        if (stepsArray == null) return emptyList()
        return stepsArray.mapNotNull { el ->
            val stepObj = el as? JsonObject ?: return@mapNotNull null
            parseJsonStep(stepObj)
        }
    }

    /**
     * 解析并验证为 DagTaskPlan（带拓扑排序）
     */
    fun parseAndBuildPlan(rawOutput: String, taskId: String = "unknown", description: String = ""): DagTaskPlan? {
        return when (val result = parse(rawOutput, taskId)) {
            is ParseResult.Success -> {
                val steps = result.steps
                val validation = DagUtils.validateDag(steps)
                if (!validation.isValid) {
                    logger.warn("[StructuredPlanParser] Validation failed: ${validation.errorMessage}")
                    return null
                }
                try {
                    val executionOrder = DagUtils.topologicalSort(steps)
                    val criticalPath = DagUtils.calculateCriticalPath(steps, executionOrder)
                    DagTaskPlan(
                        taskId = taskId,
                        description = description,
                        steps = steps,
                        executionOrder = executionOrder,
                        estimatedSteps = executionOrder.size,
                        totalSteps = steps.size,
                        criticalPath = criticalPath
                    )
                } catch (e: CircularDependencyException) {
                    logger.warn("[StructuredPlanParser] Circular dependency: ${e.message}")
                    null
                }
            }

            is ParseResult.Failure -> {
                logger.debug("[StructuredPlanParser] parse failed: ${result.reason}")
                null
            }
        }
    }

    /**
     * 从 markdown 文本中提取首个代码块（```yaml ... ``` 或 ```json ... ```）
     */
    internal fun extractCodeBlock(text: String): String? {
        // 匹配 ```yaml ... ``` 或 ```json ... ``` 或 ``` ... ```
        val fencePattern = Regex("```(?:yaml|json)?\\s*\\n([\\s\\S]*?)\\n```", RegexOption.MULTILINE)
        val match = fencePattern.find(text) ?: return null
        return match.groupValues[1].trim()
    }

    /**
     * 简单 YAML plan 解析（针对已知 schema）
     */
    internal fun parseYamlPlan(yaml: String): List<TaskStep> {
        val lines = yaml.lines()
        val steps = mutableListOf<TaskStep>()
        var inStepsBlock = false
        var currentStep: MutableMap<String, Any?> = mutableMapOf()

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            val stripped = stripComment(line).trim()
            if (stripped.isEmpty()) continue

            when {
                stripped.matches(Regex("^steps:\\s*$")) -> {
                    inStepsBlock = true
                }

                stripped.startsWith("- ") && inStepsBlock -> {
                    // 新步骤的第一行（可能有内联 id/description）
                    if (currentStep.isNotEmpty()) {
                        steps.add(buildStep(currentStep))
                    }
                    currentStep = mutableMapOf()
                    val rest = stripped.removePrefix("- ").trim()
                    if (rest.contains(":")) {
                        val (k, v) = rest.split(":", limit = 2)
                        currentStep[k.trim()] = unquote(v.trim())
                    }
                }

                stripped.contains(":") && inStepsBlock && currentStep.isNotEmpty() -> {
                    val (k, v) = stripped.split(":", limit = 2)
                    val key = k.trim()
                    val value = v.trim()
                    // 列表类型：dependencies / parallel_with
                    if (key == "dependencies" || key == "parallel_with") {
                        currentStep[key] = parseYamlList(value)
                    } else {
                        currentStep[key] = unquote(value)
                    }
                }

                !inStepsBlock -> {
                    // 顶层字段：plan, version, ...
                }
            }
        }
        if (currentStep.isNotEmpty()) {
            steps.add(buildStep(currentStep))
        }
        return steps
    }

    /**
     * 去掉行尾 # 注释（保留字符串内的 #）
     */
    private fun stripComment(line: String): String {
        // 简单启发式：找不在引号内的 # 并截断
        var inSingle = false
        var inDouble = false
        for ((i, c) in line.withIndex()) {
            when (c) {
                '\'' -> if (!inDouble) inSingle = !inSingle
                '"' -> if (!inSingle) inDouble = !inDouble
                '#' -> if (!inSingle && !inDouble) return line.substring(0, i)
            }
        }
        return line
    }

    private fun buildStep(map: Map<String, Any?>): TaskStep {
        val id = map["id"]?.toString() ?: ""
        val description = map["description"]?.toString() ?: ""
        val dependencies = (map["dependencies"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val parallelWith = (map["parallel_with"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val priority = (map["priority"]?.toString())?.let { p ->
            runCatching { TaskPriority.valueOf(p.uppercase()) }.getOrNull()
        } ?: TaskPriority.MEDIUM
        val durationMs = (map["estimated_duration_ms"]?.toString())?.toLongOrNull() ?: 0L
        return TaskStep(
            id = id,
            description = description,
            dependencies = dependencies,
            parallelWith = parallelWith,
            priority = priority,
            estimatedDurationMs = durationMs
        )
    }

    /**
     * 解析 YAML 内联列表 [a, b, c]
     */
    internal fun parseYamlList(value: String): List<String> {
        val trimmed = value.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val inner = trimmed.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return emptyList()
        return inner.split(",").map { unquote(it.trim()) }.filter { it.isNotEmpty() }
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) {
            return t.substring(1, t.length - 1)
        }
        if (t.startsWith("'") && t.endsWith("'") && t.length >= 2) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    /**
     * 尝试解析 JSON plan（由 [tryParseStructured] 间接调用）
     */


    private fun parseJsonStep(obj: JsonObject): TaskStep? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val description = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
        val deps = (obj["dependencies"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
        val parallel = (obj["parallel_with"] as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)?.contentOrNull
        } ?: emptyList()
        val priorityStr = obj["priority"]?.jsonPrimitive?.contentOrNull
        val priority = priorityStr?.let {
            runCatching { TaskPriority.valueOf(it.uppercase()) }.getOrNull()
        } ?: TaskPriority.MEDIUM
        val duration = obj["estimated_duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        return TaskStep(
            id = id,
            description = description,
            dependencies = deps,
            parallelWith = parallel,
            priority = priority,
            estimatedDurationMs = duration
        )
    }

    /**
     * 验证步骤列表
     */
    internal fun validateSteps(steps: List<TaskStep>): ParseResult.Failure? {
        // 1. 检查 id 唯一性
        val ids = steps.map { it.id }
        if (ids.toSet().size != ids.size) {
            return ParseResult.Failure("Duplicate step IDs: ${ids.groupBy { it }.filter { it.value.size > 1 }.keys}")
        }
        // 2. 检查 dependency 引用存在
        val idSet = ids.toSet()
        for (step in steps) {
            for (dep in step.dependencies) {
                if (dep !in idSet) {
                    return ParseResult.Failure("Step '${step.id}' depends on unknown step '$dep'")
                }
            }
            for (p in step.parallelWith) {
                if (p !in idSet) {
                    return ParseResult.Failure("Step '${step.id}' parallel_with unknown step '$p'")
                }
            }
        }
        // 3. 检查循环依赖
        val adjacency = steps.associate { it.id to it.dependencies }
        val cycle = DagUtils.detectCycle(adjacency)
        if (cycle.isNotEmpty()) {
            return ParseResult.Failure("Circular dependency: ${cycle.joinToString(" -> ")}")
        }
        return null
    }

    /**
     * Fallback：基于自然语言切分
     */
    private fun tryFallbackParse(text: String, taskId: String): List<TaskStep> {
        val analyzer = TaskDependencyAnalyzer()
        return analyzer.parseSteps(text)
    }

    // === 数据类 ===

    sealed class ParseResult {
        data class Success(
            val steps: List<TaskStep>,
            val strategy: ParseStrategy
        ) : ParseResult()

        data class Failure(
            val reason: String,
            val parseAttempted: Boolean = false
        ) : ParseResult()
    }

    enum class ParseStrategy { YAML, JSON, NATURAL_LANGUAGE }
}
