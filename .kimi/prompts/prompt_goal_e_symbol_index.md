# 目标 E：SymbolIndex 增量更新与继承关系索引

## 角色定义

你是一位**资深 Kotlin 工程专家 + IDE 插件开发专家**。你需要深入阅读 CodeSage 项目现有代码，识别 `SymbolIndex` 和 `SemanticSearch` 的性能瓶颈与线程安全问题，设计增量索引、原子更新、继承反向索引和搜索缓存机制，编写/修改代码并确保所有现有测试通过。

## 项目背景

**CodeSage** 是一款基于 IntelliJ Platform 的 AI Agent IDE 插件（Kotlin 语言）。`SymbolIndex` 基于 IntelliJ PSI（Program Structure Interface）缓存项目中的类、方法、字段等符号，支持快速查找和语义搜索。`SemanticSearch` 基于 `SymbolIndex` 提供多策略智能搜索（精确匹配、模糊匹配、签名匹配、注释匹配等）。

## 当前代码状态与 Gap 分析

### `SymbolIndex` 现状（`src/main/kotlin/com/codesage/analysis/SymbolIndex.kt`）

```kotlin
class SymbolIndex(private val project: Project) {
    private val nameIndex = ConcurrentHashMap<String, MutableList<PSIAnalyzer.SymbolInfo>>()
    private val fileIndex = ConcurrentHashMap<String, List<PSIAnalyzer.SymbolInfo>>()
    private val typeIndex = ConcurrentHashMap<PSIAnalyzer.SymbolType, MutableList<PSIAnalyzer.SymbolInfo>>()
    
    fun buildIndex() { ... } // 每次全量清空 nameIndex/fileIndex/typeIndex
    fun updateFile(file: VirtualFile) { ... } // remove + add，非原子
    fun findImplementations(interfaceName: String): List<PSIAnalyzer.SymbolInfo> { ... } // O(n) 全表扫描
}
```

### `SemanticSearch` 现状（`src/main/kotlin/com/codesage/analysis/SemanticSearch.kt`）

- `search()` 综合 4 种策略：精确名称、模糊名称、签名匹配、注释匹配
- `semanticQuery()` 遍历所有 METHOD + CLASS 符号做关键词打分
- `searchByDocumentation()` 遍历所有 METHOD + CLASS 符号做注释关键字匹配
- **无任何缓存机制**，同一查询在短时间内重复执行会重复全表扫描

### `PSIAnalyzer.SymbolInfo` 现状（`src/main/kotlin/com/codesage/analysis/PSIAnalyzer.kt`）

```kotlin
data class SymbolInfo(
    val name: String,
    val type: SymbolType,
    val qualifiedName: String?,
    val filePath: String,
    val lineNumber: Int,
    val docComment: String?,
    val modifiers: List<String>,
    val parameters: List<ParameterInfo> = emptyList(),
    val returnType: String? = null,
    val superTypes: List<String> = emptyList()
)
```

**E5 现状**：`SymbolInfo` 是纯数据类，不持有 `PsiElement` 引用，已满足 PSI 解耦要求。但 `buildIndex()` 中存储的是 `PSIAnalyzer.SymbolInfo`，而 `PSIAnalyzer.analyzeFileDeep()` 在分析时会临时创建 `PsiElement` 但仅提取字段后即丢弃，无内存泄漏风险。`E5` 任务在本项目中实际已满足，无需修改。

### 当前 Gap

1. **`buildIndex()` 全量重建**：每次调用都 `nameIndex.clear(); fileIndex.clear(); typeIndex.clear()`，大项目频繁触发时效率极低。
2. **`updateFile()` 非原子**：先 `remove` 再 `add`，并发搜索时可能看到符号丢失（空窗期）。
3. **`findImplementations()` O(n)**：遍历 `nameIndex.values.flatten()` 所有符号，检查 `superTypes`，时间复杂度随符号数量线性增长。
4. **`SemanticSearch` 无缓存**：`search()` / `semanticQuery()` / `searchByDocumentation()` 均为纯计算，无 LRU 缓存，重复查询浪费 CPU。

## 具体任务要求

### E1. 增量索引 —— `buildIndex()` 跳过未变更文件

在 `SymbolIndex` 中维护 `indexedFileHashes: ConcurrentHashMap<String, Long>`：

- Key = 文件绝对路径（`VirtualFile.path`）
- Value = 文件最后修改时间戳（`VirtualFile.modificationStamp`）或内容 hash（`VirtualFile.contentsToByteArray()` 的 CRC32）

