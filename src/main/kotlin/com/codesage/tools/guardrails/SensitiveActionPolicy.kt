package com.codesage.tools.guardrails

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 敏感操作策略定义
 * 控制哪些操作需要确认、哪些被禁止
 */
object SensitiveActionPolicy {

    /**
     * 操作风险等级
     */
    enum class RiskLevel {
        SAFE,       // 无需确认
        CAUTION,    // 建议确认
        DANGEROUS   // 必须确认
    }

    /**
     * 策略决策结果
     */
    data class PolicyDecision(
        val verdict: Verdict,
        val riskLevel: RiskLevel,
        val reason: String
    ) {
        enum class Verdict {
            ALLOWED,              // 允许执行，无需确认
            REQUIRES_CONFIRMATION, // 需要用户确认
            BLOCKED               // 绝对禁止，不可绕过
        }

        /** 向后兼容：是否需要用户确认 */
        val requiresConfirmation: Boolean get() = verdict == Verdict.REQUIRES_CONFIRMATION

        /** 向后兼容：是否允许执行（BLOCKED 和 REQUIRES_CONFIRMATION 都不算直接允许） */
        val allowed: Boolean get() = verdict == Verdict.ALLOWED
    }

    // 禁止删除的关键路径模式
    private val PROTECTED_PATHS = listOf(
        ".git",
        ".idea",
        "node_modules",
        ".gradle",
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "pom.xml",
        "package.json",
        "package-lock.json",
        "yarn.lock",
        "Cargo.toml",
        "go.mod",
        "go.sum"
    )

    // 危险命令模式
    private val DANGEROUS_COMMANDS = listOf(
        "rm -rf",
        "rm -r /",
        "rm -rf /",
        "dd if=",
        ":(){ :|:& };:",
        "mkfs",
        "fdisk",
        "format",
        "del /f /s /q",
        "rd /s /q"
    )

    // 写入保护的文件模式（通常不应被AI修改）
    private val PROTECTED_WRITE_PATTERNS = listOf(
        Regex(".*\\.env$"),
        Regex(".*\\.env\\..*"),
        Regex(".*credentials.*"),
        Regex(".*secret.*"),
        Regex(".*password.*"),
        Regex(".*token.*"),
        Regex(".*\\.ssh/.*"),
        Regex(".*id_rsa.*"),
        Regex(".*\\.p12$"),
        Regex(".*\\.pfx$"),
        Regex(".*\\.key$")
    )

