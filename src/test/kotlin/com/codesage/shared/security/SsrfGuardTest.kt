package com.codesage.shared.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * C5 修复验证：SSRF 防护。
 */
class SsrfGuardTest {

    @Test
    fun `rejects loopback IPv4`() {
        val result = SsrfGuard.check("http://127.0.0.1/foo")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block 127.0.0.1: $result")
    }

    @Test
    fun `rejects loopback localhost`() {
        val result = SsrfGuard.check("http://localhost/foo")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block localhost: $result")
    }

    @Test
    fun `rejects private 10_0_0_0 network`() {
        val result = SsrfGuard.check("http://10.0.0.1/admin")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block 10.x.x.x: $result")
    }

    @Test
    fun `rejects private 192_168 network`() {
        val result = SsrfGuard.check("http://192.168.1.1/router")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block 192.168.x.x: $result")
    }

    @Test
    fun `rejects private 172_16 network`() {
        val result = SsrfGuard.check("http://172.16.0.1/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block 172.16.x.x: $result")
    }

    @Test
    fun `rejects link-local 169_254 network (AWS metadata)`() {
        val result = SsrfGuard.check("http://169.254.169.254/latest/meta-data/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block 169.254.x.x: $result")
    }

    @Test
    fun `rejects IPv6 loopback`() {
        val result = SsrfGuard.check("http://[::1]/foo")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block IPv6 ::1: $result")
    }

    @Test
    fun `rejects file scheme`() {
        val result = SsrfGuard.check("file:///etc/passwd")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block file://: $result")
    }

    @Test
    fun `rejects ftp scheme`() {
        val result = SsrfGuard.check("ftp://internal-server/file")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block ftp://: $result")
    }

    @Test
    fun `rejects gopher scheme`() {
        val result = SsrfGuard.check("gopher://internal-server:11211/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block gopher://: $result")
    }

    @Test
    fun `rejects port 22 (SSH)`() {
        val result = SsrfGuard.check("http://example.com:22/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block port 22: $result")
    }

    @Test
    fun `rejects port 3306 (MySQL)`() {
        val result = SsrfGuard.check("http://example.com:3306/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block port 3306: $result")
    }

    @Test
    fun `rejects port 6379 (Redis)`() {
        val result = SsrfGuard.check("http://example.com:6379/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block port 6379: $result")
    }

    @Test
    fun `rejects URL with no scheme`() {
        val result = SsrfGuard.check("127.0.0.1/foo")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block URL with no scheme")
    }

    @Test
    fun `rejects decimal IP representation`() {
        // 2130706433 = 127.0.0.1
        val result = SsrfGuard.check("http://2130706433/")
        assertTrue(result is SsrfGuard.CheckResult.Blocked, "Should block decimal IP: $result")
    }

    @Test
    fun `isSafe returns false for internal URL`() {
        assertFalse(SsrfGuard.isSafe("http://192.168.1.1/"))
    }

    @Test
    fun `allows public web URL on port 80`() {
        // 注意：实际是否会允许取决于 DNS 解析（example.com 解析到公网 IP）。
        // 这里用 google.com (8.8.8.8) 验证不会被段位检查拦截
        val result = SsrfGuard.check("http://google.com/")
        // 仅在 DNS 可达时是 Allowed；不可达时是 Blocked with DNS error
        // 重点是不应出现 \"private/internal\" 字样
        if (result is SsrfGuard.CheckResult.Blocked) {
            assertFalse(
                result.reason.contains("private", ignoreCase = true),
                "Should not be blocked as private: $result"
            )
            assertFalse(
                result.reason.contains("loopback", ignoreCase = true),
                "Should not be blocked as loopback: $result"
            )
        }
    }

    @Test
    fun `allows public web URL on port 443`() {
        val result = SsrfGuard.check("https://google.com/")
        if (result is SsrfGuard.CheckResult.Blocked) {
            assertFalse(result.reason.contains("private", ignoreCase = true))
            assertFalse(result.reason.contains("loopback", ignoreCase = true))
        }
    }

    @Test
    fun `allows public web URL on custom port like 3000`() {
        val result = SsrfGuard.check("https://example.com:3000/")
        if (result is SsrfGuard.CheckResult.Blocked) {
            assertFalse(result.reason.contains("port", ignoreCase = true), "Should not block custom port 3000: $result")
        }
    }
}
