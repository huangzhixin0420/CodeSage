package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.model.dto.Tool
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 测试运行工具 Handler
 */
object TestToolHandlers {
    private val logger = Logger.getLogger<TestToolHandlers>()

    fun createRunTestsHandler(project: Project?): ToolHandler = FunctionalToolHandler(runTestsTool()) { args ->
        val workingDir = args["working_dir"]?.jsonPrimitive?.content
            ?: project?.basePath
            ?: System.getProperty("user.dir")
        val testClass = args["test_class"]?.jsonPrimitive?.content
        val testMethod = args["test_method"]?.jsonPrimitive?.content
        val packagePath = args["package_path"]?.jsonPrimitive?.content

        // 自动检测构建系统
        val isMaven = File(workingDir, "pom.xml").exists()
        val isGradle = File(workingDir, "build.gradle").exists()
                || File(workingDir, "build.gradle.kts").exists()

        if (!isMaven && !isGradle) {
            return@FunctionalToolHandler ToolResult.Error("No supported build system found (Maven/Gradle) in $workingDir")
        }

        val cmd = if (isMaven) {
            val mvnCmd = mutableListOf("mvn", "-B", "test")
            when {
                testClass != null && testMethod != null -> {
                    mvnCmd.add("-Dtest=${testClass}#$testMethod")
                }

                testClass != null -> {
                    mvnCmd.add("-Dtest=$testClass")
                }

                packagePath != null -> {
                    mvnCmd.addAll(listOf("-Dtest=${packagePath}.*"))
                }
            }
            mvnCmd
        } else {
            val hasWrapper = File(workingDir, "gradlew").exists()
            val gradleCmd = mutableListOf(if (hasWrapper) "./gradlew" else "gradle", "test")
            when {
                testClass != null && testMethod != null -> {
                    gradleCmd.addAll(listOf("--tests", "${testClass}.${testMethod}"))
                }

                testClass != null -> {
                    gradleCmd.addAll(listOf("--tests", testClass))
                }

                packagePath != null -> {
                    gradleCmd.addAll(listOf("--tests", "${packagePath}.*"))
                }
            }
            gradleCmd
        }

        try {
            val process = ProcessBuilder(cmd)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(600, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@FunctionalToolHandler ToolResult.Error("Test execution timed out after 600s")
            }
            val exitCode = process.exitValue()

            // 解析测试结果摘要
            val summary = parseTestSummary(stdout, isMaven)

            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "build_system" to JsonPrimitive(if (isMaven) "maven" else "gradle"),
                        "exit_code" to JsonPrimitive(exitCode),
                        "stdout" to JsonPrimitive(stdout.take(50000)),
                        "stderr" to JsonPrimitive(stderr.take(10000)),
                        "summary" to summary,
                        "success" to JsonPrimitive(exitCode == 0)
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Test execution failed", e)
            ToolResult.Error("Test execution failed: ${e.message}")
        }
    }

    private fun parseTestSummary(output: String, isMaven: Boolean): JsonObject {
        return if (isMaven) {
            val testsRun = Regex("""Tests run: (\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            val failures = Regex("""Failures: (\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            val errors = Regex("""Errors: (\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            val skipped = Regex("""Skipped: (\d+)""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            JsonObject(
                mapOf(
                    "tests_run" to JsonPrimitive(testsRun ?: 0),
                    "failures" to JsonPrimitive(failures ?: 0),
                    "errors" to JsonPrimitive(errors ?: 0),
                    "skipped" to JsonPrimitive(skipped ?: 0)
                )
            )
        } else {
            // Gradle 格式解析
            val tests = Regex("""(\d+) tests completed""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            val failed = Regex("""(\d+) failed""").find(output)?.groupValues?.get(1)?.toIntOrNull()
            JsonObject(
                mapOf(
                    "tests_run" to JsonPrimitive(tests ?: 0),
                    "failures" to JsonPrimitive(failed ?: 0)
                )
            )
        }
    }
}
