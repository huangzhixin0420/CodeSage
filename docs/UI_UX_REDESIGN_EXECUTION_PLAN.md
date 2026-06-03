# CodeSage UI/UX & 配置体系重构 — 执行计划

> 上游文档:`UI_UX_REDESIGN_PROPOSAL.md`
> 角色:Tech Lead + 实施 Owner
> 目的:把设计方案拆成可分派、可追踪、可验收的具体工作项
> 日期:2026-06-03
> 版本:v1.0(待评审)

---

## 〇、文档结构

1. 执行总览
2. 工作分解结构(WBS)
3. 时间线与里程碑
4. 任务依赖图
5. 优先级矩阵(MoSCoW)
6. 每个 Phase 的详细任务卡
7. 资源分配
8. 决策点(需要用户/产品确认)
9. 风险登记表
10. 进度跟踪机制
11. 验收检查表
12. 回滚预案
13. 沟通节奏

---

## 一、执行总览

### 1.1 关键指标

| 指标 | 数值 |
|---|---|
| 总工时 | **~20 工作日**(单人全职)/ **~12 工作日**(双人并行) |
| 阶段数 | **5 个 Phase** |
| 触达文件 | 新增 ~30,修改 ~15,废弃 ~12 |
| 预计 PR 数 | 7-9 个(每 Phase 1-2 个) |
| 预计 commit 数 | 50-80 |
| 测试用例新增 | 60+ 单元 + 30+ 手动场景 |
| 风险等级 | 中(架构调整在 Phase 4,前 3 个 Phase 风险低) |
| 业务影响窗口 | 0(每 Phase 独立可发) |

### 1.2 推进原则

1. **渐进式交付**:每 Phase 结束都能编译通过、测试通过、用户能正常用
2. **UI 与逻辑解耦**:Web UI 改动与 Kotlin 业务逻辑改动分离到不同 PR
3. **可回滚**:每 Phase 留 git tag,出问题可回退
4. **持续验证**:不积压测试,小步快跑
5. **边做边收**:每完成一个组件立刻 commit,避免大批量合并冲突

### 1.3 团队建议

| 角色 | 投入度 | 主要职责 |
|---|---|---|
| Tech Lead | 0.5 FTE | 架构决策、PR 审查、协调 |
| 前端 Owner | 1.0 FTE(Phase 1-3,5)/ 0.5 FTE(Phase 4) | Web UI 全栈 |
| 后端 Owner | 0.3 FTE(Phase 1,2,3)/ 0.7 FTE(Phase 4) | Kotlin / Settings 持久化 |
| QA | 0.3 FTE(全程) | 测试用例、手动验证 |
| UX | 0.2 FTE(全程) | 设计稿走查、可用性测试 |

> 单人模式:按 Phase 顺序执行,Phase 1-3 偏前端,Phase 4 偏后端,Phase 5 回归全栈。

---

## 二、工作分解结构(WBS)

### Phase 1:基础 — 4 天

```
P1.1 [前端 0.5d] 拆分 chat.html → index.html + 目录结构
P1.2 [前端 0.5d] 引入 tokens.css,统一颜色/字体/间距
P1.3 [前端 1.0d] 实现 7 个基础组件(cs-button/cs-input/cs-toast 等)
P1.4 [前端 0.5d] 自托管 marked + dompurify + 字体图标
P1.5 [后端 0.8d] JCEFChatPanel 事件路由重构(类 EventEmitter)
P1.6 [前端 0.4d] 错误边界 + 全局异常 toast
P1.7 [QA 0.3d] Phase 1 回归测试(聊天功能不退化)
```

### Phase 2:核心体验 — 5 天

