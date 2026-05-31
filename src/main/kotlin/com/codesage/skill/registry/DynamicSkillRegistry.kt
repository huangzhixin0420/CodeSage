package com.codesage.skill.registry

import com.codesage.model.dto.Tool
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.skill.Skill
import com.codesage.skill.SkillCategory
import com.codesage.skill.SkillDefinition
import com.codesage.shared.utils.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 动态技能注册表
 *
 * 扩展自 SkillRegistry，新增：
 * - 工具集（toolset）概念：按功能分组启用/禁用
 * - 动态 schema 覆盖：运行时调整技能 schema
 * - TTL 缓存的可用性检查：自动隐藏不可用工具
 * - 生成计数器：支持缓存失效
 *
 * 参考 Hermes 的 ToolRegistry 设计。
 */
class DynamicSkillRegistry : SkillRegistry() {

    private val logger = Logger.getLogger<DynamicSkillRegistry>()
    private val _generation = AtomicInteger(0)

    // 工具集 -> 技能ID 列表
    private val toolsetIndex = ConcurrentHashMap<String, MutableSet<String>>()

    // 工具集可用性检查函数
    private val toolsetChecks = ConcurrentHashMap<String, () -> Boolean>()

    // TTL 缓存：checkFn -> (timestamp, result)
    private val checkFnCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    // 动态 schema 覆盖：技能ID -> 覆盖函数
    private val dynamicSchemaOverrides = ConcurrentHashMap<String, () -> Map<String, Any>>()

    // 技能调用统计：技能ID -> 调用次数
    private val skillUsageStats = ConcurrentHashMap<String, AtomicInteger>()

    // 技能 provenance：技能ID -> 来源
    private val skillProvenance = ConcurrentHashMap<String, String>()

    /**
     * 注册技能到指定工具集
     */
    fun registerWithToolset(skill: Skill, toolset: String) {
        register(skill)
        toolsetIndex.getOrPut(toolset) { ConcurrentHashMap.newKeySet() }.add(skill.id)
        logger.info("Registered skill ${skill.id} to toolset: $toolset")
    }

    /**
     * 注册工具集可用性检查
     */
    fun registerToolsetCheck(toolset: String, checkFn: () -> Boolean) {
        toolsetChecks[toolset] = checkFn
        logger.info("Registered toolset check: $toolset")
    }

    /**
     * 注册带动态 schema 覆盖的技能
     */
    fun registerWithDynamicSchema(skill: Skill, dynamicOverride: () -> Map<String, Any>) {
        register(skill)
        dynamicSchemaOverrides[skill.id] = dynamicOverride
    }

    /**
     * 检查工具集是否可用（带 30s TTL 缓存）
     */
    fun isToolsetAvailable(toolset: String): Boolean {
        val check = toolsetChecks[toolset] ?: return true
        val cacheKey = toolset
        val now = System.currentTimeMillis()

        val cached = checkFnCache[cacheKey]
        if (cached != null && (now - cached.first) < TTL_MS) {
            return cached.second
        }

        val result = try {
            check()
        } catch (e: Exception) {
            logger.warn("Toolset check failed for $toolset: ${e.message}")
            false
        }

        checkFnCache[cacheKey] = now to result
        return result
    }

    /**
     * 获取指定工具集中的可用技能
     */
    fun getByToolset(toolset: String): List<Skill> {
        if (!isToolsetAvailable(toolset)) return emptyList()
        val ids = toolsetIndex[toolset] ?: return emptyList()
        return ids.mapNotNull { get(it) }
    }

    /**
     * 获取指定工具集中的技能作为 Tool 定义
     */
    fun getToolsByToolset(toolset: String): List<Tool> {
        return getByToolset(toolset).map { skillToTool(it) }
    }

    /**
     * 获取所有可用工具（按 toolset 过滤不可用的）
     */
    fun getAllAvailableTools(): List<Tool> {
        return getAll().filter { skill ->
            // 获取该技能所属的所有 toolset
            val toolsets = toolsetIndex.filter { it.value.contains(skill.id) }.keys
            // 如果没有指定 toolset，默认可用
            if (toolsets.isEmpty()) true
            else toolsets.any { isToolsetAvailable(it) }
        }.map { skillToTool(it) }
    }

    /**
     * 获取动态 schema（如果有覆盖）
     */
    fun getDynamicSchema(skillId: String): Map<String, Any>? {
        return dynamicSchemaOverrides[skillId]?.invoke()
    }

    /**
     * 记录技能调用
     */
    fun recordUsage(skillId: String) {
        skillUsageStats.getOrPut(skillId) { AtomicInteger(0) }.incrementAndGet()
    }

    /**
     * 获取技能调用次数
     */
    fun getUsageCount(skillId: String): Int {
        return skillUsageStats[skillId]?.get() ?: 0
    }

    /**
     * 设置技能来源
     */
    fun setProvenance(skillId: String, provenance: String) {
        skillProvenance[skillId] = provenance
    }

    /**
     * 获取技能来源
     */
    fun getProvenance(skillId: String): String {
        return skillProvenance[skillId] ?: "unknown"
    }

    /**
     * 按来源过滤技能
     */
    fun getByProvenance(provenance: String): List<Skill> {
        return skillProvenance.filter { it.value == provenance }.keys.mapNotNull { get(it) }
    }

    /**
     * 增加生成计数器（用于缓存失效检测）
     */
    fun incrementGeneration(): Int = _generation.incrementAndGet()

    /**
     * 获取当前生成计数
     */
    fun getGeneration(): Int = _generation.get()

    /**
     * 获取所有工具集名称
     */
    fun getToolsetNames(): Set<String> = toolsetIndex.keys.toSet()

    /**
     * 将技能转换为 Tool 定义
     */
    private fun skillToTool(skill: Skill): Tool {
        val dynamicSchema = getDynamicSchema(skill.id)
        val schema = dynamicSchema ?: skill.inputSchema

        return Tool(
            name = sanitizeToolName(skill.id),
            description = "[Skill] ${skill.name}: ${skill.description}",
            parameters = convertSchema(schema)
        )
    }

    private fun sanitizeToolName(skillId: String): String {
        val sanitized = skillId.replace(".", "_").replace(" ", "_")
        return if (sanitized.startsWith("builtin_")) "skill_$sanitized" else "skill_$sanitized"
    }

    @Suppress("UNCHECKED_CAST")
    private fun convertSchema(inputSchema: Map<String, Any>): ToolParameters {
        val properties = mutableMapOf<String, ToolProperty>()
        val required = mutableListOf<String>()

        for ((key, value) in inputSchema) {
            if (value is Map<*, *>) {
                val propMap = value as Map<String, Any>
                val type = propMap["type"] as? String ?: "string"
                val description = propMap["description"] as? String
                val enumValues = (propMap["enum"] as? List<*>)?.map { it.toString() }

                properties[key] = ToolProperty(
                    type = type,
                    description = description,
                    enum = enumValues
                )

                if (propMap.containsKey("required") && propMap["required"] == true) {
                    required.add(key)
                }
            }
        }

        return ToolParameters(
            type = "object",
            properties = properties,
            required = required
        )
    }

    companion object {
        const val TTL_MS = 30000L // 30 秒 TTL
    }
}
