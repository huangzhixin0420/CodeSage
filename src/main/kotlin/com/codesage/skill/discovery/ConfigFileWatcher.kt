package com.codesage.skill.discovery

import com.codesage.skill.*
import com.codesage.skill.registry.SkillRegistry
import com.codesage.rule.Rule
import com.codesage.rule.parser.RuleParser
import com.codesage.shared.utils.Logger
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/**
 * 配置文件监视器
 * 监视技能和规则配置文件的变更，自动热更新
 */
class ConfigFileWatcher(
    private val skillRegistry: SkillRegistry,
    private val ruleParser: RuleParser = RuleParser(),
    private val watchPaths: List<Path> = listOf()
) {
    private val logger = Logger.getLogger<ConfigFileWatcher>()
    private var watcher: WatchService? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val skillFiles = mutableSetOf<Path>()
    private val ruleFiles = mutableSetOf<Path>()

    // 变更事件
    sealed class FileChangeEvent {
        data class Created(val path: Path) : FileChangeEvent()
        data class Modified(val path: Path) : FileChangeEvent()
        data class Deleted(val path: Path) : FileChangeEvent()
    }

    /**
     * 添加监视目录
     */
    fun addWatchPath(path: Path) {
        if (!Files.exists(path)) {
            logger.warn("Watch path does not exist: $path")
            return
        }

        scanDirectory(path)
    }

    /**
     * 扫描目录并注册文件
     */
    private fun scanDirectory(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                when {
                    file.toString().endsWith(".yaml") || file.toString().endsWith(".yml") -> {
                        when {
                            file.toString().contains("skill") -> skillFiles.add(file)
                            file.toString().contains("rule") -> ruleFiles.add(file)
                            else -> {
                                // 默认作为技能文件
                                skillFiles.add(file)
                            }
                        }
                    }

                    else -> {
                        // 忽略非YAML文件
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })

        logger.info("Scanned ${skillFiles.size} skill files, ${ruleFiles.size} rule files")
    }

    /**
     * 启动文件监视
     */
    fun start() {
        if (watcher != null) {
            logger.warn("Watcher already started")
            return
        }

        watcher = FileSystems.getDefault().newWatchService()

        // 为所有监视路径注册（包括CREATE、DELETE和MODIFY）
        val watchedDirs = mutableSetOf<Path>()
        (skillFiles + ruleFiles).forEach { file ->
            val parent = file.parent
            if (parent != null && watchedDirs.add(parent)) {
                try {
                    parent.register(
                        watcher,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to register watch: $parent", e)
                }
            }
        }

        scope.launch {
            watchLoop()
        }

        logger.info("ConfigFileWatcher started")
    }

    /**
     * 停止文件监视
     */
    fun stop() {
        runBlocking {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
        watcher?.close()
        watcher = null
        logger.info("ConfigFileWatcher stopped")
    }

    /**
     * 监视循环
     */
    private suspend fun watchLoop() {
        val watchService = watcher ?: return

        while (currentCoroutineContext().isActive) {
            try {
                val key = watchService.take()

                for (event in key.pollEvents()) {
                    val eventKind = event.kind()
                    val filePath = event.context() as? Path ?: continue

                    val fullPath = key.watchable() as? Path ?: continue
                    val absolutePath = fullPath.resolve(filePath)

                    when (eventKind) {
                        StandardWatchEventKinds.ENTRY_MODIFY -> {
                            handleFileChange(FileChangeEvent.Modified(absolutePath))
                        }

                        StandardWatchEventKinds.ENTRY_CREATE -> {
                            handleFileChange(FileChangeEvent.Created(absolutePath))
                        }

                        StandardWatchEventKinds.ENTRY_DELETE -> {
                            handleFileChange(FileChangeEvent.Deleted(absolutePath))
                        }
                    }
                }

                key.reset()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                logger.error("Watch loop error", e)
            }
        }
    }

    /**
     * 处理文件变更
     */
    private suspend fun handleFileChange(event: FileChangeEvent) {
        when (event) {
            is FileChangeEvent.Modified -> handleModification(event.path)
            is FileChangeEvent.Created -> handleCreation(event.path)
            is FileChangeEvent.Deleted -> handleDeletion(event.path)
        }
    }

    private suspend fun handleModification(path: Path) {
        logger.info("File modified: $path")

        when {
            path in skillFiles -> reloadSkills(path)
            path in ruleFiles -> reloadRules(path)
        }
    }

    private fun handleCreation(path: Path) {
        logger.info("File created: $path")

        val ext = path.toString()
        when {
            ext.endsWith(".yaml") || ext.endsWith(".yml") -> {
                if (ext.contains("skill")) {
                    skillFiles.add(path)
                } else if (ext.contains("rule")) {
                    ruleFiles.add(path)
                }
            }
        }
    }

    private fun handleDeletion(path: Path) {
        logger.info("File deleted: $path")
        skillFiles.remove(path)
        ruleFiles.remove(path)
    }

    /**
     * 重新加载技能文件
     */
    private suspend fun reloadSkills(path: Path) {
        try {
            // 解析新的技能定义
            val content = Files.readString(path)
            val skills = parseSkillsFromYaml(content)

            // 更新注册中心
            skills.forEach { skill ->
                skillRegistry.hotReload(skill)
            }

            logger.info("Reloaded ${skills.size} skills from $path")
        } catch (e: Exception) {
            logger.error("Failed to reload skills from $path", e)
        }
    }

    /**
     * 重新加载规则文件
     */
    private suspend fun reloadRules(path: Path) {
        // 规则重新加载由RuleEngine处理
        logger.info("Rule file modified, engine will handle reload: $path")
    }

    /**
     * 从YAML解析技能定义
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseSkillsFromYaml(content: String): List<Skill> {
        return try {
            val yaml = org.yaml.snakeyaml.Yaml()
            val data = yaml.load<Map<String, Any>>(content)
            val skillsList = data["skills"] as? List<Map<String, Any>> ?: return emptyList()

            skillsList.mapNotNull { skillMap ->
                try {
                    parseSkillDefinition(skillMap)
                } catch (e: Exception) {
                    logger.warn("Failed to parse skill definition: $skillMap", e)
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to parse skills YAML", e)
            emptyList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSkillDefinition(map: Map<String, Any>): Skill? {
        val id = map["id"] as? String ?: return null
        val name = map["name"] as? String ?: return null
        val description = map["description"] as? String ?: ""
        val version = map["version"] as? String ?: "1.0.0"
        val categoryStr = map["category"] as? String ?: "CUSTOM"
        val category = try {
            SkillCategory.valueOf(categoryStr.uppercase())
        } catch (e: IllegalArgumentException) {
            SkillCategory.CUSTOM
        }
        val tags = (map["tags"] as? List<String>)?.toSet() ?: emptySet()
        val inputSchema = (map["inputSchema"] as? Map<String, Any>) ?: emptyMap()
        val outputSchema = (map["outputSchema"] as? Map<String, Any>) ?: emptyMap()

        val implMap = map["implementation"] as? Map<String, String>
        val implementation = if (implMap != null) {
            SkillImplementationType.External(
                type = implMap["type"] ?: "command",
                command = implMap["command"],
                url = implMap["url"],
                script = implMap["script"]
            )
        } else {
            SkillImplementationType.BuiltIn("")
        }

        return DeclarativeSkill(
            id = id,
            name = name,
            description = description,
            version = version,
            category = category,
            tags = tags,
            inputSchema = inputSchema,
            outputSchema = outputSchema,
            implementation = implementation
        )
    }
}

/**
 * MCP服务热更新器
 * 定期检查MCP服务的工具更新
 */
class MCPServiceReloader(
    private val mcpServerManager: com.codesage.mcp.server.MCPServerManager
) {
    private val logger = Logger.getLogger<MCPServiceReloader>()
    private var scope: CoroutineScope? = null

    private val checkIntervalMs: Long = 60000  // 1分钟检查一次

    /**
     * 启动热更新检查
     */
    fun start() {
        if (scope != null) {
            logger.warn("MCPServiceReloader already started")
            return
        }

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope?.launch {
            while (currentCoroutineContext().isActive) {
                delay(checkIntervalMs)
                checkForUpdates()
            }
        }

        logger.info("MCPServiceReloader started")
    }

    /**
     * 停止热更新检查
     */
    fun stop() {
        scope?.cancel()
        scope = null
        logger.info("MCPServiceReloader stopped")
    }

    /**
     * 检查更新
     */
    private suspend fun checkForUpdates() {
        try {
            val statuses = mcpServerManager.getAllServerStatuses()

            for ((serverId, status) in statuses) {
                when (status) {
                    com.codesage.mcp.transport.MCPServerStatus.CONNECTED -> {
                        val tools = mcpServerManager.listTools(serverId)
                        logger.debug("Server $serverId has ${tools.size} tools")
                    }

                    com.codesage.mcp.transport.MCPServerStatus.DISCONNECTED -> {
                        // 尝试重连
                        logger.info("Server $serverId disconnected, will attempt reconnect")
                    }

                    else -> { /* 处理其他状态 */
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error checking for MCP updates", e)
        }
    }
}

/**
 * 配置状态 (用于持久化)
 */
