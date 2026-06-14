package com.codesage.agent.tools.handlers

import kotlinx.serialization.json.*
import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * 6.8.1 run_tests 结构化结果解析器
 *
 * 扫描 Gradle / Maven 生成的 JUnit XML 报告，输出统一的测试用例列表与汇总。
 * 当未找到 XML 报告时，返回空列表，调用方仍可用 stdout 摘要兜底。
 */
object TestResultParser {

    data class ParsedTestResult(
        val testsRun: Int,
        val passed: Int,
        val failures: Int,
        val errors: Int,
        val skipped: Int,
        val tests: List<JsonObject>
    )

    private val xmlFactory = XMLInputFactory.newInstance().apply {
        // 禁用 DTD/外部实体，防止 XXE
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    }

    /**
     * 根据构建系统扫描测试报告目录。
     *
     * @param workingDir 项目根目录
     * @param buildSystem "maven" 或 "gradle"
     * @param maxResults  返回的最大用例数，默认 500，防止报告过大撑爆上下文
     */
    fun parseReports(
        workingDir: String,
        buildSystem: String,
        maxResults: Int = 500
    ): ParsedTestResult {
        val reportDirs = when (buildSystem.lowercase()) {
            "maven" -> listOf(
                File(workingDir, "target").resolve("surefire-reports"),
                File(workingDir, "target").resolve("failsafe-reports")
            )

            else -> listOf(
                File(workingDir, "build").resolve("test-results").resolve("test")
            )
        }

        val xmlFiles = reportDirs
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir -> dir.listFiles { f -> f.isFile && f.extension == "xml" }?.toList() ?: emptyList() }

        if (xmlFiles.isEmpty()) {
            return ParsedTestResult(0, 0, 0, 0, 0, emptyList())
        }

        val tests = mutableListOf<JsonObject>()
        var testsRun = 0
        var failures = 0
        var errors = 0
        var skipped = 0

        xmlFiles.forEach { file ->
            parseSuite(file) { testcase, status, message, details ->
                testsRun++
                when (status) {
                    "passed" -> { /* nothing */
                    }

                    "failure" -> failures++
                    "error" -> errors++
                    "skipped" -> skipped++
                }
                if (tests.size < maxResults) {
                    tests.add(
                        JsonObject(
                            mapOf(
                                "classname" to JsonPrimitive(testcase.className),
                                "name" to JsonPrimitive(testcase.testName),
                                "status" to JsonPrimitive(status),
                                "time" to JsonPrimitive(testcase.time),
                                "message" to JsonPrimitive(message),
                                "details" to JsonPrimitive(details)
                            )
                        )
                    )
                }
            }
        }

        return ParsedTestResult(
            testsRun = testsRun,
            passed = testsRun - failures - errors - skipped,
            failures = failures,
            errors = errors,
            skipped = skipped,
            tests = tests
        )
    }

    private data class Testcase(
        val className: String,
        val testName: String,
        val time: Double
    )

    private fun parseSuite(
        file: File,
        onTestcase: (Testcase, status: String, message: String, details: String) -> Unit
    ) {
        file.inputStream().use { stream ->
            val reader = xmlFactory.createXMLStreamReader(stream)
            var currentClass = ""
            var currentName = ""
            var currentTime = 0.0
            var status = "passed"
            var message = ""
            val details = StringBuilder()

            try {
                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> {
                            when (reader.localName) {
                                "testcase" -> {
                                    currentClass = reader.getAttributeValue(null, "classname") ?: ""
                                    currentName = reader.getAttributeValue(null, "name") ?: ""
                                    currentTime = reader.getAttributeValue(null, "time")?.toDoubleOrNull() ?: 0.0
                                    status = "passed"
                                    message = ""
                                    details.clear()
                                }

                                "failure" -> {
                                    status = "failure"
                                    message = reader.getAttributeValue(null, "message") ?: ""
                                }

                                "error" -> {
                                    status = "error"
                                    message = reader.getAttributeValue(null, "message") ?: ""
                                }

                                "skipped" -> {
                                    status = "skipped"
                                    message = reader.getAttributeValue(null, "message") ?: ""
                                }
                            }
                        }

                        XMLStreamConstants.CHARACTERS -> {
                            if (status == "failure" || status == "error" || status == "skipped") {
                                details.append(reader.text)
                            }
                        }

                        XMLStreamConstants.END_ELEMENT -> {
                            when (reader.localName) {
                                "testcase" -> {
                                    onTestcase(
                                        Testcase(currentClass, currentName, currentTime),
                                        status,
                                        message,
                                        details.toString().trim()
                                    )
                                }

                                "failure", "error", "skipped" -> { /* details already collected */
                                }
                            }
                        }
                    }
                }
            } finally {
                reader.close()
            }
        }
    }
}
