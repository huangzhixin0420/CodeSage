package com.codesage.agent.tools.handlers

import kotlinx.serialization.json.*
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * 6.8.2 run_linter 结构化问题解析器
 *
 * 扫描 Checkstyle XML、ESLint JSON、flake8 JSON 报告，输出统一 issues[] 列表。
 */
object LintResultParser {

    data class LintIssue(
        val file: String,
        val line: Int,
        val column: Int?,
        val severity: String,
        val message: String,
        val rule: String?
    )

    private val xmlFactory = XMLInputFactory.newInstance().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    /**
     * 扫描工作目录下已知的 linter 报告文件。
     *
     * @return Pair(sourceDescription, issues)
     */
    fun parseReports(workingDir: String): Pair<String, List<LintIssue>> {
        val dir = File(workingDir)

        // Checkstyle (Maven / Gradle)
        checkstyleFiles(dir).firstOrNull()?.let { file ->
            return "checkstyle:${file.name}" to parseCheckstyleXml(file)
        }

        // ESLint JSON
        eslintFiles(dir).firstOrNull()?.let { file ->
            return "eslint:${file.name}" to parseEslintJson(file)
        }

        // flake8 JSON
        flake8Files(dir).firstOrNull()?.let { file ->
            return "flake8:${file.name}" to parseFlake8Json(file)
        }

        return "none" to emptyList()
    }

    private fun checkstyleFiles(dir: File): List<File> {
        val candidates = listOf(
            dir.resolve("target").resolve("checkstyle-result.xml"),
            dir.resolve("build").resolve("reports").resolve("checkstyle")
        )
        return candidates.flatMap { candidate ->
            when {
                candidate.isFile -> listOf(candidate)
                candidate.isDirectory -> candidate.listFiles { f -> f.isFile && f.extension == "xml" }?.toList()
                    ?: emptyList()

                else -> emptyList()
            }
        }
    }

    private fun eslintFiles(dir: File): List<File> {
        val names = listOf("eslint-report.json", "report.json", "eslint_report.json")
        return names.map { dir.resolve(it) }.filter { it.isFile }
    }

    private fun flake8Files(dir: File): List<File> {
        val names = listOf("flake8-report.json", "flake8_report.json")
        return names.map { dir.resolve(it) }.filter { it.isFile }
    }

    private fun parseCheckstyleXml(file: File): List<LintIssue> {
        val issues = mutableListOf<LintIssue>()
        file.inputStream().use { stream ->
            val reader = xmlFactory.createXMLStreamReader(stream)
            var currentFile = ""
            try {
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            when (reader.localName) {
                                "file" -> currentFile = reader.getAttributeValue(null, "name") ?: ""
                                "error" -> {
                                    issues.add(
                                        LintIssue(
                                            file = currentFile,
                                            line = reader.getAttributeValue(null, "line")?.toIntOrNull() ?: 0,
                                            column = reader.getAttributeValue(null, "column")?.toIntOrNull(),
                                            severity = reader.getAttributeValue(null, "severity") ?: "error",
                                            message = reader.getAttributeValue(null, "message") ?: "",
                                            rule = reader.getAttributeValue(null, "source")
                                        )
                                    )
                                }
                            }
                        }

                        else -> { /* ignore */
                        }
                    }
                }
            } finally {
                reader.close()
            }
        }
        return issues
    }

    private fun parseEslintJson(file: File): List<LintIssue> {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(file.readText())
        if (root !is JsonArray) return emptyList()

        return root.flatMap { fileNode ->
            val filePath = fileNode.jsonObject["filePath"]?.jsonPrimitive?.content ?: ""
            val messages = fileNode.jsonObject["messages"]?.jsonArray ?: return@flatMap emptyList()
            messages.map { msg ->
                val obj = msg.jsonObject
                val severityNum = obj["severity"]?.jsonPrimitive?.intOrNull ?: 1
                LintIssue(
                    file = filePath,
                    line = obj["line"]?.jsonPrimitive?.intOrNull ?: 0,
                    column = obj["column"]?.jsonPrimitive?.intOrNull,
                    severity = if (severityNum >= 2) "error" else "warning",
                    message = obj["message"]?.jsonPrimitive?.content ?: "",
                    rule = obj["ruleId"]?.jsonPrimitive?.content
                )
            }
        }
    }

    private fun parseFlake8Json(file: File): List<LintIssue> {
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(file.readText())
        if (root !is JsonArray) return emptyList()

        return root.map { item ->
            val obj = item.jsonObject
            LintIssue(
                file = obj["filename"]?.jsonPrimitive?.content ?: "",
                line = obj["line_number"]?.jsonPrimitive?.intOrNull ?: 0,
                column = obj["column_number"]?.jsonPrimitive?.intOrNull,
                severity = "error",
                message = obj["text"]?.jsonPrimitive?.content ?: "",
                rule = obj["code"]?.jsonPrimitive?.content
            )
        }
    }
}
