package com.codesage.ide.inline.diff

/**
 * Diff 计算结果
 */
data class DiffResult(
    /** 所有 Diff 行 */
    val lines: List<DiffLine> = emptyList(),

    /** 是否有变更 */
    val hasChanges: Boolean = lines.any { it.type != DiffType.CONTEXT },

    /** 删除行数 */
    val removedCount: Int = lines.count { it.type == DiffType.REMOVED },

    /** 新增行数 */
    val addedCount: Int = lines.count { it.type == DiffType.ADDED },

    /** 修改行数 */
    val modifiedCount: Int = lines.count { it.type == DiffType.MODIFIED }
) {
    /**
     * 获取变更块的列表（连续的变更行组成一个块）
     */
    fun getChangeBlocks(): List<ChangeBlock> {
        val blocks = mutableListOf<ChangeBlock>()
        var currentBlock = mutableListOf<DiffLine>()

        for (line in lines) {
            if (line.type != DiffType.CONTEXT) {
                currentBlock.add(line)
            } else {
                if (currentBlock.isNotEmpty()) {
                    blocks.add(ChangeBlock.fromLines(currentBlock))
                    currentBlock = mutableListOf()
                }
            }
        }

        if (currentBlock.isNotEmpty()) {
            blocks.add(ChangeBlock.fromLines(currentBlock))
        }

        return blocks
    }

    companion object {
        /** 空 Diff（无变更） */
        val EMPTY = DiffResult()
    }
}

/**
 * 变更块（连续的变更行）
 */
data class ChangeBlock(
    /** 块唯一ID */
    val id: String,

    /** 起始行号 */
    val startLine: Int,

    /** 结束行号 */
    val endLine: Int,

    /** 块描述 */
    val description: String,

    /** 包含的 Diff 行 */
    val lines: List<DiffLine>
) {
    companion object {
        fun fromLines(lines: List<DiffLine>): ChangeBlock {
            val firstLine = lines.first().lineNumber
            val lastLine = lines.last().lineNumber
            val removed = lines.count { it.type == DiffType.REMOVED }
            val added = lines.count { it.type == DiffType.ADDED }

            val desc = buildString {
                if (removed > 0) append("-$removed")
                if (added > 0) {
                    if (isNotEmpty()) append(" ")
                    append("+$added")
                }
            }

            return ChangeBlock(
                id = "block_${firstLine}_${lastLine}_${System.currentTimeMillis()}",
                startLine = firstLine,
                endLine = lastLine,
                description = desc.ifEmpty { "modified" },
                lines = lines
            )
        }
    }
}
