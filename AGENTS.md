# Project Instructions

## 项目背景

- 项目类型：Kotlin/JVM IntelliJ 平台插件
- 构建工具：Gradle + Kotlin DSL
- 核心领域：AI Agent 工具链（`ToolRegistry` / `ToolExecutor` / `IDETools` / `ExtendedTools`）

## 编码规范

- 使用 Kotlin 协程处理异步逻辑
- 所有新增 public/internal API 必须有 KDoc
- 优先复用现有架构：新工具继承 `UnifiedTool` 并通过 `ToolRegistry.createDefault()` 注册
- 危险操作（写文件、删除、Shell）必须经过 `ToolGuardrails.preCheck`
- 文件操作优先走 IntelliJ VFS；`project == null` 的测试/ headless 场景可回退到 `AtomicFileWriter`

## 测试要求

- 每次修改后运行 `./gradlew test`
- 新增工具至少 2 个单元测试（正常路径 + 错误路径）
- 对依赖 IntelliJ 平台的测试，优先 mock 或使用 `project=null` 的 File I/O 路径

## 受限操作

- 不要自动提交代码或执行 `git push`
- 不要修改 `.github/workflows` 除非用户明确要求
- 不要修改 `.git` 目录或项目外文件
