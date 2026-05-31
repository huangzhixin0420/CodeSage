package com.codesage.prompt.version

import com.codesage.prompt.engine.PromptTemplate
import com.codesage.shared.utils.Logger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 提示版本管理器
 * 管理系统提示的版本历史，支持A/B测试和回滚
 */
class PromptVersionManager(
    private val storageDir: File = File(System.getProperty("user.home"), ".codesage/prompts")
) {
    private val logger = Logger.getLogger<PromptVersionManager>()
    private val versions = ConcurrentHashMap<String, PromptVersion>()
    private var activeVersionId: String? = null

    init {
        storageDir.mkdirs()
        loadVersions()
    }

    /**
     * 提示版本记录
     */
    data class PromptVersion(
        val id: String,
        val name: String,
        val template: String,
        val hash: String,
        val createdAt: Long = System.currentTimeMillis(),
        val tags: Set<String> = emptySet(),
        val metrics: VersionMetrics = VersionMetrics()
    )

    /**
     * 版本性能指标
     */
    data class VersionMetrics(
        val invocationCount: Long = 0,
        val avgToolCalls: Double = 0.0,
        val avgTokensUsed: Long = 0,
        val successRate: Double = 1.0
    )

    /**
     * 注册新版本提示
     */
    fun register(name: String, template: String, tags: Set<String> = emptySet()): String {
        val id = generateVersionId(template)
        val version = PromptVersion(
            id = id,
            name = name,
            template = template,
            hash = hash(template),
            tags = tags
        )
        versions[id] = version
        saveVersion(version)
        logger.info("Registered prompt version: $id ($name)")
        return id
    }

    /**
     * 激活指定版本
     */
    fun activate(versionId: String): Boolean {
        if (!versions.containsKey(versionId)) {
            logger.warn("Version not found: $versionId")
            return false
        }
        activeVersionId = versionId
        logger.info("Activated prompt version: $versionId")
        return true
    }

    /**
     * 获取当前激活的提示模板
     */
    fun getActiveTemplate(): PromptTemplate? {
        val version = getActiveVersion() ?: return null
        return PromptTemplate(version.template)
    }

    /**
     * 获取当前激活版本
     */
    fun getActiveVersion(): PromptVersion? = activeVersionId?.let { versions[it] }

    /**
     * 获取所有版本
     */
    fun getAllVersions(): List<PromptVersion> = versions.values.sortedByDescending { it.createdAt }

    /**
     * 获取指定版本
     */
    fun getVersion(id: String): PromptVersion? = versions[id]

    /**
     * 删除版本
     */
    fun deleteVersion(id: String): Boolean {
        if (id == activeVersionId) {
            logger.warn("Cannot delete active version: $id")
            return false
        }
        versions.remove(id)
        File(storageDir, "$id.prompt").delete()
        return true
    }

    /**
     * 更新版本指标
     */
    fun recordMetrics(versionId: String, toolCalls: Int, tokensUsed: Long, success: Boolean) {
        versions[versionId]?.let { v ->
            val newCount = v.metrics.invocationCount + 1
            val newAvgToolCalls = (v.metrics.avgToolCalls * v.metrics.invocationCount + toolCalls) / newCount
            val newAvgTokens = (v.metrics.avgTokensUsed * v.metrics.invocationCount + tokensUsed) / newCount
            val newSuccessRate = (v.metrics.successRate * v.metrics.invocationCount + if (success) 1 else 0) / newCount

            val updated = v.copy(
                metrics = VersionMetrics(
                    invocationCount = newCount,
                    avgToolCalls = newAvgToolCalls,
                    avgTokensUsed = newAvgTokens,
                    successRate = newSuccessRate
                )
            )
            versions[versionId] = updated
        }
    }

    /**
     * A/B测试：随机选择版本
     */
    fun selectForABTest(versionIds: List<String>): String? {
        if (versionIds.isEmpty()) return activeVersionId
        val available = versionIds.filter { versions.containsKey(it) }
        if (available.isEmpty()) return activeVersionId
        return available.random()
    }

    private fun generateVersionId(template: String): String {
        val hash = hash(template).take(8)
        val timestamp = System.currentTimeMillis()
        return "v_${timestamp}_$hash"
    }

    private fun hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun saveVersion(version: PromptVersion) {
        try {
            val file = File(storageDir, "${version.id}.prompt")
            file.writeText(version.template)
        } catch (e: Exception) {
            logger.error("Failed to save prompt version", e)
        }
    }

    private fun loadVersions() {
        try {
            storageDir.listFiles { _, name -> name.endsWith(".prompt") }?.forEach { file ->
                val id = file.nameWithoutExtension
                val template = file.readText()
                val version = PromptVersion(
                    id = id,
                    name = id,
                    template = template,
                    hash = hash(template)
                )
                versions[id] = version
            }
            logger.info("Loaded ${versions.size} prompt versions")
        } catch (e: Exception) {
            logger.error("Failed to load prompt versions", e)
        }
    }
}
