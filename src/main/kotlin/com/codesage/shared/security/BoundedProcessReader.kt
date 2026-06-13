package com.codesage.shared.security

import java.io.InputStream
import java.nio.charset.Charset
import java.util.concurrent.CompletableFuture

/**
 * 有界读取进程输出流。
 *
 * 读到 maxChars 字符就停止，并排空剩余流（避免进程因管道满而阻塞）。
 * 截断时保证不在 UTF-16 surrogate pair 中间断开。
 */
object BoundedProcessReader {

    fun read(input: InputStream, maxChars: Int, charset: Charset = Charsets.UTF_8): BoundedRead {
        val reader = input.bufferedReader(charset)
        val sb = StringBuilder(maxChars.coerceAtMost(maxChars + 1024))
        val buf = CharArray(8192)
        var hitCap = false

        while (true) {
            val n = reader.read(buf)
            if (n == -1) break
            sb.append(buf, 0, n)
            if (sb.length > maxChars) {
                hitCap = true
                break
            }
        }

        // 排干剩余输出，避免进程 pipe 阻塞
        if (hitCap) {
            while (reader.read(buf) != -1) { /* drain */
            }
        }

        val (final, wasSurrogate) = safeTruncate(sb.toString(), maxChars)
        return BoundedRead(final, hitCap || wasSurrogate)
    }

    /**
     * 异步启动 stdout/stderr 读取线程，返回两个 Future。
     *
     * 调用方应在 `process.waitFor(timeout)` 之后取结果；若超时仍可取到
     * 已读部分。读取异常时 Future 以空内容和 truncated=false 完成，避免
     * 调用方被 ExecutionException 打断。
     */
    fun readBothAsync(
        process: Process,
        maxChars: Int
    ): Pair<CompletableFuture<BoundedRead>, CompletableFuture<BoundedRead>> {
        val stdoutFuture = CompletableFuture<BoundedRead>()
        val stderrFuture = CompletableFuture<BoundedRead>()

        val stdoutThread = Thread {
            try {
                stdoutFuture.complete(read(process.inputStream, maxChars))
            } catch (e: Exception) {
                stdoutFuture.complete(BoundedRead("", false))
            }
        }
        stdoutThread.isDaemon = true
        stdoutThread.start()

        val stderrThread = Thread {
            try {
                stderrFuture.complete(read(process.errorStream, maxChars))
            } catch (e: Exception) {
                stderrFuture.complete(BoundedRead("", false))
            }
        }
        stderrThread.isDaemon = true
        stderrThread.start()

        return stdoutFuture to stderrFuture
    }

    private fun safeTruncate(content: String, maxChars: Int): Pair<String, Boolean> {
        if (content.length <= maxChars) return content to false
        var end = maxChars
        if (content[end - 1].isHighSurrogate()) end--
        return content.substring(0, end) to true
    }
}

/**
 * 有界读取结果。
 *
 * @param content 截断后的内容
 * @param truncated 是否因超出上限而被截断
 */
data class BoundedRead(val content: String, val truncated: Boolean)
