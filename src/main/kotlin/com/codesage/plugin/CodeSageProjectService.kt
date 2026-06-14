package com.codesage.plugin

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentConfig
import com.codesage.agent.context.ContextManager
import com.codesage.agent.planner.TaskPlanner
import com.codesage.agent.tools.SkillToolAdapter
import com.codesage.ide.ui.web.ChatConfirmationCallback
import com.codesage.shared.config.PluginConfig
import com.codesage.shared.utils.Logger
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * CodeSage 项目级服务
 * 每个打开的项目拥有独立的 AgentCore 和对话上下文
 */
@Service(Service.Level.PROJECT)
class CodeSageProjectService(private val project: Project) {
    private val logger = Logger.getLogger<CodeSageProjectService>()

    val agentCore: AgentCore

    /**
     * 主聊天面板的 ToolGuardrails confirmation 回调。
     * 由 JCEFChatPanel 在初始化时调用 [bind] 把推送通道接到自己,
     * 再由 [AgentCore] 在 [requestConfirmation] 处挂起等待用户在前端点击 ALLOW/DENY。
     * 通道未建立时静默 DENY(ToolGuardrails 默认 headless 降级策略)。
     */
    val confirmationCallback: ChatConfirmationCallback = ChatConfirmationCallback()

    init {
        logger.info("Initializing CodeSage project service for: ${project.name}")

        val appService = CodeSageAppService.getInstance()

        val taskPlanner = TaskPlanner()
        val gateway = appService.modelGateway

        val skillToolAdapter = SkillToolAdapter(
            skillRegistry = appService.skillRegistry,
            skillExecutor = appService.skillExecutor,
            project = project
        )

        agentCore = AgentCore(
            gateway = gateway,
            taskPlanner = taskPlanner,
            project = project,
            skillToolAdapter = skillToolAdapter,
            confirmationCallback = confirmationCallback,
            mcpServerManagerOverride = appService.mcpServerManager
        )

        val config = PluginConfig.getInstance()
        agentCore.initialize(
            AgentConfig(
                defaultModel = config.defaultModel,
                systemPrompt = AgentConfig.DEFAULT_SYSTEM_PROMPT,
                temperature = 0.7
            )
        )

        logger.info("CodeSage project service initialized for: ${project.name}")
    }

    /**
     * 由 JCEFChatPanel.init 阶段调用, 把 confirmation 推送通道接到自己。
     * 同时把前端的 tool_confirmation_response 转发回 callback, 完成闭环。
     */
    fun bindConfirmationBridge(pushToFrontend: (requestId: String, toolName: String, operation: String, reason: String, riskLevel: String) -> Unit) {
        confirmationCallback.pushToFrontend = pushToFrontend
    }

    fun resolveConfirmation(requestId: String, permission: com.codesage.tools.guardrails.ToolGuardrails.Permission) {
        confirmationCallback.resolve(requestId, permission)
    }

    companion object {
        fun getInstance(project: Project): CodeSageProjectService =
            project.getService(CodeSageProjectService::class.java)
    }
}
