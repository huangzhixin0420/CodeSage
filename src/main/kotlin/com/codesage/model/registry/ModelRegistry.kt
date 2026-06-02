package com.codesage.model.registry

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.adapter.minimax.MiniMaxAdapter
import com.codesage.model.dto.ModelInfo

/**
 * 模型注册中心
 * 管理所有模型适配器
 */
class ModelRegistry {

    private val adapters = mutableMapOf<String, ModelAdapter>()
    private val modelToProvider = mutableMapOf<String, String>()

    /**
     * 注册适配器
     */
    fun register(adapter: ModelAdapter) {
        adapters[adapter.providerName] = adapter
        adapter.supportedModels.forEach { model ->
            modelToProvider[model] = adapter.providerName
        }
    }

    /**
     * 获取指定模型对应的适配器
     */
    fun getAdapterForModel(model: String): ModelAdapter? {
        val provider = modelToProvider[model] ?: return null
        return adapters[provider]
    }

    /**
     * 获取提供商适配器
     */
    fun getAdapter(provider: String): ModelAdapter? = adapters[provider]

    /**
     * 列出所有可用模型
     */
    fun listAvailableModels(): List<ModelInfo> {
        return adapters.values.flatMap { adapter ->
            adapter.supportedModels.map { model ->
                adapter.getModelInfo(model)
            }
        }
    }

    /**
     * 获取提供商列表
     */
    fun listProviders(): List<String> = adapters.keys.toList()

    /**
     * T1.1 修复：按能力反查适配器。
     *
     * 接受一个能力集合，返回所有声明支持这些能力的适配器。
     * 用于 T1.4 SmartRouter 的反查逻辑。
     */
    fun getAdaptersForCapabilities(required: Set<com.codesage.model.dto.Capability>): List<ModelAdapter> {
        return adapters.values.filter { adapter ->
            adapter.capabilities.let { caps -> required.all { caps.hasCapability(it) } }
        }
    }

    /**
     * T1.4 预置：按能力获取首个可用适配器
     */
    fun getFirstAdapterForCapabilities(required: Set<com.codesage.model.dto.Capability>): ModelAdapter? =
        getAdaptersForCapabilities(required).firstOrNull()

    /**
     * 创建MiniMax适配器
     * @param models 用户配置的模型列表，传入后覆盖默认值
     */
    fun createMiniMaxAdapter(apiKey: String, baseUrl: String? = null, models: List<String>? = null): MiniMaxAdapter {
        val adapter = MiniMaxAdapter(apiKey, baseUrl ?: "https://api.minimaxi.com", models)
        register(adapter)
        return adapter
    }

    /**
     * 注册自定义 OpenAI 兼容适配器
     */
    fun registerOpenAICompatibleAdapter(
        name: String,
        apiKey: String,
        baseUrl: String,
        models: List<String>
    ): com.codesage.model.adapter.OpenAICompatibleAdapter {
        val isKimiCoding = baseUrl.contains("kimi.com/coding", ignoreCase = true)
        val adapter = object : com.codesage.model.adapter.OpenAICompatibleAdapter(apiKey, baseUrl) {
            override val providerName: String = name.lowercase().replace(" ", "_")
            override val supportedModels: List<String> = models
            override val chatEndpointPath: String = "/v1/chat/completions"
            override val userAgent: String = if (isKimiCoding) "claude-code/0.1.0" else super.userAgent
        }
        register(adapter)
        return adapter
    }

    companion object {
        @Volatile
        private var instance: ModelRegistry? = null

        fun getInstance(): ModelRegistry {
            return instance ?: synchronized(this) {
                instance ?: ModelRegistry().also { instance = it }
            }
        }
    }
}
