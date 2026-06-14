package com.codesage.model.dto

import kotlinx.serialization.Serializable

/**
 * ACP（Agent Client Protocol）配置节
 *
 * @property enabled 是否启用本地 ACP Socket 服务端
 * @property serverPort 本地服务端监听端口；0 表示自动分配
 * @property externalAgents 需要连接的外部 ACP agent 列表
 */
@Serializable
data class AcpSection(
    val enabled: Boolean = false,
    val serverPort: Int = 0,
    val externalAgents: List<AcpAgentEntry> = emptyList()
)

/**
 * 外部 ACP agent 配置
 *
 * @property id 唯一标识
 * @property name 显示名称
 * @property command 可执行文件路径
 * @property args 启动参数
 * @property env 额外环境变量
 * @property workingDir 工作目录
 * @property enabled 是否启用
 */
@Serializable
data class AcpAgentEntry(
    val id: String,
    val name: String,
    val command: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val workingDir: String? = null,
    val enabled: Boolean = true
)
