package com.codesage.ide.inline

/**
 * Inline Chat 操作模式
 */
enum class InlineChatMode {
    /** 自由输入模式 */
    CHAT,

    /** 解释代码 */
    EXPLAIN,

    /** 重构代码 */
    REFACTOR,

    /** 修复错误 */
    FIX,

    /** 生成测试 */
    TEST
}
