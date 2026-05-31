package com.codesage.agent.planner

/**
 * 自然语言依赖关键词定义
 */
enum class DependencyKeyword(
    val keywords: List<String>,
    val dependencyType: DependencyType
) {
    AFTER(
        keywords = listOf("after", "following", "subsequently", "then", "之后", "然后", "接着", "随后"),
        dependencyType = DependencyType.SEQUENTIAL
    ),
    BEFORE(
        keywords = listOf("before", "prior to", "preceding", "ahead of", "之前", "先于"),
        dependencyType = DependencyType.SEQUENTIAL_REVERSE
    ),
    DEPENDS_ON(
        keywords = listOf("depends on", "requires", "needs", "relies on", "依赖", "需要", "依赖于"),
        dependencyType = DependencyType.SEQUENTIAL
    ),
    PARALLEL(
        keywords = listOf("in parallel", "concurrently", "simultaneously", "at the same time", "同时", "并行"),
        dependencyType = DependencyType.PARALLEL
    ),
    INDEPENDENT(
        keywords = listOf("independently", "separately", "on its own", "独立"),
        dependencyType = DependencyType.INDEPENDENT
    )
}

/**
 * 依赖类型
 */
enum class DependencyType {
    /** 顺序依赖：当前步骤在指定步骤之后 */
    SEQUENTIAL,

    /** 反向顺序：当前步骤在指定步骤之前 */
    SEQUENTIAL_REVERSE,

    /** 可并行 */
    PARALLEL,

    /** 独立无依赖 */
    INDEPENDENT
}

/**
 * 解析出的依赖关系
 */
data class ParsedDependency(
    val stepIndex: Int,
    val referencedIndex: Int? = null,
    val dependencyType: DependencyType,
    val confidence: Double = 1.0
)

/**
 * 任务依赖分析器
 * 从自然语言描述中识别任务步骤和依赖关系
 */
class TaskDependencyAnalyzer {

    /**
     * 从自然语言描述中解析任务步骤和依赖关系
     *
     * @param description 用户自然语言描述
     * @return 解析后的任务步骤列表（包含识别出的依赖关系）
     */
    fun parseSteps(description: String): List<TaskStep> {
        // 1. 拆分步骤
        val rawSteps = splitIntoSteps(description)
        if (rawSteps.size <= 1) {
            return rawSteps.mapIndexed { index, desc ->
                TaskStep(
                    id = generateStepId(index),
                    description = desc
                )
            }
        }

        // 2. 识别依赖关系
        val dependencies = analyzeDependencies(rawSteps)

        // 3. 构建 TaskStep（使用 MutableList 以便后续修改）
        val steps = rawSteps.mapIndexed { index, desc ->
            val deps = dependencies
                .filter { it.stepIndex == index && it.dependencyType == DependencyType.SEQUENTIAL }
                .mapNotNull { it.referencedIndex?.let { refIdx -> generateStepId(refIdx) } }

            val parallelWith = dependencies
                .filter { it.stepIndex == index && it.dependencyType == DependencyType.PARALLEL }
                .mapNotNull { it.referencedIndex?.let { refIdx -> generateStepId(refIdx) } }

            TaskStep(
                id = generateStepId(index),
                description = desc,
                dependencies = deps,
                parallelWith = parallelWith
            )
        }.toMutableList()

        // 4. 处理 BEFORE 类型的反向依赖
        dependencies.filter { it.dependencyType == DependencyType.SEQUENTIAL_REVERSE }.forEach { dep ->
            val targetIndex = dep.referencedIndex ?: return@forEach
            val sourceIndex = dep.stepIndex
            // 将反向依赖转换为正向依赖：目标步骤依赖源步骤
            val targetStep = steps.getOrNull(targetIndex)
            if (targetStep != null) {
                val sourceId = generateStepId(sourceIndex)
                if (sourceId !in targetStep.dependencies) {
                    val updatedDeps = targetStep.dependencies + sourceId
                    steps[targetIndex] = targetStep.copy(dependencies = updatedDeps)
                }
            }
        }

        return steps
    }

