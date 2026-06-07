package com.codesage.ide.ui.web

import com.codesage.shared.config.ProxyConfig
import com.codesage.shared.config.SettingsFile
import com.codesage.shared.net.ProxyAwareHttpClientFactory
import com.codesage.shared.utils.Logger
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Network Bridge Handler — 代理配置的 get/set/test
 *
 * 协议(v2.2 新增):
 *   - get_network_proxy                          → 返回 { config: {...} }(密码不返回)
 *   - set_network_proxy { mode, type, host,      → 保存到 settings.json,密码进 PasswordSafe
 *                          port, username,        + 立即 invalidate ProxyAwareHttpClientFactory 缓存
 *                          password, noProxy }
 *   - test_network_proxy { mode?, type?, host?,  → 临时按入参构造 client 打 /generate_204,
 *                       port?, username?,        返回 { ok, latencyMs, error? }
 *                       password?, noProxy? }
 *
 * 跟 ProviderBridgeHandler 的区别:
 *   - 不写文件,只内存里更新 settings + factory 缓存
 *   - 密码存 PasswordSafe,settings.json 只存 passwordRef 引用
 *   - test 端点固定 https://www.google.com/generate_204(204 No Content,稳定,无地理偏向)
 *
 * Project 参数:为了能在 disposed project 上也工作,不强依赖。PasswordSafe 是 IDE 全局的。
 */
