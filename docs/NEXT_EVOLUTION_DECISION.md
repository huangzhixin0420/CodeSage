# CodeSage 下一步演进方向决策

> **决策依据**：基于《CodeSage能力分析报告》与当前项目实际进度综合研判
> **决策日期**：2026-05-31
> **适用版本**：CodeSage 2026.1.2+
> **文档状态**：正式决策 / 待执行

---

## 一、决策背景

### 1.1 能力分析报告核心结论

《CodeSage能力分析报告》（2025-12-11）对项目进行了全方位对标分析，核心结论如下：

**核心优势（保持）**：
- IntelliJ深度集成（PSI代码理解、VirtualFileSystem同步）
- MCP协议完整支持（2024-11-05协议、三种传输方式）
- 多Agent编排框架（Planner/Coder/Reviewer等预设角色）
- 多级上下文管理（keepRecent/compressContext/ragRetrieval/hybridTruncate）
- Skill/Rule双轨扩展系统

**关键差距（追赶）**：
- 🔴 多模态能力（竞品已支持图像理解）
- 🔴 实时协作功能
- 🔴 更丰富的工具生态（数据库/API测试/容器管理）
- 🟡 企业级安全合规（PII检测/审计报告/权限管理）
- 🟡 自适应学习能力

### 1.2 当前实际进度

截至 **2026-05-24**，以下基础能力已全面落地（174个测试全部通过）：

| 阶段 | 目标 | 核心交付 | 状态 |
|------|------|---------|------|
| Phase 1 | Agent不死 | EnhancedAgentLoop + AgentErrorRecovery + IterationBudget | ✅ 已完成 |
| Phase 2 | Agent记得 | MemoryProvider + ContextCompressor + TokenEstimator | ✅ 已完成 |
| Phase 3 | Agent成长 | DynamicSkillRegistry + SkillCurator + SkillProvenance | ✅ 已完成 |
| Phase 4 | Agent分工 | SubAgentExecutor + delegate_task（Kanban 部分于 2026-06 移除） | ✅ 已完成 |
| 优化1 | 进度可视 | SubAgentProgressPanel + 流式事件 | ✅ 已完成 |
| 优化2 | 看板管理 | _（2026-06 移除：未达价值预期）_ | 🗑️ |
| 优化3 | 工具扩充 | 6 → 24 个 IDE 工具 | ✅ 已完成 |
| 优化4 | MCP生态 | MCPServerManager + 配置持久化集成 | ✅ 已完成 |
| 优化5 | Prompt工程 | PromptTemplate + PromptAssembler + 6种角色 | ✅ 已完成 |
| 优化6 | Guardrails | SensitiveActionPolicy + OutputTruncator + ToolGuardrails | ✅ 已完成 |
| 优化7 | AST分析 | PSIAnalyzer + SymbolIndex + SemanticSearch | ✅ 已完成 |
| 优化8 | 对话持久化 | ConversationPersistence + Exporter + SessionRestore | ✅ 已完成 |
| 优化9 | 性能优化 | ResponseCache + ConcurrentLimiter + ConnectionWarmup | ✅ 已完成 |
| 优化10 | 可观测性 | StructuredLogger + MetricsCollector + ExecutionTracer | ✅ 已完成 |

**结论**：能力分析报告中 Phase 1（健壮性提升）和 Phase 2（上下文增强）的核心内容已完全实现，项目进入**功能扩展与差异化竞争**阶段。

### 1.3 现有演进路线图

`EVOLUTION_ROADMAP.md` 已规划方向11-22，优先级分布如下：

| 优先级 | 方向 | 预估工作量 |
|--------|------|-----------|
| P0 | 模型智能路由（ROUTER） | 2周 |
| P0 | Inline Chat / Editor集成（INLINE） | 3周 |
| P0 | RAG项目知识库（RAG） | 3周 |
| P1 | 自主Agent模式（AUTO） | 3周 |
| P1 | 本地模型支持（LOCAL） | 1周 |
| P1 | 安全与合规（SECURITY） | 2周 |
| P2 | 多模态（MULTIMODAL） | 3周 |
| P2 | 实时代码补全（INLINE_COMPLETION） | 4周 |
| P2 | 测试生成与验证（TESTGEN） | 2周 |
| P2 | 团队知识共享（TEAM） | 2周 |
| P2 | 语音交互（VOICE） | 2周 |
| P2 | CI/CD集成（CICD） | 2周 |

另有 **UI/UX优化计划**（21项优化，分3阶段，约14个工作日）正在进行中。

---

## 二、战略总览

### 2.1 总体战略

