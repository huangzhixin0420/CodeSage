package com.codesage.tools.guardrails

import com.codesage.shared.utils.Logger

/**
 * 工具输出截断器
 * 防止工具输出过长消耗过多token
 */
class OutputTruncator(
    private val defaultMaxLength: Int = 8000,
    private val defaultMaxLines: Int = 200
) {
    private val logger = Logger.getLogger<OutputTruncator>()

    /**
     * 截断策略
     */
    enum class TruncationStrategy {
        HEAD,       // 保留开头，截断尾部
        TAIL,       // 保留尾部，截断头部
        MIDDLE,     // 保留首尾，截断中间
        SMART       // 智能截断（保留结构）
    }

    /**
     * 截断结果
     */
    data class TruncationResult(
        val content: String,
        val wasTruncated: Boolean,
        val originalLength: Int,
        val originalLines: Int,
        val truncatedLines: Int
    )

    /**
     * 截断文本内容
     */
    fun truncate(
        content: String,
        maxLength: Int = defaultMaxLength,
        maxLines: Int = defaultMaxLines,
        strategy: TruncationStrategy = TruncationStrategy.SMART
    ): TruncationResult {
        val originalLength = content.length
        val lines = content.lines()
        val originalLines = lines.size

        // 如果内容在限制内，直接返回
        if (originalLength <= maxLength && originalLines <= maxLines) {
            return TruncationResult(
                content = content,
                wasTruncated = false,
                originalLength = originalLength,
                originalLines = originalLines,
                truncatedLines = originalLines
            )
        }

        logger.debug("Truncating content: ${originalLength} chars, $originalLines lines")

        val truncated = when (strategy) {
            TruncationStrategy.HEAD -> truncateHead(content, maxLength, maxLines)
            TruncationStrategy.TAIL -> truncateTail(content, maxLength, maxLines)
            TruncationStrategy.MIDDLE -> truncateMiddle(content, maxLength, maxLines)
            TruncationStrategy.SMART -> truncateSmart(content, maxLength, maxLines)
        }

        val truncatedLines = truncated.lines().size
        return TruncationResult(
            content = truncated,
            wasTruncated = true,
            originalLength = originalLength,
            originalLines = originalLines,
            truncatedLines = truncatedLines
        )
    }

    /**
     * 截断文件列表/搜索结果等结构化数据
     */
    fun truncateList(
        items: List<String>,
        maxItems: Int = 50,
        maxTotalLength: Int = defaultMaxLength
    ): TruncationResult {
        if (items.size <= maxItems && items.sumOf { it.length + 1 } <= maxTotalLength) {
            val content = items.joinToString("\n")
            return TruncationResult(
                content = content,
                wasTruncated = false,
                originalLength = content.length,
                originalLines = items.size,
                truncatedLines = items.size
            )
        }

        val truncatedItems = items.take(maxItems)
        val content = buildString {
            appendLine(truncatedItems.joinToString("\n"))
            if (items.size > maxItems) {
                appendLine()
                appendLine("[Output truncated...] (${items.size - maxItems} more items truncated)")
            }
        }

        return TruncationResult(
            content = content.trim(),
            wasTruncated = true,
            originalLength = items.sumOf { it.length + 1 },
            originalLines = items.size,
            truncatedLines = truncatedItems.size
        )
    }

    /**
     * 截断JSON/XML等结构化内容
     * 保留顶层结构，深层内容用占位符替换
     */
    fun truncateStructured(
        content: String,
        maxDepth: Int = 3,
        maxArrayItems: Int = 20,
        maxLength: Int = defaultMaxLength
    ): TruncationResult {
        if (content.length <= maxLength) {
            return TruncationResult(
                content = content,
                wasTruncated = false,
                originalLength = content.length,
                originalLines = content.lines().size,
                truncatedLines = content.lines().size
            )
        }

        val builder = StringBuilder()
        var depth = 0
        var inString = false
        var escapeNext = false
        var arrayItemCount = 0
        var skipUntilDepth = -1
        var skipTargetCloser = ' '

        var i = 0
        while (i < content.length) {
            if (builder.length >= maxLength - 100) {
                builder.appendLine()
                builder.appendLine("[Output truncated...] (content truncated due to length limit)")
                break
            }

            val char = content[i]

            when {
                escapeNext -> {
                    builder.append(char)
                    escapeNext = false
                    i++
                }

                char == '\\' && inString -> {
                    builder.append(char)
                    escapeNext = true
                    i++
                }

                char == '"' -> {
                    builder.append(char)
                    inString = !inString
                    i++
                }

                inString -> {
                    builder.append(char)
                    i++
                }

                skipUntilDepth >= 0 -> {
                    // We are skipping deep content
                    when (char) {
                        '{', '[' -> depth++
                        '}' -> {
                            depth--
                            if (depth < skipUntilDepth) {
                                builder.append('}')
                                skipUntilDepth = -1
                            }
                        }

                        ']' -> {
                            depth--
                            if (depth < skipUntilDepth) {
                                builder.append(']')
                                skipUntilDepth = -1
                                arrayItemCount = 0
                            }
                        }
                    }
                    i++
                }

                char == '{' || char == '[' -> {
                    depth++
                    builder.append(char)
                    if (depth > maxDepth) {
                        // Start skipping deep content
                        skipUntilDepth = depth - 1
                        builder.append(" ... ")
                    }
                    i++
                }

                char == '}' -> {
                    depth--
                    builder.append(char)
                    i++
                }

                char == ']' -> {
                    depth--
                    builder.append(char)
                    arrayItemCount = 0
                    i++
                }

                char == ',' && depth > 0 && content.getOrNull(i - 1)
                    ?.let { it == ']' || it == '}' || it == '"' } == true -> {
                    // Count array/object items at current depth
                    if (depth == 1) {
                        arrayItemCount++
                        if (arrayItemCount >= maxArrayItems) {
                            builder.append(", ... ")
                            // Skip until matching closing bracket
                            var innerDepth = depth
                            var innerString = false
                            var innerEscape = false
                            i++
                            while (i < content.length && innerDepth > 0) {
                                val c = content[i]
                                when {
                                    innerEscape -> innerEscape = false
                                    c == '\\' && innerString -> innerEscape = true
                                    c == '"' -> innerString = !innerString
                                    !innerString && (c == '{' || c == '[') -> innerDepth++
                                    !innerString && (c == '}' || c == ']') -> innerDepth--
                                }
                                i++
                            }
                            if (i <= content.length) {
                                builder.append(if (content[i - 1] == '}') "}" else "]")
                            }
                            arrayItemCount = 0
                            continue
                        }
                    }
                    builder.append(char)
                    i++
                }

                else -> {
                    builder.append(char)
                    i++
                }
            }
        }

        return TruncationResult(
            content = builder.toString().trim(),
            wasTruncated = true,
            originalLength = content.length,
            originalLines = content.lines().size,
            truncatedLines = builder.toString().lines().size
        )
    }

    private fun truncateHead(content: String, maxLength: Int, maxLines: Int): String {
        val lines = content.lines()
        val truncatedLines = if (lines.size > maxLines) lines.take(maxLines) else lines
        var result = truncatedLines.joinToString("\n")
        if (result.length > maxLength) {
            result = result.take(maxLength)
        }
        return appendTruncationNotice(result, lines.size, truncatedLines.size)
    }

    private fun truncateTail(content: String, maxLength: Int, maxLines: Int): String {
        val lines = content.lines()
        val truncatedLines = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
        var result = truncatedLines.joinToString("\n")
        if (result.length > maxLength) {
            result = result.takeLast(maxLength)
        }
        return "[Output truncated...]\n...(truncated)\n$result"
    }

    private fun truncateMiddle(content: String, maxLength: Int, maxLines: Int): String {
        val lines = content.lines()
        val halfLines = maxLines / 2
        val head = lines.take(halfLines)
        val tail = lines.takeLast(halfLines)

        var result = head.joinToString("\n")
        result += "\n\n[Output truncated...] (${lines.size - head.size - tail.size} lines omitted) ...\n\n"
        result += tail.joinToString("\n")

        if (result.length > maxLength) {
            val halfLength = maxLength / 2
            result = content.take(halfLength) + "\n[Output truncated...]\n" + content.takeLast(halfLength)
        }
        return result
    }

    private fun truncateSmart(content: String, maxLength: Int, maxLines: Int): String {
        val lines = content.lines()

        // 优先按行截断（保留行完整性）
        if (lines.size > maxLines) {
            val headLines = (maxLines * 0.7).toInt()
            val tailLines = maxLines - headLines
            val head = lines.take(headLines)
            val tail = lines.takeLast(tailLines)

            return buildString {
                appendLine(head.joinToString("\n"))
                appendLine()
                appendLine("[Output truncated...] (${lines.size - headLines - tailLines} lines truncated, total: ${lines.size} lines)")
                appendLine()
                append(tail.joinToString("\n"))
            }.trim()
        }

        // 按字符截断（保持单词/行完整性）
        if (content.length > maxLength) {
            val keepLength = maxLength - 150 // 预留截断提示空间
            val headEnd = content.lastIndexOf('\n', keepLength / 2).coerceAtLeast(keepLength / 2)
            val tailStart = content.indexOf('\n', content.length - keepLength / 2)
                .let { if (it < 0) content.length - keepLength / 2 else it }

            return buildString {
                appendLine(content.take(headEnd))
                appendLine()
                appendLine("[Output truncated...] (${content.length - headEnd - (content.length - tailStart)} chars truncated, total: ${content.length} chars)")
                appendLine()
                append(content.drop(tailStart))
            }.trim()
        }

        return content
    }

    private fun appendTruncationNotice(content: String, originalLines: Int, keptLines: Int): String {
        if (originalLines <= keptLines) return content
        return "$content\n\n[Output truncated...] (truncated from $originalLines lines)"
    }
}
