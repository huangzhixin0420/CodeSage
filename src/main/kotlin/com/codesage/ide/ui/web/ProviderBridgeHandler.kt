package com.codesage.ide.ui.web

import com.codesage.shared.config.PluginConfig
import com.codesage.shared.security.SsrfGuard
import com.codesage.shared.utils.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Provider Bridge Handler — 处理来自 Web UI 的 set_api_key / test_provider 消息
 *
 * 协议:
 *   - set_api_key { providerId, apiKey }        → 写入 PasswordSafe
 *   - test_provider { providerId, baseUrl,      → 探测连通性,返回 { ok, latencyMs, error? }
 *                     apiKey, model }
 *
 * 设计要点:
 *   - API Key 不进 settings.json,只进 PasswordSafe(用 PluginConfig.setProviderApiKey)
 *   - 其余字段(name / baseUrl / models / enabled)走 settings_update(settings.json)
 *   - test_provider 走 OkHttp,10s 超时
 *   - 探测策略:GET {baseUrl}/v1/models(OpenAI 兼容惯例),4xx 视为 ok(说明服务可达但可能鉴权)
 *   - 探测不实际消耗 token,只验证网络 + 鉴权头格式
 */
class ProviderBridgeHandler(
    private val onMessage: (Map<String, Any?>) -> Unit,
) {
    private val logger = Logger.getLogger<ProviderBridgeHandler>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 处理来自 Web UI 的消息
     * @return true 表示消息已处理
     */
    fun handle(type: String, data: Map<String, Any?>): Boolean {
        if (type != "set_api_key" && type != "test_provider") return false
        try {
            when (type) {
                "set_api_key" -> handleSetApiKey(data)
                "test_provider" -> handleTestProvider(data)
            }
        } catch (e: Exception) {
            logger.error("Provider bridge handler error for $type", e)
            sendError(type, data["requestId"] as? String, e.message ?: "unknown")
        }
        return true
    }

    private fun handleSetApiKey(data: Map<String, Any?>) {
        val providerId = data["providerId"] as? String
        if (providerId.isNullOrBlank()) {
            sendError("set_api_key", data["requestId"] as? String, "missing providerId")
            return
        }
        val apiKey = (data["apiKey"] as? String).orEmpty()
        if (apiKey.isBlank()) {
            // 空字符串 = 清空 key
            PluginConfig.getInstance().setProviderApiKey(providerId, null)
            logger.info("[ProviderBridge] cleared api key for $providerId")
        } else {
            PluginConfig.getInstance().setProviderApiKey(providerId, apiKey)
            logger.info("[ProviderBridge] saved api key for $providerId (length=${apiKey.length})")
        }
        onMessage(
            mapOf(
                "type" to "set_api_key_result",
                "requestId" to (data["requestId"] ?: ""),
                "providerId" to providerId,
                "success" to true,
            )
        )
    }

    private fun handleTestProvider(data: Map<String, Any?>) {
        val providerId = (data["providerId"] as? String).orEmpty()
        val baseUrl = (data["baseUrl"] as? String)?.trim().orEmpty()
        val apiKey = (data["apiKey"] as? String).orEmpty()
        val model = (data["model"] as? String).orEmpty()
        val requestId = (data["requestId"] as? String).orEmpty()

        if (baseUrl.isBlank()) {
            sendError("test_provider", requestId, "baseUrl 不能为空")
            return
        }

        // 如果没传 apiKey,尝试从 PasswordSafe 读取
        val effectiveKey = apiKey.ifBlank { PluginConfig.getInstance().getProviderApiKey(providerId).orEmpty() }

        val result = testConnection(baseUrl, effectiveKey, model)
        onMessage(
            mapOf(
                "type" to "test_provider_result",
                "requestId" to requestId,
                "providerId" to providerId,
                "ok" to result.ok,
                "latencyMs" to result.latencyMs,
                "httpStatus" to result.httpStatus,
                "error" to result.error,
            )
        )
    }

    /**
     * 探测 Provider 连通性
     * 策略:GET {baseUrl}/v1/models
     *   - 2xx:ok
     *   - 401/403:auth 失败但服务可达,ok=false 且附 httpStatus
     *   - 404:可能 baseUrl 不对或服务不支持,ok=false
     *   - 5xx/超时/连接失败:服务不可达,ok=false
     */
    private fun testConnection(baseUrl: String, apiKey: String, model: String): TestResult {
        val probeUrl = normalizeBaseUrl(baseUrl) + "/v1/models"

        // H15 修复：先做 SSRF 防护，block 直接返回错误，避免打到内网/loopback
        val ssrfCheck = SsrfGuard.check(probeUrl)
        if (ssrfCheck is SsrfGuard.CheckResult.Blocked) {
            return TestResult(
                ok = false,
                latencyMs = 0L,
                httpStatus = 0,
                error = "SSRF blocked: ${ssrfCheck.reason}".take(200)
            )
        }

        val url = probeUrl
        val start = System.currentTimeMillis()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (apiKey.isNotBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
            }
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                val code = response.code
                when {
                    code in 200..299 -> TestResult(ok = true, latencyMs = latency, httpStatus = code)
                    code == 401 || code == 403 -> TestResult(
                        ok = false,
                        latencyMs = latency,
                        httpStatus = code,
                        error = "鉴权失败 (HTTP $code):请检查 API Key",
                    )

                    code in 400..499 -> TestResult(
                        ok = false,
                        latencyMs = latency,
                        httpStatus = code,
                        error = "HTTP $code:可能 baseUrl 不正确或端点不存在",
                    )

                    else -> TestResult(
                        ok = false,
                        latencyMs = latency,
                        httpStatus = code,
                        error = "服务返回 HTTP $code",
                    )
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            TestResult(ok = false, error = "连接超时(>${client.connectTimeoutMillis / 1000}s)")
        } catch (e: java.net.UnknownHostException) {
            TestResult(ok = false, error = "无法解析主机: ${e.message}")
        } catch (e: java.net.ConnectException) {
            TestResult(ok = false, error = "连接被拒绝: ${e.message}")
        } catch (e: java.io.IOException) {
            TestResult(ok = false, error = "网络错误: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            TestResult(ok = false, error = "未知错误: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun normalizeBaseUrl(url: String): String {
        var s = url.trim()
        if (s.endsWith("/")) s = s.dropLast(1)
        // 自动去掉末尾的 /v1(避免探测时变 /v1/v1/models)
        if (s.endsWith("/v1")) s = s.dropLast(3)
        return s
    }

    private fun sendError(type: String, requestId: String?, message: String) {
        onMessage(
            mapOf(
                "type" to "${type}_result",
                "requestId" to (requestId ?: ""),
                "success" to false,
                "ok" to false,
                "error" to message,
            )
        )
    }

    private data class TestResult(
        val ok: Boolean,
        val latencyMs: Long? = null,
        val httpStatus: Int? = null,
        val error: String? = null,
    )
}