```
P2.1 [前端 1.0d] cs-turn 组件
P2.2 [前端 0.5d] cs-thinking 升级(三态 + 计时 + 折叠)
P2.3 [前端 1.0d] cs-tool-call 重做(参数 + 结果 + diff)
P2.4 [前端 0.5d] 轻量 diff 渲染器
P2.5 [前端 0.8d] cs-sub-agent 嵌套子 Turn
P2.6 [前端 0.7d] cs-plan Todo 列表
P2.7 [前端 0.3d] cs-budget-meter 顶栏实时
P2.8 [前端 0.2d] cs-inline-alert 统一错误
P2.9 [后端 0.5d] 事件协议补全:tool_call.arguments/diff 字段
P2.10 [QA 0.5d] 样本对话全链路验证
```

### Phase 3:输入与会话 — 3 天

```
P3.1 [前端 0.8d] 命令面板 Cmd+K + 10 个命令
P3.2 [前端 0.4d] 输入区增强:粘贴图片 + 拖拽文件 + 草稿持久化
P3.3 [前端 0.5d] 模型选择器:搜索 + Provider 分组 + 状态点
P3.4 [前端 0.7d] 会话侧边栏(对齐 SESSION_SIDEBAR_REDESIGN)
P3.5 [前端 0.3d] 主题切换:auto/light/dark + 跟随系统
P3.6 [前端 0.3d] 响应式:1280/1024/768 三档断点
P3.7 [QA 0.3d] 走查 + 与 Cursor 截图对比
```

### Phase 4:配置体系 — 4 天

```
P4.1 [后端 0.8d] SettingsRepository:文件 IO + watch + 校验 + 备份
P4.2 [后端 0.5d] SettingsSchema:kotlinx.serialization data classes
P4.3 [后端 0.5d] SettingsMigrations:从 CodeSagePlugin.xml 迁移
P4.4 [后端 0.3d] 旧 IDE Configurable 标 deprecated
P4.5 [前端 1.0d] Settings 视图骨架(6 大分组 + 导航)
P4.6 [前端 0.6d] Provider 卡片化(增删/编辑/启用/测试)
P4.7 [前端 0.4d] API Key:PasswordSafe + 显隐 + 导出
P4.8 [前端 0.4d] 迁移向导(从 IDE 旧配置到 settings.json)
P4.9 [后端 0.3d] IDE 菜单添加 Open Settings Folder / Reload
P4.10 [QA 0.4d] 迁移 + 配置流转测试
```

### Phase 5:打磨与回归 — 3-5 天

```
P5.1 [前端 0.5d] 动画规范全量落地(14 节)
P5.2 [前端 0.4d] 加载状态:骨架屏 + 断网重连
P5.3 [前端 0.4d] 暗色主题对比度审计(目标 AA+)
P5.4 [前端 0.3d] 无障碍:prefers-reduced-motion + ARIA
P5.5 [前端 0.5d] virtual scroll(>200 消息)
P5.6 [前端 0.3d] 国际化框架(中/英)
P5.7 [前端 0.4d] 首次启动 walkthrough
P5.8 [UX 0.5d] 可用性测试(5 个外部开发者)
P5.9 [QA 0.5d] 全量回归
P5.10 [Tech 0.3d] 文档与发布说明
```

---

## 三、时间线与里程碑

### 3.1 单人模式时间线(20 天)

```
Week 1 (Mon-Fri)            Week 2 (Mon-Fri)            Week 3 (Mon-Fri)            Week 4 (Mon-Thu)
┌────┬────┬────┬────┬────┐  ┌────┬────┬────┬────┬────┐  ┌────┬────┬────┬────┬────┐  ┌────┬────┬────┬────┐
│ P1 │ P1 │ P1 │ P1 │ M1 │  │ P2 │ P2 │ P2 │ P2 │ P2 │  │ P3 │ P3 │ P3 │ M3 │ M4 │  │ P5 │ P5 │ P5 │ M5 │
└────┴────┴────┴────┴────┘  └────┴────┴────┴────┴────┘  └────┴────┴────┴────┴────┘  └────┴────┴────┴────┘
M1 = Phase 1 评审 + Tag
M3 = Phase 3 评审
M4 = Phase 4 评审
M5 = 整体验收 + Tag v1.0
```

### 3.2 双人并行模式(12 天)