    /**
     * 评估删除文件操作
     */
    fun evaluateDelete(path: String, projectRoot: String?): PolicyDecision {
        val normalizedPath = normalizePath(path, projectRoot)
        val file = File(normalizedPath)
        val relativePath = projectRoot?.let { file.relativePath(it) } ?: path

        // 检查是否是受保护路径（绝对禁止）
        if (PROTECTED_PATHS.any { relativePath.contains(it) }) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.BLOCKED,
                riskLevel = RiskLevel.DANGEROUS,
                reason = "Protected path cannot be deleted: $relativePath"
            )
        }

        // 检查是否是目录（删除目录更危险，需要确认）
        if (file.isDirectory) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                riskLevel = RiskLevel.DANGEROUS,
                reason = "Deleting directory: $relativePath"
            )
        }

        // 检查文件大小（大文件删除可能丢失重要数据，需要确认）
        val size = file.length()
        return if (size > 1024 * 1024) { // > 1MB
            PolicyDecision(
                verdict = PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                riskLevel = RiskLevel.CAUTION,
                reason = "Deleting large file (${size / 1024}KB): $relativePath"
            )
        } else {
            PolicyDecision(
                verdict = PolicyDecision.Verdict.ALLOWED,
                riskLevel = RiskLevel.SAFE,
                reason = "Deleting file: $relativePath"
            )
        }
    }

    /**
     * 评估写入文件操作
     */
    fun evaluateWrite(path: String, projectRoot: String?, content: String? = null): PolicyDecision {
        val normalizedPath = normalizePath(path, projectRoot)
        val relativePath = projectRoot?.let { File(normalizedPath).relativePath(it) } ?: path

        // 检查受保护写入模式（绝对禁止）
        if (PROTECTED_WRITE_PATTERNS.any { it.matches(relativePath) }) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.BLOCKED,
                riskLevel = RiskLevel.DANGEROUS,
                reason = "Cannot modify sensitive file: $relativePath"
            )
        }

        // 检查是否覆盖已有文件（需要确认）
        val file = File(normalizedPath)
        if (file.exists()) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                riskLevel = RiskLevel.CAUTION,
                reason = "Overwriting existing file: $relativePath"
            )
        }

        return PolicyDecision(
            verdict = PolicyDecision.Verdict.ALLOWED,
            riskLevel = RiskLevel.SAFE,
            reason = "Creating new file: $relativePath"
        )
    }

    /**
     * 评估执行命令操作
     * 使用token化和模式匹配，比简单字符串contains更安全
     */
    fun evaluateCommand(command: String): PolicyDecision {
        val lowerCommand = command.lowercase()
        val tokens = tokenizeCommand(lowerCommand)

        // 检查危险命令模式（token级别匹配，更难绕过）——绝对禁止
        if (matchesDangerousPattern(tokens, lowerCommand)) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.BLOCKED,
                riskLevel = RiskLevel.DANGEROUS,
                reason = "Dangerous command detected: ${command.take(50)}"
            )
        }

        // 检查网络相关命令（可能有安全风险，需要确认）
        if (tokens.any { it == "curl" || it == "wget" || it == "nc" || it == "netcat" }) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                riskLevel = RiskLevel.CAUTION,
                reason = "Network command requires caution: ${command.take(50)}"
            )
        }

        return PolicyDecision(
            verdict = PolicyDecision.Verdict.ALLOWED,
            riskLevel = RiskLevel.SAFE,
            reason = "Command: ${command.take(50)}"
        )
    }

    /**
     * 将命令字符串token化，支持常见的shell分隔符
     */
    private fun tokenizeCommand(command: String): List<String> {
        return command.split(Regex("""[\s;|&`$()<>\"'\r\n]+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * 检查是否匹配危险命令模式
     */
    private fun matchesDangerousPattern(tokens: List<String>, rawCommand: String): Boolean {
        // 1. 检查直接的危险命令token
        val dangerousTokens = setOf("rm", "dd", "mkfs", "fdisk", "format", "del", "rd", ">", ">>")
        if (tokens.any { it in dangerousTokens }) {
            // 进一步检查是否有破坏性参数
            if (tokens.contains("-rf") || tokens.contains("-r") || tokens.contains("/") ||
                tokens.contains("-f") || tokens.contains("-s") || tokens.contains("-q")
            ) {
                return true
            }
        }

        // 2. 检查已知的危险模式（原始字符串级别的精确匹配）
        if (DANGEROUS_COMMANDS.any { rawCommand.contains(it.lowercase()) }) {
            return true
        }

        // 3. 检查fork炸弹等畸形模式
        if (rawCommand.contains(":(){ :|:& };:")) {
            return true
        }

        return false
    }

    /**
     * 评估移动文件操作
     */
    fun evaluateMove(source: String, destination: String, projectRoot: String?): PolicyDecision {
        val normalizedSrc = normalizePath(source, projectRoot)
        val normalizedDst = normalizePath(destination, projectRoot)
        val relSrc = projectRoot?.let { File(normalizedSrc).relativePath(it) } ?: source
        val relDst = projectRoot?.let { File(normalizedDst).relativePath(it) } ?: destination

        // 检查源是否是受保护路径（绝对禁止）
        if (PROTECTED_PATHS.any { relSrc.contains(it) }) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.BLOCKED,
                riskLevel = RiskLevel.DANGEROUS,
                reason = "Cannot move protected path: $relSrc"
            )
        }

        // 跨项目移动更危险（需要确认）
        val srcInProject = projectRoot != null && normalizedSrc.startsWith(projectRoot)
        val dstInProject = projectRoot != null && normalizedDst.startsWith(projectRoot)
        if (srcInProject && !dstInProject) {
            return PolicyDecision(
                verdict = PolicyDecision.Verdict.REQUIRES_CONFIRMATION,
                riskLevel = RiskLevel.DANGEROUS,
                reason = "Moving file outside project: $relSrc → $relDst"
            )
        }

        return PolicyDecision(
            verdict = PolicyDecision.Verdict.ALLOWED,
            riskLevel = RiskLevel.CAUTION,
            reason = "Moving: $relSrc → $relDst"
        )
    }

    /**
     * 规范化路径
     */
    private fun normalizePath(path: String, projectRoot: String?): String {
        val file = File(path)
        return if (file.isAbsolute) {
            file.canonicalPath
        } else {
            projectRoot?.let { File(it, path).canonicalPath } ?: file.canonicalPath
        }
    }

    private fun File.relativePath(base: String): String {
        return this.canonicalPath.removePrefix(File(base).canonicalPath).removePrefix("/")
    }
}
