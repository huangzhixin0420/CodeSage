package com.codesage.analysis

import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * PSI代码分析器
 * 利用IntelliJ的PSI（Program Structure Interface）进行语义级代码分析
 */
class PSIAnalyzer(private val project: Project) {
    private val logger = Logger.getLogger<PSIAnalyzer>()

    /**
     * 符号信息
     *
     * 设计约束（PSI 解耦）：
     * - 本类为纯数据类，所有字段均为基本类型（String / Int / List<String> 等）。
     * - 不持有任何 PsiElement 引用，分析完成后即与 PSI 树解耦，无内存泄漏风险。
     * - 任何新增字段必须是可序列化的基本类型，禁止引入 PsiElement 或 Project 引用。
     */
    data class SymbolInfo(
        val name: String,
        val type: SymbolType,
        val qualifiedName: String?,
        val filePath: String,
        val lineNumber: Int,
        val docComment: String?,
        val modifiers: List<String>,
        val parameters: List<ParameterInfo> = emptyList(),
        val returnType: String? = null,
        val superTypes: List<String> = emptyList()
    )

    data class ParameterInfo(
        val name: String,
        val type: String,
        val defaultValue: String? = null
    )

    enum class SymbolType {
        CLASS, INTERFACE, ENUM, METHOD, FIELD, PROPERTY, CONSTRUCTOR, PACKAGE
    }

    /**
     * 分析文件中的所有符号（递归）
     */
    fun analyzeFileDeep(file: VirtualFile): List<SymbolInfo> {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val result = mutableListOf<SymbolInfo>()
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val named = element as? PsiNamedElement
                if (named != null && named.name != null && isCodeElement(element)) {
                    val info = extractSymbolInfo(element)
                    if (info != null) result.add(info)
                }
                super.visitElement(element)
            }
        })
        return result
    }

    /**
     * 查找类定义（通过JavaPsiFacade）
     */
    fun findClass(className: String): SymbolInfo? {
        return try {
            val facadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
            val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val scopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope")
            val scope = scopeClass.getMethod("projectScope", Project::class.java).invoke(null, project)
            val psiClass = facadeClass.getMethod("findClass", String::class.java, scopeClass)
                .invoke(facade, className, scope)
                ?: return null
            extractClassSymbol(psiClass)
        } catch (e: Exception) {
            logger.warn("Failed to find class: $className", e)
            null
        }
    }

    /**
     * 查找文件中的所有方法
     */
    fun findMethodsInFile(file: VirtualFile): List<SymbolInfo> {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()
        val result = mutableListOf<SymbolInfo>()
        psiFile.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val named = element as? PsiNamedElement
                if (named != null && isMethodLike(element)) {
                    extractSymbolInfo(element)?.let { result.add(it) }
                }
                super.visitElement(element)
            }
        })
        return result
    }

    /**
     * 获取类继承链
     */
    fun getInheritanceChain(className: String): List<String> {
        return try {
            val facadeClass = Class.forName("com.intellij.psi.JavaPsiFacade")
            val facade = facadeClass.getMethod("getInstance", Project::class.java).invoke(null, project)
            val scopeClass = Class.forName("com.intellij.psi.search.GlobalSearchScope")
            val scope = scopeClass.getMethod("projectScope", Project::class.java).invoke(null, project)
            val psiClass = facadeClass.getMethod("findClass", String::class.java, scopeClass)
                .invoke(facade, className, scope)
                ?: return emptyList()

            val chain = mutableListOf<String>()
            var current: Any? = psiClass
            while (current != null) {
                val name = (current as? PsiNamedElement)?.name ?: "unknown"
                val qName = current.javaClass.getMethod("getQualifiedName").invoke(current) as? String
                chain.add(qName ?: name)
                current = try {
                    current.javaClass.getMethod("getSuperClass").invoke(current)
                } catch (e: Exception) {
                    null
                }
            }
            chain
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取文件概要（类、方法、字段列表）
     */
    fun getFileSummary(file: VirtualFile): FileSummary {
        val symbols = analyzeFileDeep(file)
        return FileSummary(
            filePath = file.path,
            classes = symbols.filter { it.type == SymbolType.CLASS },
            methods = symbols.filter { it.type == SymbolType.METHOD },
            fields = symbols.filter { it.type == SymbolType.FIELD },
            totalSymbols = symbols.size
        )
    }

    private fun isCodeElement(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName
        return className.contains("Class") ||
                className.contains("Method") ||
                className.contains("Function") ||
                className.contains("Field") ||
                className.contains("Property")
    }

    private fun isMethodLike(element: PsiElement): Boolean {
        val className = element.javaClass.simpleName
        return className.contains("Method") || className.contains("Function")
    }

    private fun extractSymbolInfo(element: PsiElement): SymbolInfo? {
        val named = element as? PsiNamedElement ?: return null
        val name = named.name ?: return null
        val type = inferSymbolType(element)
        val file = element.containingFile?.virtualFile?.path ?: ""
        val line = getLineNumber(element)
        val doc = extractDocComment(element)

        return SymbolInfo(
            name = name,
            type = type,
            qualifiedName = null,
            filePath = file,
            lineNumber = line,
            docComment = doc,
            modifiers = emptyList()
        )
    }

    private fun extractClassSymbol(psiClass: Any): SymbolInfo {
        val named = psiClass as? PsiNamedElement
        val name = named?.name ?: ""
        val qName = try {
            psiClass.javaClass.getMethod("getQualifiedName").invoke(psiClass) as? String
        } catch (e: Exception) {
            null
        }
        val doc = extractDocComment(psiClass as? PsiElement)
        val superTypes = try {
            val extendsList = psiClass.javaClass.getMethod("getExtendsListTypes").invoke(psiClass)
            val implementsList = psiClass.javaClass.getMethod("getImplementsListTypes").invoke(psiClass)
            emptyList<String>() // 简化处理
        } catch (e: Exception) {
            emptyList()
        }

        return SymbolInfo(
            name = name,
            type = SymbolType.CLASS,
            qualifiedName = qName,
            filePath = named?.containingFile?.virtualFile?.path ?: "",
            lineNumber = named?.let { getLineNumber(it) } ?: 0,
            docComment = doc,
            modifiers = emptyList(),
            superTypes = superTypes
        )
    }

    private fun inferSymbolType(element: PsiElement): SymbolType {
        val className = element.javaClass.simpleName
        return when {
            className.contains("Class") && !className.contains("Method") -> SymbolType.CLASS
            className.contains("Method") || className.contains("Function") -> SymbolType.METHOD
            className.contains("Field") || className.contains("Property") -> SymbolType.FIELD
            else -> SymbolType.PROPERTY
        }
    }

    private fun getLineNumber(element: PsiElement): Int {
        val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
        return document?.getLineNumber(element.textOffset)?.plus(1) ?: 0
    }

    private fun extractDocComment(element: PsiElement?): String? {
        if (element == null) return null
        return try {
            val docComment = element.javaClass.getMethod("getDocComment").invoke(element)
            docComment?.javaClass?.getMethod("getText")?.invoke(docComment) as? String
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 文件分析摘要
     */
    data class FileSummary(
        val filePath: String,
        val classes: List<SymbolInfo>,
        val methods: List<SymbolInfo>,
        val fields: List<SymbolInfo>,
        val totalSymbols: Int
    )
}
