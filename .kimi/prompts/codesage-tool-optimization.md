# CodeSage 工具能力优化实施提示词

基于 `docs/TOOL_CAPABILITY_REVIEW_2026_06.md` 及补充审阅，按以下方案优化 CodeSage 工具能力。

## 目标

1. 主循环工具调用并行化，端到端提速 2-4x。
2. 支持 `AGENTS.md` / `CLAUDE.md` 项目级配置标准。
3. 补齐 OS 级沙箱，降低命令执行安全风险。
4. 重写 System Prompt 与 Tool Description，提升任务完成率。
5. 实现上下文自管理（`get_context_remaining` + 动态截断）。

## 实施规划

### Phase 1：并行执行（最高 ROI）✅
- 修改 `EnhancedAgentLoop.kt`：将串行 `for` 改为 `async + Semaphore` 并行执行，默认并发 6。
- 保留 `toolCall.index` 保序，UI 事件按原始顺序渲染。
- 修复 `ModelGateway.chatStream` fallback 中 tool call index 丢失为 0 的问题。
- 单元测试：`EnhancedAgentLoopTest` 并行执行顺序与耗时测试通过。

### Phase 2：AGENTS.md / CLAUDE.md 支持 ✅
- 在 `PromptAssembler` 中新增 `loadAgentsMd()` 配置加载段。
- 发现顺序：`{projectRoot}/AGENTS.md` → `{projectRoot}/CLAUDE.md` → `~/.codesage/AGENTS.md`。
- `AgentCore.kt` 将检测到的 `projectRoot` 传入 `AssemblyContext`。
- 单元测试：`PromptAssemblerTest` AGENTS.md / CLAUDE.md 加载测试通过。

### Phase 3：OS 级沙箱 ✅
- 新增 `CommandSandbox` 抽象及平台实现：
  - macOS：`SeatbeltSandbox`（`sandbox-exec`），默认禁止网络、禁止项目外写入。
  - Linux：`BubblewrapSandbox`（`bwrap`），不可用则降级为 `PathBasedSandbox`。
  - Windows / 兜底：`PathBasedSandbox`（路径审计，不阻止执行）。
- `IDETools.runCommand` 与 `ExtendedTools.execShell` 注入 `CommandSandbox`；未注入时保留旧版行为（兼容测试）。
- `ToolRegistry.createDefault()` 自动创建并注入 `WORKSPACE_WRITE` 沙箱。
- 新增 `BoundedProcessReader` 提供有界并发输出读取，避免 pipe 阻塞与 OOM。
- 修复 `EnhancedAgentLoop.executeToolCallsParallel`：检测到中断时取消 in-flight 工具并 emit `ToolCallError`。
- 单元测试：`CommandSandboxTest`、`CommandSandboxIntegrationTest` 通过；`EnhancedAgentLoopCancellationTest` 适配并行执行。

### Phase 4：System Prompt + Tool Description 重写 ✅
- 重写 `PromptAssembler.DEFAULT_BASE_PROMPT` 与 `GENERAL_GUIDELINES` 为中文结构化提示（~150 行），包含：
  - ReAct 协议（Thought → Action → Observation → Answer）
  - 并行工具调用引导
  - 权限策略（Permission Policy）
  - 上下文预算（Context Budget）
  - 编辑规范（DO / DON'T）
  - 沙箱/危险命令提示
- 同步更新 `AgentConfig.DEFAULT_SYSTEM_PROMPT` 为一致的中文结构。
- 全部工具 description 标准化为：Summary / Args / Do / Don't / Parallel / Cap。
- 新增 `PromptAssemblerTest` 验证默认提示包含 ReAct、并行调用、权限策略、上下文预算、DO/DON'T、沙箱等关键段。

### Phase 5：上下文自管理 ✅
- 新增 `get_context_remaining` 工具，返回 `tokens_used / tokens_left / percent`。
- 实现 `ContextBudgetManager`，根据预算动态调整 `OutputTruncator` 阈值。
- 在 `EnhancedAgentLoop` 每轮 LLM 调用前触发主动压缩（`ContextManager.compress`）。
- 压缩时保留 system prompt + 前 N 条 user 消息，保护 prefix cache。

## 约束

- **最小改动**：优先复用已有基础设施（`SubAgentExecutor` Semaphore、`OutputTruncator`、`ContextManager`）。
- **向后兼容**：不删除现有 tool 定义、不改变已有 public API、老用户配置可平滑迁移。
- **安全第一**：OS 沙箱默认开启；`danger-full-access` 必须显式命名并警告。
- **保序**：并行执行后 UI 事件顺序必须与 `toolCalls` 原始顺序一致。
- **缓存友好**：Prompt 增强仅追加，不修改头部 prefix；AGENTS.md 内容作为前缀固定保留。
- **平台差异**：macOS/Linux/Windows 沙箱实现可不一致，但行为语义一致（限制写/网络）。

## 验收标准

### 功能验收
- [x] 6 个独立 tool call 被并行执行，结果顺序正确。
- [x] 项目根目录存在 `AGENTS.md` 时，system prompt 包含其内容。
- [x] `run_command` / `exec_shell` 在沙箱内执行，网络与外项目写入受限。
- [x] `get_context_remaining` 返回的 `tokens_left` 随对话增长递减。
- [x] 大工具输出（>8KB）自动截断，模型收到截断提示。

### 测试验收
- [x] `EnhancedAgentLoopTest` 新增并行执行顺序测试。
- [x] `CommandSandboxTest` / `CommandSandboxIntegrationTest` 新增沙箱测试。
- [x] `PromptAssemblerTest` 新增 AGENTS.md 加载测试与默认提示结构测试。
- [x] `OutputTruncatorTest` 新增动态阈值测试。
- [x] 所有现有测试通过，无回归。

### 文档验收
- [x] 更新 `docs/TOOL_CAPABILITY_REVIEW_2026_06.md` 实施状态。
- [x] 新增 `docs/AGENTS_MD_SUPPORT.md` 说明发现顺序与格式。
- [x] 新增 `docs/OS_SANDBOX.md` 说明平台实现与安全模式。
- [x] 新增 `docs/SYSTEM_PROMPT_V2.md` 说明新系统提示结构。

## 输出要求

每完成一个 Phase，提交简洁的变更摘要，包含：修改文件、关键设计决策、测试命令、验证结果。遇到架构分歧先停下来确认，不要擅自扩大范围。
