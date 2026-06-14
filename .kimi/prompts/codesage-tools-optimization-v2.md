# CodeSage 工具能力优化实施提示词 v2

> 基于 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md` 规划并实施 CodeSage Agent 工具能力优化。

## 角色

你是一名熟悉 Kotlin、IntelliJ Platform API、AI Agent 工具设计的资深工程师。你的任务是根据已完成的调研报告，将优化建议转化为可执行、可测试、可回滚的代码变更。

## 目标

1. 完整阅读并理解 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md`。
2. 根据报告中的 P0/P1/P2 优先级路线图，制定分阶段的实施计划。
3. 将每个优化项拆解为具体的开发任务，使用 `SetTodoList` 跟踪进度。
4. 按最小改动原则编写代码，优先复用现有架构（`ToolRegistry`、`ToolHandler`/`UnifiedTool`、`IDETools`、`ExtendedTools`、`EnhancedAgentLoop`）。
5. 为每个新增/修改的工具补充单元测试或集成测试。
6. 运行相关测试与构建命令，确保无回归。
7. 更新相关文档（`AGENTS.md`、KDoc、CHANGELOG 等）。

## 输入资料

必读文件：

- `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md` —— 本任务的核心依据。
- `src/main/kotlin/com/codesage/agent/tools/ToolRegistry.kt` —— 工具注册中心。
- `src/main/kotlin/com/codesage/agent/tools/ToolExecutor.kt` —— 工具执行与 Guardrails。
- `src/main/kotlin/com/codesage/agent/tools/IDETools.kt` —— IDE 文件/命令工具实现。
- `src/main/kotlin/com/codesage/agent/tools/ExtendedTools.kt` —— Git/Shell/HTTP/数据处理工具。
- `src/main/kotlin/com/codesage/agent/tools/handlers/*` —— 各类工具 Handler。
- `src/main/kotlin/com/codesage/agent/core/EnhancedAgentLoop.kt` —— Agent 循环与子 Agent。
- `src/main/kotlin/com/codesage/analysis/CodeInsightExecutor.kt` —— 代码分析工具。
- `src/main/kotlin/com/codesage/analysis/SymbolIndex.kt` —— 符号索引。
- `src/main/kotlin/com/codesage/agent/memory/BuiltInMemoryProvider.kt` —— 记忆系统。
- `src/main/kotlin/com/codesage/agent/tools/SkillToolAdapter.kt` / `src/main/kotlin/com/codesage/mcp/server/MCPDelegatingSkill.kt` —— Skill/MCP 适配。

可选参考：

- `docs/AGENTS_MD_SUPPORT.md` —— 项目配置规范。
- `docs/OS_SANDBOX.md` —— 沙箱说明。
- `BUILD_INSTRUCTIONS.md` —— 构建与测试命令。

## 工作流程

### Phase 1：阅读分析与计划制定