```
          Frontend Lead              Backend Lead
Week 1    P1.1-P1.4 (前端基础)        P1.5-P1.6 (JCEF 重构)
Week 2    P2.1-P2.8 (核心体验)        P2.9 (事件协议)
Week 3    P3.1-P3.6 (输入/会话)        ─
Week 4    ─                          P4.1-P4.4 (Settings 后端)
Week 5    P4.5-P4.8 (Settings 前端)   P4.9 (菜单) + 协作
Week 6    P5.1-P5.7 (打磨)            P5.10 (文档/发布)
```

### 3.3 关键里程碑

| # | 里程碑 | 准入条件 | 准出标准 | 计划日期(单) |
|---|---|---|---|---|
| M0 | 设计方案评审通过 | 用户/产品签字 | 冻结设计 | D0 |
| M1 | Phase 1 完成 | chat.html 拆分 + 设计系统基础 | `./gradlew test` 通过,聊天功能不退化 | D4 |
| M2 | Phase 2 完成 | 所有流式事件新组件就位 | 样本对话验证通过 | D9 |
| M3 | Phase 3 完成 | 输入区 + 模型 + 会话 | UX 走查通过 | D12 |
| M4 | Phase 4 完成 | settings.json 存储 + Settings 视图 | 迁移向导通过 | D16 |
| M5 | 整体验收发布 | 全部 P0/P1 验收项通过 | SUS ≥ 75,所有测试通过 | D20 |

---

## 四、任务依赖图

```
M0 (设计评审)
  │
  ├── Phase 1 ─────────────────┐
  │   P1.1 拆文件                │
  │   ├─► P1.2 tokens.css        │
  │   │     ├─► P1.3 基础组件   │
  │   │     │     └─► P1.4 vendor
  │   │     └─► P1.6 错误边界   │
  │   └─► P1.5 JCEF 重构         │
  │         └─► P1.7 QA ────────► M1
  │                              │
  ├── Phase 2 ──────────────────┤
  │   P2.1 cs-turn               │
  │   ├─► P2.2 cs-thinking       │
  │   ├─► P2.3 cs-tool-call      │
  │   │     └─► P2.4 diff 渲染器 │
  │   ├─► P2.5 cs-sub-agent      │
  │   ├─► P2.6 cs-plan           │
  │   ├─► P2.7 cs-budget-meter   │
  │   ├─► P2.8 cs-inline-alert   │
  │   └─► P2.9 事件协议补全 (后端)│
  │         └─► P2.10 QA ──────► M2
  │                              │
  ├── Phase 3 ──────────────────┤
  │   P3.1 命令面板              │
  │   P3.2 输入区增强             │
  │   P3.3 模型选择器            │
  │   P3.4 会话侧边栏 (已有设计) │
  │   P3.5 主题切换              │
  │   P3.6 响应式                │
  │   └─► P3.7 UX 走查 ────────► M3
  │                              │
  ├── Phase 4 ──────────────────┤
  │   P4.1 Repository            │
  │   P4.2 Schema                │
  │   P4.3 Migrations            │
  │   ├─► P4.5 Settings 视图     │
  │   ├─► P4.6 Provider 卡片     │
  │   ├─► P4.7 API Key           │
  │   ├─► P4.8 迁移向导          │
  │   └─► P4.9 菜单              │
  │   └─► P4.10 QA ─────────────► M4
  │                              │
  └── Phase 5 ──────────────────┘
      P5.1 动画
      P5.2 加载状态
      P5.3 对比度审计
      P5.4 无障碍
      P5.5 virtual scroll
      P5.6 i18n
      P5.7 walkthrough
      P5.8 可用性测试
      P5.9 全量回归
      P5.10 文档/发布
      └─► M5 (Tag v1.0)
```

关键路径:`P1.1 → P1.2 → P1.3 → P2.1 → P2.3 → P2.6 → P3.4 → P4.5 → P5.5 → M5`

---

## 五、优先级矩阵(MoSCoW)

