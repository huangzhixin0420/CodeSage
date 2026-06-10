package com.codesage.ide.ui.web

import com.codesage.tools.guardrails.ToolGuardrails
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * 主聊天面板的 ToolGuardrails confirmation 回调。
 *
 * 为什么需要这个:
 *   - CodeSageProjectService 创建的 AgentCore 不带 confirmationCallback,
 *     触发的写操作类工具(git_add/git_commit/edit_file/write_file 等)会被静默 DENY
 *     并报 'User declined ... in headless mode'(ToolGuardrails.kt:122 降级路径)。
 *   - WebUI 收到 tool_confirmation_needed 事件后只渲染静态 alert,
 *     没有 ALLOW/DENY 按钮,30 秒后 withTimeoutOrNull 超时 → 默认 DENY。
 *
 * 工作方式:
 *   1. requestConfirmation 注册一个 CompletableDeferred 并阻塞等待结果
 *   2. 同时通过 pushCallback 把请求 id 推给前端,前端渲染弹窗
 *   3. 用户点 ALLOW/DENY → JS 通过 JBCefJSQuery 回 'tool_confirmation_response'
 *   4. JCEFChatPanel.handleToolConfirmationResponse(id, permission) 触发 deferred 完成
 *   5. 协程拿到 Permission 返回给 ToolGuardrails
 *
 * 超时与失联保护:
 *   - ToolGuardrails 自身有 30s withTimeoutOrNull; 这里不重复加,
 *     避免双层超时叠加。
 *   - 若前端断连/事件未送达,deferred 会无限挂起直到外层 ToolGuardrails 超时。
 */
class ChatConfirmationCallback : ToolGuardrails.ConfirmationCallback {

    /** 推送给前端的回调, 由 JCEFChatPanel 在初始化时设置 */
    @Volatile
    var pushToFrontend: ((requestId: String, toolName: String, operation: String, reason: String, riskLevel: String) -> Unit)? = null

    private val pending = ConcurrentHashMap<String, CompletableDeferred<ToolGuardrails.Permission>>()

    override suspend fun requestConfirmation(
        toolName: String,
        operation: String,
        reason: String,
        riskLevel: com.codesage.tools.guardrails.SensitiveActionPolicy.RiskLevel
    ): ToolGuardrails.Permission {
        val requestId = java.util.UUID.randomUUID().toString()
        val deferred = CompletableDeferred<ToolGuardrails.Permission>()
        pending[requestId] = deferred

        try {
            val push = pushToFrontend
            if (push == null) {
                // 通道未建立(例如没有 JCEF 浏览器),保守拒绝
                return ToolGuardrails.Permission.DENY
            }
            push(requestId, toolName, operation, reason, riskLevel.name)
            return deferred.await()
        } finally {
            pending.remove(requestId)
        }
    }

    /**
     * 由 JCEFChatPanel 收到前端的 tool_confirmation_response 时调用。
     * 幂等: 多次调用以最后一次为准, 或对已完成的 deferred 写入被忽略。
     */
    fun resolve(requestId: String, permission: ToolGuardrails.Permission) {
        val deferred = pending.remove(requestId) ?: return
        deferred.complete(permission)
    }
}