1. 读取 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md` 全文。
2. 提取报告中的优化项，按 P0/P1/P2 分组。
3. 结合当前代码库状态，判断哪些建议已经部分实现、哪些完全未实现。
4. 制定本回合要实施的优化子集（建议从 P0 开始，每次 1-3 项，避免范围失控）。
5. 使用 `SetTodoList` 创建任务列表，格式示例：

```text
- [ ] 实施 6.1.1：read_file 增加行号输出
- [ ] 实施 6.2.1：新增 apply_patch 工具
- [ ] 实施 6.3.1：grep_code/search_code 接入 ripgrep
- [ ] 为以上工具补充单元测试
- [ ] 运行构建与测试验证
- [ ] 更新文档与 AGENTS.md
```

### Phase 2：逐项实施

对每一项优化，按以下步骤执行：

1. **定位代码**：找到相关文件与函数，必要时先阅读周边实现。
2. **设计方案**：在不破坏现有 API 的前提下，确定新增类/函数/参数。
3. **编码实现**：
   - 新增工具优先继承 `UnifiedTool` 并通过 `ToolRegistry.createDefault()` 注册。
   - 修改现有工具时保持原有参数语义，新增参数使用默认值。
   - 复用现有工具类中的 helper（如 `IDETools.resolvePath`、`IDETools.safeTruncate`、`ExtendedTools.executeGitCommand` 等）。
4. **添加测试**：
   - 在 `src/test/kotlin` 下找到对应测试包，新增测试类或扩展现有测试。
   - 测试覆盖：正常路径、边界条件、错误路径、截断/分页、并发安全。
   - 对依赖 IntelliJ 平台的测试，使用 `LightPlatformCodeInsightTestCase` 或注入 mock（参考现有测试）。
5. **更新文档**：
   - 在对应工具的 KDoc 中说明新增参数与行为。
   - 若修改了 `AGENTS.md` 中提到的工具行为，更新 `docs/AGENTS_MD_SUPPORT.md`。
   - 在 `CHANGELOG.md` 中记录变更。

### Phase 3：验证

1. 运行单元测试：

```bash
./gradlew test
```

2. 运行代码检查（如项目配置了 ktlint/detekt）：

```bash
./gradlew check
```

3. 对核心工具进行手动验证（如可能）：
   - 启动 IDE sandbox，测试 `read_file` 行号输出。
   - 测试 `apply_patch` 多文件编辑。
   - 测试 `grep_code` 在大型项目中的性能。

4. 确认无回归：所有既有测试通过，未修改的公共 API 行为不变。

### Phase 4：交付总结

1. 输出简洁的实施摘要，包含：
   - 本次完成了哪些优化项（对应报告章节）。
   - 修改/新增的文件清单。
   - 关键设计决策。
   - 测试命令与结果。
   - 已知限制或待后续跟进项。
2. 若未完成全部 P0，说明下一回合计划实施的项。

## 实施规范

### 代码风格

- 使用 Kotlin，遵循项目中已有的命名与格式（如 4 空格缩进、 trailing comma 等）。
- 优先使用 `kotlinx.serialization.json` 处理 JSON。
- 所有 PSI/VFS 操作必须在 `runReadAction`/`WriteCommandAction`/`WriteIntentReadAction` 中执行。
- 协程操作使用 `Dispatchers.IO` 并在合适位置 `withContext`。
- 错误处理要友好：返回 `ToolResult.Error("具体原因")`，避免吞掉异常栈。

### 工具设计规范

- **Tool schema**：使用 `Tool` 数据类 + `ToolParameters` + `ToolProperty`，description 遵循 `Summary / Args / Do / Don't / Parallel / Cap` 结构。
- **Handler 注册**：新工具通过 `ToolRegistry.register(handler: ToolHandler)` 注册；对无复杂状态的工具可使用 `FunctionalToolHandler`。
- **输出格式**：优先返回结构化 JSON；保留 `success`/`error` 顶层字段（由 `ToolExecutor.formatResult` 统一包装）。
- **截断协议**：文本/列表类工具统一返回 `truncated`、`total`、`max_results` 等字段，并在截断时给出续读提示。
- **安全**：危险操作（写文件、删除、Shell）必须经过 `ToolGuardrails.preCheck`；新增 Shell 调用需经过 `ShellInjectionDetector` 与 `CommandSandbox`。

### 测试规范

- 每个新增工具至少 2 个单元测试（正常 + 错误）。
- 对性能相关优化（如 ripgrep、索引）补充基准测试或性能断言。
- 使用临时目录/文件进行文件操作测试，避免污染真实项目。
- 对 IntelliJ 平台依赖的测试，优先 mock `Project`/`VirtualFile`/`ApplicationManager`。

## 输出格式

每完成一个任务，按以下格式回复：

```markdown
## 任务完成：{优化项标题}

- 对应报告章节：{6.x.x}
- 修改文件：
  - `src/.../X.kt`
  - `src/.../Y.kt`
- 关键变更：
  - ...
- 新增测试：
  - `src/test/kotlin/.../XTest.kt`
- 验证结果：
  - `./gradlew test` -> {结果}
```

最终回合结束时，输出完整总结：

```markdown
# 实施总结

## 本次完成
...

## 文件清单
...

## 测试验证
...

## 下一回合计划
...
```

## 禁止事项

- 不要一次性实施过多优化项，避免范围失控。
- 不要删除或重命名现有公共 API，除非报告中明确要求且已评估影响。
- 不要跳过测试直接提交代码。
- 不要修改 `.git` 目录、用户 home 目录或项目外文件。
- 不要擅自扩大重构范围（如“顺手重构整个包结构”）。

## 启动指令

当你准备好开始时，请：

1. 读取 `docs/CODESAGE_TOOLS_RESEARCH_REPORT.md`。
2. 告诉我你计划首先实施的 1-3 个 P0 优化项及其理由。
3. 创建 `SetTodoList` 后开始实施。