> **巩固"最强IntelliJ Agent插件"定位，以 Editor集成 + RAG知识库 为核心体验突破口，以 多模态 + MCP市场 构建差异化护城河。**

### 2.2 决策原则

1. **用户感知优先**：直接提升日活和留存的功能排在最前
2. **复用现有基础**：优先复用 PSI/MCP/SubAgent/Guardrails 等已成熟的能力
3. **企业市场卡位**：安全合规是B端付费的前提，必须在差异化功能之前完成
4. **差异化聚焦**：MCP生态是CodeSage独有优势（竞品均无），应持续强化
5. **不做重复造轮子**：已有成熟方案的方向（如Inline Completion对标Copilot）谨慎投入

---

## 三、分阶段演进路线

### Phase A：当下（立即执行）—— 体验打磨

**目标**：完成已启动的UI/UX优化，消除影响用户留存的核心缺陷。

| 任务 | 来源 | 状态 | 预计完成 |
|------|------|------|---------|
| UI/UX优化计划 阶段一（P0） | `UI_UX_OPTIMIZATION_PLAN.md` | 待执行 | 3个工作日 |
| UI/UX优化计划 阶段二（P1） | `UI_UX_OPTIMIZATION_PLAN.md` | 待执行 | 7个工作日 |
| UI/UX优化计划 阶段三（P2） | `UI_UX_OPTIMIZATION_PLAN.md` | 待执行 | 4个工作日 |

**关键交付**：
- 输入框字符计数与上限警告
- 思考过程默认折叠 + 全局显示开关
- 空状态引导页面
- 流式输出加载动画
- 快捷键支持（Ctrl+Enter发送、Escape停止）
- 设置页字段联动与实时校验

**准入条件**：全部21项优化完成，视觉回归测试通过，无P0/P1级Bug。

---

### Phase B：短期（1-2个月）—— 核心体验突破

**目标**：打造用户感知最强、日活提升最直接的核心功能。

按优先级排序：

#### B1. Inline Chat / Editor深度集成（P0）

| 属性 | 内容 |
|------|------|
| **代号** | INLINE |
| **预估工作量** | 3周 |
| **依赖** | 无（复用AgentCore、IDETools、ToolGuardrails） |
| **竞品参考** | Cursor / GitHub Copilot Inline Chat |

**为什么排第一**：
- 用户无需离开编辑上下文即可获取AI辅助，直接降低上下文切换成本
- 是Cursor/Copilot的标配功能，缺失会导致用户流失
- 可100%复用现有AgentCore.chatWithTools()和IDETools能力

**核心交付**：
- InlineChatBubble UI组件（选区下方弹出）
- 编辑器Action体系（Explain/Refactor/Fix/Test）
- Diff预览与一键应用（支持UndoableAction）
- 代码Lens集成（方法/类上方AI摘要）

#### B2. RAG项目知识库（P0）

| 属性 | 内容 |
|------|------|
| **代号** | RAG |
| **预估工作量** | 3周 |
| **依赖** | SymbolIndex、SemanticSearch（已有基础） |
| **竞品参考** | Cursor @codebase / Copilot Chat with workspace |

**为什么排第二**：
- 让AI"理解"整个项目，问答准确率从文本搜索的~40%跃升至RAG的~80%
- SymbolIndex和SemanticSearch已为向量化检索打下基础
- 与Auto Mode配合可产生乘数效应

**核心交付**：
- 文档分块器（按类/方法/段落分块）
- Embedding接口 + SQLite向量存储（无额外依赖）
- 混合检索引擎（BM25 + 向量融合）
- 增量索引（文件保存时自动更新）
- 自动上下文注入（AgentCore集成）

#### B3. 模型智能路由（P0）

| 属性 | 内容 |
|------|------|
| **代号** | ROUTER |
| **预估工作量** | 2周 |
| **依赖** | ModelGateway、ModelRegistry（已有基础） |
| **竞品参考** | 无直接竞品，属于降本增效基础设施 |

**为什么排第三**：
- 成本降低30-50%（简单任务用轻量模型）
- 自动故障切换提升可用性
- 为后续多模型场景（本地+云端混合）打基础

**核心交付**：
- 基于规则的路由策略引擎（任务复杂度/模型能力/成本/延迟）
- 模型能力标签体系
- 健康检查与熔断机制
- 成本追踪面板

---

### Phase C：中期（2-3个月）—— 企业级与自动化

**目标**：满足企业客户准入门槛，实现复杂任务自动化。

#### C1. 安全与合规（P1）

| 属性 | 内容 |
|------|------|
| **代号** | SECURITY |
| **预估工作量** | 2周 |
| **依赖** | ToolGuardrails、StructuredLogger（已有基础） |
| **竞品参考** | GitHub Copilot Enterprise |

