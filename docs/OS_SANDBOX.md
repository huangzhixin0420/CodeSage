# CodeSage OS 级命令沙箱

## 目标

为 AI 执行的 `run_command` / `exec_shell` 等命令提供 OS 级隔离，降低路径逃逸、网络外联和恶意操作的风险。

## 架构

```
                    run_command / exec_shell
                            │
                            ▼
                   IDETools / ExtendedTools
                            │
                            ▼
              CommandSandbox.create(projectRoot, mode)
                            │
        ┌───────────────────┼───────────────────┐
        ▼                   ▼                   ▼
  SeatbeltSandbox    BubblewrapSandbox    PathBasedSandbox
   (macOS)             (Linux)            (Windows / 兜底)
```

## 接口

```kotlin
interface CommandSandbox {
    enum class Mode { READ_ONLY, WORKSPACE_WRITE, DANGEROUS_FULL_ACCESS }
    fun execute(command: String, workingDir: File, timeoutMs: Long, maxOutputChars: Int = Int.MAX_VALUE): SandboxResult
}
```

- `READ_ONLY`：只允许读取项目目录，禁止任何写入与网络。
- `WORKSPACE_WRITE`（默认）：允许读取/写入项目目录，禁止网络与项目外写入。
- `DANGEROUS_FULL_ACCESS`：不启用 OS 级沙箱，仅记录 warn 日志；用于特殊调试场景。

## 平台实现

### macOS — SeatbeltSandbox

使用系统自带的 `sandbox-exec`，通过内联 profile 控制权限：

```scheme
(version 1)
(allow default)
(deny network*)
(deny file-write*)
(allow file-write* (subpath "<projectRoot>"))
```

- 默认允许大多数系统调用，只显式禁止网络与项目外写入。
- 保留 shell 命令的兼容性（`echo`、`sleep`、`git`、`gradle` 等常见命令可直接运行）。

### Linux — BubblewrapSandbox

使用 `bwrap` 创建最小文件系统视图：

- `/` 重新挂载为 tmpfs。
- `/bin`、`/usr`、`/lib`、`/lib64`、`/etc` 只读绑定。
- 项目目录绑定到 `/workspace` 作为工作目录。
- `--unshare-net` 禁止网络。
- `--unshare-all` 隔离用户/IPC/挂载命名空间。

若系统未安装 `bubblewrap`，自动降级为 `PathBasedSandbox`。

### Windows / 兜底 — PathBasedSandbox

当平台不支持 OS 级沙箱或用户显式需要兼容性时使用：

- 仅对命令字符串进行路径模式审计。
- 对可疑的绝对路径操作记录 warn 日志。
- 不阻止执行，依赖操作系统本身的权限约束。

## 集成点

`ToolRegistry.createDefault()` 会自动创建并注入沙箱：

```kotlin
val commandSandbox = CommandSandbox.create(projectRoot, CommandSandbox.Mode.WORKSPACE_WRITE)
val ideTools = IDETools(project, auditLog, commandSandbox)
val extendedTools = ExtendedTools(project, commandSandbox)
```

`IDETools` 与 `ExtendedTools` 的构造函数中 `commandSandbox` 为可空，不传时保留旧版 `ProcessBuilder` 行为，保证单元测试与直接实例化的兼容性。

## 输出与降级

- `SandboxResult.sandboxed` 标记是否实际进入 OS 级沙箱；`PathBasedSandbox` 返回 `false`。
- 沙箱执行失败（如 `sandbox-exec` 不存在）会记录 error 日志并返回包含错误信息的 `SandboxResult`，不会静默吞掉异常。
- 命令超时由 `timeoutMs` 控制；超时后不再阻塞等待 reader 线程，避免孤儿子进程持有 pipe 导致挂起。

## 测试

```bash
./gradlew test --tests "com.codesage.shared.security.CommandSandboxTest"
./gradlew test --tests "com.codesage.agent.tools.CommandSandboxIntegrationTest"
```

覆盖场景：

- 简单命令执行与输出捕获
- 超时快速返回
-  oversized 输出截断
- `IDETools.runCommand` / `ExtendedTools.execShell` 沙箱集成
- 中断时 in-flight 工具 emit `ToolCallError`

## 已知限制

1. **进程取消**：当前实现取消的是协程作用域，阻塞中的 shell 子进程会继续运行到自身超时；后续可补充 `Process.destroyForcibly()` 或进程组信号彻底终止。
2. **Windows**：尚无真正的 OS 级隔离，仅提供路径审计；需要 Windows Sandbox / AppContainer 时后续扩展。
3. **Seatbelt 兼容性**：`sandbox-exec` 在 macOS 上仍可运行，但 Apple 更推荐应用级沙箱；CLI/IDE 插件场景下 seatbelt 仍是可行方案。
