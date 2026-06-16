package com.codesage.agent.tools.handlers

import com.codesage.agent.core.AgentStreamEvent
import com.codesage.agent.tools.*
import com.codesage.tools.guardrails.SensitiveActionPolicy
import com.codesage.model.dto.Tool
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 测试运行工具 Handler
 *
 * 6.8.1：新增流式输出能力；默认关闭时保持原有同步行为。
 */
object TestToolHandlers {
    private val logger = Logger.getLogger<TestToolHandlers>()

    fun createRunTestsHandler(project: Project?): ToolHandler =
        StreamingFunctionalToolHandler(
            tool = runTestsTool(),
            executor = { args -> executeRunTests(args, project) },
            executorStreaming = { args, onStream -> executeRunTestsStreaming(args, project, onStream) },
            riskLevel = SensitiveActionPolicy.RiskLevel.DANGEROUS
        )

    /**
     * 默认非流式路径：保持向后兼容。
     */
    private fun executeRunTests(args: JsonObject, project: Project?): ToolResult {
        val workingDir = args.workingDir(project)
        val (cmd, isMaven) = buildTestCommand(args, workingDir)
            ?: return ToolResult.Error("No supported build system found (Maven/Gradle) in $workingDir")

        return try {
            val process = ProcessBuilder(cmd)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(600, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ToolResult.Error("Test execution timed out after 600s")
            }
            val exitCode = process.exitValue()
            buildResultJson(workingDir, isMaven, exitCode, stdout, stderr)
        } catch (e: Exception) {
            logger.error("Test execution failed", e)
            ToolResult.Error("Test execution failed: ${e.message}")
        }
    }

    /**
     * 流式路径：实时 emit stdout/stderr，命令结束后解析 XML 并返回结构化结果。
     */
    private suspend fun executeRunTestsStreaming(
        args: JsonObject,
        project: Project?,
        onStream: suspend (AgentStreamEvent) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val workingDir = args.workingDir(project)
        val streamOutput = args["stream_output"]?.jsonPrimitive?.booleanOrNull ?: false
        val (cmd, isMaven) = buildTestCommand(args, workingDir)
            ?: return@withContext ToolResult.Error("No supported build system found (Maven/Gradle) in $workingDir")

        try {
            val process = ProcessBuilder(cmd)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()

            val stdoutChannel = Channel<String>(Channel.UNLIMITED)
            val stderrChannel = Channel<String>(Channel.UNLIMITED)
            val stdoutCollector = StringBuilder()
            val stderrCollector = StringBuilder()

            val stdoutJob = launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val chunk = line + "\n"
                            stdoutCollector.append(chunk)
                            stdoutChannel.trySend(chunk)
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    logger.warn("Test stdout reader failed: ${e.message}")
                } finally {
                    stdoutChannel.close()
                }
            }

