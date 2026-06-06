# CodeSage 详细优化计划

> 基于 Phase 1-4 已完成的基础上，继续进行 UI 集成、工具扩充、系统增强等优化。
> 每个优化项为独立可交付的小任务，完成后更新进度。

---

## 优化清单

### 优化1: UI层集成 — 子Agent进度可视化
**目标**: 在对话界面中实时显示子 Agent 的执行进度
**文件**:
- `ide/ui/components/chat/SubAgentProgressPanel.kt` — 新组件：子Agent进度面板
- `ide/ui/components/chat/ChatPanel.kt` — 集成 SubAgentProgressPanel
- `agent/core/AgentStreamEvent.kt` — 添加 SubAgentStart/SubAgentProgress/SubAgentComplete 事件
**验收标准**:
- 当模型调用 `delegate_task` 时，UI 显示子任务卡片
- 实时更新子 Agent 的工具调用进度
- 子 Agent 完成后显示结果摘要

---

### ~~优化2: UI层集成 — Kanban看板面板~~（2026-06 移除）
**目标**: 在 IDE 工具窗口中提供 Kanban 看板视图（已撤回）
**文件**:
- ~~`ide/ui/components/kanban/KanbanBoardPanel.kt` — 新组件：看板面板~~（已删除）
- ~~`ide/ui/components/kanban/KanbanTaskCard.kt` — 任务卡片~~（已删除）
- `ide/toolwindow/AgentToolWindowPanel.kt` — 集成看板标签页
**验收标准**:
- ~~工具窗口新增 "Kanban" 标签页~~（已撤回）
- 可拖拽/点击更新任务状态
- 显示 BACKLOG/IN_PROGRESS/DONE 三列

---

### 优化3: UI层集成 — Thinking状态与工具调用可视化增强
**目标**: 提升 Thinking 状态和工具调用的可视化体验
**文件**:
- `ide/ui/components/chat/ThinkingIndicator.kt` — 增强动画和状态文本
- `ide/ui/components/chat/ToolCallPanel.kt` — 添加工具参数折叠/展开
- `ide/ui/components/chat/ChatPanel.kt` — 优化事件处理顺序
**验收标准**:
- Thinking 状态显示当前操作（如"正在搜索代码..."）
- 工具调用面板支持展开查看参数和完整结果

---

### 优化4: 扩充内置工具 — 文件/搜索类工具(8个)
**目标**: 将内置工具从 6 个扩充到 14 个
**新增工具**:
- `find_file` — 按名称模式查找文件
- `grep_code` — 在项目中执行类 grep 搜索
- `get_file_info` — 获取文件元数据（大小、修改时间等）
- `read_multiple_files` — 批量读取多个文件
- `edit_file` — 基于 diff 的精确编辑
- `delete_file` — 删除文件
- `copy_file` — 复制文件
- `move_file` — 移动文件
**文件**:
- `agent/tools/ToolRegistry.kt` — 注册新工具
- `agent/tools/ToolExecutor.kt` — 实现新工具执行逻辑
- `agent/tools/IDETools.kt` — 添加底层 IDE 操作

---

### 优化5: 扩充内置工具 — Git/终端/网络类工具(8个)
**目标**: 继续扩充到 22 个工具
**新增工具**:
- `git_status` — 查看 git 状态
- `git_diff` — 查看变更差异
- `git_log` — 查看提交历史
- `git_branch` — 查看/切换分支
- `exec_shell` — 执行任意 shell 命令
- `curl_request` — 执行 HTTP 请求
- `parse_json` — 解析和查询 JSON
- `encode_base64` — base64 编解码
**文件**:
- `agent/tools/ToolRegistry.kt` — 注册新工具
- `agent/tools/ToolExecutor.kt` — 实现执行逻辑

---

