package com.codesage.analysis

import com.intellij.psi.PsiElement

/**
 * T5.1 修复：PSI 元素类型分类
 *
 * 替代原 `PSIAnalyzer` 中基于 `element.javaClass.simpleName.contains("Class")`
 * 的脆弱字符串匹配。改用 fully-qualified class name 匹配 + 显式类型检查。
 *
 * 注意：本类不直接 import `PsiClass` / `PsiMethod` / `PsiField`，因为
 * 这些类在测试 classpath 中不可用（intellij-platform-gradle-plugin
 * 提供的 API 子集）。改用 fully-qualified class name 字符串比较，
 * 比 simpleName 匹配更精确（避免误判嵌套类如 `CompanionObject`）。
 */
object ElementClassifier {

    /**
     * 把 PSI 元素分类为代码洞察工具用的 SymbolType
     *
     * @return 分类结果；如果不是代码元素则返回 null
     */
    fun classify(element: PsiElement): PSIAnalyzer.SymbolType? {
        val qName = element::class.qualifiedName ?: return null
        return when {
            isClassLike(qName) -> classifyClassLike(qName)
            isMethodLike(qName) -> PSIAnalyzer.SymbolType.METHOD
            isFieldLike(qName) -> PSIAnalyzer.SymbolType.FIELD
            qName.endsWith(".PsiPackage") -> PSIAnalyzer.SymbolType.PACKAGE
            else -> null
        }
    }

    /**
     * Class-like 元素：PsiClass / KtClass / *ClassImpl（Impl 类的后缀）
     * 包括 PsiClassImpl, PsiEnumClassImpl, PsiAnnotationClassImpl, PsiInterfaceImpl
     */
    private fun isClassLike(qName: String): Boolean {
        return qName.endsWith(".PsiClass") ||
                qName.endsWith(".KtClass") ||
                qName.endsWith("ClassImpl") ||  // PsiClassImpl, PsiEnumClassImpl, PsiAnnotationClassImpl
                qName.endsWith("InterfaceImpl")  // PsiInterfaceImpl
    }

    /**
     * Method-like 元素
     */
    private fun isMethodLike(qName: String): Boolean {
        return qName.endsWith(".PsiMethod") ||
                qName.endsWith(".KtNamedFunction") ||
                qName.endsWith("MethodImpl")  // PsiMethodImpl
    }

    /**
     * Field-like 元素
     */
    private fun isFieldLike(qName: String): Boolean {
        return qName.endsWith(".PsiField") ||
                qName.endsWith(".KtProperty") ||
                qName.endsWith("FieldImpl")  // PsiFieldImpl
    }

    /**
     * 对于 class-like 元素，进一步区分类、接口、枚举
     */
    private fun classifyClassLike(qName: String): PSIAnalyzer.SymbolType {
        return when {
            // 枚举：PsiEnumClassImpl / KtEnum / 等
            qName.contains("Enum") && (qName.endsWith("Class") || qName.endsWith("ClassImpl")) -> PSIAnalyzer.SymbolType.ENUM
            // 注解：PsiAnnotationClass / PsiAnnotationClassImpl
            qName.contains("Annotation") -> PSIAnalyzer.SymbolType.CLASS
            // 接口：PsiInterfaceImpl / KtClass but isInterface=true
            // 简单启发：含 "Interface" 字样
            qName.contains("Interface") -> PSIAnalyzer.SymbolType.INTERFACE
            else -> PSIAnalyzer.SymbolType.CLASS
        }
    }
}
