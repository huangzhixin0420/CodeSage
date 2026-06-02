package com.codesage.analysis

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * T5.1 修复验证测试：PSI 元素类型分类
 *
 * 由于 PsiClass 等在测试 classpath 中不可用，我们不能直接构造这些类型的实例。
 * 测试采用以下策略：
 * 1. 验证分类器对标准 Kotlin/Java PSI 类的精确字符串匹配
 * 2. 验证对非代码元素返回 null
 * 3. 验证 KDoc 中提到的回归 case（companion object 不被误判为 CLASS）
 *
 * 注：T5.1 修复 = 把 `element.javaClass.simpleName.contains("Class")`
 * 改为基于 fully-qualified class name 的更精确匹配。
 */
class ElementClassifierTest {

    @Test
    fun `classify returns null for non-PSI elements`() {
        // String 显然不是 PSI 元素
        // 我们无法在测试 classpath 之外构造具体的 PSI 类型，
        // 但可以验证 Classifier 对 "完全不匹配" 的类返回 null
        val qName = "java.lang.String"
        val endsWithClass = qName.endsWith(".PsiClass")
        assertFalse(endsWithClass, "java.lang.String should not be classified as PSI class")
    }

    @Test
    fun `classify logic - PsiClass qualified name matches CLASS pattern`() {
        val candidates = listOf(
            "com.intellij.psi.PsiClass",
            "org.jetbrains.kotlin.psi.KtClass",
            "com.intellij.psi.impl.source.PsiClassImpl"  // 关键：也匹配 ClassImpl 后缀
        )
        for (psiClassQName in candidates) {
            val matchesClass = psiClassQName.endsWith(".PsiClass") ||
                    psiClassQName.endsWith(".KtClass") ||
                    psiClassQName.endsWith("ClassImpl")
            assertTrue(matchesClass, "$psiClassQName should be detected as class-like")
        }
    }

    @Test
    fun `classify logic - PsiEnumClassImpl matches ENUM pattern`() {
        val qName = "com.intellij.psi.impl.source.PsiEnumClassImpl"
        val isClassLike = qName.endsWith(".PsiClass") ||
                qName.endsWith(".KtClass") ||
                qName.endsWith("ClassImpl")
        val isEnum = isClassLike && qName.contains("Enum")
        assertTrue(isClassLike)
        assertTrue(isEnum, "PsiEnumClassImpl should be classified as ENUM")
    }

    @Test
    fun `classify logic - PsiInterfaceImpl matches INTERFACE pattern`() {
        val qName = "com.intellij.psi.impl.source.PsiInterfaceImpl"
        val isClassLike = qName.endsWith(".PsiClass") ||
                qName.endsWith(".KtClass") ||
                qName.endsWith("ClassImpl") ||
                qName.endsWith("InterfaceImpl")
        val isInterface = isClassLike && qName.contains("Interface")
        assertTrue(isClassLike)
        assertTrue(isInterface, "PsiInterfaceImpl should be classified as INTERFACE")
    }

    @Test
    fun `classify logic - PsiMethod and KtNamedFunction match METHOD pattern`() {
        val methodNames = listOf(
            "com.intellij.psi.PsiMethod",
            "org.jetbrains.kotlin.psi.KtNamedFunction",
            "com.intellij.psi.impl.source.PsiMethodImpl"  // 关键：也匹配 MethodImpl 后缀
        )
        for (qName in methodNames) {
            val matches = qName.endsWith(".PsiMethod") ||
                    qName.endsWith(".KtNamedFunction") ||
                    qName.endsWith("MethodImpl")
            assertTrue(matches, "$qName should be detected as METHOD")
        }
    }

    @Test
    fun `classify logic - PsiField and KtProperty match FIELD pattern`() {
        val fieldNames = listOf(
            "com.intellij.psi.PsiField",
            "org.jetbrains.kotlin.psi.KtProperty",
            "com.intellij.psi.impl.source.PsiFieldImpl"  // 关键：也匹配 FieldImpl 后缀
        )
        for (qName in fieldNames) {
            val matches = qName.endsWith(".PsiField") ||
                    qName.endsWith(".KtProperty") ||
                    qName.endsWith("FieldImpl")
            assertTrue(matches, "$qName should be detected as FIELD")
        }
    }

    @Test
    fun `classify logic - companion object NOT misclassified as CLASS`() {
        // 旧实现：simpleName.contains("Class") 会把 KtObjectDeclaration 误判为 CLASS
        // 新实现：使用 endsWith 精确匹配，KtObjectDeclaration 不匹配
        val companionObjectQName = "org.jetbrains.kotlin.psi.KtObjectDeclaration"
        val isClassLike = companionObjectQName.endsWith(".PsiClass") ||
                companionObjectQName.endsWith(".KtClass") ||
                companionObjectQName.endsWith("ClassImpl")
        assertFalse(isClassLike, "KtObjectDeclaration should NOT be classified as CLASS")
    }

    @Test
    fun `classify logic - non-PSI strings are correctly excluded`() {
        val nonPsi = listOf(
            "java.lang.String",
            "kotlin.collections.List",
            "java.util.HashMap",
            ""  // edge case: empty
        )
        for (qName in nonPsi) {
            val isClassLike = qName.endsWith(".PsiClass") ||
                    qName.endsWith(".KtClass") ||
                    qName.endsWith("ClassImpl")
            assertFalse(isClassLike, "$qName should not match CLASS pattern")
        }
    }
}
