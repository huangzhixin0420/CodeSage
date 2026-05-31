package com.codesage.ide.inline.modification

/**
 * 代码变更类型
 */
enum class ChangeType {
    /** 替换行范围 */
    REPLACE,

    /** 插入新行 */
    INSERT,

    /** 删除行范围 */
    DELETE
}

/**
 * 单次代码变更操作
 */
data class CodeChange(
    /** 变更类型 */
    val type: ChangeType,

    /** 起始行号（0-based） */
    val startLine: Int,

    /** 结束行号（0-based，包含） */
    val endLine: Int = startLine,

    /** 新内容（REPLACE/INSERT 时使用） */
    val newContent: String = "",

    /** 原始内容（用于验证） */
    val originalContent: String = ""
) {
    /**
     * 验证变更是否有效
     */
    fun isValid(): Boolean {
        return startLine >= 0 && endLine >= startLine
    }

    /**
     * 获取变更影响的行数
     */
    fun affectedLineCount(): Int = when (type) {
        ChangeType.REPLACE -> (endLine - startLine + 1)
        ChangeType.INSERT -> 0
        ChangeType.DELETE -> (endLine - startLine + 1)
    }

    /**
     * 获取新增内容的行数
     */
    fun newLineCount(): Int = if (newContent.isBlank()) 0 else newContent.lines().size
}

/**
 * 一组代码变更（一次 Inline Chat 产生的所有修改）
 */
data class CodeChanges(
    /** 变更列表 */
    val changes: List<CodeChange>,

    /** 变更说明 */
    val description: String = ""
) {
    /**
     * 验证所有变更是否有效
     */
    fun isValid(): Boolean = changes.all { it.isValid() }

    /**
     * 获取变更总数
     */
    fun changeCount(): Int = changes.size

    /**
     * 获取总删除行数
     */
    fun totalDeletedLines(): Int = changes.filter { it.type == ChangeType.DELETE }
        .sumOf { it.affectedLineCount() }

    /**
     * 获取总新增行数
     */
    fun totalAddedLines(): Int = changes.filter { it.type == ChangeType.INSERT || it.type == ChangeType.REPLACE }
        .sumOf { it.newLineCount() }
}