| Must have | Should have | Could have | Won't have |
|---|---|---|---|
| ✅ Phase 1 全部 | ✅ 动画规范(14 节) | ❌ 自定义主题(Monokai 等) | ❌ Web 端独立使用 |
| ✅ Phase 2 全部(尤其 cs-tool-call + cs-plan) | ✅ 主题 auto/light/dark | ❌ Pinned 会话 | ❌ Kanban 完全 Web 化(本期保留 Swing) |
| ✅ Phase 3 模型选择器 + 主题 + 响应式 | ✅ 命令面板 | ❌ 消息搜索 | ❌ Voice input |
| ✅ Phase 4 全部 | ✅ 国际化(中/英) | ❌ 拖拽文件到输入 | ❌ 自定义 CSS |
| ✅ Phase 5 全部 | ✅ walkthrough | ❌ telemetry(可选) | ❌ 多 IDE 同步 |

---

## 六、详细任务卡(每任务一卡,直接可分派)

> 每张卡包含:目标、范围、依赖、产出、验收、估时、负责人、风险

### Phase 1 任务卡

---

#### P1.1 拆分 chat.html

| 项 | 内容 |
|---|---|
| 目标 | 把 3407 行的单文件拆为多文件结构 |
| 范围 | `src/main/resources/webui/chat.html` → `index.html` + `styles/*.css` + `js/main.js` + `js/views/chat.js` |
| 依赖 | 无 |
| 产出 | 拆分后的目录,功能完全等价 |
| 验收 | 聊天功能完全不变,`./gradlew test` 通过,DOM 结构 1:1 |
| 估时 | 0.5d |
| 风险 | 低(纯移动) |
| 备注 | 用 ESM `<script type="module">`,先不引入打包工具 |

---

#### P1.2 引入 tokens.css

| 项 | 内容 |
|---|---|
| 目标 | 统一颜色、字体、间距、阴影、圆角 token |
| 范围 | 新建 `styles/tokens.css` + `styles/themes.css` |
| 依赖 | P1.1 |
| 产出 | tokens.css 包含 §15 所有变量,两套主题 |
| 验收 | 现有颜色全部替换为 var(--xxx),亮/暗主题切换无遗漏 |
| 估时 | 0.5d |
| 风险 | 低 |

---

#### P1.3 7 个基础组件

| 项 | 内容 |
|---|---|
| 目标 | 实现 `cs-button` `cs-icon-button` `cs-input` `cs-textarea` `cs-toggle` `cs-tooltip` `cs-toast` `cs-spinner` |
| 范围 | `js/components/*.js`,每个用 `customElements.define` |
| 依赖 | P1.2 |
| 产出 | 8 个组件文件 + 单元测试 |
| 验收 | 组件可独立使用,Storybook 风格 demo 页,基本 a11y |
| 估时 | 1.0d |
| 风险 | 中(custom elements 兼容性) |
| 备注 | 不引入 React/Vue,纯 custom element + shadow DOM |

---

#### P1.4 自托管 vendor 资源

| 项 | 内容 |
|---|---|
| 目标 | 消除 CDN 依赖,离线可用 |
| 范围 | 下载 `marked.min.js`、`dompurify.min.js`、字体图标子集 → `vendor/` |
| 依赖 | P1.1 |
| 产出 | `vendor/` 目录,`index.html` 改引本地 |
| 验收 | 断网下 chat.html 仍能加载渲染 |
| 估时 | 0.5d |
| 风险 | 低 |

---

#### P1.5 JCEFChatPanel 事件路由重构

| 项 | 内容 |
|---|---|
| 目标 | 把 100+ 行 `when` 改成 EventEmitter 模式,新增事件零成本 |
| 范围 | `JCEFChatPanel.kt`,新增 `EventRouter` 类 |
| 依赖 | 无(可与 P1.1 并行) |
| 产出 | 重构后 JCEFChatPanel,事件 handler 注册表 |
| 验收 | 所有现有事件行为完全不变,新增事件只需 1 行 |
| 估时 | 0.8d |
| 风险 | 中(动到核心) |

