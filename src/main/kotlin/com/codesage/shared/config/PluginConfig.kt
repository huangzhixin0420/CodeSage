package com.codesage.shared.config

import com.codesage.shared.utils.Logger
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection

/**
 * 提供商类型常量
 */
object ProviderTypes {
    const val MINIMAX = "minimax"
    const val KIMI = "kimi"
    const val OPENAI = "openai"
    const val OPENAI_COMPATIBLE = "openai-compatible"

    fun displayName(type: String): String = when (type) {
        MINIMAX -> "MiniMax"
        KIMI -> "Kimi (Moonshot)"
        OPENAI -> "OpenAI"
        OPENAI_COMPATIBLE -> "OpenAI 兼容"
        else -> type
    }
}

/**
 * 预置的提供商模板
 */
data class ProviderTemplate(
    val name: String,
    val providerType: String,
    val baseUrl: String,
    val defaultModels: List<String>
) {
    companion object {
        val TEMPLATES = listOf(
            ProviderTemplate(
                name = "MiniMax",
                providerType = ProviderTypes.MINIMAX,
                baseUrl = "https://api.minimaxi.com",
                defaultModels = listOf(
                    "MiniMax-M2.7",
                    "MiniMax-M2.7-highspeed",
                    "MiniMax-M2.5",
                    "MiniMax-M2.5-highspeed",
                    "MiniMax-M2.1",
                    "MiniMax-M2.1-highspeed",
                    "MiniMax-M2"
                )
            ),
            ProviderTemplate(
                name = "Kimi (Moonshot)",
                providerType = ProviderTypes.KIMI,
                baseUrl = "https://api.moonshot.cn",
                defaultModels = listOf(
                    "kimi-k2.6",
                    "kimi-k2.5",
                    "moonshot-v1-auto",
                    "moonshot-v1-8k",
                    "moonshot-v1-32k",
                    "moonshot-v1-128k"
                )
            ),
            ProviderTemplate(
                name = "OpenAI",
                providerType = ProviderTypes.OPENAI,
                baseUrl = "https://api.openai.com",
                defaultModels = listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-3.5-turbo")
            ),
            ProviderTemplate(
                name = "自定义 OpenAI 兼容",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
                baseUrl = "",
                defaultModels = listOf()
            )
        )

        fun findByType(type: String): ProviderTemplate? =
            TEMPLATES.find { it.providerType == type }
    }
}

/**
 * 单个提供商配置
 * 必须使用 var 字段 + 默认构造函数，确保 XmlSerializer 可以正确序列化
 */
class ProviderConfig {
    @Tag("id")
    var id: String = ""

    @Tag("name")
    var name: String = ""

    @Tag("providerType")
    var providerType: String = ProviderTypes.OPENAI_COMPATIBLE

    @Tag("baseUrl")
    var baseUrl: String = ""

    @Tag("models")
    @XCollection(elementTypes = [String::class])
    var models: MutableList<String> = ArrayList()

    @Tag("enabled")
    var isEnabled: Boolean = true

    fun copy(): ProviderConfig {
        val copy = ProviderConfig()
        copy.id = id
        copy.name = name
        copy.providerType = providerType
        copy.baseUrl = baseUrl
        copy.models = ArrayList(models)
        copy.isEnabled = isEnabled
        return copy
    }

    fun isValid(): Boolean = id.isNotBlank()

    override fun toString(): String = name.ifEmpty { ProviderTypes.displayName(providerType) }
}

/**
 * 插件持久化状态
 */
/**
 * MCP服务器持久化配置
 */
class MCPServerPersistentConfig {
    @Tag("id")
    var id: String = ""

    @Tag("name")
    var name: String = ""

    @Tag("transportType")
    var transportType: String = "stdio" // stdio, http, websocket

    @Tag("command")
    var command: String = "" // for stdio

    @Tag("args")
    @XCollection(elementTypes = [String::class])
    var args: MutableList<String> = ArrayList()

    @Tag("url")
    var url: String = "" // for http/websocket

    @Tag("enabled")
    var enabled: Boolean = true

    fun isValid(): Boolean = id.isNotBlank()
}

class PluginConfigState {
    @Tag("providers")
    @XCollection(elementTypes = [ProviderConfig::class])
    var providers: MutableList<ProviderConfig> = ArrayList()

    @Tag("defaultProviderId")
    var defaultProviderId: String = ""

    @Tag("defaultModel")
    var defaultModel: String = ""

