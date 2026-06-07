package com.codesage.agent.tools

import com.codesage.shared.utils.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 原子写文件 helper。
 *
 * **目的**：在写文件被 cancel / 崩溃中断时，保证目标文件不会被写成"半完成"状态。
 *
 * **机制**（综合 git / vim / SQLite 等工具的最佳实践）：
 * 1. temp 文件放在目标同目录（POSIX rename(2) 原子性的前提：同文件系统）
 * 2. 隐藏命名（`.${name}.tmp.${uuid}`），不被 IDE 索引 / 显示
 * 3. 写完 temp 后显式 fsync（电源故障安全）
 * 4. cancel check（rename 前的最后放弃机会）
 * 5. atomic rename（`Files.move` + `ATOMIC_MOVE` + `REPLACE_EXISTING`）
 * 6. 降级：FS 不支持 `ATOMIC_MOVE` 时 fallback 到普通 `REPLACE_EXISTING`
 * 7. 异常清理：任何异常都 deleteIfExists(temp)
 * 8. 启动时 / 写之前清同名的旧临时文件（孤儿清理两层）
 *
 * **支持取消**：
 * - 用户 / 父 agent cancel 时，子 agent 的 tool 协程被父 Job 取消
 * - 写入期间的 `Thread.currentThread().isInterrupted` 检查响应取消
 * - rename 之前的 cancel check 提供最后一次主动放弃机会
 * - 任何情况下，目标文件保持"原内容"或"新内容"，**永不半完成**
 */
object AtomicFileWriter {
    private val logger = Logger.getLogger<AtomicFileWriter>()

    /**
     * 原子写文件。
     *
     * @param target 要写入的目标文件（已存在的或即将创建的）
     * @param content 要写入的内容
     * @return 写入的字节数（UTF-8 编码后）
     * @throws java.io.IOException 写失败
     * @throws InterruptedIOException 取消响应
     */
    fun write(target: File, content: String): Long {
        val targetPath = target.toPath()
        val parent = targetPath.parent
            ?: throw IllegalArgumentException("target has no parent dir: ${target.path}")

        // 步骤 0：确保 parent dir 存在（不依赖调用方先 mkdirs）
        target.parentFile?.let { parentDir ->
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                throw java.io.IOException("Failed to create parent dir: ${parentDir.path}")
            }
        }
        // 步骤 0b：清同名的旧临时文件（孤儿清理 layer 1）
        listOrphanTempFiles(target).forEach { old ->
            runCatching { Files.deleteIfExists(old.toPath()) }
                .onFailure { logger.warn("[atomic-write] failed to delete stale temp: ${old.path}") }
        }

        // 步骤 1：在同目录创建 temp 文件
        val temp = Files.createTempFile(
            parent,
            ".${target.name}.tmp.",  // 前缀：隐藏 + 关联 target
            ""                         // 后缀：空
        )

        try {
            // 步骤 2：写内容
            // 关键：用 Files.write + CREATE + WRITE + TRUNCATE_EXISTING 组合
            // IntelliJ TracingFileSystemProvider 在 test sandbox 中会"吞掉" FileOutputStream
            // 写入但 read 时返回 0 字节，必须用 Path-based Files.write 才能写透
            val bytes = content.toByteArray(Charsets.UTF_8)
            java.nio.file.Files.write(
                temp, bytes,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            )

            // 步骤 3：显式 fsync（电源故障安全，SSD ~10ms，HDD ~100ms）
            // 失败不阻塞（best-effort）
            // **Bug fix**: 之前用 FileOutputStream(temp).fd.sync() 调 fsync，
            // 但 FileOutputStream 构造默认 truncate 文件 — 把刚写好的内容清空，
            // 然后 fsync 一个空文件，导致 rename 后 target 是 0 字节。
            // 正确做法：用 RandomAccessFile("rw") 不 truncate 然后 sync。
            runCatching {
                java.io.RandomAccessFile(temp.toFile(), "rw").use { raf ->
                    raf.fd.sync()
                }
            }.onFailure {
                logger.warn("[atomic-write] fsync failed, continuing: ${it.message}")
            }

            // 步骤 4：cancel check（rename 之前的最后放弃机会）
            if (Thread.currentThread().isInterrupted) {
                Files.deleteIfExists(temp)
                throw InterruptedIOException("cancelled before atomic rename: ${target.path}")
            }

            // 步骤 5：原子 rename（POSIX rename(2) 在同 FS 上是原子的）
            try {
                Files.move(
                    temp, targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: AtomicMoveNotSupportedException) {
                // 极端情况：FS 不支持 ATOMIC_MOVE（NFSv3、某些 FUSE、旧 SMB）
                // 降级到普通 rename + warn（不阻塞功能）
                logger.warn(
                    "[atomic-write] ATOMIC_MOVE not supported on this FS for ${target.path}, " +
                        "falling back to non-atomic replace: ${e.message}"
                )
                Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }

            return bytes.size.toLong()
        } catch (e: Exception) {
            // 步骤 6：任何异常都清理 temp
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    /**
     * 检查并清理孤儿临时文件（layer 2 清理，可由 AgentCore 启动时调用）。
     *
     * @param rootDir 扫描的根目录（通常是项目根）
     * @param olderThanMs 只删除修改时间早于这个阈值的（避免误删正在写的）
     * @return 删除的文件数
     */
    fun cleanupOrphanTempFiles(rootDir: File, olderThanMs: Long = 3600_000L): Int {
        if (!rootDir.exists() || !rootDir.isDirectory) return 0
        val cutoff = System.currentTimeMillis() - olderThanMs
        var deleted = 0
        rootDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.name.matches(Regex(""".*\.[a-zA-Z0-9_-]+\.tmp\.[a-f0-9-]+$""")) }
            .filter { it.lastModified() < cutoff }
            .forEach { file ->
                runCatching { file.delete() }
                    .onSuccess { if (it) deleted++ }
                    .onFailure { logger.warn("[atomic-write] orphan cleanup failed: ${file.path}") }
            }
        return deleted
    }

    /**
     * 列出与 target 同名的旧临时文件。
     */
    private fun listOrphanTempFiles(target: File): List<File> =
        target.parentFile?.listFiles { f ->
            f.isFile && f.name.startsWith(".${target.name}.tmp.")
        }?.toList() ?: emptyList()
}