---

#### P1.6 错误边界 + 全局异常 toast

| 项 | 内容 |
|---|---|
| 目标 | 任何 JS 异常不阻塞 UI |
| 范围 | `js/main.js` 加 `window.onerror` + `unhandledrejection` |
| 依赖 | P1.3 |
| 产出 | 全局错误捕获,toast 提示 + 上报到 Kotlin 日志 |
| 验收 | 故意 throw 错误后,UI 不白屏,显示 toast |
| 估时 | 0.4d |

---

#### P1.7 Phase 1 回归测试

| 项 | 内容 |
|---|---|
| 目标 | 验证聊天功能完全不退化 |
| 范围 | 手动 + 单元测试 |
| 依赖 | P1.1-P1.6 全部 |
| 产出 | 测试报告 |
| 验收 | 全部 Phase 0 测试用例通过 + 新组件单元测试通过 |
| 估时 | 0.3d |

---

### Phase 2 任务卡(节选,详尽版省略)

#### P2.3 cs-tool-call 重做(关键)

| 项 | 内容 |
|---|---|
| 目标 | 工具调用卡片显示入参 + 结果(diff/code/text) |
| 范围 | `js/components/tool-call.js` |
| 依赖 | P1.3, P2.9 |
| 产出 | 工具卡片组件 + 7 种 `kind` 渲染器 |
| 验收 | 各种工具类型视觉差异化,可 Apply to editor |
| 估时 | 1.0d |
| 风险 | 高(diff 渲染 + Apply 流程) |

#### P2.6 cs-plan Todo 列表(关键)

| 项 | 内容 |
|---|---|
| 目标 | Plan 渲染为可勾选 Todo,带状态机 |
| 范围 | `js/components/plan.js` |
| 依赖 | P1.3 |
| 产出 | cs-plan 组件 + 状态机 |
| 验收 | Plan 步骤实时更新,全部完成自动折叠 |
| 估时 | 0.7d |
| 风险 | 中 |

---

### Phase 4 任务卡(节选)

#### P4.1 SettingsRepository

| 项 | 内容 |
|---|---|
| 目标 | 文件 IO + watch + 校验 + 备份 |
| 范围 | `shared/config/SettingsRepository.kt` |
| 依赖 | 无 |
| 产出 | Repository 类 + 单元测试(10+ 用例) |
| 验收 | 并发安全、原子写、损坏自动备份回退 |
| 估时 | 0.8d |
| 风险 | 高(并发 + 原子性) |

#### P4.5 Settings 视图骨架

| 项 | 内容 |
|---|---|
| 目标 | 6 大分组导航 + 路由 |
| 范围 | `js/views/settings.js` + `js/components/form-field.js` |
| 依赖 | P4.1, P4.2, P1.3 |
| 产出 | Settings 视图可切换分组 |
| 验收 | 6 个分组都可访问,URL hash 同步 |
| 估时 | 1.0d |
| 风险 | 中 |

---

## 七、资源分配(双人模式)

| Week | Frontend Lead | Backend Lead | QA | UX |
|---|---|---|---|---|
| W1 | P1.1-P1.4 (2.5d) | P1.5-P1.6 (1.2d) | P1.7 (0.3d) | 评审设计稿 |
| W2 | P2.1-P2.8 (4.0d) | P2.9 (0.5d) | P2.10 (0.5d) | 组件设计 review |
| W3 | P3.1-P3.6 (3.0d) | 支持/Code Review | P3.7 (0.3d) | UX 走查 |
| W4 | 协作 | P4.1-P4.4 (2.0d) | 支持 | 协助 |
| W5 | P4.5-P4.8 (2.4d) | P4.9 (0.3d) | P4.10 (0.4d) | 协助 |
| W6 | P5.1-P5.7 (2.8d) | P5.10 (0.3d) | P5.9 (0.5d) | P5.8 (0.5d) |

---

