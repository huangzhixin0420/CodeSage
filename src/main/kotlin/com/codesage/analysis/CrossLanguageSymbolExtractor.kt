package com.codesage.analysis

import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 6.5.3 跨语言符号轻量提取器
 *
 * 对 PSI 树难以直接解析或没有标准 PSI 表示的文件类型（配置文件、SQL、Markdown、
 * Vue/Svelte 单文件组件等），做轻量级符号提取：
 * - JSON / YAML：顶层 key
 * - SQL：CREATE TABLE / VIEW / FUNCTION / PROCEDURE / INDEX / TRIGGER 名称
 * - Markdown：标题（# heading）
 * - Vue / Svelte：组件名（文件名）
 *
 * 不追求完整 AST，只提取“项目结构理解”和“搜索召回”所需的关键符号。
 */
internal object CrossLanguageSymbolExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 提取配置文件符号（JSON / YAML）。
     */
    fun extractConfigSymbols(file: VirtualFile): List<PSIAnalyzer.SymbolInfo> {
        val ext = file.extension?.lowercase() ?: return emptyList()
        val content = readFile(file) ?: return emptyList()
        return extractFromContent(file.path, ext, content)
    }

    /**
     * 提取文本类文件符号（SQL / Markdown / Vue / Svelte）。
     */
    fun extractTextSymbols(file: VirtualFile): List<PSIAnalyzer.SymbolInfo> {
        val ext = file.extension?.lowercase() ?: return emptyList()
        val content = readFile(file) ?: return emptyList()
        val fileName = file.extension?.let { e -> file.name.removeSuffix(".$e") } ?: file.name
        return extractFromContent(file.path, ext, content, fileName = fileName)
    }

    /**
     * 6.5.3：按文件路径、扩展名和内容提取符号（便于测试与纯文本处理）。
     */
    fun extractFromContent(
        filePath: String,
        extension: String,
        content: String,
        fileName: String = File(filePath).nameWithoutExtension
    ): List<PSIAnalyzer.SymbolInfo> {
        return when (extension.lowercase()) {
            "json" -> extractJsonSymbols(filePath, content)
            "yaml", "yml" -> extractYamlSymbols(filePath, content)
            "sql" -> extractSqlSymbols(filePath, content)
            "md" -> extractMarkdownSymbols(filePath, content)
            "vue", "svelte" -> listOfNotNull(componentSymbol(filePath, fileName))
            else -> emptyList()
        }
    }

    private fun readFile(file: VirtualFile): String? {
        return try {
            String(file.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonSymbols(filePath: String, content: String): List<PSIAnalyzer.SymbolInfo> {
        return try {
            val element = json.parseToJsonElement(content)
            if (element is JsonObject) {
                element.keys.mapNotNull { key ->
                    if (key.isValidIdentifier()) symbolInfo(filePath, key, PSIAnalyzer.SymbolType.FIELD) else null
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractYamlSymbols(filePath: String, content: String): List<PSIAnalyzer.SymbolInfo> {
        return try {
            val root = Yaml().load<Any?>(content)
            when (root) {
                is Map<*, *> -> root.keys.mapNotNull { key ->
                    val name = key?.toString()
                    if (name != null && name.isValidIdentifier()) symbolInfo(
                        filePath,
                        name,
                        PSIAnalyzer.SymbolType.FIELD
                    ) else null
                }

                else -> emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractSqlSymbols(filePath: String, content: String): List<PSIAnalyzer.SymbolInfo> {
        val results = mutableListOf<PSIAnalyzer.SymbolInfo>()
        val seen = mutableSetOf<String>()
        val regex = Regex(
            """(?im)^\s*CREATE\s+(?:OR\s+REPLACE\s+)?(?:TABLE|VIEW|FUNCTION|PROCEDURE|INDEX|TRIGGER)\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"\[]?([a-zA-Z_][a-zA-Z0-9_]*)"""
        )
        regex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            if (seen.add(name)) {
                val keyword = match.groupValues[0]
                    .uppercase()
                    .replace(Regex("""^\s*CREATE\s+(?:OR\s+REPLACE\s+)?"""), "")
                    .trim()
                    .substringBefore(" ")
                val type = if (keyword in setOf(
                        "FUNCTION",
                        "PROCEDURE"
                    )
                ) PSIAnalyzer.SymbolType.METHOD else PSIAnalyzer.SymbolType.CLASS
                results.add(symbolInfo(filePath, name, type))
            }
        }
        return results
    }

    private fun extractMarkdownSymbols(filePath: String, content: String): List<PSIAnalyzer.SymbolInfo> {
        val headingRegex = Regex("""^#{1,6}\s+(.+)\s*$""", RegexOption.MULTILINE)
        return headingRegex.findAll(content).mapNotNull { match ->
            val title = match.groupValues[1].trim()
            if (title.isValidIdentifier(allowSpaces = false)) symbolInfo(
                filePath,
                title,
                PSIAnalyzer.SymbolType.CLASS
            ) else null
        }.toList()
    }

    private fun componentSymbol(filePath: String, name: String?): PSIAnalyzer.SymbolInfo? {
        if (name.isNullOrBlank() || !name.isValidIdentifier()) return null
        return symbolInfo(filePath, name, PSIAnalyzer.SymbolType.CLASS)
    }

    private fun symbolInfo(filePath: String, name: String, type: PSIAnalyzer.SymbolType): PSIAnalyzer.SymbolInfo {
        return PSIAnalyzer.SymbolInfo(
            name = name,
            type = type,
            qualifiedName = null,
            filePath = filePath,
            lineNumber = 1,
            docComment = null,
            modifiers = emptyList()
        )
    }

    private fun String.isValidIdentifier(allowSpaces: Boolean = false): Boolean {
        if (isBlank()) return false
        if (this[0].isDigit()) return false
        val pattern = if (allowSpaces) {
            Regex("""^[\p{L}\p{N}_][\p{L}\p{N}\s_\-./:]*$""")
        } else {
            Regex("""^[\p{L}\p{N}_][\p{L}\p{N}\s_\-./:]*$""")
        }
        return pattern.matches(this)
    }
}