    @Tag("codingModel")
    var codingModel: String = ""

    @Tag("reasoningModel")
    var reasoningModel: String = ""

    @Tag("enableStreaming")
    var enableStreaming: Boolean = true

    @Tag("maxContextMessages")
    var maxContextMessages: Int = 50

    @Tag("truncationStrategy")
    var truncationStrategy: String = "HYBRID"

    @Tag("mcpServers")
    @XCollection(elementTypes = [MCPServerPersistentConfig::class])
    var mcpServers: MutableList<MCPServerPersistentConfig> = ArrayList()

    @Tag("promptRole")
    var promptRole: String = "ASSISTANT"

    @Tag("autoSaveEnabled")
    var autoSaveEnabled: Boolean = true

    @Tag("allowContinueOnExhaustion")
    var allowContinueOnExhaustion: Boolean = true
}

/**
 * 插件配置服务
 *
 * 采用"提供商(Provider)为中心"的配置架构：
 * - 支持配置多个 LLM 提供商
 * - 每个提供商独立管理 API Key、Base URL、模型列表
 * - 默认模型从所有已启用提供商的模型中选择
 * - API Key 使用 IntelliJ PasswordSafe 按 providerId 独立存储
 */
@State(
    name = "PluginConfig",
    storages = [Storage("CodeSagePlugin.xml")]
)
@Service(Service.Level.APP)
class PluginConfig : PersistentStateComponent<PluginConfigState> {

    private val logger = Logger.getLogger<PluginConfig>()
    private var state = PluginConfigState()

    override fun getState(): PluginConfigState {
        // M22 修复：getState 在 IDE 启动和配置变更时频繁调用，INFO 级别会污染日志
        logger.debug("getState called, providers count=${state.providers.size}")
        return state
    }

    override fun loadState(newState: PluginConfigState) {
        // M22 修复：loadState 的常规路径走 DEBUG，保留异常路径的 WARN
        // 旧实现即使 providers=0 也打 INFO，会让用户误以为"配置丢失"
        logger.debug("loadState called, newState providers count=${newState.providers.size}")
        state = newState
        // 清理无效数据：过滤掉 id 为空的 provider
        val removed = state.providers.filter { !it.isValid() }
        if (removed.isNotEmpty()) {
            logger.warn("loadState removed ${removed.size} invalid providers: ${removed.map { it.id }}")
        }
        state.providers.removeAll { !it.isValid() }
        logger.info("loadState finished, state providers count=${state.providers.size}")
    }

    // ==================== 提供商管理 ====================

    val providers: List<ProviderConfig>
        get() = state.providers.toList()

    val enabledProviders: List<ProviderConfig>
        get() = state.providers.filter { it.isEnabled }

    fun addProvider(provider: ProviderConfig) {
        if (!provider.isValid()) {
            logger.warn("addProvider skipped invalid provider: id=${provider.id}")
            return
        }
        logger.info("addProvider: id=${provider.id}, name=${provider.name}, type=${provider.providerType}")
        state.providers.add(provider)
    }