修改 `buildIndex()` 逻辑：
1. 获取项目中的所有代码文件（现有逻辑）。
2. 对每个文件，检查 `indexedFileHashes[file.path]`：
   - 若存在且等于当前 `modificationStamp`，**跳过分析**。
   - 若不存在或已变更，执行 `analyzer.analyzeFileDeep(file)` 并更新 hash。
3. **清理已删除文件**：遍历 `indexedFileHashes` 的 keys，若文件已不在当前项目文件集合中，调用 `removeFileSymbols(path)` 清理索引并删除 hash 记录。

**性能目标**：修改单个文件后重新 `buildIndex()`，只分析该文件，耗时 < 首次 buildIndex 的 10%。

### E2. 原子更新 —— `updateFile()` 线程安全

当前 `updateFile()` 实现：
```kotlin
fun updateFile(file: VirtualFile) {
    fileIndex[file.path]?.forEach { symbol ->
        nameIndex[symbol.name]?.remove(symbol)
        typeIndex[symbol.type]?.remove(symbol)
    }
    val symbols = analyzer.analyzeFileDeep(file)
    fileIndex[file.path] = symbols
    symbols.forEach { symbol ->
        nameIndex.getOrPut(symbol.name) { mutableListOf() }.add(symbol)
        typeIndex.getOrPut(symbol.type) { mutableListOf() }.add(symbol)
    }
}
```

问题：`remove` 和 `add` 之间的时间窗口内，并发调用 `findByName()` 或 `fuzzySearch()` 可能看不到该文件的任何符号。

**修复方案**：使用 `synchronized` 块或 `ReentrantReadWriteLock`：
- 对 `updateFile()` 和 `buildIndex()` 的索引写入操作加写锁。
- 对 `findByName()`、`fuzzySearch()`、`findImplementations()` 等读操作加读锁。
- 由于 `ConcurrentHashMap` 本身是线程安全的，但 `MutableList` 的 `remove`/`add` 组合不是原子的，因此需要在 `updateFile()` 级别加锁。

简化方案（最小侵入）：
```kotlin
private val indexLock = ReentrantReadWriteLock()

fun updateFile(file: VirtualFile) {
    indexLock.writeLock().withLock {
        // 原有 remove + add 逻辑
    }
}

fun findByName(name: String): List<SymbolInfo> {
    indexLock.readLock().withLock {
        return nameIndex[name]?.toList() ?: emptyList()
    }
}
```

注意：`CopyOnWriteArrayList` 不适合此场景（写操作频繁时拷贝开销大），`ReentrantReadWriteLock` 更合适。

### E3. 继承反向索引 —— `findImplementations()` O(1) 查询

新增 `inheritanceIndex: ConcurrentHashMap<String, CopyOnWriteArrayList<SymbolInfo>>`：

- Key = `superType` 的全限定名或简单名（如 `"MyInterface"`、`"java.lang.Runnable"`）
- Value = 实现/继承该类型的所有 `SymbolInfo` 列表

修改 `buildIndex()` / `updateFile()`：
- 在索引符号时，若 `symbol.superTypes.isNotEmpty()`，将符号注册到每个 superType 的 `inheritanceIndex` 中。
- `updateFile()` 原子更新时，先清理旧符号在 `inheritanceIndex` 中的注册，再添加新注册。

修改 `findImplementations(interfaceName: String)`：
```kotlin
fun findImplementations(interfaceName: String): List<SymbolInfo> {
    ensureIndexed()
    return inheritanceIndex[interfaceName]?.toList() ?: emptyList()
}
```

**复杂度目标**：对于 10k 符号的项目，`findImplementations("MyInterface")` < 1ms。

**边界处理**：`superTypes` 中可能同时包含简单名和全限定名，需要对两者都建立索引（或仅索引简单名，查询时也用简单名）。当前 `PSIAnalyzer` 中提取的 `superTypes` 为空列表（简化处理），但架构上需支持未来完善。

### E4. 搜索缓存 —— `SemanticSearch` LRU 缓存

在 `SemanticSearch` 中引入查询结果缓存：

- 使用**简单 LRU Map**（无需引入 Caffeine 等外部依赖，项目中未配置）：
  ```kotlin
  private val searchCache = object : LinkedHashMap<String, Pair<Long, List<SearchResult>>>(100, 0.75f, true) {
      override fun removeEldestEntry(eldest: Map.Entry<String, Pair<Long, List<SearchResult>>>): Boolean {
          return size > 100 || (System.currentTimeMillis() - eldest.value.first) > 60000
      }
  }
  ```
