package com.codesage.skill.registry

import com.codesage.skill.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * 技能注册中心
 * 管理所有可用技能
 */
open class SkillRegistry {
    private val logger = Logger.getLogger<SkillRegistry>()

    protected open val skills = ConcurrentHashMap<String, Skill>()
    private val categoryIndex = ConcurrentHashMap<SkillCategory, MutableList<String>>()

    private val _skillChanges = MutableSharedFlow<SkillChangeEvent>()
    val skillChanges: SharedFlow<SkillChangeEvent> = _skillChanges.asSharedFlow()

    /**
     * 注册技能
     */
    fun register(skill: Skill) {
        skills[skill.id] = skill
        categoryIndex.getOrPut(skill.category) { mutableListOf() }
            .add(skill.id)

        logger.info("Registered skill: ${skill.id}")

        _skillChanges.tryEmit(SkillChangeEvent.Added(skill))
    }

    /**
     * 批量注册技能
     */
    fun registerAll(newSkills: List<Skill>) {
        newSkills.forEach { register(it) }
    }

    /**
     * 移除技能
     */
    fun unregister(skillId: String) {
        val skill = skills.remove(skillId)
        if (skill != null) {
            categoryIndex[skill.category]?.remove(skillId)
            logger.info("Unregistered skill: $skillId")
            _skillChanges.tryEmit(SkillChangeEvent.Removed(skillId))
        }
    }

    /**
     * 获取技能
     */
    fun get(skillId: String): Skill? = skills[skillId]

    /**
     * 获取所有技能
     */
    fun getAll(): List<Skill> = skills.values.toList()

    /**
     * 按类别获取技能
     */
    fun getByCategory(category: SkillCategory): List<Skill> {
        return categoryIndex[category]?.mapNotNull { skills[it] } ?: emptyList()
    }

    /**
     * 按标签搜索技能
     */
    fun searchByTags(tags: Set<String>): List<Skill> {
        return skills.values.filter { skill ->
            tags.any { tag -> tag in skill.tags }
        }
    }

    /**
     * 搜索技能 (名称或描述)
     */
    fun search(query: String): List<Skill> {
        val lowerQuery = query.lowercase()
        return skills.values.filter { skill ->
            skill.name.lowercase().contains(lowerQuery) ||
            skill.description.lowercase().contains(lowerQuery) ||
            skill.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * 检查技能是否存在
     */
    fun contains(skillId: String): Boolean = skills.containsKey(skillId)

    /**
     * 获取技能数量
     */
    fun count(): Int = skills.size

    /**
     * 获取所有技能ID
     */
    fun getAllIds(): Set<String> = skills.keys.toSet()

    /**
     * 清空所有技能
     */
    fun clear() {
        skills.clear()
        categoryIndex.clear()
        logger.info("Cleared all skills")
    }

    /**
     * 热更新技能
     */
    open fun hotReload(skill: Skill) {
        if (skills.containsKey(skill.id)) {
            skills[skill.id] = skill
            _skillChanges.tryEmit(SkillChangeEvent.Updated(skill))
            logger.info("Hot reloaded skill: ${skill.id}")
        } else {
            register(skill)
        }
    }

    companion object {
        @Volatile
        private var instance: SkillRegistry? = null

        fun getInstance(): SkillRegistry {
            return instance ?: synchronized(this) {
                instance ?: SkillRegistry().also { instance = it }
            }
        }
    }
}

/**
 * 可热更新的技能注册中心
 */
class HotReloadableSkillRegistry : SkillRegistry() {
    private val versionCache = ConcurrentHashMap<String, String>()

    fun update(skill: Skill) {
        versionCache[skill.id] = System.currentTimeMillis().toString()
        super.hotReload(skill)
    }

    fun needsReload(skillId: String, newVersion: String): Boolean {
        val cached = versionCache[skillId]
        return cached == null || cached != newVersion
    }
}
