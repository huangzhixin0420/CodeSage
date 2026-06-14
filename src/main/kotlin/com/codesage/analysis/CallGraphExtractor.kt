package com.codesage.analysis

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement

/**
 * 6.5.1 PSI 调用图提取器
 *
 * 替代原 `CodeInsightExecutor.findCallees` 中的正则启发式扫描，直接基于 PSI 树
 * 识别方法/函数调用表达式，避免把 `if/for/println` 等语法关键字误判为调用目标，
 * 并能正确处理扩展函数、中缀调用、带接收者的调用等复杂语法。
 *
 * 设计约束：
 * - 不直接 import Java/Kotlin 专用 PSI 类（如 `PsiMethodCallExpression`、
 *   `KtCallExpression`），而是通过 class name + 反射调用特定 getter，保证在
 *   测试 classpath 中类缺失时也能安全降级。
 * - 所有提取逻辑均为纯函数或无副作用访问 PSI，便于单元测试。
 */
internal object CallGraphExtractor {

    /**
     * 调用提取中应忽略的非目标标识符（语法关键字 / 作用域函数 / 常见标准库调用）。
     */
    val IGNORED_CALLEE_NAMES: Set<String> = setOf(
        "if", "when", "for", "while", "do", "return", "throw", "try", "catch", "finally",
        "println", "print", "require", "check", "assert",
        "also", "let", "run", "apply", "with", "use", "synchronized", "lazy",
        "takeIf", "takeUnless", "filter", "map", "forEach", "flatMap", "groupBy",
        "associate", "to", "until", "downTo", "step", "rangeTo",
        "is", "in", "as", "it", "this", "super"
    )

    /**
     * 从调用表达式的 PSI 元素中提取被调用符号的简单名称。
     *
     * 支持的表达式类型：
     * - Java: `PsiMethodCallExpression` -> `methodExpression.referenceName`
     * - Java: `PsiNewExpression` -> `classReference.name`
     * - Kotlin: `KtCallExpression` -> `calleeExpression.text` 再取最后一段标识符
     *
     * @return 被调用符号名；如果不是可识别的调用表达式，返回 null
     */
    fun extractCalleeName(element: PsiElement): String? {
        val qualifiedName = element::class.qualifiedName ?: return null
        return when {
            qualifiedName.endsWith(".PsiMethodCallExpression") -> extractJavaMethodCallName(element)
            qualifiedName.endsWith(".PsiNewExpression") -> extractJavaNewExpressionName(element)
            qualifiedName.endsWith(".KtCallExpression") -> extractKotlinCallName(element)
            else -> null
        }
    }

    /**
     * 从 Kotlin 调用文本（如 `foo.bar`、`bar`、`this.bar`）中提取最后一段方法名。
     * 会去掉泛型参数 `<...>`，并验证结果符合 Java/Kotlin 标识符规则。
     */
    fun extractCallName(calleeText: String): String? {
        val trimmed = calleeText.trim()
        if (trimmed.isEmpty()) return null
        // 去掉泛型参数，例如 "list.map<String>" -> "list.map"
        val withoutGenerics = trimmed.replace(genericArgsRegex, "")
        val lastSegment = withoutGenerics.substringAfterLast('.').trim()
        return if (identifierRegex.matches(lastSegment)) lastSegment else null
    }

    private fun extractJavaMethodCallName(element: PsiElement): String? = runCatching {
        val methodExpression = element.javaClass.getMethod("getMethodExpression").invoke(element)
        methodExpression?.javaClass?.getMethod("getReferenceName")?.invoke(methodExpression) as? String
    }.getOrNull()

    private fun extractJavaNewExpressionName(element: PsiElement): String? = runCatching {
        val classReference = element.javaClass.getMethod("getClassReference").invoke(element) as? PsiElement
        (classReference as? PsiNamedElement)?.name
    }.getOrNull()

    private fun extractKotlinCallName(element: PsiElement): String? = runCatching {
        val calleeExpression = element.javaClass.getMethod("getCalleeExpression").invoke(element) as? PsiElement
        val text = calleeExpression?.text ?: return null
        extractCallName(text)
    }.getOrNull()

    private val genericArgsRegex = Regex("<[^>]*>")
    private val identifierRegex = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
}