## 八、决策点(需要用户/产品确认)

> 在开始实施前,以下问题需明确:

| # | 决策点 | 现状默认 | 建议 | 阻塞 Phase |
|---|---|---|---|---|
| D1 | 配置存储用 JSON5 还是 TOML | JSON5 | JSON5(IDE 工具链支持更好,Kotlin 有 kotlinx-serialization-json5) | Phase 4 |
| D2 | 是否保留 Swing 聊天作为 fallback | 保留 | 保留(避免 JCEF 不可用时白屏) | Phase 1 |
| D3 | 是否废弃 Kanban 面板(改用 Web Plan) | 保留并降级 | 本期保留,但加 deprecation banner | Phase 5 |
| D4 | 自定义 Web 组件 vs 引入 React | 自定义 | 自定义(50 个组件内,无依赖,符合 JCEF 体积要求) | Phase 1 |
| D5 | 是否引入构建工具(webpack/vite) | 不引入 | 暂不引入,ESM + 文件拆分足够 | Phase 1 |
| D6 | API Key 存储:PasswordSafe / 文件 / 环境变量 | PasswordSafe | 全部支持,默认 PasswordSafe | Phase 4 |
| D7 | 是否在 IDE 旧 Configurable 弹 deprecation banner | 弹 | 弹 + 显示指向新配置 | Phase 4 |
| D8 | 国际化是否第一期 | 是 | 是,留出 i18n 框架,先中文(默认) | Phase 5 |
| D9 | 是否支持 voice input | 否 | 不支持(超出范围) | — |
| D10 | 优先级排序:Phase 1 全部 vs 7 个立即见效项 | 全 Phase 1 | 视团队节奏,见 §9 | Phase 1 |

---

## 九、风险登记表

| ID | 风险 | 概率 | 影响 | 缓解措施 | 负责人 | 状态 |
|---|---|---|---|---|---|---|
| R-1 | JCEF 性能/兼容性问题 | 中 | 高 | 保留 `createFallbackHTML`,Phase 1 不删 Swing 兜底 | Frontend Lead | 监控 |
| R-2 | chat.html 拆分后 JCEF 加载慢 | 中 | 中 | 用 ESM 原生 module,避免重打包,测首屏 < 600ms | Frontend Lead | Phase 1 验证 |
| R-3 | settings.json 与 IDE 旧配置双向同步导致脏数据 | 高 | 高 | 单一 source of truth = settings.json;IDE 旧 Configurable 标记 deprecated 但保留只读 | Backend Lead | Phase 4 解决 |
| R-4 | 自定义 Web 组件(无 React)在事件管理上踩坑 | 中 | 中 | 抽 `EventTarget` 基类,组件间通信走 `CustomEvent`,先做 3 个 POC | Frontend Lead | Phase 1 POC |
| R-5 | Plan/Sub-agent 事件协议不完整(Kotlin 端没发 arguments) | 高 | 高 | Phase 2.9 先盘点所有事件字段,补全再前端做 | Backend Lead | Phase 2 启动前 |
| R-6 | 用户大量旧配置需要兼容 | 中 | 中 | 迁移向导 + 备份;失败回退 + 提示 | Backend Lead | Phase 4 验证 |
| R-7 | 暗色主题对比度不达标 | 中 | 中 | Phase 5.3 跑 axe-core 审计 | UX | Phase 5 |
| R-8 | 大消息列表性能差(>1000 消息) | 中 | 中 | Phase 5.5 virtual scroll | Frontend Lead | Phase 5 |
| R-9 | 单人模式时间翻倍 | 中 | 中 | 砍 P2 砍 Phase 5,优先 Phase 1-4 走通 | Tech Lead | 启动前 |
| R-10 | JCEF 在 Windows 性能差 | 中 | 中 | 已知问题,Phase 5.3 重点验证 | QA | Phase 5 |
| R-11 | 用户对 IDE 旧 Configurable 有依赖,新配置未生效 | 中 | 高 | 默认保留旧 Configurable 只读,新功能只能从新配置走;Phase 4 完成后再完全切换 | Backend Lead | Phase 4 |
| R-12 | `AssistantMessage.content` 流式累积时 Markdown 重排闪烁 | 中 | 中 | Phase 2.2 改为「段落级 streaming」,而不是文本级 | Frontend Lead | Phase 2 |