**为什么排第一**：
- 企业客户采购的安全审计前提
- 防止敏感信息泄露到第三方LLM（GDPR/等保要求）
- ToolGuardrails和StructuredLogger已为审计追踪打下基础

**核心交付**：
- PII检测引擎（API Key/密码/邮箱/身份证/信用卡）
- 脱敏策略（Mask/Hash/Remove）
- 请求拦截器（数据发送到LLM前扫描）
- 审计报告导出（按时间范围/敏感操作统计/异常行为检测）

#### C2. 自主Agent模式（P1）

| 属性 | 内容 |
|------|------|
| **代号** | AUTO |
| **预估工作量** | 3周 |
| **依赖** | SubAgentExecutor、TaskPlanner、AgentErrorRecovery |
| **竞品参考** | Claude Code（命令行自主执行） |

**为什么排第二**：
- 复杂任务从手动多步进化到"一键执行"
- 已有SubAgent/ErrorRecovery/Guardrails全套基础
- Human-in-the-loop设计降低风险，企业可接受

**核心交付**：
- 验证器体系（编译/测试/Lint/AI自我审查）
- 自主执行循环（Planner → Executor → Validator → Reporter）
- 人类确认面板（高/中/低置信度分级确认）
- 自动回滚能力（写操作前快照备份）

#### C3. 本地模型支持（P1）

| 属性 | 内容 |
|------|------|
| **代号** | LOCAL |
| **预估工作量** | 1周 |
| **依赖** | OpenAICompatibleAdapter（已支持） |
| **竞品参考** | Continue.dev / Ollama Integration |

**为什么排第三**：
- 隐私敏感场景的刚需
- 工作量极小（OpenAICompatibleAdapter已支持自定义base URL）
- 离线可用，零API成本

**核心交付**：
- 本地模型自动发现（Ollama/LM Studio/vLLM端口探测）
- 一键配置模板
- 隐私模式切换（Cloud Only / Hybrid / Local Only）

---

### Phase D：长期（3-6个月）—— 差异化护城河

**目标**：构建竞品难以复制的独特竞争力。

#### D1. 多模态支持（P2）

| 属性 | 内容 |
|------|------|
| **代号** | MULTIMODAL |
| **预估工作量** | 3周 |
| **依赖** | 模型适配器扩展 |
| **竞品参考** | Claude 3.5 / GPT-4o / Gemini Pro Vision |

**为什么重点投入**：
- 能力分析报告明确列为"需要追赶的差距"之首
- 报错截图分析、UI设计稿转代码、架构图理解是开发者高频需求
- CodeSage作为IDE插件，截图→代码的闭环体验天然优于纯CLI工具

**核心交付**：
- 图片消息类型（粘贴/拖拽/截图）
- 模型适配器扩展（支持image_url）
- 截图快捷键（macOS截图API集成）
- 图片上传/显示UI

#### D2. MCP工具市场（P2，差异化方向）

| 属性 | 内容 |
|------|------|
| **代号** | MCP_MARKET |
| **预估工作量** | 4周 |
| **依赖** | MCP生态集成（已完成） |
| **竞品参考** | 无直接竞品——独立生态 |

**为什么重点投入**：
- MCP是CodeSage独有优势（Cursor/Copilot/Claude Code均不支持）
- 当前MCP服务器配置为手动JSON，门槛高
- 可视化市场 + 一键安装可构建独立开发者生态

**核心交付**：
- MCP服务器可视化市场（分类/搜索/评分）
- 一键安装/卸载/更新
- 社区贡献入口（服务器描述标准化）
- 与SkillRegistry自动同步

#### D3. 测试生成与验证（P2）

| 属性 | 内容 |
|------|------|
| **代号** | TESTGEN |
| **预估工作量** | 2周 |
| **依赖** | Auto Mode（推荐）、IDETools.runCommand |
| **竞品参考** | Cursor Tab / GitHub Copilot Test Generation |

**核心交付**：
- 测试框架自动检测（JUnit/TestNG/pytest/Jest等）
- 自动生成单元测试 + 自动运行
- 失败分析 + 修复循环
- 覆盖率反馈与补充建议

#### D4. 实时代码补全（P2，谨慎投入）

| 属性 | 内容 |
|------|------|
| **代号** | INLINE_COMPLETION |
| **预估工作量** | 4周 |
| **依赖** | 大量IDE API |
| **竞品参考** | GitHub Copilot / Codeium |

