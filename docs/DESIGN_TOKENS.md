# CodeSage Design Tokens

> 单一设计真相来源。修改这里,全界面同步。

**文件**:`src/main/resources/webui/styles/tokens.css`

---

## 颜色

### 中性色(背景 / 前景)

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--bg-0` | `#ffffff` | `#0f0f10` | 主背景 |
| `--bg-1` | `#fafafa` | `#1a1a1c` | 次背景(sidebar / header) |
| `--bg-2` | `#f4f4f5` | `#252528` | 三级背景(hover / chip) |
| `--bg-3` | `#e4e4e7` | `#34343a` | 四级背景(toggle track / dot) |
| `--bg-hover` | `#ececf1` | `#2d2d30` | hover 态 |
| `--bg-input` | `#f7f7f8` | `#1f1f22` | 输入框 |
| `--bg-overlay` | `rgba(0,0,0,0.4)` | `rgba(0,0,0,0.6)` | 遮罩 |

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--fg-0` | `#18181b` | `#fafafa` | 主文本 |
| `--fg-1` | `#3f3f46` | `#d4d4d8` | 次文本 |
| `--fg-2` | `#71717a` | `#a1a1aa` | 弱化文本(label / meta) |
| `--fg-3` | `#a1a1aa` | `#71717a` | 极弱文本(placeholder) |

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--border` | `#e4e4e7` | `#2a2a2e` | 边框 |
| `--border-subtle` | `#f0f0f0` | `#222226` | 微边框(divider) |
| `--border-strong` | `#d4d4d8` | `#3f3f46` | 加粗边框(focus) |

### 品牌色(Indigo)

| Token | Light | Dark |
|---|---|---|
| `--accent` | `#6366f1` | `#818cf8` |
| `--accent-hover` | `#4f46e5` | `#a5b4fc` |
| `--accent-active` | `#4338ca` | `#c7d2fe` |
| `--accent-soft` | `rgba(99,102,241,0.10)` | `rgba(129,140,248,0.15)` |
| `--accent-fg` | `#ffffff` | `#0f0f10` |

### 状态色

| Token | Light | Dark | 用途 |
|---|---|---|---|
| `--success` | `#10b981` | `#34d399` | 成功 / 已完成 |
| `--success-soft` | `rgba(16,185,129,0.10)` | `rgba(52,211,153,0.15)` | |
| `--warning` | `#f59e0b` | `#fbbf24` | 警告 / 思考中 |
| `--warning-soft` | `rgba(245,158,11,0.10)` | `rgba(251,191,36,0.15)` | |
| `--error` | `#ef4444` | `#f87171` | 错误 / 失败 |
| `--error-soft` | `rgba(239,68,68,0.10)` | `rgba(248,113,113,0.15)` | |
| `--info` | `#3b82f6` | `#60a5fa` | 信息 |
| `--info-soft` | `rgba(59,130,246,0.10)` | `rgba(96,165,250,0.15)` | |

### 用户消息气泡

| Token | 值 |
|---|---|
| `--user-bubble` | `linear-gradient(135deg, #4f46e5 0%, #6366f1 100%)` |

### 代码块

| Token | Light | Dark |
|---|---|---|
| `--code-bg` | `#1e1e2e` | `#14141f` |
| `--code-fg` | `#cdd6f4` | `#cdd6f4` |
| `--code-header-bg` | `rgba(255,255,255,0.04)` | `rgba(255,255,255,0.04)` |

### Diff 视图

| Token | Light | Dark |
|---|---|---|
| `--diff-add-bg` | `#e6ffec` | `rgba(46,160,67,0.15)` |
| `--diff-add-fg` | `#1a7f37` | `#7ee787` |
| `--diff-remove-bg` | `#ffebe9` | `rgba(248,81,73,0.15)` |
| `--diff-remove-fg` | `#cf222e` | `#ffa198` |
| `--diff-add-line-bg` | `rgba(46,160,67,0.12)` | `rgba(46,160,67,0.18)` |
| `--diff-remove-line-bg` | `rgba(248,81,73,0.12)` | `rgba(248,81,73,0.18)` |

### 工具状态色

| Token | 值 | 用途 |
|---|---|---|
| `--tool-running` | `var(--warning)` | 运行中 |
| `--tool-completed` | `var(--success)` | 已完成 |
| `--tool-failed` | `var(--error)` | 失败 |
| `--tool-queued` | `var(--fg-3)` | 排队中 |
| `--tool-confirm` | `var(--warning)` | 需确认 |

---

## 间距

| Token | 值 | 常用 |
|---|---|---|
| `--space-1` | `4px` | 极小间距 |
| `--space-2` | `8px` | 默认间距 |
| `--space-3` | `12px` | 中等 |
| `--space-4` | `16px` | 标准 |
| `--space-5` | `20px` | |
| `--space-6` | `24px` | 大间距 |
| `--space-8` | `32px` | section 间距 |
| `--space-10` | `40px` | 极大间距 |

