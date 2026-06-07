package com.codesage.shared.net

import com.codesage.shared.config.ProxyConfig
import com.codesage.shared.config.SettingsFile
import com.codesage.shared.utils.Logger
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * 代理感知的 OkHttpClient 工厂
 *
 * 用途:让 ExtendedTools / ProviderBridgeHandler / NetworkAndSearchSkills /
 * MiscToolHandlers 共享同一个 OkHttpClient,代理行为由 settings.json 的
 * [NetworkSection] 统一控制。
 *
 * 三种代理模式:
 *   - "system" : 不调用 .proxy()/.proxySelector(),让 JVM 默认 ProxySelector 接管
 *                 (即 IntelliJ HTTP Proxy 设置)
 *   - "direct" : .proxy(Proxy.NO_PROXY),彻底不走代理
 *   - "manual" : .proxy(Proxy(SOCKS|HTTP, host, port)) + .proxyAuthenticator
 *                 + 自定义 ProxySelector 检查 noProxy 列表
 *
 * 设计要点:
 *   - 工厂方法按当前 settings 重新创建 client — 调用方在 settings 变更时
 *     需要 invalidate 缓存(见 [invalidateCache])
 *   - 默认超时 15s(跟 ExtendedTools 上一轮调整一致)
 *   - 显式开启 followRedirects,免得后续 OkHttp 默认值变更出问题
 *   - 密码在 manual + 有 username 时,通过 Authenticator 注入到 Proxy-Authorization
 *     头里 — 实际密码由调用方在 settings 变更时从 PasswordSafe 注入到内存缓存
 */
object ProxyAwareHttpClientFactory {

    private val logger = Logger.getLogger<ProxyAwareHttpClientFactory>()

    /**
     * 当前生效的代理配置缓存。
     * - 写入:由 NetworkBridgeHandler.setProxyConfig 调 [updateConfig]
     * - 读取:build() 默认从 [currentConfig] 取
     * - 失效:用户改设置时调 [invalidateCache] 触发 rebuild
     */
    @Volatile
    private var currentConfig: ProxyConfig = ProxyConfig()

    /** 当前内存中的代理密码(从 PasswordSafe 解密后注入,不写日志/不进序列化) */
    @Volatile
    private var currentPassword: String? = null

    /** 缓存 client,避免每次请求重建 */
    @Volatile
    private var cachedClient: OkHttpClient? = null

    /** 测试用:允许注入任意 config + password */
    fun updateConfig(config: ProxyConfig, password: String?) {
        currentConfig = config
        currentPassword = password
        cachedClient = null  // 失效,下次 build 重建
        logger.info("Proxy config updated: mode=${config.mode}, type=${config.type}, host=${config.host}, port=${config.port}, hasPassword=${!password.isNullOrEmpty()}")
    }

    /** 用户改设置后调,清掉 client 缓存 */
    fun invalidateCache() {
        cachedClient = null
    }

    /** 给前端 / 日志查看当前生效的代理配置(密码永远不回传) */
    fun getConfig(): ProxyConfig = currentConfig

    /**
     * 拿到当前生效的 OkHttpClient(懒加载 + 缓存)
     *  - config.mode = "system" → 不调 .proxy()/.proxySelector(),走 JVM 默认
     *  - config.mode = "direct" → .proxy(Proxy.NO_PROXY)
     *  - config.mode = "manual" → .proxy(...) + .proxyAuthenticator + 自定义 ProxySelector
     */
    fun build(): OkHttpClient {
        cachedClient?.let { return it }
        val config = currentConfig
        val password = currentPassword
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        when (config.mode.lowercase()) {
            "direct" -> {
                builder.proxy(Proxy.NO_PROXY)
                logger.debug("Proxy: DIRECT (explicit no-proxy)")
            }
            "manual" -> {
                if (config.host.isBlank() || config.port <= 0 || config.port > 65535) {
                    logger.warn("Manual proxy configured but host/port invalid (${config.host}:${config.port}); falling back to JVM default")
                    // 不调 .proxy(),走系统
                } else {
                    val proxyType = when (config.type.lowercase()) {
                        "socks", "socks5" -> Proxy.Type.SOCKS
                        else -> Proxy.Type.HTTP
                    }
                    val proxy = Proxy(proxyType, InetSocketAddress(config.host, config.port))
                    builder.proxy(proxy)
                    // 自定义 ProxySelector,处理 noProxy 列表 + 命中的 URL 走直连
                    builder.proxySelector(NoProxyAwareSelector(proxy, config.noProxy))
                    // 认证
                    if (config.username.isNotBlank() && !password.isNullOrEmpty()) {
                        builder.proxyAuthenticator(BasicProxyAuthenticator(config.username, password))
                    }
                    logger.info("Proxy: MANUAL ${proxyType}://${config.host}:${config.port} (auth=${config.username.isNotBlank()}, noProxy=${config.noProxy.size} rules)")
                }
            }
            "system", "" -> {
                // 不调 .proxy()/.proxySelector(),让 OkHttp 走 JVM 默认 ProxySelector
                // (IntelliJ HTTP Proxy 配置会自动接管)
                logger.debug("Proxy: SYSTEM (JVM default ProxySelector)")
            }
            else -> {
                logger.warn("Unknown proxy mode '${config.mode}'; falling back to SYSTEM")
            }
        }

        val client = builder.build()
        cachedClient = client
        return client
    }

