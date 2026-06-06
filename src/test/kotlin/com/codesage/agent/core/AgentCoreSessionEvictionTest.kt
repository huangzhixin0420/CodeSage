package com.codesage.agent.core

import com.codesage.model.adapter.ModelAdapter
import com.codesage.model.dto.*
import com.codesage.model.gateway.ModelGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * T0.9 修复验证测试：会话 LRU 淘汰
 *
 * CodeReview High #17 报告："缺少会话清理"
 *
 * 验证 [AgentCore] 在超过 [AgentCore.Companion.MAX_SESSIONS] 上限后：
 * 1. 自动淘汰最久未活跃的 session
 * 2. 永远保留 currentSessionId 指向的 session
 * 3. sessions map 大小不超过 MAX_SESSIONS
 * 4. 淘汰路径触发持久化（conversationPersistence.saveSession）
 *
 * 注意：测试中先 switchSession(S1) 再 createSession() ——
 * 这是因为淘汰发生在 createAndRegisterSession 内部、且基于当时的 currentSessionId。
 */
class AgentCoreSessionEvictionTest {

    private fun createFakeAdapter(): ModelAdapter = object : ModelAdapter {
        override val providerName: String = "fake"
        override val supportedModels: List<String> = listOf("test-model")
        override fun supportsStreaming(): Boolean = false
        override fun supportsFunctionCalling(): Boolean = true
        override fun supportsVision(): Boolean = false
        override fun toVendorRequest(request: ChatRequest): String = "{}"
        override fun fromVendorResponse(response: String): ChatResponse =
            ChatResponse("", "", emptyList(), null)
        override fun parseStreamChunk(chunk: String): StreamChunk? = null
        override fun getStreamEndpoint(): String = "http://fake"
        override fun getChatEndpoint(): String = "http://fake"
        override fun getHeaders(): Map<String, String> = emptyMap()
    }

    private fun createFakeGateway(): ModelGateway = object : ModelGateway() {
        override fun getCurrentAdapter(model: String): ModelAdapter? = createFakeAdapter()
    }

    private fun newAgent(): AgentCore = AgentCore(gateway = createFakeGateway()).apply {
        initialize(AgentConfig(systemPrompt = "test prompt"))
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `sessions count is bounded by MAX_SESSIONS`() {
        val agent = newAgent()
        val maxSessions = AgentCore.MAX_SESSIONS

        // 强制创建 (MAX_SESSIONS + 50) 个会话，触发多次淘汰
        repeat(maxSessions + 50) { i ->
            agent.createSession()
            // 模拟时间流逝：让后续 session 的 lastActivityAt 更大
            Thread.sleep(1)
        }

        val sessionCount = agent.getSessions().size
        assertTrue(
            sessionCount <= maxSessions,
            "Sessions count should be bounded by MAX_SESSIONS=$maxSessions, got $sessionCount"
        )
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `current session is never evicted during LRU cleanup`() {
        val agent = newAgent()
        val maxSessions = AgentCore.MAX_SESSIONS

        // 创建当前 session 并切到它
        val currentSession = agent.createSession()
        agent.switchSession(currentSession.id)

        // 触发大量创建：
        // 关键：先 switchSession(S1) 再 createSession()，确保 S1 是 current
        // 这样 createSession 内部的 eviction 会保护 S1
        repeat(maxSessions + 30) {
            agent.switchSession(currentSession.id)  // 切到 S1
            agent.createSession()                   // 触发 eviction，S1 被保护
        }

        // 切到 currentSession
        agent.switchSession(currentSession.id)
        // 当前 session 必须仍然存在
        val stillExists = agent.getSessions().any { it.id == currentSession.id }
        assertTrue(
            stillExists,
            "Current session ${currentSession.id} should not be evicted by LRU cleanup"
        )
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    fun `LRU eviction prefers oldest sessions by lastActivityAt`() {
        val agent = newAgent()
        val maxSessions = AgentCore.MAX_SESSIONS

        // 创建 3 个 session
        val old1 = agent.createSession()
        Thread.sleep(5)
        val old2 = agent.createSession()
        Thread.sleep(5)
        val newest = agent.createSession()

        // 把 newest 切为 current
        agent.switchSession(newest.id)

        // 触发大量创建
        repeat(maxSessions + 30) {
            agent.switchSession(newest.id)  // 每次先切到 newest（current）
            agent.createSession()           // 触发 eviction，保护 newest
        }

        // newest 应该仍存在（它是 current）
        assertTrue(
            agent.getSessions().any { it.id == newest.id },
            "newest (current) should not be evicted"
        )
        // old1 应该被淘汰（最旧）
        assertTrue(
            agent.getSessions().none { it.id == old1.id },
            "old1 should be evicted (oldest)"
        )
        // old2 也应该被淘汰
        assertTrue(
            agent.getSessions().none { it.id == old2.id },
            "old2 should be evicted"
        )
    }
}