---

## 十、进度跟踪机制

### 10.1 看板(用 GitHub Projects / GitLab Board)

```
Columns:
  📋 Backlog → 🎯 Ready → 🚧 In Progress(单任务 WIP)→ 👀 Review → ✅ Done

每张卡有标签:
  phase:1 / phase:2 / ...
  area:frontend / backend / design / qa
  prio:M / S / C / W
  effort:S(≤0.5d) / M(0.5-1d) / L(>1d)
```

### 10.2 每日站会(15 分钟)

模板:
```
- 昨天完成了什么?
- 今天要做什么?
- 有什么 blocker?
- 哪张卡可以拉走?
```

### 10.3 每周回顾(周五下午,1h)

```
- 本周 Phase 进度(% 完成,vs 计划)
- 测试覆盖率
- 已知 P0/P1 bug 数
- 下周计划
- 风险 review
```

### 10.4 Phase 评审(每 Phase 结束,半天)

```
- Demo
- 验收 checklist 全过
- Git tag: v0.x-uiux-p{N}
- 发布说明(DRAFT)
- 风险表更新
```

### 10.5 度量指标

| 指标 | 频率 | 目标 |
|---|---|---|
| 编译通过率 | 每次 commit | ≥ 95% |
| 单元测试覆盖率(JS) | 每 PR | ≥ 60% |
| 单元测试覆盖率(Kotlin) | 每 PR | ≥ 80% |
| 手动回归通过率 | 每 Phase | 100% |
| P0 bug 修复时长 | 持续 | < 24h |
| P1 bug 修复时长 | 持续 | < 72h |

---

## 十一、验收检查表(每 Phase 通用 + 专项)

### 11.1 通用检查项

- [ ] `./gradlew compileKotlin` 通过
- [ ] `./gradlew test` 通过(无新增 failure)
- [ ] `./gradlew buildPlugin` 通过
- [ ] 插件可在 IDE 启动,无启动报错
- [ ] 聊天主流程:发送 → 流式接收 → 完成,完整跑通
- [ ] 模型切换可用
- [ ] 主题切换可用
- [ ] 旧 Configurable 仍然可访问(未完全删除)
- [ ] 关闭/重开 IDE,配置保留
- [ ] 无新增 deprecation warning
- [ ] 无内存泄漏(测试 1 小时长对话后 heap < 500MB)
- [ ] Windows + macOS + Linux 至少跨 2 平台验证

### 11.2 Phase 专项验收

| Phase | 专项验收 |
|---|---|
| P1 | chat.html 拆分后行数 < 原文件;组件 demo 页可访问;JCEF 事件路由单元测试覆盖 100% |
| P2 | 样本对话(thinking + 3 tool + 2 sub-agent + plan)截图对比通过;每种 tool kind 各有 1 个测试用例 |
| P3 | UX 走查对比 Cursor 评分 ≥ 7/10;命令面板 10 个命令都可触发;模型选择器搜索可用 |
| P4 | 迁移向导 3 路径(preview/migrate/skip)全过;settings.json 损坏自动备份回退;6 大分组完整;Provider 增删改查通过 |
| P5 | 暗色对比度 axe-core 0 critical;reduced-motion 下无动画;1000 消息滚动 60fps;首次 walkthrough 5 步内完成;SUS ≥ 75 |

---

## 十二、回滚预案

### 12.1 Phase 1-3 回滚

- 风险:Web UI 改坏
- 回滚:还原 `chat.html` 单文件(已 git 保留)+ revert 相关 PR
- 数据:`PluginConfig` 未动,旧 IDE Configurable 完全可用
- 验证:聊天功能完全恢复

