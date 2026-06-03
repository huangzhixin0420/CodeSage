package com.codesage.shared.config

import com.codesage.shared.utils.Logger
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.*

/**
 * Settings 仓库
 *
 * 职责:
 *   - settings.json 文件 IO(读 / 写 / 监听)
 *   - 原子写:tmp + rename
 *   - 损坏自动备份(settings.json.bak.<ts>)+ 回退到默认
 *   - 与 PluginConfig 双向桥接(写入即通知 IDE 旧配置读取方)
 *   - 文件监听(Java NIO WatchService, 跨平台)
 *
 * 文件位置:`~/.codesage/settings.json`
 * 备份:`~/.codesage/settings.json.bak.<timestamp>
 *
 * 线程模型:
 *   - 所有公共方法线程安全
 *   - 大文件 IO 在 IO 调度器上
 *   - 监听事件在独立线程
 */
@Service(Service.Level.APP)
class SettingsRepository {

    private val logger = Logger.getLogger<SettingsRepository>()

    private val _changes = MutableSharedFlow<SettingsFile>(replay = 0, extraBufferCapacity = 8)
    val changes: SharedFlow<SettingsFile> = _changes

    private val current = AtomicReference<SettingsFile>(DefaultSettings.create())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var settingsPath: Path? = null
    @Volatile
    private var watchService: WatchService? = null
    @Volatile
    private var watchJob: Job? = null

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { initializeFile() }
                .onFailure { logger.error("SettingsRepository init failed", it) }
        }
    }

    // ==================== Public API ====================

    /** 获取当前 settings(同步) */
    fun get(): SettingsFile = current.get()

    /** 异步刷新(从文件重新读) */
    fun reload(): SettingsFile {
        val path = settingsPath ?: resolvePath().also { settingsPath = it }
        return try {
            val file = path.toFile()
            if (!file.exists()) {
                val defaults = DefaultSettings.create()
                current.set(defaults)
                return defaults
            }
            val text = file.readText(Charsets.UTF_8)
            val parsed = DefaultSettings.JSON.decodeFromString(SettingsFile.serializer(), text)
            current.set(parsed)
            logger.info("Settings reloaded: ${parsed.providers.size} providers, ${parsed.mcp.servers.size} mcp")
            parsed
        } catch (e: SerializationException) {
            logger.warn("Settings parse failed, backing up and using defaults: ${e.message}")
            backupCorrupted()
            val defaults = DefaultSettings.create()
            current.set(defaults)
            defaults
        } catch (e: Exception) {
            logger.error("Settings reload failed", e)
            current.get()
        }
    }

    /**
     * 保存 settings(原子写)
     * @return true 成功
     */
    fun save(settings: SettingsFile): Boolean {
        return try {
            val path = settingsPath ?: resolvePath().also { settingsPath = it }
            path.parent?.createDirectories()
            val tmp = path.resolveSibling("${path.fileName}.tmp")
            val text = DefaultSettings.JSON.encodeToString(settings)
            tmp.writeText(text, Charsets.UTF_8)
            // 原子替换
            Files.move(
                tmp,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            current.set(settings)
            logger.info("Settings saved: ${settings.providers.size} providers")
            scope.launch { _changes.emit(settings) }
            true
        } catch (e: Exception) {
            logger.error("Settings save failed", e)
            // 尝试回退到非原子写
            saveNonAtomic(settings)
        }
    }

    /** 更新并保存(基于当前值) */
    fun update(transform: (SettingsFile) -> SettingsFile): Boolean {
        val updated = transform(current.get())
        return save(updated)
    }

    /** 启动文件监听(可重复调用,内部去重) */
    fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch { watchLoop() }
    }

    /** 停止监听 */
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    fun dispose() {
        stopWatching()
        watchService?.close()
        watchService = null
    }

    /** 获取 settings.json 路径(可能不存在) */
    fun getPath(): Path? = settingsPath

    /** 备份当前文件(手动) */
    fun backup(reason: String = "manual"): Path? {
        return try {
            val path = settingsPath ?: resolvePath().also { settingsPath = it }
            if (!path.exists()) return null
            val ts = System.currentTimeMillis()
            val bak = path.resolveSibling("${path.fileName}.bak.$ts")
            Files.copy(path, bak, StandardCopyOption.REPLACE_EXISTING)
            logger.info("Settings backed up: $bak (reason: $reason)")
            bak
        } catch (e: Exception) {
            logger.error("Backup failed", e)
            null
        }
    }

    // ==================== Internals ====================

    private fun resolvePath(): Path {
        val home = System.getProperty("user.home") ?: "."
        return Paths.get(home, ".codesage", "settings.json")
    }

    private fun initializeFile() {
        val path = resolvePath()
        settingsPath = path
        if (!path.exists()) {
            try {
                path.parent?.createDirectories()
                val defaults = DefaultSettings.create().copy(
                    providers = DefaultSettings.DEFAULT_PROVIDERS,
                )
                save(defaults)
                logger.info("Settings file initialized: $path")
            } catch (e: Exception) {
                logger.warn("Failed to create default settings.json: ${e.message}")
            }
        } else {
            // 文件存在,加载
            reload()
        }
        startWatching()
    }

    private fun saveNonAtomic(settings: SettingsFile): Boolean {
        return try {
            val path = settingsPath ?: return false
            path.parent?.createDirectories()
            val text = DefaultSettings.JSON.encodeToString(settings)
            path.writeText(text, Charsets.UTF_8)
            current.set(settings)
            scope.launch { _changes.emit(settings) }
            true
        } catch (e: Exception) {
            logger.error("Settings non-atomic save failed", e)
            false
        }
    }

    private fun backupCorrupted() {
        try {
            val path = settingsPath ?: return
            if (!path.exists()) return
            val ts = System.currentTimeMillis()
            val bak = path.resolveSibling("${path.fileName}.bak.$ts")
            Files.move(path, bak, StandardCopyOption.REPLACE_EXISTING)
            logger.warn("Corrupted settings moved to: $bak")
        } catch (e: Exception) {
            logger.error("Failed to backup corrupted settings", e)
        }
    }

    private suspend fun watchLoop() {
        val path = settingsPath ?: return
        val dir = path.parent ?: return

        val watchSvc = try {
            dir.fileSystem.newWatchService().also { watchService = it }
        } catch (e: Exception) {
            logger.warn("WatchService unavailable, file watching disabled: ${e.message}")
            return
        }
        try {
            dir.register(watchSvc, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            logger.info("Watching settings dir: $dir")
            while (currentCoroutineContext().isActive) {
                val key = withContext(Dispatchers.IO) { watchSvc.take() }
                val events = key.pollEvents()
                for (event in events) {
                    val kind = event.kind()
                    if (kind == OVERFLOW) continue
                    val filename = (event.context() as? Path)?.fileName ?: continue
                    if (filename != path.fileName) continue
                    if (kind == ENTRY_DELETE) {
                        logger.info("Settings file deleted externally, keeping in-memory state")
                        continue
                    }
                    // 短暂 debounce:文件保存时可能多次 modify
                    delay(150)
                    val newSettings = withContext(Dispatchers.IO) { reload() }
                    _changes.emit(newSettings)
                }
                if (!key.reset()) {
                    logger.warn("Watch key no longer valid")
                    break
                }
            }
        } catch (e: CancellationException) {
            // shutdown
        } catch (e: Exception) {
            logger.error("Watch loop error", e)
        } finally {
            runCatching { watchSvc.close() }
        }
    }

    companion object {
        fun getInstance(): SettingsRepository =
            ApplicationManager.getApplication().service<SettingsRepository>()
    }
}