    fun updateProvider(updated: ProviderConfig) {
        if (!updated.isValid()) {
            logger.warn("updateProvider skipped invalid provider: id=${updated.id}")
            return
        }
        val index = state.providers.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            logger.info("updateProvider: id=${updated.id}, name=${updated.name}")
            state.providers[index] = updated
        } else {
            logger.warn("updateProvider failed, provider not found: id=${updated.id}")
        }
    }

    fun removeProvider(providerId: String) {
        logger.info("removeProvider: id=$providerId")
        state.providers.removeAll { it.id == providerId }
        // 清理该提供商的 API Key
        savePassword(apiKeyId(providerId), null)
        // 如果删除的是默认提供商，清空默认设置
        if (state.defaultProviderId == providerId) {
            state.defaultProviderId = ""
            state.defaultModel = ""
        }
    }

    fun getProvider(providerId: String): ProviderConfig? =
        state.providers.find { it.id == providerId }

    // ==================== 默认模型 ====================

    var defaultProviderId: String
        get() = state.defaultProviderId
        set(value) {
            state.defaultProviderId = value
        }

    var defaultModel: String
        get() = state.defaultModel
        set(value) {
            state.defaultModel = value
        }

    var codingModel: String
        get() = state.codingModel
        set(value) {
            state.codingModel = value
        }

    var reasoningModel: String
        get() = state.reasoningModel
        set(value) {
            state.reasoningModel = value
        }

    /**
     * 获取当前默认模型所属的提供商配置
     */
    fun getDefaultProvider(): ProviderConfig? {
        val provider = state.providers.find { it.id == state.defaultProviderId && it.isEnabled }
        if (provider != null) return provider

        // 回退：尝试根据模型名反查提供商
        return state.providers.find {
            it.isEnabled && it.models.contains(state.defaultModel)
        } ?: enabledProviders.firstOrNull()
    }

    /**
     * 获取所有可用模型（带提供商前缀显示）
     */
    fun listAllAvailableModels(): List<Pair<String, String>> {
        return enabledProviders.flatMap { provider ->
            provider.models.map { model ->
                Pair(model, provider.name.ifEmpty { provider.providerType })
            }
        }
    }

    // ==================== 通用设置 ====================

    var enableStreaming: Boolean
        get() = state.enableStreaming
        set(value) {
            state.enableStreaming = value
        }

    var maxContextMessages: Int
        get() = state.maxContextMessages
        set(value) {
            state.maxContextMessages = value
        }

    var truncationStrategy: String
        get() = state.truncationStrategy
        set(value) {
            state.truncationStrategy = value
        }

    var allowContinueOnExhaustion: Boolean
        get() = state.allowContinueOnExhaustion
        set(value) {
            state.allowContinueOnExhaustion = value
        }

    var promptRole: String
        get() = state.promptRole
        set(value) {
            state.promptRole = value
        }

    var autoSaveEnabled: Boolean
        get() = state.autoSaveEnabled
        set(value) {
            state.autoSaveEnabled = value
        }

    // ==================== API Key 管理 ====================

    /**
     * 获取指定提供商的 API Key
     */
    fun getProviderApiKey(providerId: String): String? {
        return loadPassword(apiKeyId(providerId))
    }

    /**
     * 设置指定提供商的 API Key
     */
    fun setProviderApiKey(providerId: String, apiKey: String?) {
        if (apiKey.isNullOrBlank()) {
            savePassword(apiKeyId(providerId), null)
        } else {
            savePassword(apiKeyId(providerId), apiKey)
        }
    }

    // ==================== 兼容性方法 ====================

    @Deprecated("Use provider-based configuration instead")
    val miniMaxApiKey: String
        get() = findLegacyProviderKey(ProviderTypes.MINIMAX) ?: ""

    @Deprecated("Use provider-based configuration instead")
    val kimiApiKey: String
        get() = findLegacyProviderKey(ProviderTypes.KIMI) ?: ""

    private fun findLegacyProviderKey(type: String): String? {
        return state.providers
            .find { it.providerType == type }
            ?.let { getProviderApiKey(it.id) }
    }

    // ==================== MCP 服务器管理 ====================

    val mcpServerConfigs: List<MCPServerPersistentConfig>
        get() = state.mcpServers.toList()

    fun addMCPServer(config: MCPServerPersistentConfig) {
        if (!config.isValid()) {
            logger.warn("addMCPServer skipped invalid config: id=${config.id}")
            return
        }
        state.mcpServers.add(config)
        logger.info("addMCPServer: id=${config.id}, name=${config.name}")
    }

    fun removeMCPServer(serverId: String) {
        state.mcpServers.removeAll { it.id == serverId }
        logger.info("removeMCPServer: id=$serverId")
    }

    fun getMCPServer(serverId: String): MCPServerPersistentConfig? =
        state.mcpServers.find { it.id == serverId }

    fun updateMCPServer(updated: MCPServerPersistentConfig) {
        val index = state.mcpServers.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            state.mcpServers[index] = updated
        }
    }

    // ==================== 检查配置状态 ====================

    fun isConfigured(): Boolean {
        return enabledProviders.any { provider ->
            !getProviderApiKey(provider.id).isNullOrBlank()
        }
    }

    // ==================== 密码存储私有方法 ====================

    private fun apiKeyId(providerId: String): String = "provider:$providerId:apikey"

    private fun loadPassword(key: String): String? {
        return try {
            PasswordSafe.instance.getPassword(createCredentialAttributes(key))
        } catch (e: Exception) {
            null
        }
    }

    private fun savePassword(key: String, value: String?) {
        try {
            PasswordSafe.instance.setPassword(createCredentialAttributes(key), value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createCredentialAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(
            /* serviceName = */ "CodeSage:$key",
            /* userName = */ key
        )
    }

    companion object {
        fun getInstance(): PluginConfig {
            return ApplicationManager.getApplication().service<PluginConfig>()
        }
    }
}