### 12.2 Phase 4 回滚

- 风险:settings.json 损坏
- 回滚:PluginConfig 仍持久化旧 IDE 状态,新 SettingsRepository 有 `.bak.<timestamp>` 自动备份
- 验证:删除 `~/codesage/settings.json`,回退 IDE 旧 Configurable

### 12.3 Git Tag 策略

每 Phase 结束打 tag:

```
git tag -a v0.x-uiux-p1 -m "Phase 1: 设计系统基础"
git tag -a v0.x-uiux-p2 -m "Phase 2: 核心体验"
git tag -a v0.x-uiux-p3 -m "Phase 3: 输入与会话"
git tag -a v0.x-uiux-p4 -m "Phase 4: 配置体系"
git tag -a v0.x-uiux-p5 -m "Phase 5: 打磨回归"
```

回滚命令:`git checkout v0.x-uiux-p3`

---

## 十三、沟通节奏

| 场合 | 频率 | 时长 | 参与人 | 输出 |
|---|---|---|---|---|
| 每日站会 | 工作日 | 15 min | 全员 | 进度/Blocker |
| 周回顾 | 周五 | 60 min | 全员 | 周报 + 风险表 |
| Phase 评审 | 5 次 | 4h | 全员 + Stakeholder | Demo + 验收 + Tag |
| 设计走查 | Phase 2,5 | 1h | UX + Frontend | 走查报告 |
| 用户可用性测试 | Phase 5 | 2h | UX + 5 用户 | SUS 评分 + 报告 |
| Release 沟通 | M5 后 | — | Tech Lead | Release Note |

---

## 十四、立即开始的检查清单(Pre-Phase 0)

在开 Phase 1 前确认:

- [ ] **D1**:JSON5 vs TOML — 已确认用 JSON5
- [ ] **D2**:保留 Swing 兜底 — 同意
- [ ] **D4**:自定义 Web 组件 — 同意
- [ ] **D5**:不引入构建工具 — 同意
- [ ] 备份当前 `chat.html` 到 `docs/archive/chat.html.v0`
- [ ] 备份 `JCEFChatPanel.kt` 到 `docs/archive/JCEFChatPanel.kt.v0`
- [ ] 备份 `ProviderSettingsConfigurable.kt` 到 `docs/archive/`
- [ ] 创建 feature 分支:`git checkout -b feature/uiux-redesign`
- [ ] 创建看板(Project / Board)
- [ ] 标记 M0 为完成,记录到 CHANGELOG
- [ ] 通知所有协作者分支策略

---

## 十五、附:推荐起点(如果你只能挑一件事做)

如果时间/资源极度受限,**优先做 §十九 7 个立即见效项中的 1+2+3+4**:
1. 统一顶栏
2. 助手消息去气泡
3. 工具卡片加 Inputs / Diff
4. Plan 组件

这 4 项在 **3-5 天**内可完成,且能让 CodeSage 立即看起来「商业级」。

> 后续等节奏稳定后,再启动 Phase 1 的「拆分 chat.html」,把上述临时改的代码正式模块化。

---

## 十六、附录:执行节奏与发布建议

| 时点 | 事件 | 备注 |
|---|---|---|
| D0 | 设计评审通过,M0 | 当前文档定稿 |
| D1 | 创建 feature 分支 | |
| D4 | M1:Phase 1 完成,Tag,内部发布 | |
| D9 | M2:Phase 2 完成,Tag,内部发布 | |
| D12 | M3:Phase 3 完成,Tag | UX 走查 + 内部 demo |
| D16 | M4:Phase 4 完成,Tag | 灰度给核心用户 |
| D20 | M5:整体验收,Tag v1.0,正式发布 | Release Note + 推广 |

**发布策略**:
- Phase 1-3:Plugin Marketplace → 「Beta channel」
- Phase 4 完成后:全量发布
- Phase 5 完成后:全量 + Release Note 推送

---

执行计划 v1.0 完成。等待评审后即可启动 Phase 0 → Phase 1。
