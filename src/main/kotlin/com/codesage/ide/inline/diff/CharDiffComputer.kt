package com.codesage.ide.inline.diff

/**
 * 字符级 Diff 计算器
 *
 * 基于编辑距离的回溯算法，找出两个字符串之间的最小差异。
 * 适用于单行代码的精确对比。
 */
object CharDiffComputer {

    /**
     * 计算两个字符串的字符级 Diff
     */
    fun compute(oldText: String, newText: String): List<CharDiff> {
        if (oldText == newText) return emptyList()
        if (oldText.isEmpty()) {
            return listOf(CharDiff(0, 0, isDeletion = false, replacement = newText))
        }
        if (newText.isEmpty()) {
            return listOf(CharDiff(0, oldText.length, isDeletion = true))
        }

        val edits = computeEdits(oldText, newText)
        return mergeEdits(edits)
    }

    /**
     * 计算编辑操作序列
     */
    private fun computeEdits(oldText: String, newText: String): List<Edit> {
        val m = oldText.length
        val n = newText.length
        val dp = Array(m + 1) { IntArray(n + 1) }

        // 初始化边界
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        // 填充动态规划表
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (oldText[i - 1] == newText[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    minOf(
                        dp[i - 1][j] + 1,     // 删除
                        dp[i][j - 1] + 1,     // 插入
                        dp[i - 1][j - 1] + 1  // 替换
                    )
                }
            }
        }

        // 回溯找出编辑路径
        val edits = mutableListOf<Edit>()
        var i = m
        var j = n

        while (i > 0 || j > 0) {
            when {
                i == 0 -> {
                    edits.add(0, Edit.Insert(0, newText[j - 1]))
                    j--
                }

                j == 0 -> {
                    edits.add(0, Edit.Delete(i - 1, oldText[i - 1]))
                    i--
                }

                oldText[i - 1] == newText[j - 1] -> {
                    i--
                    j--
                }

                dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    edits.add(0, Edit.Delete(i - 1, oldText[i - 1]))
                    edits.add(0, Edit.Insert(i - 1, newText[j - 1]))
                    i--
                    j--
                }

                dp[i][j] == dp[i - 1][j] + 1 -> {
                    edits.add(0, Edit.Delete(i - 1, oldText[i - 1]))
                    i--
                }

                else -> {
                    edits.add(0, Edit.Insert(i, newText[j - 1]))
                    j--
                }
            }
        }

        return edits
    }

    /**
     * 合并连续的编辑操作
     */
    private fun mergeEdits(edits: List<Edit>): List<CharDiff> {
        if (edits.isEmpty()) return emptyList()

        val result = mutableListOf<CharDiff>()
        var delStart = -1
        var delEnd = -1
        var insText = StringBuilder()
        var insPos = -1

        fun flushDeletions() {
            if (delStart >= 0) {
                result.add(CharDiff(delStart, delEnd, isDeletion = true))
                delStart = -1
                delEnd = -1
            }
        }

        fun flushInsertions() {
            if (insText.isNotEmpty()) {
                result.add(CharDiff(insPos, insPos, isDeletion = false, replacement = insText.toString()))
                insText = StringBuilder()
                insPos = -1
            }
        }

        for (edit in edits) {
            when (edit) {
                is Edit.Delete -> {
                    flushInsertions()
                    if (delStart < 0) {
                        delStart = edit.index
                        delEnd = edit.index + 1
                    } else if (edit.index == delEnd) {
                        delEnd++
                    } else {
                        flushDeletions()
                        delStart = edit.index
                        delEnd = edit.index + 1
                    }
                }

                is Edit.Insert -> {
                    flushDeletions()
                    if (insText.isEmpty()) {
                        insPos = edit.index
                        insText.append(edit.char)
                    } else if (edit.index == insPos) {
                        // 同一位置插入多个字符（在回溯时可能拆分为多个edit）
                        insText.append(edit.char)
                    } else {
                        flushInsertions()
                        insPos = edit.index
                        insText.append(edit.char)
                    }
                }
            }
        }

        flushDeletions()
        flushInsertions()

        return result
    }

    private sealed class Edit {
        data class Delete(val index: Int, val char: Char) : Edit()
        data class Insert(val index: Int, val char: Char) : Edit()
    }
}
