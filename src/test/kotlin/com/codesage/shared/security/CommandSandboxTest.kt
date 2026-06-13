package com.codesage.shared.security

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CommandSandboxTest {

    @Test
    fun `PathBasedSandbox should run simple command and capture output`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.WORKSPACE_WRITE)
        val result = sandbox.execute("echo hello", tempDir, 5000L, 1000)

        assertEquals(0, result.exitCode, "Expected exit code 0, stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("hello"), "stdout should contain 'hello': ${result.stdout}")
        assertFalse(result.sandboxed, "PathBasedSandbox should report sandboxed=false")
    }

    @Test
    fun `PathBasedSandbox should timeout long running command`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.WORKSPACE_WRITE)
        val start = System.currentTimeMillis()
        val result = sandbox.execute("sleep 10", tempDir, 500L, 1000)
        val duration = System.currentTimeMillis() - start

        assertEquals(-1, result.exitCode)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("timed out", ignoreCase = true))
        assertTrue(duration < 3000, "Timeout should fire quickly, but took ${duration}ms")
    }

    @Test
    fun `PathBasedSandbox should truncate oversized output`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.WORKSPACE_WRITE)
        val result = sandbox.execute("printf 'x%.0s' {1..100}", tempDir, 5000L, 50)

        assertEquals(0, result.exitCode, "Expected exit code 0, stderr: ${result.stderr}")
        assertEquals(50, result.stdout.length, "Output should be truncated to exactly 50 chars")
    }

    @Test
    fun `PathBasedSandbox READ_ONLY mode should still allow reading workspace files`(@TempDir tempDir: File) {
        File(tempDir, "test.txt").writeText("secret")
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.READ_ONLY)
        val result = sandbox.execute("cat test.txt", tempDir, 5000L, 1000)

        assertEquals(0, result.exitCode, "Expected exit code 0, stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("secret"), "Should be able to read workspace file")
    }

    @Test
    fun `CommandSandbox create should not return null`() {
        val sandbox = CommandSandbox.create(null, CommandSandbox.Mode.WORKSPACE_WRITE)
        assertNotNull(sandbox)
    }

    @Test
    fun `DANGEROUS_FULL_ACCESS mode should run without OS sandbox but still bounded`(@TempDir tempDir: File) {
        val sandbox = PathBasedSandbox(tempDir, CommandSandbox.Mode.DANGEROUS_FULL_ACCESS)
        val result = sandbox.execute("echo ok", tempDir, 5000L, 1000)

        assertEquals(0, result.exitCode, "Expected exit code 0, stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("ok"))
        assertFalse(result.sandboxed)
    }
}
