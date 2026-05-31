package com.codesage.ide.inline

/**
 * Inline Chat 上下文信息
 * 封装一次 Inline Chat 请求所需的全部上下文数据
 */
data class InlineChatContext(
    /** 选中的代码文本 */
    val selectedText: String? = null,

    /** 选中/光标起始行号（0-based） */
    val startLine: Int = 0,

    /** 选中/光标结束行号（0-based） */
    val endLine: Int = 0,

    /** 操作模式 */
    val mode: InlineChatMode = InlineChatMode.CHAT,

    /** 当前文件路径 */
    val filePath: String? = null,

    /** 语言类型（如 "Kotlin", "Java"） */
    val language: String? = null,

    /** 当前行的诊断信息（错误/警告消息） */
    val diagnostics: List<DiagnosticInfo> = emptyList()
) {
    /**
     * 是否有代码选中
     */
    fun hasSelection(): Boolean = !selectedText.isNullOrBlank()

    /**
     * 获取选中行数
     */
    fun selectedLineCount(): Int = (endLine - startLine + 1).coerceAtLeast(1)

    /**
     * 获取模式对应的默认提示词
     */
    fun getDefaultPrompt(): String = when (mode) {
        InlineChatMode.CHAT -> ""
        InlineChatMode.EXPLAIN -> "解释这段代码的功能、关键逻辑、潜在问题和优化建议"
        InlineChatMode.REFACTOR -> "重构这段代码，提高可读性和性能"
        InlineChatMode.FIX -> "修复这段代码中的错误"
        InlineChatMode.TEST -> "为这段代码生成单元测试"
    }
}

/**
 * 诊断信息
 */
data class DiagnosticInfo(
    /** 诊断消息 */
    val message: String,

    /** 严重程度：ERROR, WARNING, INFO */
    val severity: String,

    /** 所在行号 */
    val lineNumber: Int
)
