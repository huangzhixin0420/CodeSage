package com.codesage.shared.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * SSRF (Server-Side Request Forgery) 防护工具。
 *
 * 背景（来自 code review C5）：
 * 之前 ExtendedTools 用 11 个 Regex 块拼凑的 URL 模式黑名单做防护，可被以下方式绕过：
 * - 127.0.0.1 的十进制/八进制/十六进制表示 (2130706433 / 0177.0.0.1 / 0x7f.0.0.1)
 * - LOCALHOST.evil.com (子域名欺骗)
 * - DNS rebinding (URL 解析时合法公网,连接时被解析到内网)
 * - URL 片段注入 (#@internal:8080/admin)
 * - gopher / file / ftp 等危险 scheme
 *
 * 修法：解析 URL -> 解析 hostname -> InetAddress.getAllByName() 拿所有 IP -> 对每个 IP 做段位检查。
 * 仅在所有 IP 都在白名单段（公网）时才放行。
 *
 * 用法：
 * ```
 * val result = SsrfGuard.check(url)
 * if (result !is SsrfGuard.CheckResult.Allowed) {
 *     return ToolResult.Error("SSRF blocked: ${result.reason}")
 * }
 * ```
 */
object SsrfGuard {

    /**
     * 检测结果
     */
    sealed class CheckResult {
        /** URL 合法，可以发起请求 */
        data class Allowed(val url: String) : CheckResult()

        /** 检测到 SSRF 风险，拒绝请求 */
        data class Blocked(val reason: String) : CheckResult()
    }

    /**
     * 白名单 scheme (仅允许 http 和 https; 其余一律拒绝)
     */
    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * 入口：判断 [rawUrl] 是否可以安全请求。
     */
    fun check(rawUrl: String): CheckResult {
        // 1) scheme 校验
        val scheme = extractScheme(rawUrl)
            ?: return CheckResult.Blocked("URL missing scheme")
        if (scheme.lowercase() !in ALLOWED_SCHEMES) {
            return CheckResult.Blocked("Disallowed URL scheme: $scheme")
        }

        // 2) 解析 URL 各部分
        val uri = try {
            URI(rawUrl)
        } catch (e: Exception) {
            return CheckResult.Blocked("URL parse error: ${e.message}")
        }
        val host = uri.host
            ?: return CheckResult.Blocked("URL missing host")

        // 3) 解析所有 IP（防 DNS rebinding：拿所有解析结果逐一检查）
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            return CheckResult.Blocked("Cannot resolve host: $host")
        } catch (e: Exception) {
            return CheckResult.Blocked("DNS resolution error: ${e.message}")
        }

        if (addresses.isEmpty()) {
            return CheckResult.Blocked("No addresses resolved for host: $host")
        }

        // 4) 端口限制：禁止命中已知危险端口（数据库 / 内部服务 / SSH / RDP 等）
        val port = if (uri.port < 0) defaultPortFor(scheme) else uri.port
        if (port in BLOCKED_PORTS) {
            return CheckResult.Blocked("Disallowed port: $port (likely internal service)")
        }

        // 5) 逐个 IP 检查是否在内网/loopback/链路本地段
        for (addr in addresses) {
            val reason = isPrivateAddress(addr)
            if (reason != null) {
                return CheckResult.Blocked(
                    "Host $host resolves to private/internal IP ${addr.hostAddress}: $reason"
                )
            }
        }

        return CheckResult.Allowed(rawUrl)
    }

    /**
     * 简化版：只返回 boolean
     */
    fun isSafe(rawUrl: String): Boolean = check(rawUrl) is CheckResult.Allowed

    /**
     * 提取 scheme (容忍大小写 + 去除前导空白)
     */
    private fun extractScheme(rawUrl: String): String? {
        val colonIdx = rawUrl.indexOf(':')
        if (colonIdx <= 0) return null
        val scheme = rawUrl.substring(0, colonIdx).trim()
        return if (scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) scheme else null
    }

    private fun defaultPortFor(scheme: String): Int = when (scheme.lowercase()) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }

    /**
     * 危险端口黑名单：常见数据库 / 内部协议端口
     * - 数据库: 1433 (MSSQL), 1521 (Oracle), 3306 (MySQL), 5432 (PostgreSQL), 6379 (Redis), 27017 (MongoDB), 9200 (ES), 11211 (Memcached)
     * - 系统服务: 22 (SSH), 23 (Telnet), 25 (SMTP), 135/139/445 (SMB), 3389 (RDP)
     * - 元数据/管理: 2375/2376 (Docker), 8500 (Consul), 10250 (Kubelet)
     * - Java 远程: 1099 (RMI), 4848 (GlassFish), 8000 (in some configs), 8443 (HTTPS alt - 这里允许)
     * - 文件/打印: 21 (FTP), 69 (TFTP), 873 (rsync), 2049 (NFS)
     *
     * 端口 80/443/8080 等公开 web 端口都允许；只阻止上述"内网服务"端口。
     */
    private val BLOCKED_PORTS = setOf(
        22, 23, 25, 21, 69, 873, 2049,            // 系统/文件服务
        135, 139, 445, 3389,                       // Windows 系统
        1099, 1433, 1521, 3306, 5432, 6379,        // 数据库 / Java RMI
        9200, 9300, 11211, 27017, 27018, 27019,    // ES / Memcached / MongoDB
        2375, 2376, 8500, 10250                    // Docker / Consul / Kubelet
    )

    /**
     * 检查 IP 是否为内网/loopback/链路本地/ULA。
     * 返回 null 表示安全（公网 IP），非 null 表示是禁止段的描述。
     */
    private fun isPrivateAddress(addr: InetAddress): String? {
        // 1) isSiteLocalAddress / isLoopbackAddress / isLinkLocalAddress / isMulticastAddress
        //    这几个是 InetAddress 自带的内网段检测，涵盖 RFC 1918 / 127.0.0.0/8 / 169.254.0.0/16 等
        if (addr.isLoopbackAddress) return "loopback"
        if (addr.isAnyLocalAddress) return "any-local (0.0.0.0)"
        if (addr.isLinkLocalAddress) return "link-local (169.254/16)"
        if (addr.isSiteLocalAddress) return "site-local (RFC 1918 private)"
        if (addr.isMulticastAddress) return "multicast"

        if (addr is Inet4Address) {
            val octets = addr.address
            if (octets.size != 4) return "invalid IPv4"
            val b0 = octets[0].toInt() and 0xFF
            val b1 = octets[1].toInt() and 0xFF

            // 10.0.0.0/8        (RFC 1918)
            // 172.16.0.0/12     (RFC 1918)
            // 192.168.0.0/16    (RFC 1918)
            // 100.64.0.0/10     (CGN)
            // 169.254.0.0/16    (link-local)
            // 0.0.0.0/8         (this network)
            // 127.0.0.0/8       (loopback) - 已被 isLoopbackAddress 涵盖
            // 224.0.0.0/4       (multicast) - 已被 isMulticastAddress 涵盖
            // 240.0.0.0/4       (reserved)
            if (b0 == 10) return "10.0.0.0/8 private"
            if (b0 == 172 && b1 in 16..31) return "172.16.0.0/12 private"
            if (b0 == 192 && b1 == 168) return "192.168.0.0/16 private"
            if (b0 == 100 && b1 in 64..127) return "100.64.0.0/10 CGN"
            if (b0 == 0) return "0.0.0.0/8 this-network"
            if (b0 in 240..255) return "240.0.0.0/4 reserved"
        } else if (addr is Inet6Address) {
            // IPv6 内网/特殊段检查
            val raw = addr.address
            if (raw.size != 16) return "invalid IPv6"
            val b0 = raw[0].toInt() and 0xFF
            // ::1/128 (loopback) - isLoopbackAddress
            // ::/128 (unspecified) - isAnyLocalAddress
            // fe80::/10 (link-local) - isLinkLocalAddress
            // fc00::/7 (unique local) - 需手查
            // ::ffff:0:0/96 (IPv4-mapped) - 需对尾部 v4 部分做上面 v4 检查
            if (b0 and 0xFE == 0xFC) return "fc00::/7 ULA"
            // IPv4-mapped IPv6 (::ffff:x.x.x.x)
            if (raw[0] == 0.toByte() && raw[1] == 0.toByte() &&
                raw[2] == 0.toByte() && raw[3] == 0.toByte() &&
                raw[4] == 0.toByte() && raw[5] == 0.toByte() &&
                raw[6] == 0.toByte() && raw[7] == 0.toByte() &&
                raw[8] == 0.toByte() && raw[9] == 0.toByte() &&
                raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
            ) {
                val v4 = byteArrayOf(raw[12], raw[13], raw[14], raw[15])
                val mapped = Inet4Address.getByAddress(v4)
                val innerReason = isPrivateAddress(mapped)
                if (innerReason != null) return "IPv4-mapped $innerReason"
            }
        }
        return null
    }
}
