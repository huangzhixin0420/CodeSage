package com.codesage.skill.curator

import com.codesage.agent.core.AgentCore
import com.codesage.model.dto.Message
import com.codesage.model.dto.Role
import com.codesage.skill.Skill
import com.codesage.skill.SkillCategory
import com.codesage.skill.SkillDefinition
import com.codesage.skill.SkillImplementationType
import com.codesage.skill.registry.DynamicSkillRegistry
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 技能策展器
 *
 * 参考 Hermes 的 curator.py 设计：
 * 1. 后台审查 fork：分析对话历史，识别重复模式，自动创建/改进技能
 * 2. 定期策展：合并重复技能、删除未使用的 agent_created 技能
 *
 * 触发条件：
 * - 复杂任务使用了 >5 个 tool iterations
 * - 每 N 轮对话（memory nudge interval）
 * - 用户显式触发 /curate
 */
class SkillCurator(
    private val agentCore: AgentCore,
    private val skillRegistry: DynamicSkillRegistry
) {

    private val logger = Logger.getLogger<SkillCurator>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val isReviewing = AtomicBoolean(false)

    // 自动保存目录
    private val autoSkillDir: File by lazy {
        File(System.getProperty("user.home"), ".codesage/skills/auto").apply { mkdirs() }
    }

    // 审查历史：避免重复审查同一会话
    private val reviewedSessions = ConcurrentHashMap.newKeySet<String>()

    // 技能使用统计窗口（最近 N 天）
    private val skillLastUsed = ConcurrentHashMap<String, Long>()

    /**
     * 后台审查 fork
     *
     * 在独立协程中运行，分析对话历史，识别是否需要新技能。
     */
    suspend fun runBackgroundReview(
        sessionId: String,
        conversationHistory: List<Message>,
        toolIterations: Int
    ) {
        if (isReviewing.get()) {
            logger.info("Background review already in progress, skipping")
            return
        }

        if (reviewedSessions.contains(sessionId)) {
            logger.info("Session $sessionId already reviewed, skipping")
            return
        }

        // 触发条件：复杂任务使用了 >5 个 tool iterations
        if (toolIterations <= 5) {
            logger.info("Tool iterations ($toolIterations) <= 5, skipping background review")
            return
        }

        if (!isReviewing.compareAndSet(false, true)) {
            return
        }

        try {
            logger.info("Starting background review for session $sessionId")
            SkillProvenance.set(SkillProvenance.BACKGROUND_REVIEW)

            // 1. 分析对话历史，识别重复模式
            val patterns = analyzeConversationPatterns(conversationHistory)

            // 2. 判断是否需要新技能
            val missingSkills = identifyMissingSkills(patterns)

            // 3. 为识别的缺失技能生成定义
            for (pattern in missingSkills) {
                if (!skillRegistry.contains(pattern.suggestedSkillId)) {
                    val skillDef = generateSkillDefinition(pattern)
                    saveAutoSkill(skillDef)
                    logger.info("Auto-generated skill: ${skillDef.id} from pattern: ${pattern.description}")
                }
            }

            // 4. 标记已审查
            reviewedSessions.add(sessionId)

            logger.info("Background review completed for session $sessionId")
        } catch (e: Exception) {
            logger.error("Background review failed", e)
        } finally {
            SkillProvenance.reset()
            isReviewing.set(false)
        }
    }

    /**
     * 定期策展：合并重复技能、删除未使用的技能
     */
    suspend fun consolidate() {
        logger.info("Starting skill consolidation")

        try {
            // 1. 按功能相似度聚类（基于名称和标签）
            val clusters = clusterSimilarSkills()

            // 2. 合并重复技能
            for (cluster in clusters) {
                if (cluster.size > 1) {
                    mergeSkillCluster(cluster)
                }
            }

            // 3. 删除 30 天未调用的 agent_created 技能
            val now = System.currentTimeMillis()
            val threshold = 30L * 24 * 60 * 60 * 1000 // 30 天

            val toRemove = skillRegistry.getByProvenance(SkillProvenance.AGENT_CREATED).filter { skill ->
                val lastUsed = skillLastUsed[skill.id] ?: 0L
                val age = now - lastUsed
                age > threshold && skillRegistry.getUsageCount(skill.id) == 0
            }

            toRemove.forEach { skill ->
                skillRegistry.unregister(skill.id)
                deleteAutoSkillFile(skill.id)
                logger.info("Pruned unused skill: ${skill.id}")
            }

            logger.info("Skill consolidation completed. Removed ${toRemove.size} skills.")
        } catch (e: Exception) {
            logger.error("Skill consolidation failed", e)
        }
    }

    /**
     * 手动触发策展
     */
    suspend fun curateNow() {
        consolidate()
    }

    /**
     * 记录技能使用
     */
    fun recordSkillUsed(skillId: String) {
        skillLastUsed[skillId] = System.currentTimeMillis()
        skillRegistry.recordUsage(skillId)
    }

    // === 内部方法 ===

    data class Pattern(
        val description: String,
        val frequency: Int,
        val suggestedSkillId: String,
        val suggestedName: String,
        val category: SkillCategory
    )

    /**
     * 分析对话历史中的重复模式
     *
     * 轻量级规则引擎：检测重复的工具调用模式、相似的用户请求
     */
    private fun analyzeConversationPatterns(history: List<Message>): List<Pattern> {
        val patterns = mutableListOf<Pattern>()

        // 统计工具调用频率
        val toolCallCounts = mutableMapOf<String, Int>()
        history.forEach { msg ->
            msg.toolCalls?.forEach { toolCall ->
                toolCallCounts[toolCall.name] = toolCallCounts.getOrDefault(toolCall.name, 0) + 1
            }
        }

        // 检测高频工具组合（>3 次）
        val frequentTools = toolCallCounts.filter { it.value >= 3 }
        if (frequentTools.size >= 2) {
            val toolNames = frequentTools.keys.sorted().joinToString("_")
            patterns.add(
                Pattern(
                    description = "Frequent use of ${frequentTools.keys.joinToString(", ")}",
                    frequency = frequentTools.values.sum(),
                    suggestedSkillId = "auto_combined_$toolNames",
                    suggestedName = "Combined ${frequentTools.keys.joinToString(" and ")}",
                    category = SkillCategory.CUSTOM
                )
            )
        }

        // 检测重复的用户请求模式（关键词匹配）
        val userMessages = history.filter { it.role == Role.USER }.map { it.content }
        val commonPrefixes = listOf("create", "generate", "refactor", "fix", "analyze", "search")

        commonPrefixes.forEach { prefix ->
            val matches = userMessages.filter { it.lowercase().startsWith(prefix) }
            if (matches.size >= 3) {
                patterns.add(
                    Pattern(
                        description = "Frequent $prefix requests (${matches.size} times)",
                        frequency = matches.size,
                        suggestedSkillId = "auto_${prefix}_helper",
                        suggestedName = "${prefix.replaceFirstChar { it.uppercase() }} Helper",
                        category = SkillCategory.CUSTOM
                    )
                )
            }
        }

        return patterns.sortedByDescending { it.frequency }
    }

    /**
     * 识别缺失技能（过滤已有技能）
     */
    private fun identifyMissingSkills(patterns: List<Pattern>): List<Pattern> {
        return patterns.filter { pattern ->
            // 检查是否已存在类似技能
            val existing = skillRegistry.search(pattern.suggestedName)
            existing.isEmpty()
        }
    }

    /**
     * 生成技能定义（JSON/YAML）
     */
    private fun generateSkillDefinition(pattern: Pattern): SkillDefinition {
        return SkillDefinition(
            id = pattern.suggestedSkillId,
            name = pattern.suggestedName,
            description = "Auto-generated skill based on observed pattern: ${pattern.description}",
            version = "0.1.0",
            category = pattern.category,
            tags = setOf("auto-generated", "curated"),
            inputSchema = mapOf(
                "query" to mapOf(
                    "type" to "string",
                    "description" to "Input query for the ${pattern.suggestedName}",
                    "required" to true
                )
            ),
            outputSchema = mapOf(
                "result" to mapOf(
                    "type" to "string",
                    "description" to "Execution result"
                )
            ),
            implementation = SkillImplementationType.External(
                type = "script",
                script = "# Auto-generated skill placeholder\n# Implement your logic here"
            ),
            config = mapOf(
                "provenance" to SkillProvenance.AGENT_CREATED,
                "generated_at" to System.currentTimeMillis()
            )
        )
    }

    /**
     * 保存自动生成的技能到文件系统
     */
    private fun saveAutoSkill(definition: SkillDefinition) {
        val file = File(autoSkillDir, "${definition.id}.json")
        file.writeText(json.encodeToString(definition))
        logger.info("Saved auto-skill to: ${file.absolutePath}")
    }

    /**
     * 删除自动生成的技能文件
     */
    private fun deleteAutoSkillFile(skillId: String) {
        val file = File(autoSkillDir, "$skillId.json")
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * 加载所有自动生成的技能定义
     */
    fun loadAutoSkills(): List<SkillDefinition> {
        if (!autoSkillDir.exists()) return emptyList()

        return autoSkillDir.listFiles { f -> f.extension == "json" }?.mapNotNull { file ->
            try {
                json.decodeFromString<SkillDefinition>(file.readText())
            } catch (e: Exception) {
                logger.error("Failed to load auto-skill: ${file.name}", e)
                null
            }
        } ?: emptyList()
    }

    /**
     * 按功能相似度聚类技能
     */
    private fun clusterSimilarSkills(): List<List<Skill>> {
        val allSkills = skillRegistry.getAll()
        val clusters = mutableListOf<MutableList<Skill>>()

        for (skill in allSkills) {
            var added = false
            for (cluster in clusters) {
                if (isSimilar(skill, cluster.first())) {
                    cluster.add(skill)
                    added = true
                    break
                }
            }
            if (!added) {
                clusters.add(mutableListOf(skill))
            }
        }

        return clusters.filter { it.size > 1 }
    }

    /**
     * 判断两个技能是否相似（基于名称、描述、标签）
     */
    private fun isSimilar(a: Skill, b: Skill): Boolean {
        val nameSimilarity = calculateSimilarity(a.name.lowercase(), b.name.lowercase())
        val descSimilarity = calculateSimilarity(a.description.lowercase(), b.description.lowercase())
        val tagOverlap = a.tags.intersect(b.tags).size.toDouble() / maxOf(a.tags.size, b.tags.size, 1)

        return nameSimilarity > 0.7 || descSimilarity > 0.6 || tagOverlap > 0.5
    }

    /**
     * 简单的字符串相似度（基于公共子串比例）
     */
    private fun calculateSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0

        val longer = if (a.length > b.length) a else b
        val shorter = if (a.length > b.length) b else a

        // 检查是否一个包含另一个
        if (longer.contains(shorter)) return shorter.length.toDouble() / longer.length

        // 基于单词重叠
        val wordsA = a.split(Regex("""\s+""")).toSet()
        val wordsB = b.split(Regex("""\s+""")).toSet()
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size

        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    /**
     * 合并技能簇（保留使用最多的，其余删除）
     */
    private fun mergeSkillCluster(cluster: List<Skill>) {
        val keeper = cluster.maxByOrNull { skillRegistry.getUsageCount(it.id) } ?: cluster.first()
        val toRemove = cluster.filter { it.id != keeper.id }

        toRemove.forEach { skill ->
            skillRegistry.unregister(skill.id)
            logger.info("Merged skill ${skill.id} into ${keeper.id}")
        }
    }

    companion object {
        const val CONSOLIDATION_INTERVAL_DAYS = 7L
    }
}
