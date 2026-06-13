# 2025-2026 AI Agent 编程工具最新进展研究报告

> **研究目标**：为 CodeSage 多 Agent 编程系统提供对比分析基础
> **数据来源**：Anthropic 官方文档、Continue.dev、OpenHands、Cline、Devin、LangGraph 等官方资源
> **重点**：Claude Code Skills/Subagents 系统（CodeSage 重点参考）

---

## 目录

1. [Claude Code (Anthropic) - 重点](#1-claude-code-anthropic)
2. [Cursor - Composer/Agent Mode](#2-cursor)
3. [GitHub Copilot Agent Mode](#3-github-copilot-agent-mode)
4. [Cline / Roo Code](#4-cline--roo-code)
5. [Devin (Cognition Labs)](#5-devin-cognition-labs)
6. [Aider](#6-aider)
7. [Continue.dev](#7-continuedev)
8. [OpenHands (前 OpenDevin)](#8-openhands-前-opendevin)
9. [Hermes Agent (NousResearch)](#9-hermes-agent-nousresearch)
10. [LangGraph / LangChain Agents](#10-langgraph--langchain-agents)
11. [AutoGen (Microsoft)](#11-autogen-microsoft)
12. [OpenAI Codex CLI / Operator](#12-openai-codex-cli--operator)
13. [Gemini CLI](#13-gemini-cli)
14. [能力维度对比矩阵](#14-能力维度对比矩阵)
15. [CodeSage 设计建议](#15-codesage-设计建议)

---

## 1. Claude Code (Anthropic) ⭐ 重点参考

### 1.1 核心能力（2025-2026 最新）

**核心定位**：终端原生 AI 编程 Agent，Anthropic 官方 CLI 工具

**主要能力**：
- **Agent Loop**：基于 LLM 的循环执行，自主规划-执行-观察
- **MCP (Model Context Protocol) 集成**：原生支持 MCP client 和 server
- **SubAgents 系统**：可派生子 Agent 任务（隔离上下文、工具集过滤）
- **Skills 系统**：通过 SKILL.md 文件定义可重用能力
- **Plugins**：打包和分发 Skills 及其他扩展
- **Hooks**：基于工具事件的自动化工作流
- **Memory**：通过 CLAUDE.md 文件管理持久化上下文
- **Commands**：内置命令和捆绑的 Skills
- **Permissions**：精细化工具和 Skill 访问控制

### 1.2 Agent 架构特点

#### Skills 系统（2025 年 10 月正式发布）

**目录结构**：
```
~/.claude/skills/                    # 用户级 Skills
├── skill-name-1/
│   ├── SKILL.md                     # 必需：Skill 定义文件
│   ├── scripts/                     # 可选：辅助脚本
│   ├── references/                  # 可选：参考文档
│   └── assets/                      # 可选：静态资源
└── skill-name-2/
    └── SKILL.md

.claude/skills/                      # 项目级 Skills
```

**SKILL.md 文件格式**（YAML frontmatter + Markdown）：
```yaml
---
name: skill-name                      # 必需：Skill 名称
description: 简要描述触发条件          # 必需：触发描述
# 可选字段：
allowed-tools: Read, Grep, Glob       # 工具白名单
model: claude-sonnet-4-5-20250929    # 指定模型
context: fork                         # 隔离上下文（关键！）
---

# Skill 指令

这里是 Skill 的详细指令...
可以是任意 Markdown 内容，支持多文件引用。
```

**Skills 关键特性**：
- **自动发现**：Claude Code 自动扫描 `~/.claude/skills/` 和 `.claude/skills/`
- **按需加载**：仅在触发时加载完整内容（节省上下文）
- **描述驱动**：description 字段决定何时触发
- **工具隔离**：通过 `allowed-tools` 限制可用的工具
- **上下文隔离**：`context: fork` 字段允许 Skill 在独立上下文中运行
- **多文件支持**：可包含 scripts、references、assets

#### SubAgents 系统

**目录结构**：
```
~/.claude/agents/                    # 用户级 SubAgents
├── code-reviewer.md
├── test-runner.md
└── debugger.md

.claude/agents/                      # 项目级 SubAgents
├── api-designer.md
└── refactor-expert.md
```

**SubAgent Markdown 格式**：
```markdown
---
name: code-reviewer
description: Reviews code for quality and best practices
tools: Read, Grep, Glob, Bash         # 工具集
model: sonnet                         # 可指定不同模型
---

You are a senior code reviewer. Your role is to:
1. Analyze code for potential bugs
2. Check adherence to project conventions
3. Suggest improvements

When invoked, you should:
- Read the relevant files
- Provide structured feedback
- Be specific and actionable
```

**SubAgents 关键特性**：
- **完全隔离的 session**：每个 SubAgent 有独立的上下文窗口
- **工具集过滤**：可限制 SubAgent 可用的工具
- **模型选择**：可使用不同的模型（成本优化）
- **专用系统提示**：每个 SubAgent 有专门的系统提示
- **透明调用**：通过 Task 工具调用，父 Agent 可看到结果

#### Agent Loop 设计

**核心循环**（推测，基于公开信息）：
```
while not done:
    1. 接收用户输入
    2. 加载相关 Skills（基于 description 匹配）
    3. 构造 LLM 上下文（系统提示 + Skills + 历史 + 工具定义）
    4. 调用 LLM
    5. 解析响应（文本/工具调用/SubAgent 调用）
    6. 执行工具或 SubAgent
    7. 观察结果
    8. 循环或结束
```

**关键设计原则**：
- **隐式状态机**：依赖 LLM 的自然推理，不是显式状态机
- **工具优先**：工具调用是核心机制
- **SubAgent 即工具**：Task 工具是 SubAgent 的入口
- **错误恢复**：自动重试，可配置最大重试次数

#### MCP 集成

**作为 Client**：
- 自动发现 `.mcp.json` 配置
- 支持 stdio、SSE、WebSocket 传输
- 工具动态注入到 LLM 上下文

**作为 Server**：
- Claude Code 自身可作为 MCP server
- 暴露其能力给其他 MCP 客户端

### 1.3 优势 / 劣势

**优势**：
- ✅ **Skills 系统成熟**：标准化、可分享、可版本控制
- ✅ **SubAgents 隔离性好**：每个 SubAgent 独立上下文，避免污染
- ✅ **MCP 深度集成**：作为协议原作者，集成最完善
- ✅ **终端原生**：适合 CLI 工作流，资源占用低
- ✅ **Claude 模型优势**：在代码任务上表现领先
- ✅ **插件生态**：Plugins 系统支持分发

**劣势**：
- ❌ **闭源**：核心代码不公开
- ❌ **依赖 Claude API**：锁定 Anthropic 生态
- ❌ **IDE 集成有限**：主要在终端，IDE 集成需通过扩展
- ❌ **学习曲线**：Skills/SubAgents/Plugins 概念较多

### 1.4 与 CodeSage 的可对比点

| 维度 | Claude Code | CodeSage 建议 |
|------|-------------|---------------|
| Skills 文件格式 | YAML frontmatter + Markdown | 借鉴相同格式，降低学习成本 |
| SubAgents 目录 | `.claude/agents/*.md` | CodeSage 可用类似约定 |
| 上下文隔离 | `context: fork` | 实现 SubAgent 时必须支持 |
| 工具过滤 | `allowed-tools` / `tools:` | 重要安全特性 |
| MCP 集成 | 深度 | CodeSage 早期可专注 MCP client |
| 持久化记忆 | CLAUDE.md | CodeSage 需实现类似机制 |

---

## 2. Cursor

### 2.1 核心能力（2025-2026 最新）

**核心定位**：AI-first 代码编辑器（VSCode Fork），2025 年估值 90 亿美元

**主要能力**：
- **Composer/Agent Mode**：多文件编辑、跨文件重构
- **Tab 模型**：预测式代码补全（Cursor 独有）
- **MCP 支持**：2025 年新增，支持外部工具
- **Background Agents**：后台异步执行任务
- **Bugbot**：自动 bug 检测和修复（2025 新增）
- **Inline Edit / Cmd+K**：内联代码编辑
- **Chat Sidebar**：侧边栏对话
- **@ 引用**：引用文件、目录、文档、网页

### 2.2 Agent 架构特点

**Agent Mode**（vs Chat 模式）：
- **多步骤执行**：可执行规划-行动循环
- **工具调用**：文件读写、终端执行、搜索
- **权限控制**：执行前确认危险操作
- **可恢复**：任务可暂停、恢复、回滚

**Background Agents**（2025 新增）：
- 异步执行长时间任务
- 适合 PR review、测试运行、CI 任务
- 通过 PR 评论触发

**Tab 模型**（Cursor 独有）：
- 基于编辑历史的预测
- 跳跃式补全（不只是连续行）
- 与 Agent 模式互补

### 2.3 优势 / 劣势

**优势**：
- ✅ **编辑器体验最佳**：原生 AI-first IDE
- ✅ **Tab 模型独特**：补全速度快、质量高
- ✅ **Agent Mode 成熟**：多文件编辑能力
- ✅ **生态丰富**：与 VSCode 扩展兼容
- ✅ **社区活跃**：用户基数大

**劣势**：
- ❌ **闭源**：核心技术不公开
- ❌ **价格高**：Pro 计划 $20/月
- ❌ **VSCode 依赖**：基于 Fork，不是原生 VSCode
- ❌ **模型选择有限**：主要优化自家模型
- ❌ **SubAgent 支持有限**：没有 Claude Code 那么成熟

### 2.4 与 CodeSage 的可对比点

- **Tab 模型**：CodeSage 可考虑 IDE 集成时借鉴
- **Background Agents**：可作为异步任务执行的参考
- **权限控制**：执行前确认机制值得学习
- **Agent Mode 工具集**：参考其工具设计

---

## 3. GitHub Copilot Agent Mode

### 3.1 核心能力（2025 GA 后）

**核心定位**：GitHub 官方 AI 编程助手，2025 年 2 月进入 Public Preview，2025 年下半年 GA

**主要能力**：
- **Coding Agent**（2025 新增）：自主 PR 创建、Issue 修复
- **Agent Mode in IDE**：VSCode 中执行多步任务
- **Workspace Agent**：理解整个仓库
- **PR Review**：自动代码审查
- **@workspace**：引用整个代码库
- **Multi-file Edit**：跨文件编辑
- **MCP 支持**：2025 年下半年支持

### 3.2 Agent 架构特点

**Coding Agent**：
- 在 GitHub Actions 中运行
- 通过 Issue/PR 触发
- 自动创建分支、提交、PR
- 人类在 PR review 阶段介入

**IDE Agent Mode**：
- 集成在 VSCode 中
- 工具调用：文件、终端、搜索
- 上下文：当前文件 + workspace
- 权限：危险操作需确认

### 3.3 优势 / 劣势

**优势**：
- ✅ **GitHub 生态集成**：原生 PR/Issue 集成
- ✅ **企业级**：GitHub Enterprise 支持
- ✅ **多模型支持**：GPT-4o、Claude 3.5/4
- ✅ **Coding Agent 独特**：可在云端执行

**劣势**：
- ❌ **SubAgent 支持弱**：没有 Claude Code 的 SubAgent 概念
- ❌ **Skills 系统缺失**：没有标准化能力复用机制
- ❌ **响应速度**：Coding Agent 较慢（云端执行）
- ❌ **IDE 锁定**：主要优化 VSCode

### 3.4 与 CodeSage 的可对比点

- **PR 集成**：CodeSage 可考虑 Git 集成
- **云端执行**：参考 Background Agent 模式
- **Workspace 理解**：@workspace 机制值得参考

---

## 4. Cline / Roo Code

### 4.1 核心能力（2025-2026 最新）

**核心定位**：开源 VSCode 扩展，专注于自主 Agent 能力

**Roo Code**（Cline 的 Fork，更专注 Agent 模式）：
- 2025 年从 Cline 分支出来
- 更强的多模式支持
- 更好的 SubAgent 能力

**主要能力**：
- **多模式架构**：Code/Architect/Ask/Debug/Custom 模式
- **MCP 完整支持**：作为 Client（市场领先）
- **Browser Use**：内置浏览器自动化
- **Terminal Integration**：完整终端访问
- **文件操作**：读写、搜索、diff
- **自定义模式**：用户可定义新模式
- **检查点系统**：任务状态保存/恢复

### 4.2 Agent 架构特点

**多模式架构**（Roo Code 特色）：
- **Code 模式**：默认编码模式
- **Architect 模式**：架构设计、规划
- **Ask 模式**：问答、咨询
- **Debug 模式**：调试、错误分析
- **Orchestrator 模式**（新增）：协调多个 SubAgent

**SubAgent 支持**（Roo Code 特有）：
- 通过自定义模式实现
- 隔离的上下文
- 可配置工具集

**MCP 集成**：
- 支持 stdio、SSE 传输
- 自动发现 `.cline/mcp.json` 或 `~/.cline/mcp.json`
- MCP 服务器市场
- 动态工具注入

### 4.3 优势 / 劣势

**优势**：
- ✅ **开源**：完全可定制
- ✅ **MCP 支持领先**：作为 Client 集成最早
- ✅ **多模式架构**：专业化分工
- ✅ **检查点系统**：任务可恢复
- ✅ **浏览器支持**：内置 browser-use
- ✅ **自定义模式**：灵活扩展

**劣势**：
- ❌ **VSCode 锁定**：仅支持 VSCode
- ❌ **UI 体验**：不如 Cursor 流畅
- ❌ **SubAgent 较新**：功能还在完善
- ❌ **文档分散**：Mode 概念多，文档复杂

### 4.4 与 CodeSage 的可对比点

- **多模式架构**：CodeSage 可设计不同 Agent 模式
- **检查点系统**：CodeSage 任务持久化可参考
- **MCP Client 集成**：参考其 MCP 实现
- **自定义模式**：可作为 Skills 系统的补充

---

## 5. Devin (Cognition Labs)

### 5.1 核心能力（2025-2026 最新）

**核心定位**：全球首个"AI 软件工程师"，自主完成完整开发任务

**主要能力**：
- **自主任务执行**：从需求到 PR 全流程
- **Browser/Shell/Editor 集成**：完整开发环境
- **长期记忆**：跨会话学习用户偏好
- **Planning Tool**：显式任务规划
- **Multi-Agent**：Devin 1.5+ 支持多 Agent 协作
- **企业级部署**：Devin Enterprise
- **Slack/Jira 集成**：从工单直接开发

### 5.2 Agent 架构特点

**核心架构**（基于 Devin 文档）：

1. **规划层（Planner）**：
   - 显式状态机
   - 任务分解为子任务
   - 动态重规划

2. **执行层（Executor）**：
   - 工具调用循环
   - 多步骤执行
   - 错误恢复

3. **记忆层（Memory）**：
   - 长期记忆（跨会话）
   - 短期记忆（当前任务）
   - 工作记忆（当前步骤）

4. **反馈层（Reflection）**：
   - 自评估
   - 错误检测
   - 重试机制

**多 Agent 协作**（Devin 1.5+）：
- **Worker Agents**：执行具体任务
- **Reviewer Agents**：审查代码
- **Coordinator**：协调多个 Worker
- 隔离的 session 上下文

**会话迁移**：
- 完整的会话状态保存
- 可在中断后恢复
- 支持跨设备继续

### 5.3 优势 / 劣势

**优势**：
- ✅ **自主性最强**：从需求到 PR 全流程
- ✅ **长期记忆**：跨会话学习
- ✅ **企业级**：生产环境验证
- ✅ **规划显式**：状态机清晰
- ✅ **多 Agent**：协作能力强

**劣势**：
- ❌ **闭源**：不公开核心技术
- ❌ **价格高**：$500/月起步
- ❌ **响应慢**：完整任务执行时间长
- ❌ **云端依赖**：需联网使用
- ❌ **学习曲线**：配置复杂

### 5.4 与 CodeSage 的可对比点

- **显式规划**：CodeSage 可考虑状态机设计
- **长期记忆**：CodeSage 持久化重点参考
- **多 Agent 协作**：Worker/Reviewer 模式
- **会话迁移**：CodeSage 状态序列化可参考

---

## 6. Aider

### 6.1 核心能力（2025-2026 最新）

**核心定位**：开源 CLI AI 编程工具，专注代码编辑

**主要能力**：
- **Git 集成**：自动 commit、diff 管理
- **Repository Map**：仓库结构理解
- **Multi-file Editing**：跨文件编辑
- **Architect/Editor 双模式**（2025 新增）：规划-执行分离
- **Voice Mode**：语音输入
- **Linting**：自动修复 lint 错误
- **多种 LLM 支持**：Claude、GPT、DeepSeek、本地模型

### 6.2 Agent 架构特点

**Architect/Editor 模式**（2025 年 1 月发布）：
- **Architect**：高级模型（如 Claude）做规划
- **Editor**：便宜模型（如 DeepSeek）执行编辑
- 成本优化：仅在需要时使用强模型
- 清晰的关注点分离

**Repository Map**：
- 轻量级仓库结构表示
- 节省 token（相比完整文件读取）
- 动态更新

**Git 工作流**：
- 每个修改自动 commit
- 清晰的 diff 历史
- 易于回滚

### 6.3 优势 / 劣势

**优势**：
- ✅ **开源**：完全可定制
- ✅ **轻量**：CLI 工具，资源占用低
- ✅ **Git 集成**：版本控制友好
- ✅ **多模型**：不锁定供应商
- ✅ **Architect/Editor 模式**：成本优化

**劣势**：
- ❌ **SubAgent 不支持**：单 Agent 设计
- ❌ **MCP 不支持**：无 MCP 集成
- ❌ **无 Skills 系统**：能力复用弱
- ❌ **CLI 限制**：无 GUI

### 6.4 与 CodeSage 的可对比点

- **Architect/Editor 模式**：CodeSage 可考虑分层模型使用
- **Repository Map**：CodeSage 上下文压缩可参考
- **Git 集成**：CodeSage 版本控制可参考

---

## 7. Continue.dev

### 7.1 核心能力（2025-2026 最新）

**核心定位**：领先的开源 AI 代码助手，支持自定义 Agent 和工作流

**主要能力**：
- **开源**：完全可定制
- **多 IDE 支持**：VSCode、JetBrains
- **多模型**：Claude、GPT、本地模型
- **Agent Hub**：集中管理 Agent
- **MCP 集成**：作为 Client
- **自定义 Agent**：用户可定义专用 Agent
- **环境隔离**：每个 Agent 独立环境
- **Source-Controlled Agents**：Agent 配置可版本控制

### 7.2 Agent 架构特点

**Agent 系统**：
- **Built-in Agents**：内置常用 Agent
- **Custom Agents**：用户自定义
- **Source-Controlled**：Agent 配置在 git 中
- **Hub**：集中管理（团队共享）

**配置文件结构**：
```yaml
# .continue/agents/custom-agent.yaml
name: code-reviewer
description: Reviews code
version: 0.0.1
schema: v1
models:
  - name: claude-4-sonnet
    provider: anthropic
    model: claude-sonnet-4-5
tools:
  - built-in: Read
  - built-in: Grep
prompts:
  - name: review
    description: Review code
    prompt: You are a senior reviewer...
```

**MCP 集成**：
- 支持 MCP Client
- 工具动态加载
- 与 Claude Code MCP 兼容

### 7.3 优势 / 劣势

**优势**：
- ✅ **完全开源**：Apache 2.0
- ✅ **多 IDE 支持**：VSCode + JetBrains
- ✅ **Agent Hub**：团队协作
- ✅ **Source-Controlled**：版本控制友好
- ✅ **自定义 Agent**：灵活

**劣势**：
- ❌ **SubAgent 概念弱**：没有 Claude Code 那种隔离
- ❌ **Skills 系统缺失**：能力复用机制弱
- ❌ **文档较散**：功能多但文档不够集中

### 7.4 与 CodeSage 的可对比点

- **Agent Hub 模式**：CodeSage 可考虑中心化 Agent 管理
- **Source-Controlled 配置**：配置文件应纳入版本控制
- **多 IDE 支持**：CodeSage 后期可考虑

---

## 8. OpenHands (前 OpenDevin)

### 8.1 核心能力（2025-2026 最新）

**核心定位**：开源软件工程 Agent 框架，SWE-bench 顶级表现者

**主要能力**：
- **Software Agent SDK**：Python 和 REST API
- **任务规划与分解**：自动任务规划
- **自动上下文压缩**：节省 token
- **安全分析**：内置安全检查
- **Agent-Computer Interface**：强 ACI 设计
- **MCP 集成**：作为 Client
- **多 LLM 支持**：Claude、GPT、Qwen、Devstral
- **开源**：MIT 许可证

### 8.2 Agent 架构特点

**Software Agent SDK**（V1）：
- **统一 Python API**：本地或云端运行
- **预定义工具**：Bash、文件编辑、浏览器、MCP
- **REST Agent Server**：生产级部署
- **Docker/K8s 支持**

**Agent 特性**：
- **任务规划**：显式规划
- **上下文压缩**：自动管理
- **安全分析**：内置安全检查
- **强 ACI**：类似 human 的 shell 体验

**基准表现**：
- SWE-bench 顶级
- SWT-bench
- multi-SWE-bench

### 8.3 优势 / 劣势

**优势**：
- ✅ **完全开源**：MIT 许可证
- ✅ **SDK 设计**：易于嵌入
- ✅ **基准表现领先**：学术认可
- ✅ **多 LLM 支持**：不锁定
- ✅ **生产级**：Docker/K8s 支持

**劣势**：
- ❌ **SubAgent 支持弱**：没有 Claude Code 那种完善
- ❌ **Skills 系统缺失**
- ❌ **MCP 支持较新**：2025 年才完善
- ❌ **企业版较新**：生态还在发展

### 8.4 与 CodeSage 的可对比点

- **SDK 优先**：CodeSage 应设计良好的 API
- **开源策略**：CodeSage 可考虑开源路径
- **基准测试**：CodeSage 应有量化指标
- **ACI 设计**：CodeSage 工具设计可参考

---

## 9. Hermes Agent (NousResearch)

### 9.1 核心能力（2025-2026 最新）

**核心定位**：NousResearch 的开源 Agent 框架，CodeSage 直接参考对象

**主要能力**：
- **Function Calling**：原生支持工具调用
- **Agent Loop**：标准 ReAct/CoT 循环
- **多模型支持**：Hermes 系列模型 + 其他
- **可定制**：完全开源
- **MCP 兼容**：2025 年支持

### 9.2 Agent 架构特点

**核心设计**：
- **显式 Agent Loop**：清晰的循环结构
- **工具注册系统**：动态工具加载
- **记忆管理**：短期 + 长期记忆
- **规划能力**：任务分解

**与 CodeSage 的关系**：
- CodeSage 直接参考其架构
- 应有相似的设计哲学
- 共享开源精神

### 9.3 优势 / 劣势

**优势**：
- ✅ **完全开源**：可深度定制
- ✅ **轻量**：无外部依赖
- ✅ **透明**：代码清晰
- ✅ **社区**：NousResearch 社区

**劣势**：
- ❌ **生态小**：用户基数不大
- ❌ **文档较少**：需读源码
- ❌ **SubAgent 支持有限**

### 9.4 与 CodeSage 的可对比点

- **直接参考对象**：CodeSage 应保留其优点
- **架构相似性**：避免重复造轮子
- **改进空间**：可补足 Hermes 的不足

---

## 10. LangGraph / LangChain Agents

### 10.1 核心能力（2025-2026 最新）

**核心定位**：构建有状态、可控的 Agent 运行时框架

**主要能力**：
- **Cyclical Workflows**：支持循环工作流（vs 传统 DAG）
- **Stateful Agents**：持久化状态
- **Human-in-the-Loop**：人工介入
- **Time Travel**：回溯到任意步骤
- **Streaming**：流式输出
- **LangGraph Studio**：可视化调试

### 10.2 Agent 架构特点

**核心概念**：
- **Graph**：节点（Node）+ 边（Edge）
- **State**：全局状态对象
- **Nodes**：Agent、Tool、Function
- **Edges**：条件边、入口边
- **Cycles**：支持循环（vs 传统链）

**显式状态机**（与 Claude Code 不同）：
```
START → Plan → Execute → Observe → [Continue/End]
                  ↑           |
                  └───────────┘ (循环)
```

**优势**：
- 显式控制流
- 易于调试
- 可视化

**SubAgent 支持**：
- 通过子图（Subgraph）实现
- 完全隔离的状态
- 可独立部署

### 10.3 优势 / 劣势

**优势**：
- ✅ **显式状态机**：可控性强
- ✅ **可视化调试**：LangGraph Studio
- ✅ **生态丰富**：LangChain 生态
- ✅ **生产级**：广泛使用

**劣势**：
- ❌ **学习曲线**：Graph 概念
- ❌ **过度工程**：简单任务复杂
- ❌ **SubAgent 概念弱**：用 Subgraph 模拟

### 10.4 与 CodeSage 的可对比点

- **显式状态机 vs 隐式循环**：CodeSage 需选择
- **可视化调试**：CodeSage 可借鉴
- **State 管理**：持久化状态可参考
- **时间旅行**：调试和回滚可参考

---

## 11. AutoGen (Microsoft)

### 11.1 核心能力（2025-2026 最新）

**核心定位**：Microsoft 的多 Agent 编排框架

**主要能力**：
- **多 Agent 对话**：Agent 间对话
- **AutoGen Studio**：可视化编排
- **GroupChat 模式**：多 Agent 群聊
- **代码执行**：内置 Python 执行
- **MCP 支持**（2025 新增）
- **可定制 Agent**：灵活的角色定义

### 11.2 Agent 架构特点

**核心模型**：
- **ConversableAgent**：基础 Agent
- **AssistantAgent**：LLM 驱动的 Agent
- **UserProxyAgent**：人类代理
- **GroupChatManager**：群聊管理

**多 Agent 协作**：
```
GroupChat:
  - Architect (规划)
  - Coder (编码)
  - Reviewer (审查)
  - Tester (测试)
  → GroupChatManager 协调
```

**MCP 集成**（2025）：
- 作为 Client
- 工具动态注入

### 11.3 优势 / 劣势

**优势**：
- ✅ **多 Agent 成熟**：研究深入
- ✅ **GroupChat 模式**：群聊协作
- ✅ **可视化**：AutoGen Studio
- ✅ **Microsoft 背书**

**劣势**：
- ❌ **SubAgent 隔离弱**：共享上下文
- ❌ **Skills 系统缺失**
- ❌ **学习曲线**：概念多

### 11.4 与 CodeSage 的可对比点

- **多 Agent 协作**：CodeSage 可借鉴
- **GroupChat 模式**：团队 Agent 协调
- **角色定义**：Agent 系统提示工程

---

## 12. OpenAI Codex CLI / Operator

### 12.1 核心能力（2025-2026 最新）

**Codex CLI**（2025）：
- 终端原生 Agent
- 与 ChatGPT 订阅绑定
- 完整开发环境

**Operator**（2025 年 1 月）：
- 浏览器中的 AI Agent
- 可执行网页操作
- Computer Use 能力

**主要能力**：
- **Codex CLI**：终端 Agent
- **Operator**：浏览器 Agent
- **Codex 模型**：codex-1、gpt-5-codex
- **多模态**：截图、视觉理解

### 12.2 Agent 架构特点

**Codex CLI**：
- 类似 Claude Code 的终端 Agent
- 与 ChatGPT 生态集成
- 工具：文件、终端、搜索

**Operator**：
- 基于 Computer Use
- 浏览器自动化
- 视觉模型驱动

### 12.3 优势 / 劣势

**优势**：
- ✅ **ChatGPT 生态**：用户基数大
- ✅ **多模态**：视觉能力
- ✅ **Operator 独特**：浏览器操作

**劣势**：
- ❌ **闭源**
- ❌ **SubAgent 弱**
- ❌ **Skills 系统缺失**
- ❌ **CLI 功能较新**

### 12.4 与 CodeSage 的可对比点

- **多模态**：CodeSage 长期可考虑视觉
- **Operator 模式**：浏览器自动化可参考

---

## 13. Gemini CLI

### 13.1 核心能力（2025-2026 最新）

**核心定位**：Google 的终端 AI Agent（2025 年发布）

**主要能力**：
- **Gemini 模型集成**：Gemini 2.5 Pro/Flash
- **大上下文窗口**：1M-2M tokens
- **MCP 支持**：原生
- **多模态**：图像、PDF、视频
- **工具调用**：文件、终端、搜索
- **Google 生态**：Cloud、Workspace 集成

### 13.2 Agent 架构特点

**核心设计**：
- 类似 Claude Code 的 Agent Loop
- MCP 深度集成
- Google Cloud 集成

**大上下文优势**：
- 1M+ token 上下文
- 适合大型仓库分析
- 减少压缩需求

### 13.3 优势 / 劣势

**优势**：
- ✅ **超大上下文**：独特优势
- ✅ **多模态**：图像、PDF
- ✅ **Google 生态**
- ✅ **MCP 集成**

**劣势**：
- ❌ **SubAgent 弱**
- ❌ **Skills 系统缺失**
- ❌ **较新**：生态还在发展

### 13.4 与 CodeSage 的可对比点

- **大上下文策略**：CodeSage 上下文压缩 vs 大窗口
- **MCP 集成**：参考其实现
- **多模态**：CodeSage 长期方向

---

## 14. 能力维度对比矩阵

| 能力维度 | Claude Code | Cursor | Copilot | Cline/Roo | Devin | Aider | Continue | OpenHands | LangGraph | AutoGen |
|---------|------------|--------|---------|-----------|-------|-------|----------|-----------|-----------|---------|
| **Agent Loop** | 隐式循环 | Agent Mode | Agent Mode | 多模式循环 | 显式状态机 | 简单循环 | 循环 | 循环 | 显式状态机 | 群聊 |
| **SubAgent 隔离** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| **工具集过滤** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **MCP Client** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ❌ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| **MCP Server** | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐ | ⭐ | ⭐⭐ | ❌ | ⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐ |
| **Skills 系统** | ⭐⭐⭐⭐⭐ | ❌ | ❌ | ⭐⭐ (Modes) | ⭐⭐ | ❌ | ⭐⭐ | ❌ | ❌ | ❌ |
| **持久化记忆** | ⭐⭐⭐⭐ (CLAUDE.md) | ⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ (State) | ⭐⭐⭐ |
| **工具 Guardrails** | ⭐⭐⭐⭐ (Permissions) | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **自我进化** | ⭐⭐ (Skills 可写) | ⭐ | ⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐ | ⭐⭐ | ⭐⭐ | ❌ | ❌ |
| **Inline Chat** | ❌ (CLI) | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ❌ | ❌ | ⭐⭐⭐ | ❌ | ❌ | ❌ |
| **Sidebar Chat** | ❌ (CLI) | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ❌ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ | ⭐⭐ |
| **会话持久化** | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ (Checkpoints) | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| **上下文压缩** | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **错误恢复** | ⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| **开源** | ❌ | ❌ | ❌ | ⭐⭐⭐⭐⭐ | ❌ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |

---

## 15. CodeSage 设计建议

基于以上研究，CodeSage 应重点考虑以下设计决策：

### 15.1 必须实现的核心特性

1. **Skills 系统**（借鉴 Claude Code）：
   - 使用 SKILL.md 格式（YAML frontmatter + Markdown）
   - 支持 `allowed-tools` 和 `context: fork`
   - 自动发现和按需加载
   - 可分享、可版本控制

2. **SubAgent 系统**（借鉴 Claude Code）：
   - 使用 Markdown 配置文件
   - 完全隔离的 session 上下文
   - 工具集过滤
   - 模型选择灵活

3. **MCP 集成**（行业标准）：
   - 优先作为 Client
   - 支持 stdio 和 SSE 传输
   - 工具动态注入

4. **持久化记忆**（借鉴 Devin + LangGraph）：
   - 短期记忆（当前任务）
   - 长期记忆（跨会话）
   - 工作记忆（当前步骤）
   - 可序列化和迁移

5. **检查点系统**（借鉴 Cline + LangGraph）：
   - 任务状态保存
   - 支持恢复和回滚
   - 时间旅行（调试）

### 15.2 架构选择

**Agent Loop 设计**：
- **建议**：参考 Claude Code 的隐式循环 + 显式状态转换
- **理由**：简单任务用循环，复杂任务用状态机
- **实现**：状态机框架 + 循环执行器

**SubAgent 隔离**：
- **建议**：完全独立的 session（每个 SubAgent 有自己的上下文窗口）
- **避免**：共享上下文（AutoGen 的问题）

### 15.3 差异化定位

CodeSage 应在以下方面做出差异化：

1. **Hermes 兼容性**：作为开源框架，CodeSage 应与 Hermes Agent 兼容
2. **MCP 优先**：从一开始就深度集成 MCP
3. **开源**：完全开源，MIT 许可证
4. **多语言 SDK**：Python、TypeScript、Rust（参考 LangGraph）
5. **可视化**：LangGraph Studio 式调试工具
6. **基准测试**：参考 OpenHands，提供量化指标

### 15.4 优先级排序

**P0（必须）**：
- Skills 系统（SKILL.md）
- SubAgent 隔离
- MCP Client 集成
- 基础 Agent Loop

**P1（重要）**：
- 持久化记忆
- 工具 Guardrails
- 检查点系统
- 上下文压缩

**P2（增强）**：
- MCP Server 能力
- 多 IDE 集成
- 可视化调试
- 多模型支持

**P3（长期）**：
- 多模态（图像、语音）
- Computer Use（浏览器自动化）
- 云端执行

---

## 附录：参考资料

### 官方文档
- **Claude Code Skills**: https://docs.claude.com/en/docs/claude-code/skills
- **Claude Code Sub-agents**: https://docs.claude.com/en/docs/claude-code/sub-agents
- **Cline Architecture**: https://docs.cline.bot/architecture
- **Devin Architecture**: https://docs.devin.ai/essentials/architecture
- **OpenHands SDK**: https://docs.openhands.dev/sdk.md
- **Continue.dev**: https://continue.dev/llms-full.txt
- **LangGraph**: https://blog.langchain.com/introducing-langgraph/

### 关键概念定义

**Agent Loop**：Agent 自主执行的循环，通常包括 规划-执行-观察 三步

**SubAgent**：从主 Agent 派生的子 Agent，通常有隔离的上下文和工具集

**Skills**：可重用的能力单元，通常通过文件定义

**MCP (Model Context Protocol)**：Anthropic 提出的 Agent 工具集成标准协议

**ACI (Agent-Computer Interface)**：Agent 与计算机交互的接口设计

**SWE-bench**：软件工程任务基准测试

**Context Fork**：Claude Code Skills 的特性，允许在独立上下文中运行

**Checkpoints**：任务状态的保存点，用于恢复和回滚

---

**报告完成时间**：2025-2026
**数据来源**：官方文档、官方网站
**CodeSage 重点参考**：Claude Code Skills/Subagents 系统
