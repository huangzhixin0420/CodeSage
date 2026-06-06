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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Volatile
    private var disposed = false

    private val initFuture = CompletableFuture<Unit>()
    private val watchingStarted = AtomicBoolean(false)

    init {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                initializeFile()
            } catch (e: Exception) {
                logger.error("SettingsRepository init failed", e)
            } finally {
                initFuture.complete(Unit)
            }
        }
    }

    // ==================== Public API ====================

    /** 获取当前 settings(同步) */
    fun get(): SettingsFile = current.get()

    /** 异步刷新(从文件重新读) */
    fun reload(): SettingsFile {
        awaitInit()
        val path = settingsPath ?: resolvePath().also { settingsPath = it }
        return try {
            val file = path.toFile()
            if (!file.exists()) {
                val defaults = DefaultSettings.create()
                current.set(defaults)
                return defaults
            }
            // OOM 防护: 文件超过 1 MB 时不再尝试读。
            // 注意:这里必须用 logger.warn,不能用 logger.error —— IntelliJ Platform 的
            // Logger.error(String) 重载会直接抛 Throwable 把日志调用本身变成抛异常,
            // 污染 IDE 日志并打断调用方。生产代码报“可恢复”问题用 warn / info 即可。
            // 顺便自动备份旧文件 + 回退默认,不让用户卡在“文件太大读不了”的状态。
            val fileSize = file.length()
            if (fileSize > 1_000_000) {
                logger.warn(
                    "Settings file too large ($fileSize bytes at $path) — backing up and falling back to defaults to prevent OOM"
                )
                backupCorrupted() // 复用既有的 .bak.<ts> rename 逻辑
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
        awaitInit()
        if (disposed) {
            logger.warn("SettingsRepository already disposed, save ignored")
            return false
        }
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
            if (scope.isActive) {
                scope.launch { _changes.emit(settings) }
            }
            true
        } catch (e: Exception) {
            logger.error("Settings save failed", e)
            // 尝试回退到非原子写
            saveNonAtomic(settings)
        }
    }

    /** 更新并保存(基于当前值) — CAS 循环保证原子性
     *
     * C8 修复：
     * 1. 加 [maxAttempts] 上限防止 transform 持续抛异常时死循环
     * 2. transform 抛异常时不修改 current 状态
     * 3. save 失败时回滚 current 到 old，保持内存与磁盘一致
     */
    fun update(
        transform: (SettingsFile) -> SettingsFile,
        maxAttempts: Int = 10
    ): Boolean {
        require(maxAttempts > 0) { "maxAttempts must be > 0" }
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val old = current.get()
            val updated = try {
                transform(old)
            } catch (e: Exception) {
                // transform 抛异常时不修改 current，向上抛给调用方
                throw e
            }
            if (updated === old) return true // 无变化
            if (current.compareAndSet(old, updated)) {
                val saveResult = try {
                    save(updated)
                } catch (e: Exception) {
                    // save 失败时回滚内存到 old，避免内存与磁盘不一致
                    current.compareAndSet(updated, old)
                    throw e
                }
                if (!saveResult) {
                    // save 返回 false 时回滚
                    current.compareAndSet(updated, old)
                }
                return saveResult
            }
            // CAS 失败，重试（基于最新 current 值重新计算）
        }
        // 超过 maxAttempts：可能并发极高或 transform 不稳定
        throw IllegalStateException(
            "SettingsRepository.update: CAS loop exceeded $maxAttempts attempts"
        )
    }

    /** 启动文件监听(可重复调用,内部去重) */
    fun startWatching() {
        if (watchingStarted.getAndSet(true)) return
        watchJob = scope.launch { watchLoop() }
    }

    /** 停止监听 */
    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
        watchingStarted.set(false)
    }

    fun dispose() {
        disposed = true
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

    private fun awaitInit() {
        if (initFuture.isDone) return
        try {
            initFuture.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("SettingsRepository initialization wait timed out, proceeding anyway: ${e.message}")
        }
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
        if (disposed) return false
        return try {
            val path = settingsPath ?: return false
            path.parent?.createDirectories()
            val text = DefaultSettings.JSON.encodeToString(settings)
            path.writeText(text, Charsets.UTF_8)
            current.set(settings)
            if (scope.isActive) {
                scope.launch { _changes.emit(settings) }
            }
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
            // 在循环外累积 dirty 标记:一次 watchSvc.take() 后把 pollEvents() 拿到的所有事件
            // 都看作"文件被改了",在一轮处理里最多 reload 一次
            while (currentCoroutineContext().isActive) {
                val key = withContext(Dispatchers.IO) { watchSvc.take() }
                val events = key.pollEvents()
                var dirty = false
                var deleted = false
                for (event in events) {
                    val kind = event.kind()
                    if (kind == OVERFLOW) continue
                    val filename = (event.context() as? Path)?.fileName ?: continue
                    if (filename != path.fileName) continue
                    if (kind == ENTRY_DELETE) {
                        logger.info("Settings file deleted externally, keeping in-memory state")
                        deleted = true
                    } else {
                        dirty = true
                    }
                }
                if (dirty && !deleted) {
                    // debounce:多次 modify 合并为一次 reload
                    delay(300)
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
