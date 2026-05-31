package com.codesage.skill.discovery

import com.codesage.skill.*
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 声明式技能实现
 * 从 YAML/JSON 配置创建的技能，通过外部命令/脚本/HTTP 执行
 */
class DeclarativeSkill(
    override val id: String,
    override val name: String,
    override val description: String,
    override val version: String,
    override val category: SkillCategory,
    override val tags: Set<String>,
    override val inputSchema: Map<String, Any>,
    override val outputSchema: Map<String, Any>,
    private val implementation: SkillImplementationType
) : Skill {

    private val logger = Logger.getLogger<DeclarativeSkill>()

    override fun canExecute(context: ExecutionContext): CanExecuteResult {
        return when (implementation) {
            is SkillImplementationType.BuiltIn ->
                CanExecuteResult(false, "Built-in implementation should not be used in declarative skill")

            is SkillImplementationType.External -> {
                when (implementation.type) {
                    "command" -> {
                        if (implementation.command.isNullOrBlank()) {
                            CanExecuteResult(false, "External command not specified")
                        } else {
                            CanExecuteResult(true)
                        }
                    }

                    "http" -> {
                        if (implementation.url.isNullOrBlank()) {
                            CanExecuteResult(false, "External URL not specified")
                        } else {
                            CanExecuteResult(true)
                        }
                    }

                    "script" -> {
                        if (implementation.script.isNullOrBlank()) {
                            CanExecuteResult(false, "External script not specified")
                        } else {
                            CanExecuteResult(true)
                        }
                    }

                    else -> CanExecuteResult(false, "Unknown implementation type: ${implementation.type}")
                }
            }
        }
    }

    override suspend fun execute(input: SkillInput, context: ExecutionContext): SkillResult {
        return when (implementation) {
            is SkillImplementationType.BuiltIn ->
                SkillResult.Failure("Built-in implementation not supported in declarative skill")

            is SkillImplementationType.External -> executeExternal(implementation, input, context)
        }
    }

    private suspend fun executeExternal(
        impl: SkillImplementationType.External,
        input: SkillInput,
        context: ExecutionContext
    ): SkillResult = withContext(Dispatchers.IO) {
        try {
            when (impl.type) {
                "command" -> executeCommand(impl.command!!, input, context)
                "http" -> executeHttp(impl.url!!, input)
                "script" -> executeScript(impl.script!!, input, context)
                else -> SkillResult.Failure("Unknown implementation type: ${impl.type}")
            }
        } catch (e: Exception) {
            logger.error("Declarative skill execution failed: $id", e)
            SkillResult.Failure("Execution failed: ${e.message}", e)
        }
    }

    private fun executeCommand(command: String, input: SkillInput, context: ExecutionContext): SkillResult {
        val projectPath = context.projectPath
            ?: return SkillResult.Failure("No project context available")
        val workingDir = File(projectPath)

        // Build command with arguments using list to avoid shell injection
        val commandList = mutableListOf(command)
        input.arguments.entries.forEach { (k, v) ->
            commandList.add("--$k")
            commandList.add(v.toString())
        }

        val process = ProcessBuilder(commandList)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        return if (exitCode == 0) {
            SkillResult.Success(mapOf("output" to output, "exitCode" to exitCode))
        } else {
            SkillResult.Failure("Command failed with exit code $exitCode: $output")
        }
    }

    private fun executeHttp(url: String, input: SkillInput): SkillResult {
        // 简化实现：将输入序列化为 JSON 后通过 HTTP POST 发送
        // 实际生产环境需要更完善的 HTTP 客户端处理
        return SkillResult.Failure("HTTP implementation is a placeholder")
    }

    private fun executeScript(script: String, input: SkillInput, context: ExecutionContext): SkillResult {
        // 简化实现：将脚本写入临时文件后执行
        val tempFile = File.createTempFile("codesage_skill_", ".sh")
        tempFile.writeText(script)
        tempFile.setExecutable(true)

        try {
            val process = ProcessBuilder(tempFile.absolutePath)
                .directory(context.projectPath?.let { File(it) } ?: File("."))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            return if (exitCode == 0) {
                SkillResult.Success(mapOf("output" to output, "exitCode" to exitCode))
            } else {
                SkillResult.Failure("Script failed with exit code $exitCode: $output")
            }
        } finally {
            tempFile.delete()
        }
    }
}