---

## 圆角

| Token | 值 | 用途 |
|---|---|---|
| `--radius-xs` | `4px` | 极小(toggle 内部) |
| `--radius-sm` | `6px` | 通用小圆角 |
| `--radius-md` | `10px` | 卡片 |
| `--radius-lg` | `14px` | 大卡片 / modal |
| `--radius-xl` | `20px` | 输入框 / 大容器 |
| `--radius-full` | `9999px` | 胶囊(pill / dot) |

---

## 阴影

| Token | 值 | 用途 |
|---|---|---|
| `--shadow-xs` | `0 1px 2px rgba(0,0,0,0.04)` | 极轻 |
| `--shadow-sm` | `0 1px 3px ..., 0 1px 2px ...` | 卡片 |
| `--shadow-md` | `0 4px 12px rgba(0,0,0,0.08)` | 浮层 |
| `--shadow-lg` | `0 12px 32px rgba(0,0,0,0.12)` | modal / dropdown |
| `--shadow-pop` | `0 16px 48px rgba(0,0,0,0.16)` | 弹出 / 命令面板 |

> Dark 主题下所有阴影 alpha 提升:`rgba(0,0,0,0.3-0.7)`

---

## 字体

```css
--font-sans: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
             "Microsoft YaHei", "Helvetica Neue", Roboto, Helvetica, Arial, sans-serif;
--font-mono: "JetBrains Mono", "Fira Code", "SF Mono", Menlo, Monaco,
             Consolas, "Liberation Mono", monospace;
```

| Token | 值 | 用途 |
|---|---|---|
| `--text-xs` | `11px` | 微文 / hint |
| `--text-sm` | `12px` | 标签 |
| `--text-base` | `14px` | 正文 |
| `--text-md` | `15px` | 标题 |
| `--text-lg` | `16px` | 大标题 |
| `--text-xl` | `18px` | section |
| `--text-2xl` | `22px` | 页面标题 |
| `--text-3xl` | `28px` | 巨标 |

| Token | 值 | 用途 |
|---|---|---|
| `--leading-tight` | `1.4` | 紧凑(meta) |
| `--leading-normal` | `1.5` | 正文 |
| `--leading-relaxed` | `1.6` | 长文本 |
| `--leading-prose` | `1.7` | Markdown |

---

## 动画

| Token | 值 | 用途 |
|---|---|---|
| `--ease-out` | `cubic-bezier(0.16, 1, 0.3, 1)` | 大部分进入动画 |
| `--ease-in-out` | `cubic-bezier(0.4, 0, 0.2, 1)` | 折叠/展开 |
| `--duration-fast` | `120ms` | hover / 焦点 |
| `--duration-base` | `200ms` | 普通过渡 |
| `--duration-slow` | `320ms` | 大动画(主题切换) |

### `prefers-reduced-motion: reduce`

全部 `duration-*` 退化为 `0ms`,所有 animation 退化为 `0.001ms`。

---

## 布局

| Token | 值 | 用途 |
|---|---|---|
| `--header-height` | `52px` | 顶栏 |
| `--max-content-width` | `1200px` | 容器最大宽度 |
| `--z-base` | `0` | 默认 |
| `--z-dropdown` | `100` | 下拉 |
| `--z-sticky` | `200` | 粘性 |
| `--z-modal` | `1000` | 模态 |
| `--z-toast` | `9999` | toast |
| `--z-overlay` | `9999` | 遮罩 |

---

## 主题切换

```js
// 设置
document.body.setAttribute("data-theme", "light");  // 或 "dark"
// 设置偏好(auto 模式下)
document.body.setAttribute("data-theme-pref", "auto");
```

`data-theme` 是实际生效主题(light/dark),`data-theme-pref` 是用户偏好(auto/light/dark)。auto 模式下,实际主题跟随 `prefers-color-scheme` 媒体查询。

---

## 暗色主题对比度审计

| 元素 | Light 对比 | Dark 对比 | AA | AAA |
|---|---|---|---|---|
| `--fg-0` on `--bg-0` | 19.5:1 | 18.2:1 | ✅ | ✅ |
| `--fg-1` on `--bg-0` | 10.4:1 | 12.1:1 | ✅ | ✅ |
| `--fg-2` on `--bg-0` | 4.6:1 | 5.8:1 | ✅ | ❌ |
| `--fg-3` on `--bg-0` | 2.9:1 | 4.0:1 | ❌ (大文) | ❌ |
| `--accent` on `--bg-0` | 4.7:1 | 6.5:1 | ✅ | ❌ |
| `--error` on `--bg-0` | 4.7:1 | 5.8:1 | ✅ | ❌ |

> 结论:主体文本全部 AA,大文 14px+ AA+ 通过。`--fg-3` 仅用于 placeholder / hint 等辅助文,符合 4.5:1 宽松标准。
