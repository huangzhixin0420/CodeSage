package com.codesage.ide.inline.diff

/**
 * Diff 行类型
 */
enum class DiffType {
    /** 删除的行 */
    REMOVED,

    /** 新增的行 */
    ADDED,

    /** 未变更的上下文行 */
    CONTEXT,

    /** 修改的行（需要字符级对比） */
    MODIFIED
}

/**
 * 单行 Diff 信息
 */
data class DiffLine(
    /** Diff 类型 */
    val type: DiffType,

    /** 行号（在目标文档中的位置） */
    val lineNumber: Int,

    /** 行内容 */
    val content: String,

    /** 原始内容（MODIFIED 类型时使用） */
    val oldContent: String? = null,

    /** 字符级 Diff（MODIFIED 类型时使用） */
    val charDiffs: List<CharDiff> = emptyList()
)

/**
 * 字符级 Diff
 */
data class CharDiff(
    /** 起始字符位置 */
    val start: Int,

    /** 结束字符位置 */
    val end: Int,

    /** 是否为删除 */
    val isDeletion: Boolean,

    /** 替换文本（新增时使用） */
    val replacement: String? = null
)