**风险提示**：
- IntelliJ Inline Completion API较新且变化快
- 需要大量调优才能达到可用精度
- 与Copilot直接竞争，差异化不足

**建议**：作为可选探索方向，投入1-2周做MVP验证，精度不达标则暂缓。

---

### Phase E：暂缓/可选方向

以下方向**当前不推荐投入核心资源**：

| 方向 | 暂缓理由 |
|------|---------|
| 语音交互（VOICE） | 锦上添花，跨平台实现成本高（macOS/Windows/Linux差异大） |
| CI/CD集成（CICD） | 垂直场景，优先级低于核心编码体验 |
| 团队知识共享（TEAM） | 依赖B端客户基础，待产品成熟后推进 |
| 实时协作 | 技术复杂度极高，且VS Code Live Share已垄断该市场 |
| 自适应学习 | 需要大量用户数据积累，当前阶段ROI低 |

---

## 四、执行顺序总图

```
现在 ────────────────────────────────────────────────────────> 6个月后
 │                                                              │
 ▼                                                              ▼
┌──────────┐    ┌──────────────────────┐    ┌──────────────────────────┐
│ Phase A  │───>│     Phase B          │───>│       Phase C            │
│ UI/UX优化 │    │  核心体验突破 (1-2月)  │    │   企业级与自动化 (2-3月)  │
│ ~2周     │    │                      │    │                          │
│          │    │ 1. Inline Chat       │    │ 1. 安全与合规             │
│ 21项优化  │    │ 2. RAG知识库         │    │ 2. 自主Agent模式          │
│ 体验打磨  │    │ 3. 模型智能路由       │    │ 3. 本地模型支持           │
└──────────┘    └──────────────────────┘    └──────────────────────────┘
                       │                              │
                       └──────────────────────────────┘
                                      │
                                      ▼
                       ┌──────────────────────────┐
                       │       Phase D            │
                       │   差异化护城河 (3-6月)     │
                       │                          │
                       │ 1. 多模态支持             │
                       │ 2. MCP工具市场 ⭐ 差异化    │
                       │ 3. 测试生成与验证          │
                       │ 4. Inline Completion (MVP)│
                       └──────────────────────────┘
```

---

## 五、资源分配建议

### 5.1 人力分配（假设2名全职开发）

| 阶段 | 人员A | 人员B |
|------|-------|-------|
| Phase A | UI/UX优化（Web端） | UI/UX优化（Swing端） |
| Phase B | Inline Chat + 模型路由 | RAG知识库 |
| Phase C | 安全合规 + 本地模型 | 自主Agent模式 |
| Phase D | 多模态 + MCP市场 | 测试生成 + Inline Completion MVP |

### 5.2 里程碑检查点

| 里程碑 | 时间 | 验收标准 |
|--------|------|---------|
| M1 | +2周 | UI/UX优化全部完成，无P0/P1 Bug |
| M2 | +6周 | Inline Chat + RAG知识库可用，内部测试通过 |
| M3 | +10周 | 模型路由 + 安全合规 + Auto Mode可用 |
| M4 | +16周 | 多模态 + MCP市场上线，发布2026.2版本 |

---

## 六、风险评估与应对

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| Inline Chat与IDE API冲突 | 高 | 中 | 提前做MVP验证；关注IntelliJ EAP版本兼容性 |
| RAG向量存储性能问题 | 中 | 中 | 第一阶段用SQLite+余弦距离，监控性能后再决定是否引入专用向量库 |
| 多模态模型适配复杂 | 中 | 高 | 优先支持GPT-4o和Claude 3.5，本地LLaVA作为可选 |
| MCP市场冷启动 | 低 | 高 | 先集成官方MCP服务器清单，再开放社区贡献 |
| 企业安全合规标准变化 | 中 | 低 | PII检测采用可配置正则+插件化架构，便于快速响应新规 |

---

## 七、附录

### 7.1 参考文档

- [CodeSage能力分析报告](/Users/leo/temp_files/CodeSage能力分析报告.md)
- [EVOLUTION_ROADMAP.md](./EVOLUTION_ROADMAP.md) — 方向11-22详细技术设计
- [OPTIMIZATION_PROGRESS.md](./OPTIMIZATION_PROGRESS.md) — 已完成工作记录
- [UI_UX_OPTIMIZATION_PLAN.md](./UI_UX_OPTIMIZATION_PLAN.md) — UI/UX优化计划

### 7.2 决策修订记录

| 日期 | 修订内容 | 决策人 |
|------|---------|--------|
| 2026-05-31 | 初始版本 | Kimi Code CLI |

---

*本文档为正式演进决策，后续迭代应基于此框架展开详细技术设计。*