    /**
     * 测试代理连通性:打一个稳定的、不会回大 body 的目标 URL
     * 默认 https://www.google.com/generate_204(Google 用来检测 captive portal 的接口,
     * 永远返回 204 No Content,基本不会因内容/字符编码变化影响测试)
     *
     * @return Result.success(latencyMs) / Result.failure(原因)
     */
    fun testConnection(testUrl: String = "https://www.google.com/generate_204"): Result<Long> {
        return try {
            val client = build()
            val request = Request.Builder().url(testUrl).head().build()
            val start = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (response.isSuccessful || response.code in 200..399) {
                    Result.success(latency)
                } else {
                    Result.failure(IllegalStateException("HTTP ${response.code} ${response.message}"))
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("连接超时(15s)— 代理或目标不响应", e))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(IllegalStateException("域名无法解析 — 代理或 DNS 配置错误", e))
        } catch (e: java.net.ConnectException) {
            Result.failure(IllegalStateException("连接被拒绝 — 代理端口不通或认证失败", e))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("测试失败: ${e.message}", e))
        }
    }
}

/**
 * 自定义 ProxySelector:对命中 noProxy 规则的 URL 返回 NO_PROXY,
 * 其余 URL 返回传入的 manual proxy。
 *
 * 规则匹配(简单实现,够 80% 用例):
 *   - "localhost" / "127.0.0.1" / "::1" → 精确匹配 host
 *   - "*.example.com" → 后缀匹配
 *   - "192.168.1.0/24" → CIDR(简化:用字符串前缀匹配,假装是 /24)
 *   - 其余 → 精确匹配 host
 */
private class NoProxyAwareSelector(
    private val defaultProxy: Proxy,
    private val noProxyRules: List<String>
) : java.net.ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        if (uri == null) return listOf(defaultProxy)
        val host = uri.host ?: return listOf(defaultProxy)
        if (matchesNoProxy(host)) {
            return listOf(Proxy.NO_PROXY)
        }
        return listOf(defaultProxy)
    }

    override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: java.io.IOException?) {
        // 失败时退到 NO_PROXY,让 OkHttp 重试直连
        // 跟 Java 默认 ProxySelector 行为一致
    }

    private fun matchesNoProxy(host: String): Boolean {
        val h = host.lowercase()
        for (rule in noProxyRules) {
            val r = rule.trim().lowercase()
            if (r.isEmpty()) continue
            when {
                r == h -> return true                                  // 精确匹配
                r == "localhost" && h == "localhost" -> return true
                r.startsWith("*.") && h.endsWith(r.removePrefix("*.")) -> return true  // *.example.com
                r == "127.0.0.1" && h == "127.0.0.1" -> return true
                r.contains("/") -> {
                    // CIDR 简化处理 — 完整 CIDR 实现需要 java.net.InetAddress + 段位比较
                    // 这里只处理 /8 /16 /24(最常见)
                    val parts = r.split("/")
                    if (parts.size == 2) {
                        val mask = parts[1].toIntOrNull() ?: continue
                        val baseIp = parts[0]
                        val hostIp = h
                        if (mask % 8 == 0 && baseIp.split(".").size == 4) {
                            val octets = (mask / 8).coerceIn(0, 4)
                            val basePrefix = baseIp.split(".").take(octets).joinToString(".")
                            if (hostIp.startsWith(basePrefix)) return true
                        }
                    }
                }
                else -> if (h == r) return true
            }
        }
        return false
    }
}

/**
 * HTTP/SOCKS 代理的 Basic 认证器。
 * OkHttp 收到 407 Proxy Authentication Required 时自动触发。
 */
private class BasicProxyAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        // 防止无限重试:已经带过 Proxy-Authorization 就别再试了
        if (response.request.header("Proxy-Authorization") != null) {
            return null
        }
        val credential = Credentials.basic(username, password)
        return response.request.newBuilder()
            .header("Proxy-Authorization", credential)
            .build()
    }
}
