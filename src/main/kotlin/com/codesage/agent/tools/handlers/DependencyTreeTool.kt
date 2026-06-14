package com.codesage.agent.tools.handlers

import com.codesage.agent.tools.ToolResult
import com.codesage.agent.tools.UnifiedTool
import com.codesage.model.dto.ToolCategory
import com.codesage.model.dto.ToolParameters
import com.codesage.model.dto.ToolProperty
import com.codesage.shared.serialization.JsonArgDecoders
import com.codesage.shared.utils.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 返回 Maven / Gradle 项目的结构化依赖树。
 *
 * - Maven：调用 `mvn dependency:tree -DoutputType=json -DoutputFile=... -Dscope=<scope>`，
 *   解析插件生成的 JSON（要求 maven-dependency-plugin >= 3.x）。
 * - Gradle：调用 `gradle dependencies --configuration <scope>`，解析文本树输出。
 *
 * 调用前会根据 `pom.xml` / `build.gradle[.kts]` 判断项目类型；两者都不存在时返回友好错误。
 *
 * @param project 当前 IntelliJ 项目，用于获取默认项目根目录；headless 场景可为 null。
 */
class DependencyTreeTool(private val project: Project?) : UnifiedTool(
    name = "dependency_tree",
    description = """
        Summary: Return a structured dependency tree for Maven or Gradle projects.
        Args: path (string, optional): project root directory, defaults to current project root; scope (string, optional): dependency scope such as compile, test, runtime, default compile; max_depth (int, optional): maximum tree depth to return, default unlimited.
        Do: Use to inspect direct and transitive dependencies. The tool first checks for pom.xml / build.gradle(.kts) to detect the build system.
        Don't: Don't call on projects without supported build files.
        Parallel: Yes, multiple independent projects can be analyzed in parallel.
        Cap: Large trees can be limited with max_depth; Gradle (*) / (c) markers are preserved in the markers field.
    """.trimIndent(),
    parameters = ToolParameters(
        type = "object",
        properties = mapOf(
            "path" to ToolProperty(
                type = "string",
                description = "Project root directory (default: current project root)"
            ),
            "scope" to ToolProperty(
                type = "string",
                description = "Dependency scope, e.g. compile, test, runtime (default: compile)"
            ),
            "max_depth" to ToolProperty(
                type = "integer",
                description = "Maximum tree depth to return; 0 or omitted means unlimited"
            )
        ),
        required = emptyList()
    )
) {
    override val tool = super.tool.copy(
        category = ToolCategory.BUILD,
        tags = setOf("dependencies", "maven", "gradle", "build")
    )

    private val logger = Logger.getLogger<DependencyTreeTool>()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = JsonArgDecoders.stringArgOrNull(args, "path")
            ?: project?.basePath
            ?: System.getProperty("user.dir")
        val scope = JsonArgDecoders.stringArg(args, "scope", default = "compile")
            .lowercase()
            .takeIf { it.isNotBlank() } ?: "compile"
        val maxDepth = JsonArgDecoders.intArgOrNull(args, "max_depth")?.takeIf { it > 0 }

        val dir = File(path)
        if (!dir.isDirectory) {
            return ToolResult.Error("Project path does not exist or is not a directory: $path")
        }

        val hasPom = File(dir, "pom.xml").exists()
        val hasGradle = File(dir, "build.gradle").exists() || File(dir, "build.gradle.kts").exists()

        return when {
            hasPom -> resolveMavenTree(dir, scope, maxDepth)
            hasGradle -> resolveGradleTree(dir, scope, maxDepth)
            else -> ToolResult.Error(
                "No supported build system found in $path. " +
                        "Expected one of: pom.xml, build.gradle, build.gradle.kts."
            )
        }
    }

    /**
     * 解析 Maven 依赖树。
     */
    private suspend fun resolveMavenTree(
        dir: File,
        scope: String,
        maxDepth: Int?
    ): ToolResult = withContext(Dispatchers.IO) {
        val outputFile = File(dir, MAVEN_OUTPUT_FILE)
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val cmd = BuildCommandResolver.mavenCommand(
            dir.absolutePath,
            listOf(
                "-B",
                "dependency:tree",
                "-DoutputType=json",
                "-DoutputFile=${outputFile.absolutePath}",
                "-Dscope=$scope"
            )
        )

        val result = runCommand(cmd, dir, timeoutSec = MAVEN_TIMEOUT_SEC)
        if (result.exitCode != 0) {
            return@withContext ToolResult.Error(
                "Maven dependency:tree failed (exit ${result.exitCode}): ${result.output.takeLast(2000)}"
            )
        }
        if (!outputFile.exists()) {
            return@withContext ToolResult.Error(
                "Maven did not produce the expected JSON output file: ${outputFile.absolutePath}"
            )
        }

        val jsonText = outputFile.readText()
        if (!jsonText.trimStart().startsWith("{")) {
            return@withContext ToolResult.Error(
                "Maven output is not JSON. Ensure maven-dependency-plugin >= 3.x supports -DoutputType=json."
            )
        }

        try {
            val root = json.parseToJsonElement(jsonText).jsonObject
            val rawDeps = root["children"]?.jsonArray
                ?.map { parseMavenNode(it.jsonObject) } ?: emptyList()
            val deps = applyMaxDepth(rawDeps, maxDepth)
            val counts = countNodes(deps)

            ToolResult.Success(
                buildJsonObject {
                    put("success", true)
                    put("build_system", "maven")
                    put("scope", scope)
                    put("dependencies", JsonArray(deps.map { it.toJsonObject() }))
                    put("total_top_level", counts.first)
                    put("total_transitive", counts.second)
                }
            )
        } catch (e: Exception) {
            logger.error("Failed to parse Maven dependency tree JSON", e)
            ToolResult.Error("Failed to parse Maven dependency tree JSON: ${e.message}")
        }
    }

    /**
     * 解析 Gradle 依赖树。
     */
    private suspend fun resolveGradleTree(
        dir: File,
        scope: String,
        maxDepth: Int?
    ): ToolResult = withContext(Dispatchers.IO) {
        val configuration = gradleConfiguration(scope)
        val cmd = BuildCommandResolver.gradleCommand(
            dir.absolutePath,
            listOf("dependencies", "--configuration", configuration)
        )

        val result = runCommand(cmd, dir, timeoutSec = GRADLE_TIMEOUT_SEC)
        if (result.exitCode != 0) {
            return@withContext ToolResult.Error(
                "Gradle dependencies task failed (exit ${result.exitCode}): ${result.output.takeLast(2000)}"
            )
        }

        try {
            val rawDeps = parseGradleOutput(result.output, scope)
            val deps = applyMaxDepth(rawDeps, maxDepth)
            val counts = countNodes(deps)

            ToolResult.Success(
                buildJsonObject {
                    put("success", true)
                    put("build_system", "gradle")
                    put("scope", scope)
                    put("configuration", configuration)
                    put("dependencies", JsonArray(deps.map { it.toJsonObject() }))
                    put("total_top_level", counts.first)
                    put("total_transitive", counts.second)
                }
            )
        } catch (e: Exception) {
            logger.error("Failed to parse Gradle dependencies output", e)
            ToolResult.Error("Failed to parse Gradle dependencies output: ${e.message}")
        }
    }

    /**
     * 将用户传入的 scope 映射到 Gradle 配置名。
     *
     * 已知 scope 做友好映射；未知 scope 直接透传，方便高级用户传入自定义配置名。
     */
    private fun gradleConfiguration(scope: String): String = when (scope) {
        "compile" -> "compileClasspath"
        "runtime" -> "runtimeClasspath"
        "test" -> "testCompileClasspath"
        "testruntime" -> "testRuntimeClasspath"
        "provided" -> "compileOnly"
        else -> scope
    }

    /**
     * 解析 Maven JSON 节点为内部树节点。
     */
    private fun parseMavenNode(obj: JsonObject): DepNode {
        val children = obj["children"]?.jsonArray
            ?.map { parseMavenNode(it.jsonObject) } ?: emptyList()
        return DepNode(
            groupId = obj["groupId"]?.jsonPrimitive?.contentOrNull ?: "",
            artifactId = obj["artifactId"]?.jsonPrimitive?.contentOrNull ?: "",
            version = obj["version"]?.jsonPrimitive?.contentOrNull ?: "",
            scope = obj["scope"]?.jsonPrimitive?.contentOrNull ?: "",
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "",
            classifier = obj["classifier"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            optional = obj["optional"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.toBooleanStrictOrNull(),
            children = children.toMutableList()
        )
    }

    /**
     * 解析 Gradle `dependencies` 文本输出为内部树节点。
     */
    private fun parseGradleOutput(output: String, scope: String): List<DepNode> {
        val lines = output.lines()
        val treeLines = mutableListOf<Pair<Int, String>>()
        var started = false

        for (raw in lines) {
            if (!started) {
                // Gradle 输出中树开始于 "<configuration> - <description>" 这一行
                if (raw.matches(GRADLE_CONFIG_LINE_REGEX)) {
                    started = true
                }
                continue
            }
            if (raw.isBlank()) break

            val markerPos = raw.indexOfFirst { it == '+' || it == '\\' }
            if (markerPos < 0) break
            val depth = markerPos / GRADLE_INDENT_SIZE
            val prefixEnd = raw.indexOf(' ', markerPos) + 1
            val text = raw.substring(prefixEnd).trimEnd()
            treeLines.add(depth to text)
        }

        val roots = mutableListOf<DepNode>()
        // 按深度保存当前路径上每个节点的 children 列表
        val stack = mutableListOf<MutableList<DepNode>>()

        for ((depth, text) in treeLines) {
            val node = parseGradleNode(text, scope)
            if (depth == 0) {
                roots.add(node)
                setStack(stack, 0, node.children)
            } else {
                ensureStackDepth(stack, depth)
                stack[depth - 1].add(node)
                setStack(stack, depth, node.children)
            }
        }

        return roots
    }

    /**
     * 从 Gradle 节点文本提取坐标与标记。
     */
    private fun parseGradleNode(text: String, scope: String): DepNode {
        val markers = mutableListOf<String>()
        var coordinate = text.trim()

        // 提取末尾的 (*) / (c) 标记
        while (true) {
            val match = GRADLE_MARKER_REGEX.find(coordinate)
            if (match == null || match.range.start != coordinate.length - match.value.length) break
            markers.add(match.groupValues[1])
            coordinate = coordinate.substring(0, match.range.start).trimEnd()
        }

        val parts = coordinate.split(":")
        return when (parts.size) {
            3 -> DepNode(
                groupId = parts[0],
                artifactId = parts[1],
                version = parts[2],
                scope = scope,
                markers = markers.joinToString(" ")
            )

            2 -> DepNode(
                groupId = parts[0],
                artifactId = parts[1],
                scope = scope,
                markers = markers.joinToString(" ")
            )

            else -> DepNode(
                artifactId = coordinate,
                scope = scope,
                markers = markers.joinToString(" ")
            )
        }
    }

    /**
     * 对树应用最大深度限制。
     *
     * @return 新的节点列表，超过 [maxDepth] 的层级会被截断（children 为空）。
     */
    private fun applyMaxDepth(nodes: List<DepNode>, maxDepth: Int?, currentDepth: Int = 1): List<DepNode> {
        if (maxDepth == null) return nodes
        return nodes.map { node ->
            node.copy(
                children = if (currentDepth < maxDepth) {
                    applyMaxDepth(node.children, maxDepth, currentDepth + 1).toMutableList()
                } else {
                    mutableListOf()
                }
            )
        }
    }

    /**
     * 统计可见节点数量。
     *
     * @return Pair(顶层依赖数, 传递依赖数)
     */
    private fun countNodes(nodes: List<DepNode>): Pair<Int, Int> {
        val topLevel = nodes.size
        val total = nodes.sumOf { countSubtree(it) }
        return topLevel to (total - topLevel)
    }

    private fun countSubtree(node: DepNode): Int =
        1 + node.children.sumOf { countSubtree(it) }

    /**
     * 执行外部命令并收集输出。
     */
    private suspend fun runCommand(
        cmd: List<String>,
        dir: File,
        timeoutSec: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext CommandResult(-1, output + "\nCommand timed out after ${timeoutSec}s")
            }
            CommandResult(process.exitValue(), output)
        } catch (e: Exception) {
            logger.error("Command execution failed: ${cmd.joinToString(" ")}", e)
            CommandResult(-1, "Command execution failed: ${e.message}")
        }
    }

    /**
     * 内部依赖树节点。
     */
    private data class DepNode(
        val groupId: String = "",
        val artifactId: String = "",
        val version: String = "",
        val scope: String = "",
        val type: String = "jar",
        val markers: String = "",
        val classifier: String? = null,
        val optional: Boolean? = null,
        val children: MutableList<DepNode> = mutableListOf()
    ) {
        /**
         * 转换为工具返回的 JSON 对象。
         */
        fun toJsonObject(): JsonObject = buildJsonObject {
            if (groupId.isNotBlank()) put("group_id", groupId)
            if (artifactId.isNotBlank()) put("artifact_id", artifactId)
            if (version.isNotBlank()) put("version", version)
            if (scope.isNotBlank()) put("scope", scope)
            if (type.isNotBlank()) put("type", type)
            if (markers.isNotBlank()) put("markers", markers)
            classifier?.let { put("classifier", it) }
            optional?.let { put("optional", it) }
            if (children.isNotEmpty()) put("children", JsonArray(children.map { it.toJsonObject() }))
        }
    }

    private fun setStack(stack: MutableList<MutableList<DepNode>>, depth: Int, children: MutableList<DepNode>) {
        ensureStackDepth(stack, depth + 1)
        stack[depth] = children
    }

    private fun ensureStackDepth(stack: MutableList<MutableList<DepNode>>, depth: Int) {
        while (stack.size < depth) {
            stack.add(mutableListOf())
        }
    }

    private companion object {
        const val MAVEN_OUTPUT_FILE = "target/codesage-dependency-tree.json"
        const val MAVEN_TIMEOUT_SEC = 300L
        const val GRADLE_TIMEOUT_SEC = 300L
        const val GRADLE_INDENT_SIZE = 5
        val GRADLE_CONFIG_LINE_REGEX = Regex("""^[\w]+Classpath\s+-\s+.*$""")
        val GRADLE_MARKER_REGEX = Regex("""\s*(\(\*\)|\(c\))$""")
    }
}

/**
 * 命令执行结果。
 */
private data class CommandResult(val exitCode: Int, val output: String)