### 优化6: 工具系统增强 — 结果截断与Guardrails
**目标**: 防止超长工具结果撑爆 context，添加安全约束
**文件**:
- `agent/tools/ToolExecutor.kt` — 添加 `max_result_size_chars` 截断
- `agent/tools/ToolGuardrails.kt` — 新文件：工具调用频率限制、危险操作确认
- `agent/core/EnhancedAgentLoop.kt` — 集成 Guardrails 检查
**验收标准**:
- 工具结果超过 8000 字符自动截断
- 同一工具连续调用超过 3 次触发警告
- `delete_file` / `exec_shell` 等危险工具添加确认标记

---

### 优化7: 流式工具调用支持
**目标**: chatWithTools 支持流式响应中逐步检测 tool_calls
**文件**:
- `agent/core/EnhancedAgentLoop.kt` — 流式响应解析 tool_calls JSON
- `model/gateway/ModelGateway.kt` — 流式请求支持 tools 参数
**验收标准**:
- 流式中检测到 `tool_calls` 开始标记时暂停文本输出
- 逐步累积 JSON 参数，完整后触发工具执行
- 工具结果返回后继续流式输出

---

### 优化8: 系统提示缓存持久化 + Context压缩后Session迁移
**目标**: 生产级缓存和压缩迁移
**文件**:
- `agent/memory/BuiltInMemoryProvider.kt` — 新增 cached_prompts 表
- `agent/core/EnhancedAgentLoop.kt` — 缓存持久化/恢复
- `agent/context/ContextCompressor.kt` — 压缩后创建新 session
**验收标准**:
- 系统提示缓存到 SQLite，重启后可恢复
- Context 压缩后生成新 session ID
- 历史消息迁移到新 session 记录

---

### 优化9: AgentStreamEvent体系增强 + 性能优化
**目标**: 更细粒度的事件 + 性能提升
**文件**:
- `agent/core/AgentStreamEvent.kt` — 添加 PhaseChange/ContextCompressed/MemoryInjected 事件
- `agent/core/EnhancedAgentLoop.kt` — 发射更多事件
- 全局协程作用域优化
**验收标准**:
- UI 可感知 context 压缩事件
- UI 可感知记忆注入事件
- 协程取消正确传播

---

### 优化10: 测试覆盖率提升 + 集成测试
**目标**: 为新增组件补全测试，确保 115+ 测试全部通过
**文件**:
- `src/test/kotlin/...` — 各组件单元测试
**验收标准**:
- 每个新工具至少 2 个测试用例
- SubAgent 组件测试覆盖核心路径
- 整体测试数 > 130，0 失败

---

## 执行优先级

```
P0: 优化1 (UI子Agent进度) → 优化4+5 (扩充工具) → 优化6 (Guardrails)
P1: 优化3 (Thinking增强) → 优化7 (流式工具)（优化2 Kanban 面板 已撤回）
P2: 优化8 (缓存持久化) → 优化9 (事件增强) → 优化10 (测试覆盖)
```

## 进度追踪

| 优化项 | 状态 | 完成时间 | 测试数 |
|--------|------|---------|--------|
| 优化1: 子Agent进度可视化 | ✅ 已完成 | 2026-05-24 | 115 |
| 优化2: Kanban看板面板 | 🗑️ 已撤回 (2026-06) | - | - |
| 优化2: Kanban看板面板 | 🗑️ 已撤回 (2026-06) | - | - |
| 优化3: Thinking/工具可视化增强 | ⏳ 待开始 | - | - |
| 优化4: 文件/搜索类工具(8个) | ⏳ 待开始 | - | - |
| 优化5: Git/终端/网络类工具(8个) | ⏳ 待开始 | - | - |
| 优化6: 结果截断与Guardrails | ⏳ 待开始 | - | - |
| 优化7: 流式工具调用 | ⏳ 待开始 | - | - |
| 优化8: 缓存持久化+Session迁移 | ⏳ 待开始 | - | - |
| 优化9: 事件体系+性能优化 | ⏳ 待开始 | - | - |
| 优化10: 测试覆盖率提升 | ⏳ 待开始 | - | - |
