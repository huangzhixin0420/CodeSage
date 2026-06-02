package com.codesage.plugin

import com.codesage.model.gateway.ModelGateway
import com.codesage.model.registry.ModelRegistry
import com.codesage.mcp.server.MCPServerManager
import com.codesage.mcp.transport.MCPServerConfig
import com.codesage.mcp.transport.TransportType
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.config.ProviderTypes
import com.codesage.shared.utils.Logger
import com.codesage.skill.SkillProvider
import com.codesage.skill.builtin.BuiltInSkills
import com.codesage.skill.discovery.DeclarativeSkillLoader
import com.codesage.skill.executor.SkillExecutor
import com.codesage.skill.registry.SkillRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.ExtensionPointName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * CodeSage 应用级服务
 * 管理全局生命周期组件：模型层、技能系统
 */
@Service(Service.Level.APP)
class CodeSageAppService {
    private val logger = Logger.getLogger<CodeSageAppService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val modelRegistry: ModelRegistry = ModelRegistry.getInstance()
    val modelGateway: ModelGateway = ModelGateway.getInstance()
    val skillRegistry: SkillRegistry = SkillRegistry.getInstance()
    val skillExecutor: SkillExecutor = SkillExecutor(skillRegistry)
    val mcpServerManager: MCPServerManager = MCPServerManager(skillRegistry)

    @Volatile
    var isModelLayerInitialized: Boolean = false
        private set

    init {
        logger.info("Initializing CodeSage application service...")
        try {
            initializeSkillSystem()
            initializeMCPServers()
            // 在后台线程初始化模型层，避免在 EDT 上执行慢操作（如 PasswordSafe.getPassword）
            scope.launch {
                initializeModelLayer()
                isModelLayerInitialized = true
                logger.info("Model layer initialization completed")
            }
            logger.info("CodeSage application service initialized successfully")
        } catch (e: Exception) {
            logger.error("Failed to initialize CodeSage application service", e)
        }
    }

    private fun initializeModelLayer() {
        logger.info("Initializing model layer...")
        val config = PluginConfig.getInstance()

        val enabledProviders = config.enabledProviders
        if (enabledProviders.isEmpty()) {
            logger.info("No enabled providers configured")
            return
        }

        enabledProviders.forEach { provider ->
            val apiKey = try {
                config.getProviderApiKey(provider.id)
            } catch (e: Exception) {
                logger.error("Failed to retrieve API key for provider ${provider.name} from PasswordSafe", e)
                return@forEach
            }
            if (apiKey.isNullOrBlank()) {
                logger.warn("Provider ${provider.name} has no API Key, skipping")
                return@forEach
            }

            try {
                when (provider.providerType) {
                    ProviderTypes.MINIMAX -> {
                        modelRegistry.createMiniMaxAdapter(apiKey, provider.baseUrl, provider.models)
                        logger.info("MiniMax adapter registered: ${provider.name}, models: ${provider.models}")
                    }

                    ProviderTypes.KIMI, ProviderTypes.OPENAI, ProviderTypes.OPENAI_COMPATIBLE -> {
                        if (provider.models.isNotEmpty()) {
                            modelRegistry.registerOpenAICompatibleAdapter(
                                name = provider.name,
                                apiKey = apiKey,
                                baseUrl = provider.baseUrl,
                                models = provider.models
                            )
                            logger.info("OpenAI-compatible adapter registered: ${provider.name}")
                        }
                    }

                    else -> {
                        logger.warn("Unknown provider type: ${provider.providerType}")
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to register adapter for ${provider.name}", e)
            }
        }

        val availableModels = modelRegistry.listAvailableModels()
        logger.info("Total available models: ${availableModels.size}")
    }

    private fun initializeSkillSystem() {
        logger.info("Initializing skill system...")
        BuiltInSkills.registerAll(skillRegistry)

        // 加载声明式技能配置
        val declarativeLoader = DeclarativeSkillLoader(skillRegistry)
        val declarativeCount = declarativeLoader.loadDefaultConfigs()

        // 加载外部插件贡献的 Skill
        var externalSkillCount = 0
        try {
            val skillProviders = SkillProvider.EP_NAME.extensionList
            skillProviders.forEach { provider ->
                try {
                    val skills = provider.getSkills()
                    skills.forEach { skillRegistry.register(it) }
                    externalSkillCount += skills.size
                    logger.info("Loaded ${skills.size} skills from provider: ${provider.providerName}")
                } catch (e: Exception) {
                    logger.error("Failed to load skills from provider: ${provider.providerName}", e)
                }
            }
        } catch (e: IllegalArgumentException) {
            // 测试环境中扩展点不可用，安全跳过
            logger.debug("SkillProvider extension point not available (test environment), skipping external skills")
        }

        logger.info(
            "Skill system initialized: ${skillRegistry.getAll().size} total " +
                    "($declarativeCount declarative, $externalSkillCount external)"
        )
    }

    private fun initializeMCPServers() {
        logger.info("Initializing MCP servers...")
        val config = PluginConfig.getInstance()
        val mcpConfigs = config.mcpServerConfigs.filter { it.enabled }

        if (mcpConfigs.isEmpty()) {
            logger.info("No MCP servers configured")
            return
        }

        scope.launch {
            mcpConfigs.forEach { persistentConfig ->
                try {
                    val transportType = when (persistentConfig.transportType.lowercase()) {
                        "stdio" -> TransportType.StdIO(
                            command = persistentConfig.command,
                            args = persistentConfig.args
                        )

                        "http" -> TransportType.HTTP(
                            url = persistentConfig.url
                        )

                        "websocket" -> TransportType.WebSocket(
                            url = persistentConfig.url
                        )

                        else -> {
                            logger.warn("Unknown MCP transport type: ${persistentConfig.transportType}")
                            return@forEach
                        }
                    }

                    val serverConfig = MCPServerConfig(
                        id = persistentConfig.id,
                        name = persistentConfig.name,
                        transportType = transportType
                    )

                    val status = mcpServerManager.addServer(serverConfig)
                    logger.info("MCP server ${persistentConfig.name} status: $status")
                } catch (e: Exception) {
                    logger.error("Failed to initialize MCP server ${persistentConfig.name}", e)
                }
            }
        }
    }

    fun shutdown() {
        logger.info("Shutting down CodeSage application service...")
        try {
            scope.launch {
                mcpServerManager.disconnectAll()
            }
            skillExecutor.shutdown()
            logger.info("CodeSage application service shut down")
        } catch (e: Exception) {
            logger.error("Error shutting down application service", e)
        }
    }

    companion object {
        fun getInstance(): CodeSageAppService =
            ApplicationManager.getApplication().getService(CodeSageAppService::class.java)
    }
}