class NetworkBridgeHandler(
    private val project: Project?,
    private val onMessage: (Map<String, Any?>) -> Unit,
) {
    private val logger = Logger.getLogger<NetworkBridgeHandler>()

    /**
     * 当前生效的 settings 引用(由 JCEFChatPanel 注入,setNetworkProxy 时回写)
     * - 读:用 currentSettings.get() 拿到最新值
     * - 写:set 后回写到引用,持久化由 JCEFChatPanel 处理(或前端走 settings_update)
     *
     * 用 AtomicReference 是为了在 Coroutine 里安全读写
     */
    private val currentSettings = AtomicReference<SettingsFile>(SettingsFile())

    /** JCEFChatPanel 注入 settings getter/setter */
    fun bindSettings(getter: () -> SettingsFile, setter: (SettingsFile) -> Unit) {
        // 把 getter/setter 存到 closure — 但 AtomicReference 只存最新值
        // 简单实现:每次 set 时调一次外部 setter 持久化
        this.externalSetter = setter
        currentSettings.set(getter())
    }

    private var externalSetter: ((SettingsFile) -> Unit)? = null

    /**
     * 处理来自 Web UI 的 network_* 消息
     * @return true 表示消息已处理
     */
    fun handle(type: String, payload: Map<String, Any?>): Boolean {
        if (!type.startsWith("network_")) return false
        return try {
            when (type) {
                "network_get_proxy" -> handleGet()
                "network_set_proxy" -> handleSet(payload)
                "network_test_proxy" -> handleTest(payload)
                else -> {
                    logger.debug("Unhandled network message: $type")
                    false
                }
            }
        } catch (e: Exception) {
            logger.error("Network bridge error: $type", e)
            onMessage(
                mapOf(
                    "type" to "network_error",
                    "operation" to type,
                    "message" to (e.message ?: "unknown"),
                ),
            )
            true
        }
    }

    private fun handleGet(): Boolean {
        val config = ProxyAwareHttpClientFactory.getConfig()
        onMessage(
            mapOf(
                "type" to "network_proxy_config",
                "config" to mapOf(
                    "mode" to config.mode,
                    "type" to config.type,
                    "host" to config.host,
                    "port" to config.port,
                    "username" to config.username,
                    "hasPassword" to (readPassword() != null),
                    "noProxy" to config.noProxy,
                    "passwordRef" to config.passwordRef,
                ),
            ),
        )
        return true
    }

    private fun handleSet(payload: Map<String, Any?>): Boolean {
        val mode = (payload["mode"] as? String) ?: "system"
        val type = (payload["proxyType"] as? String) ?: (payload["type"] as? String) ?: "http"
        val host = (payload["host"] as? String) ?: ""
        val port = (payload["port"] as? Number)?.toInt() ?: 0
        val username = (payload["username"] as? String) ?: ""
        val password = payload["password"] as? String
        val noProxy = (payload["noProxy"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

        // 1) 写密码到 PasswordSafe(如果提供了)
        val passwordRef = if (mode == "manual" && !password.isNullOrEmpty()) {
            writePassword(password)
            PASSWORD_REF
        } else if (mode == "manual" && username.isNotBlank()) {
            // 提供了 username 但没改密码 — 保留旧密码(不删,用户可能想保留)
            configOrEmpty().passwordRef
        } else {
            // 不需要认证,清空密码
            clearPassword()
            ""
        }

        val newConfig = ProxyConfig(
            mode = mode,
            type = type,
            host = host,
            port = port,
            username = username,
            passwordRef = passwordRef,
            noProxy = noProxy,
        )

        // 2) 更新工厂(立即生效)
        val effectivePassword = if (mode == "manual" && username.isNotBlank()) readPassword() else null
        ProxyAwareHttpClientFactory.updateConfig(newConfig, effectivePassword)

        // 3) 回写 settings.json
        val current = currentSettings.get()
        val updated = current.copy(network = current.network.copy(proxy = newConfig))
        currentSettings.set(updated)
        externalSetter?.invoke(updated)
        logger.info("Network proxy saved: mode=$mode, type=$type, host=$host:$port")

        onMessage(
            mapOf(
                "type" to "network_proxy_saved",
                "ok" to true,
            ),
        )
        return true
    }

    private fun handleTest(payload: Map<String, Any?>): Boolean {
        // 临时按入参构造 config,不影响已保存的
        val mode = (payload["mode"] as? String) ?: ProxyAwareHttpClientFactory.getConfig().mode
        val type = (payload["proxyType"] as? String) ?: (payload["type"] as? String) ?: ProxyAwareHttpClientFactory.getConfig().type
        val host = (payload["host"] as? String) ?: ProxyAwareHttpClientFactory.getConfig().host
        val port = (payload["port"] as? Number)?.toInt() ?: ProxyAwareHttpClientFactory.getConfig().port
        val username = (payload["username"] as? String) ?: ProxyAwareHttpClientFactory.getConfig().username
        val password = payload["password"] as? String ?: readPassword()
        val noProxy = (payload["noProxy"] as? List<*>)?.filterIsInstance<String>() ?: ProxyAwareHttpClientFactory.getConfig().noProxy

        val testConfig = ProxyConfig(
            mode = mode,
            type = type,
            host = host,
            port = port,
            username = username,
            passwordRef = if (password != null) PASSWORD_REF else "",
            noProxy = noProxy,
        )

        // 用 Coroutine 异步测试,不阻塞主线程
        CoroutineScope(Dispatchers.IO).launch {
            val originalConfig = ProxyAwareHttpClientFactory.getConfig()
            val originalPassword = if (originalConfig.passwordRef.isNotEmpty()) readPassword() else null
            try {
                ProxyAwareHttpClientFactory.updateConfig(testConfig, password)
                val result = ProxyAwareHttpClientFactory.testConnection()
                val response: Map<String, Any?> = result.fold(
                    onSuccess = { latency ->
                        mapOf(
                            "type" to "network_proxy_test_result",
                            "ok" to true,
                            "latencyMs" to latency,
                        )
                    },
                    onFailure = { err ->
                        mapOf(
                            "type" to "network_proxy_test_result",
                            "ok" to false,
                            "error" to (err.message ?: err.javaClass.simpleName),
                        )
                    },
                )
                onMessage(response)
            } finally {
                // 恢复原配置(测试是临时的,不应污染当前生效配置)
                ProxyAwareHttpClientFactory.updateConfig(originalConfig, originalPassword)
            }
        }
        return true
    }

    // ===== PasswordSafe helpers =====

    private fun configOrEmpty(): ProxyConfig = ProxyAwareHttpClientFactory.getConfig()

    private fun writePassword(password: String) {
        try {
            PasswordSafe.instance.setPassword(ATTRIBUTES, password)
            logger.info("Proxy password saved to PasswordSafe")
        } catch (e: Exception) {
            logger.warn("Failed to save proxy password to PasswordSafe: ${e.message}")
        }
    }

    private fun readPassword(): String? {
        return try {
            PasswordSafe.instance.getPassword(ATTRIBUTES)
        } catch (e: Exception) {
            logger.warn("Failed to read proxy password from PasswordSafe: ${e.message}")
            null
        }
    }

    private fun clearPassword() {
        try {
            PasswordSafe.instance.setPassword(ATTRIBUTES, null)
        } catch (e: Exception) {
            logger.debug("Failed to clear proxy password: ${e.message}")
        }
    }

    companion object {
        private const val PASSWORD_REF = "passwordsafe:codesage.network.proxy"
        private val ATTRIBUTES = com.intellij.credentialStore.CredentialAttributes(
            serviceName = "CodeSage",
            userName = "codesage.network.proxy",
        )
    }
}