    /**
     * 从结构化数据（如 LLM 输出的 YAML/JSON）解析任务步骤
     */
    fun parseStructuredSteps(structuredData: List<StructuredStep>): List<TaskStep> {
        return structuredData.mapIndexed { index, data ->
            TaskStep(
                id = data.id ?: generateStepId(index),
                description = data.description,
                dependencies = data.dependencies ?: emptyList(),
                parallelWith = data.parallelWith ?: emptyList(),
                priority = data.priority?.let { TaskPriority.valueOf(it.uppercase()) } ?: TaskPriority.MEDIUM,
                estimatedDurationMs = data.estimatedDurationMs ?: 0L
            )
        }
    }

    /**
     * 将步骤列表拆分为独立步骤
     * 支持多种分隔符：换行、数字编号、逗号/分号等
     */
    private fun splitIntoSteps(description: String): List<String> {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 尝试按换行 + 数字/符号编号拆分
        val numberedPattern = Regex("^\\s*(?:\\d+[.)、.]|[-•*])\\s*(.+)$", RegexOption.MULTILINE)
        val numberedMatches = numberedPattern.findAll(trimmed).toList()

        if (numberedMatches.size >= 2) {
            return numberedMatches.map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }
        }

        // 尝试按换行拆分
        val lines = trimmed.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 2) {
            return lines
        }

        // 按中文/英文分号、逗号拆分
        val delimiters = Regex("(?=[,，;；])|(?<=[,，;；])")
        val parts = trimmed.split(delimiters)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("[,，;；]")) }

        return if (parts.size >= 2) parts else listOf(trimmed)
    }

    /**
     * 分析步骤间的依赖关系
     */
    private fun analyzeDependencies(steps: List<String>): List<ParsedDependency> {
        val dependencies = mutableListOf<ParsedDependency>()

        steps.forEachIndexed { index, stepDesc ->
            val lowerDesc = stepDesc.lowercase()

            // 检查每种依赖关键词
            DependencyKeyword.entries.forEach { keywordDef ->
                keywordDef.keywords.forEach { keyword ->
                    if (lowerDesc.contains(keyword.lowercase())) {
                        val depType = keywordDef.dependencyType
                        when (depType) {
                            DependencyType.SEQUENTIAL -> {
                                // 默认依赖前一步（如果没有明确引用）
                                val referencedIndex = findReferencedIndex(lowerDesc, steps, index) ?: (index - 1)
                                if (referencedIndex >= 0 && referencedIndex != index) {
                                    dependencies.add(
                                        ParsedDependency(
                                            stepIndex = index,
                                            referencedIndex = referencedIndex,
                                            dependencyType = depType
                                        )
                                    )
                                }
                            }

                            DependencyType.SEQUENTIAL_REVERSE -> {
                                val referencedIndex = findReferencedIndex(lowerDesc, steps, index) ?: (index + 1)
                                if (referencedIndex < steps.size && referencedIndex != index) {
                                    dependencies.add(
                                        ParsedDependency(
                                            stepIndex = index,
                                            referencedIndex = referencedIndex,
                                            dependencyType = depType
                                        )
                                    )
                                }
                            }

                            DependencyType.PARALLEL -> {
                                val referencedIndex = findReferencedIndex(lowerDesc, steps, index)
                                dependencies.add(
                                    ParsedDependency(
                                        stepIndex = index,
                                        referencedIndex = referencedIndex,
                                        dependencyType = depType
                                    )
                                )
                            }

                            DependencyType.INDEPENDENT -> {
                                dependencies.add(
                                    ParsedDependency(
                                        stepIndex = index,
                                        referencedIndex = null,
                                        dependencyType = depType
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return dependencies
    }

    /**
     * 尝试从描述中找出引用的步骤索引
     * 例如 "after step 1"、"following the first step" 等
     */
    private fun findReferencedIndex(description: String, steps: List<String>, currentIndex: Int): Int? {
        // 匹配 "step 1", "step 2" 等
        val stepNumberPattern = Regex("step\\s*(\\d+)", RegexOption.IGNORE_CASE)
        stepNumberPattern.find(description)?.let {
            val stepNum = it.groupValues[1].toIntOrNull()
            if (stepNum != null && stepNum in 1..steps.size) {
                return stepNum - 1 // 转换为 0-based 索引
            }
        }

        // 匹配序数词 "first", "second", "third" 等
        val ordinalMap = mapOf(
            "first" to 0, "1st" to 0, "second" to 1, "2nd" to 1,
            "third" to 2, "3rd" to 2, "fourth" to 3, "4th" to 3,
            "fifth" to 4, "5th" to 4
        )
        ordinalMap.entries.forEach { (word, idx) ->
            if (description.contains(word)) {
                if (idx < steps.size) return idx
            }
        }

        // 匹配 "previous" / "上一个"
        if (description.contains("previous") || description.contains("上一个") || description.contains("前一个")) {
            return if (currentIndex > 0) currentIndex - 1 else null
        }

        return null
    }

    /**
     * 从结构化文本（YAML/JSON 风格）解析任务计划
     * 用于解析 LLM 输出的结构化计划
     */
    fun parseFromStructuredText(text: String): List<StructuredStep> {
        val steps = mutableListOf<StructuredStep>()
        val lines = text.lines()
        var currentStep: StructuredStep? = null

        for (line in lines) {
            val trimmed = line.trim()
            when {
                // YAML 风格步骤定义: "- id: step_1" 或 "step_1:"
                trimmed.startsWith("- id:") || trimmed.matches(Regex("^\\s*-\\s*\\w+.*")) -> {
                    currentStep?.let { steps.add(it) }
                    val id = Regex("id:\\s*([^\\s,]+)").find(trimmed)?.groupValues?.get(1)
                    currentStep = StructuredStep(id = id ?: "")
                }

                trimmed.startsWith("id:") || trimmed.startsWith("- id:") -> {
                    currentStep?.let { steps.add(it) }
                    val id = trimmed.substringAfter("id:").trim().trimEnd(',').trim('"', '\'')
                    currentStep = StructuredStep(id = id)
                }

                trimmed.startsWith("description:") || trimmed.startsWith("desc:") -> {
                    currentStep = currentStep?.copy(
                        description = trimmed.substringAfter(":").trim().trim('"', '\'')
                    )
                }

                trimmed.startsWith("dependencies:") || trimmed.startsWith("depends_on:") -> {
                    val deps = trimmed.substringAfter(":").trim()
                        .split(",", " ")
                        .map { it.trim().trim('[', ']', '"', '\'') }
                        .filter { it.isNotEmpty() }
                    currentStep = currentStep?.copy(dependencies = deps)
                }

                trimmed.startsWith("parallel_with:") || trimmed.startsWith("parallel:") -> {
                    val parallel = trimmed.substringAfter(":").trim()
                        .split(",", " ")
                        .map { it.trim().trim('[', ']', '"', '\'') }
                        .filter { it.isNotEmpty() }
                    currentStep = currentStep?.copy(parallelWith = parallel)
                }

                trimmed.startsWith("priority:") -> {
                    currentStep = currentStep?.copy(
                        priority = trimmed.substringAfter(":").trim().trim('"', '\'')
                    )
                }
            }
        }
        currentStep?.let { steps.add(it) }
        return steps.filter { it.description.isNotBlank() || it.id.isNotBlank() }
    }

    private fun generateStepId(index: Int): String = "step_$index"
}

/**
 * 结构化步骤数据（用于从 LLM 输出解析）
 */
data class StructuredStep(
    val id: String = "",
    val description: String = "",
    val dependencies: List<String>? = null,
    val parallelWith: List<String>? = null,
    val priority: String? = null,
    val estimatedDurationMs: Long? = null
)
