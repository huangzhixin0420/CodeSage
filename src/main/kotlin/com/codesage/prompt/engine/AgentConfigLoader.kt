package com.codesage.prompt.engine

import com.codesage.shared.utils.Logger
import java.io.File

/**
 * 加载项目级 AI Agent 配置文件。
 *
 * 支持的行业标准与兼容格式：
 * - `AGENTS.md`：OpenAI Codex、GitHub Copilot、Google Jules、Cursor、Windsurf 等支持。
 * - `CLAUDE.md`：Claude Code 的历史约定。
 *
 * 发现顺序（优先级从高到低）：
 * 1. `{projectRoot}/AGENTS.md`
 * 2. `{projectRoot}/CLAUDE.md`
 * 3. `{projectRoot}/.codesage/AGENTS.md`
 * 4. `{user.home}/.codesage/AGENTS.md`
 *
 * 加载后的内容会作为 system prompt 的固定前缀注入，避免被上下文压缩策略丢弃。
 */
object AgentConfigLoader {

    private val logger = Logger.getLogger<AgentConfigLoader>()

    /** 单份配置最大字符数，防止超大文件撑爆上下文。 */
    private const val MAX_CONFIG_LENGTH = 8_000

    /**
     * 加载 Agent 配置文件内容。
     *
     * @param projectRoot 项目根目录，可为 null（未打开项目时）。
     * @return 配置文件内容；未找到时返回 null。
     */
    fun load(projectRoot: String?): String? {
        val candidates = buildList {
            projectRoot?.let { root ->
                add(File(root, "AGENTS.md"))
                add(File(root, "CLAUDE.md"))
                add(File(root, ".codesage${File.separator}AGENTS.md"))
            }
            System.getProperty("user.home")?.let { home ->
                add(File(home, ".codesage${File.separator}AGENTS.md"))
            }
        }

        for (file in candidates) {
            if (file.exists() && file.isFile) {
                return try {
                    val content = file.readText(Charsets.UTF_8)
                    logger.info("Loaded agent config from ${file.absolutePath} (${content.length} chars)")
                    if (content.length > MAX_CONFIG_LENGTH) {
                        logger.warn(
                            "Agent config ${file.name} exceeds $MAX_CONFIG_LENGTH chars, " +
                                    "truncating to avoid bloating context"
                        )
                        content.take(MAX_CONFIG_LENGTH)
                    } else {
                        content
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to read agent config ${file.absolutePath}: ${e.message}")
                    null
                }
            }
        }

        return null
    }
}