            @Suppress("ReplaceJavaStaticMethodWithKotlinAnalog")
            val stderrJob = launch(Dispatchers.IO) {
                try {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val chunk = line + "\n"
                            stderrCollector.append(chunk)
                            stderrChannel.trySend(chunk)
                        }
                    }
                } catch (_: InterruptedException) {
                } catch (e: Exception) {
                    logger.warn("Test stderr reader failed: ${e.message}")
                } finally {
                    stderrChannel.close()
                }
            }

            val emitterJob = launch {
                var stdoutClosed = false
                var stderrClosed = false
                var batchStdout = StringBuilder()
                var batchStderr = StringBuilder()

                suspend fun flushBatch() {
                    if (!streamOutput) {
                        batchStdout.clear()
                        batchStderr.clear()
                        return
                    }
                    if (batchStdout.isNotEmpty() || batchStderr.isNotEmpty()) {
                        onStream(
                            AgentStreamEvent.CommandOutputStream(
                                stdout = batchStdout.toString(),
                                stderr = batchStderr.toString()
                            )
                        )
                        batchStdout = StringBuilder()
                        batchStderr = StringBuilder()
                    }
                }

                while (!stdoutClosed || !stderrClosed) {
                    val stdoutChunk = if (!stdoutClosed) stdoutChannel.tryReceive().getOrNull() else null
                    val stderrChunk = if (!stderrClosed) stderrChannel.tryReceive().getOrNull() else null

                    if (stdoutChunk == null && !stdoutClosed && stdoutChannel.isClosedForReceive) {
                        stdoutClosed = true
                    } else if (stdoutChunk != null) {
                        batchStdout.append(stdoutChunk)
                    }

                    if (stderrChunk == null && !stderrClosed && stderrChannel.isClosedForReceive) {
                        stderrClosed = true
                    } else if (stderrChunk != null) {
                        batchStderr.append(stderrChunk)
                    }

                    if (stdoutClosed || stderrClosed || batchStdout.length + batchStderr.length >= 1024) {
                        flushBatch()
                    }

                    if ((!stdoutClosed || !stderrClosed) && stdoutChunk == null && stderrChunk == null) {
                        delay(16)
                    }
                }
                flushBatch()
            }

            val finished = runInterruptible(Dispatchers.IO) {
                process.waitFor(600, TimeUnit.SECONDS)
            }

            stdoutJob.cancel()
            stderrJob.cancel()
            emitterJob.join()

            if (!finished) {
                runInterruptible(Dispatchers.IO) { process.destroyForcibly() }
                if (streamOutput) {
                    onStream(
                        AgentStreamEvent.CommandOutputStream(
                            stderr = "Test execution timed out after 600s",
                            done = true
                        )
                    )
                }
                return@withContext ToolResult.Error("Test execution timed out after 600s")
            }

            val exitCode = runInterruptible(Dispatchers.IO) { process.exitValue() }

            if (streamOutput) {
                onStream(
                    AgentStreamEvent.CommandOutputStream(
                        stdout = "",
                        stderr = "",
                        exitCode = exitCode,
                        done = true
                    )
                )
            }

            val result = buildResultJson(
                workingDir = workingDir,
                isMaven = isMaven,
                exitCode = exitCode,
                stdout = stdoutCollector.toString(),
                stderr = stderrCollector.toString()
            )

            // 仅在真正流式输出时标记 streamed=true
            val enriched = if (result is ToolResult.Success && streamOutput) {
                val obj = result.data.jsonObject
                val updated = JsonObject(obj.toMutableMap().apply { put("streamed", JsonPrimitive(true)) })
                ToolResult.Success(updated)
            } else result

            enriched
        } catch (e: Exception) {
            logger.error("Streaming test execution failed", e)
            if (streamOutput) {
                onStream(
                    AgentStreamEvent.CommandOutputStream(
                        stderr = "Test execution failed: ${e.message}",
                        done = true
                    )
                )
            }
            ToolResult.Error("Test execution failed: ${e.message}")
        }
    }

    /**
     * 构造 Maven/Gradle 测试命令，并返回构建系统标识。
     */
    private fun buildTestCommand(args: JsonObject, workingDir: String): Pair<List<String>, Boolean>? {
        val isMaven = File(workingDir, "pom.xml").exists()
        val isGradle = File(workingDir, "build.gradle").exists()
                || File(workingDir, "build.gradle.kts").exists()

        if (!isMaven && !isGradle) return null

        val testClass = args["test_class"]?.jsonPrimitive?.content
        val testMethod = args["test_method"]?.jsonPrimitive?.content
        val packagePath = args["package_path"]?.jsonPrimitive?.content

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
        return cmd to isMaven
    }

    private fun JsonObject.workingDir(project: Project?): String {
        return this["working_dir"]?.jsonPrimitive?.content
            ?: project?.basePath
            ?: System.getProperty("user.dir")
    }

    /**
     * 统一构造最终返回结果：解析 XML、摘要、失败定位。
     */
    private fun buildResultJson(
        workingDir: String,
        isMaven: Boolean,
        exitCode: Int,
        stdout: String,
        stderr: String
    ): ToolResult {
        val buildSystem = if (isMaven) "maven" else "gradle"
        val reports = TestResultParser.parseReports(workingDir, buildSystem)
        val summary = if (reports.testsRun > 0) {
            JsonObject(
                mapOf(
                    "tests_run" to JsonPrimitive(reports.testsRun),
                    "passed" to JsonPrimitive(reports.passed),
                    "failures" to JsonPrimitive(reports.failures),
                    "errors" to JsonPrimitive(reports.errors),
                    "skipped" to JsonPrimitive(reports.skipped)
                )
            )
        } else {
            parseTestSummary(stdout, isMaven)
        }

        val failureLocations = TestFailureLocator.locateFailures(workingDir, reports.tests)

        return ToolResult.Success(
            JsonObject(
                mapOf(
                    "build_system" to JsonPrimitive(buildSystem),
                    "exit_code" to JsonPrimitive(exitCode),
                    "stdout" to JsonPrimitive(stdout.take(50000)),
                    "stderr" to JsonPrimitive(stderr.take(10000)),
                    "summary" to summary,
                    "tests" to JsonArray(reports.tests),
                    "failure_locations" to JsonArray(failureLocations),
                    "success" to JsonPrimitive(exitCode == 0)
                )
            )
        )
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
