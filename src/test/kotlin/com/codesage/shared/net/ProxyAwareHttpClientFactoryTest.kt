package com.codesage.shared.net

import com.codesage.shared.config.ProxyConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.Proxy

/**
 * ProxyAwareHttpClientFactory 的单元测试
 *
 * 覆盖:
 *   1) 三种 mode(system / direct / manual)build 出对应的 proxy 配置
 *   2) invalid host/port 退化到 system 模式(不抛异常)
 *   3) cached client 在 updateConfig 后失效
 *   4) 缓存命中:同一 config 多次 build 返回同一 client 实例
 *
 * 不依赖 IntelliJ Platform,可在普通 JUnit 环境跑。
 */
class ProxyAwareHttpClientFactoryTest {

    @AfterEach
    fun reset() {
        ProxyAwareHttpClientFactory.updateConfig(ProxyConfig(), null)
    }

    @Test
    fun `system mode by default should not set explicit proxy`() {
        val client = ProxyAwareHttpClientFactory.build()
        // system 模式下我们没注入 custom ProxySelector,client.proxy 保持 null
        // 让 OkHttp 走 JVM 默认 ProxySelector
        assertNull(client.proxy, "system 模式下 OkHttp client.proxy 应该是 null(走 JVM 默认)")
    }

    @Test
    fun `direct mode should set Proxy NO_PROXY`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(mode = "direct"),
            null
        )
        val client = ProxyAwareHttpClientFactory.build()
        assertEquals(Proxy.NO_PROXY, client.proxy, "direct 模式应显式 NO_PROXY")
    }

    @Test
    fun `manual HTTP proxy should be configured`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(
                mode = "manual",
                type = "http",
                host = "proxy.example.com",
                port = 8080,
            ),
            null
        )
        val client = ProxyAwareHttpClientFactory.build()
        assertNotNull(client.proxy, "manual 模式应设 proxy")
        val addr = client.proxy!!.address() as java.net.InetSocketAddress
        assertEquals("proxy.example.com", addr.hostString)
        assertEquals(8080, addr.port)
        assertEquals(Proxy.Type.HTTP, client.proxy!!.type())
    }

    @Test
    fun `manual SOCKS proxy should be configured`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(
                mode = "manual",
                type = "socks",
                host = "127.0.0.1",
                port = 1080,
            ),
            null
        )
        val client = ProxyAwareHttpClientFactory.build()
        assertEquals(Proxy.Type.SOCKS, client.proxy!!.type())
        assertEquals(1080, (client.proxy!!.address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `manual mode with invalid port should fall back to system`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(
                mode = "manual",
                type = "http",
                host = "proxy.example.com",
                port = 99999, // 越界
            ),
            null
        )
        val client = ProxyAwareHttpClientFactory.build()
        assertNull(client.proxy, "无效端口应退化到 system 模式(client.proxy = null)")
    }

    @Test
    fun `manual mode with blank host should fall back to system`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(
                mode = "manual",
                type = "http",
                host = "",
                port = 8080,
            ),
            null
        )
        val client = ProxyAwareHttpClientFactory.build()
        assertNull(client.proxy, "空 host 应退化到 system 模式")
    }

    @Test
    fun `cached client should be invalidated after updateConfig`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(mode = "direct"),
            null
        )
        val c1 = ProxyAwareHttpClientFactory.build()
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(mode = "system"),
            null
        )
        val c2 = ProxyAwareHttpClientFactory.build()
        assertNotSame(c1, c2, "updateConfig 应失效缓存,下次 build 出新 client")
        assertNull(c2.proxy, "新 client 应该是 system 模式")
    }

    @Test
    fun `same config should return cached client`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(mode = "direct"),
            null
        )
        val c1 = ProxyAwareHttpClientFactory.build()
        val c2 = ProxyAwareHttpClientFactory.build()
        assertSame(c1, c2, "没改 config 时应返回缓存的 client")
    }

    @Test
    fun `getConfig should return current config`() {
        val cfg = ProxyConfig(
            mode = "manual",
            type = "http",
            host = "p.example.com",
            port = 3128,
            username = "alice",
            noProxy = listOf("localhost", "*.internal"),
        )
        ProxyAwareHttpClientFactory.updateConfig(cfg, null)
        val got = ProxyAwareHttpClientFactory.getConfig()
        assertEquals(cfg, got, "getConfig 应返回最近 updateConfig 的配置")
    }

    @Test
    fun `unknown mode should fall back to system`() {
        ProxyAwareHttpClientFactory.updateConfig(
            ProxyConfig(mode = "bogus-mode"),
            null
        )
        val client = ProxyAwareHttpClientFactory.build()
        assertNull(client.proxy, "未知 mode 应退化到 system")
    }
}
