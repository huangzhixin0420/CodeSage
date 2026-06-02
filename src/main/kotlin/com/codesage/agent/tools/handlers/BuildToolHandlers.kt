package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.*
import com.codesage.model.dto.Tool
import com.codesage.shared.utils.Logger
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 构建工具 Handler：Maven / Gradle
 */
object BuildToolHandlers {
    private val logger = Logger.getLogger<BuildToolHandlers>()

    fun createMavenHandler(): ToolHandler = FunctionalToolHandler(mavenTool()) { args ->
        val workingDir = args["working_dir"]?.jsonPrimitive?.content
            ?: System.getProperty("user.dir")
        val goals = args["goals"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'goals' parameter")
        val profiles = args["profiles"]?.jsonPrimitive?.content
        val properties = args["properties"]?.jsonObject

        val pomFile = File(workingDir, "pom.xml")
        if (!pomFile.exists()) {
            return@FunctionalToolHandler ToolResult.Error("pom.xml not found in $workingDir")
        }

        val cmd = mutableListOf("mvn", "-B")
        profiles?.let { cmd.addAll(listOf("-P", it)) }
        properties?.forEach { (k, v) ->
            if (v is JsonPrimitive) {
                cmd.addAll(listOf("-D$k=${v.content}"))
            }
        }
        cmd.addAll(goals.split(" ").filter { it.isNotBlank() })

        executeBuildCommand(cmd, workingDir, "Maven")
    }

    fun createGradleHandler(): ToolHandler = FunctionalToolHandler(gradleTool()) { args ->
        val workingDir = args["working_dir"]?.jsonPrimitive?.content
            ?: System.getProperty("user.dir")
        val tasks = args["tasks"]?.jsonPrimitive?.content
            ?: return@FunctionalToolHandler ToolResult.Error("Missing 'tasks' parameter")
        val extraArgs = args["args"]?.jsonPrimitive?.content

        val hasWrapper = File(workingDir, "gradlew").exists()
        val hasBuildGradle = File(workingDir, "build.gradle").exists()
                || File(workingDir, "build.gradle.kts").exists()

        if (!hasBuildGradle) {
            return@FunctionalToolHandler ToolResult.Error("build.gradle / build.gradle.kts not found in $workingDir")
        }

        val cmd = mutableListOf(if (hasWrapper) "./gradlew" else "gradle")
        cmd.addAll(tasks.split(" ").filter { it.isNotBlank() })
        extraArgs?.let { cmd.addAll(it.split(" ").filter { a -> a.isNotBlank() }) }

        executeBuildCommand(cmd, workingDir, "Gradle")
    }

    private fun executeBuildCommand(cmd: List<String>, workingDir: String, toolName: String): ToolResult {
        return try {
            val process = ProcessBuilder(cmd)
                .directory(File(workingDir))
                .redirectErrorStream(false)
                .start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(300, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ToolResult.Error("$toolName command timed out after 300s")
            }
            val exitCode = process.exitValue()
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "tool" to JsonPrimitive(toolName.lowercase()),
                        "exit_code" to JsonPrimitive(exitCode),
                        "stdout" to JsonPrimitive(stdout.take(50000)),
                        "stderr" to JsonPrimitive(stderr.take(10000)),
                        "success" to JsonPrimitive(exitCode == 0)
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("$toolName execution failed", e)
            ToolResult.Error("$toolName execution failed: ${e.message}")
        }
    }
}
