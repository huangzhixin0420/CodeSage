package com.codesage.ide.inline.prompt

import com.codesage.ide.inline.InlineChatMode

/**
 * Inline Chat 专用 Prompt 模板
 */
object InlineChatPrompts {

    val INLINE_SYSTEM_PROMPT = """
        你是 CodeSage，一个集成在 IntelliJ IDEA 中的 AI 编程助手。
        你正在通过 Inline Chat 模式与用户交互，这意味着：
        - 用户选中了编辑器中的一段代码，并请求你修改或解释
        - 你的回复将直接以 Diff 形式展示在编辑器中
        - 用户可以在代码行内直接接受或拒绝你的修改

        规则：
        1. **只输出修改后的完整代码块**，用 ```language 包裹
        2. 不要输出解释性文字（除非用户明确要求 Explain）
        3. 保持代码的缩进、格式和原有风格一致
        4. 如果用户要求 Refactor，只修改必要部分，不要重写整个文件
        5. 如果无法修改，输出 ```\n// 无法完成：原因\n```
        6. 对于 Fix 模式，修复后代码必须消除原始错误
        7. 对于 Test 模式，生成完整的测试方法，使用项目已有的测试框架
    """.trimIndent()

    fun buildPrompt(
        mode: InlineChatMode,
        selectedCode: String,
        language: String?,
        userInstruction: String,
        diagnostics: List<String> = emptyList()
    ): String {
        val lang = language ?: ""
        return when (mode) {
            InlineChatMode.EXPLAIN -> buildExplainPrompt(selectedCode, lang)
            InlineChatMode.REFACTOR -> buildRefactorPrompt(selectedCode, lang, userInstruction)
            InlineChatMode.FIX -> buildFixPrompt(selectedCode, lang, diagnostics)
            InlineChatMode.TEST -> buildTestPrompt(selectedCode, lang)
            InlineChatMode.CHAT -> buildChatPrompt(selectedCode, lang, userInstruction)
        }
    }

    private fun buildExplainPrompt(selectedCode: String, language: String): String {
        val header = if (language.isNotBlank()) "请解释以下 $language 代码：" else "请解释以下代码："
        val fence = if (language.isNotBlank()) "```$language" else "```"
        return """
            $header
            $fence
            $selectedCode
            $fence
            解释其功能、关键逻辑、潜在问题和优化建议。
        """.trimIndent()
    }

    private fun buildRefactorPrompt(selectedCode: String, language: String, userInstruction: String): String {
        val header = if (language.isNotBlank()) "请重构以下 $language 代码：" else "请重构以下代码："
        val fence = if (language.isNotBlank()) "```$language" else "```"
        return """
            $header
            $fence
            $selectedCode
            $fence
            用户要求：$userInstruction
            只输出重构后的代码块，保持原有缩进风格。
        """.trimIndent()
    }

    private fun buildFixPrompt(selectedCode: String, language: String, diagnostics: List<String>): String {
        val header = if (language.isNotBlank()) "以下 $language 代码存在错误，请修复：" else "以下代码存在错误，请修复："
        val fence = if (language.isNotBlank()) "```$language" else "```"
        val errorInfo = if (diagnostics.isNotEmpty()) {
            "错误信息：${diagnostics.joinToString("; ")}"
        } else {
            "代码存在错误，请修复。"
        }
        return """
            $header
            $fence
            $selectedCode
            $fence
            $errorInfo
            只输出修复后的代码块。
        """.trimIndent()
    }

    private fun buildTestPrompt(selectedCode: String, language: String): String {
        val header = if (language.isNotBlank()) "为以下 $language 代码生成单元测试：" else "为以下代码生成单元测试："
        val fence = if (language.isNotBlank()) "```$language" else "```"
        return """
            $header
            $fence
            $selectedCode
            $fence
            生成完整的测试方法，覆盖正常路径和边界条件。
            只输出测试代码块。
        """.trimIndent()
    }

    private fun buildChatPrompt(selectedCode: String, language: String, userInstruction: String): String {
        val header = if (language.isNotBlank()) "以下 $language 代码：" else "以下代码："
        val fence = if (language.isNotBlank()) "```$language" else "```"
        return """
            $header
            $fence
            $selectedCode
            $fence
            $userInstruction
            只输出修改后的代码块。
        """.trimIndent()
    }
}
