package com.codesage.acp.manager

import com.codesage.acp.server.AcpServer
import com.codesage.acp.server.AcpSocketServer
import com.codesage.acp.transport.AcpProcessTransport
import com.codesage.agent.tools.ToolExecutor
import com.codesage.agent.tools.ToolRegistry
import com.codesage.model.dto.AcpAgentEntry
import com.codesage.model.dto.AcpSection
import com.codesage.shared.config.SettingsRepository
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * ACP 全局管理器
 *
 * 负责：
 * 1. 根据 [AcpSection] 配置启动/停止本地 ACP Socket 服务端；
 * 2. 连接配置的外部 ACP agent（如 Kimi CLI）并同步其能力；
 * 3. 响应 [SettingsRepository.changes] 实现动态启停。
 */
class AcpServerManager(
    private val projectProvider: () -> Project? = { ProjectManager.getInstance().openProjects.firstOrNull() },
    private val settingsProvider: () -> com.codesage.model.dto.AcpSection = {
        SettingsRepository.getInstance().get().acp
    }
) {
    private val logger = Logger.getLogger<AcpServerManager>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socketServer: AcpSocketServer? = null

    init {
        // 监听配置变更，动态启停 ACP 服务端
        SettingsRepository.getInstance().changes
            .onEach { settings -> applySettings(settings.acp) }
            .launchIn(scope)
    }

    /**
     * 首次初始化：应用当前配置
     */
    fun initialize() {
        applySettings(settingsProvider())
    }

    /**
     * 关闭所有 ACP 连接并取消后台协程
     */
    fun shutdown() {
        stopServer()
        scope.cancel()
    }

    private fun applySettings(config: AcpSection) {
        if (config.enabled) {
            startServer(config.serverPort)
        } else {
            stopServer()
        }

        // 外部 ACP agent 目前作为 client 连接；在独立协程中建立连接
        config.externalAgents.filter { it.enabled }.forEach { agent ->
            scope.launch {
                connectExternalAgent(agent)
            }
        }
    }

    private fun startServer(port: Int) {
        if (socketServer != null) return
        try {
            val registry = createToolRegistry()
            val server = AcpServer(
                toolRegistry = registry,
                toolExecutorFactory = { createToolExecutor(registry) }
            )
            val socketServer = AcpSocketServer(
                sessionFactory = { server },
                port = port
            )
            socketServer.start()
            this.socketServer = socketServer
            logger.info("ACP server started on port ${socketServer.actualPort}")
        } catch (e: Exception) {
            logger.error("Failed to start ACP server", e)
        }
    }

    private fun stopServer() {
        socketServer?.stop()
        socketServer = null
    }

    private fun createToolRegistry(): ToolRegistry {
        val project = projectProvider()
        return ToolRegistry.createDefault(
            project = project,
            skillRegistry = null,
            skillExecutor = null
        )
    }

    private fun createToolExecutor(registry: ToolRegistry): ToolExecutor {
        val project = projectProvider()
        return ToolExecutor(
            project = project,
            toolRegistry = registry
        )
    }

    private suspend fun connectExternalAgent(agent: AcpAgentEntry) {
        logger.info("Connecting to external ACP agent: ${agent.name}")
        try {
            val transport = AcpProcessTransport(
                command = agent.command,
                args = agent.args,
                env = agent.env,
                workingDir = agent.workingDir?.let { java.io.File(it) }
            )
            val client = com.codesage.acp.client.AcpClient(transport)
            client.initialize()
            val tools = client.listTools()
            logger.info("External ACP agent ${agent.name} exposes ${tools.size} tools")
            // TODO: 将远端 ACP agent 的工具同步为 CodeSage Skill，实现跨 agent 调用
            client.shutdown()
        } catch (e: Exception) {
            logger.warn("Failed to connect external ACP agent ${agent.name}: ${e.message}")
        }
    }
}
