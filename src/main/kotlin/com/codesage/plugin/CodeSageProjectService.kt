package com.codesage.plugin

import com.codesage.agent.core.AgentCore
import com.codesage.agent.core.AgentConfig
import com.codesage.agent.context.ContextManager
import com.codesage.agent.multiagent.MultiAgentOrchestrator
import com.codesage.agent.planner.TaskPlanner
import com.codesage.agent.tools.SkillToolAdapter
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
    val multiAgentOrchestrator: MultiAgentOrchestrator

    init {
        logger.info("Initializing CodeSage project service for: ${project.name}")

        val appService = CodeSageAppService.getInstance()

        val taskPlanner = TaskPlanner()
        val gateway = appService.modelGateway

        val skillToolAdapter = SkillToolAdapter(
            skillRegistry = appService.skillRegistry,
            skillExecutor = appService.skillExecutor
        )

        agentCore = AgentCore(
            gateway = gateway,
            taskPlanner = taskPlanner,
            project = project,
            skillToolAdapter = skillToolAdapter
        )

        multiAgentOrchestrator = MultiAgentOrchestrator(agentCore)

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

    companion object {
        fun getInstance(project: Project): CodeSageProjectService =
            project.getService(CodeSageProjectService::class.java)
    }
}