- Key = `"${query.lowercase().trim()}:$limit"`
- Value = `(timestamp, results)`
- TTL = 60 秒
- 缓存命中时直接返回结果副本（避免外部修改缓存内容）。

需要缓存的方法：
- `search(query, limit)`
- `semanticQuery(description, limit)`
- `findRelatedClasses(className)`

不需要缓存的方法（结果集小或实时性要求高）：
- `findDefinition()`（精确查询，本身已 O(1)）
- `findMethodCalls()`

**缓存失效**：当 `SymbolIndex` 发生 `buildIndex()` 或 `updateFile()` 时，应通知 `SemanticSearch` 清空缓存。可在 `SymbolIndex` 中维护一个 `version: AtomicLong`，每次索引变更时递增；`SemanticSearch` 缓存 Key 中纳入 `symbolIndex.version`，版本不匹配时自动失效。

### E5. PSI 解耦（验证与加固）

虽然 `SymbolInfo` 已不直接持有 `PsiElement`，但需验证以下代码路径无泄漏风险：

- `PSIAnalyzer.analyzeFileDeep()`：确认分析完成后无 `PsiElement` 引用残留到 `SymbolInfo` 中。
- `SymbolIndex` 中存储的 `SymbolInfo` 列表：确认无间接持有（如通过 lambda 捕获）。

**结论**：经代码审查，`SymbolInfo` 为纯数据类，所有字段均为 `String`/`Int`/`List<String>`，已满足解耦要求。本任务只需在代码注释和架构文档中明确声明此设计约束，无需修改数据结构。

## 验收标准

- [ ] `SymbolIndexTest` 新增测试：`buildIndex()` 首次执行后，修改单个文件（模拟 `modificationStamp` 变更）再次执行，仅分析该文件；断言第二次耗时 < 第一次的 10%（或通过计数器断言 `analyzer.analyzeFileDeep()` 调用次数）。
- [ ] `SymbolIndexTest` 新增测试：`findImplementations("MyInterface")` 在 10k 符号场景下 < 1ms（可通过预构建 10k  mock `SymbolInfo` 测试，不依赖真实 PSI）。
- [ ] `SymbolIndexTest` 新增测试：并发执行 `updateFile()` 和 `findByName()` 100 次，无 `ConcurrentModificationException` 或符号丢失。
- [ ] `SemanticSearchTest` 新增测试：同一查询连续调用 2 次，第二次从缓存返回；修改 `symbolIndex.version` 后再次查询，缓存失效并重新计算。
- [ ] `./gradlew test` 全部通过，无回归（允许已存在的 `ConversationPersistenceTest` flaky 失败）。
- [ ] 架构文档 `docs/ARCHITECTURE.md` 中 Symbol Index 章节同步更新，说明增量索引、继承反向索引、搜索缓存的设计。

## 通用约束与规范

1. **最小侵入性**：不修改 `PSIAnalyzer.SymbolInfo` 数据类签名，不修改 `SemanticSearch.SearchResult` 结构。新增功能通过新增字段/方法实现。
2. **线程安全**：`SymbolIndex` 的所有可变状态必须使用 `ConcurrentHashMap`、`AtomicLong`、`ReentrantReadWriteLock` 保护。`SemanticSearch` 的缓存使用 `synchronized` 或线程安全的 `LinkedHashMap` 子类。
3. **资源管理**：`ReentrantReadWriteLock` 使用 `try-finally` 或 `withLock` 确保锁释放。
4. **日志规范**：使用 `Logger.getLogger<T>()`，索引构建/更新/缓存命中/失效时输出 `debug` 日志。
5. **测试覆盖**：增量索引、原子更新、继承索引、缓存命中/失效均需有独立 `@Test`，并发测试使用 `kotlinx.coroutines` 的 `async/await` 或 `Thread` 模拟。
6. **性能可观测**：`SymbolIndex` 的 `getStats()` 扩展返回 `cacheHitRate` 和 `indexVersion`，便于后续 MetricsCollector 接入。

## 输出格式要求

完成代码修改后，请按以下格式回复：

```markdown
## 完成报告：目标 E

### 修改文件清单
1. `src/main/kotlin/...` - 修改说明
2. ...

### 关键设计决策
- 决策 A：原因...
- 决策 B：原因...

### 测试验证
```bash
./gradlew test
# 结果：BUILD SUCCESSFUL / X tests completed, Y failed
```
```
