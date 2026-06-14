/**
 * i18n 框架 — 极简多语言支持
 *
 * 用法:
 *   import { t, setLocale, getLocale } from "../i18n.js";
 *   t("common.save")  // -> "保存"
 *   t("settings.title")  // -> "设置"
 *
 * 支持嵌套 key:"settings.providers.title"
 * 支持插值:t("greeting", { name: "Alice" })  // -> "你好,Alice"
 *
 * 默认 locale 跟随 settings.ui.language,可在运行时切换。
 */

const STRINGS = {
  "zh-CN": {
    common: {
      save: "保存",
      cancel: "取消",
      confirm: "确认",
      delete: "删除",
      edit: "编辑",
      add: "添加",
      remove: "移除",
      enable: "启用",
      disable: "禁用",
      back: "返回",
      reload: "重载",
      close: "关闭",
      yes: "是",
      no: "否",
      ok: "确定",
    },
    welcome: {
      title: "CodeSage AI 助手",
      subtitle:
        "我可以帮你编写代码、调试问题、重构项目、生成测试等。支持多轮对话、工具调用、代码生成。",
      promptExplain: "解释代码",
      promptRefactor: "重构代码",
      promptTest: "生成测试",
      promptFix: "修复 Bug",
    },
    status: {
      ready: "就绪",
      thinking: "思考中...",
      generating: "生成中...",
      error: "错误",
      retry: "重试",
    },
    input: {
      placeholder:
        "输入消息... (Enter / Ctrl+Enter 发送, Shift+Enter 换行, @引用文件, Esc 停止生成)",
      send: "发送消息 (Enter)",
      stop: "停止生成 (Esc)",
      hintSend: "Enter 发送",
      hintNewLine: "Shift + Enter 换行",
      hintAt: "@ 引用文件",
      hintCmdK: "⌘K 命令面板",
      hintEsc: "Esc 停止",
      charCounter: "{count} / {max}",
    },
    sidebar: {
      newSession: "新会话 (Cmd+N)",
      search: "搜索会话...",
      empty: "还没有会话",
      emptyHint: "点击 + 开始新对话",
      today: "今天",
      yesterday: "昨天",
      week: "近 7 天",
      older: "更早",
      group: "代码助手",
      noResults: "无匹配结果",
      rename: "重命名",
      confirmDelete: "确认删除",
      cmdPalette: "命令面板 (Cmd+K)",
      settings: "设置",
    },
    settings: {
      title: "设置",
      backTooltip: "返回对话 (Esc)",
      reloadTooltip: "从磁盘重载",
      openFolder: "打开文件夹",
      saveSuccess: "设置已保存",
      saveFailed: "保存失败: {message}",
      path: "~/codesage/settings.json",
      groups: {
        general: { label: "通用", subtitle: "显示语言、用户名、遥测" },
        models: { label: "Models", subtitle: "Provider / Model / API Key" },
        agent: { label: "预算 & Agent", subtitle: "迭代 / Token / SubAgent" },
        ui: { label: "UI", subtitle: "主题 / 字号 / 动画" },
        shortcuts: { label: "快捷键", subtitle: "查看与重绑" },
        mcp: { label: "MCP", subtitle: "外部工具服务器" },
        advanced: { label: "高级", subtitle: "日志 / 遥测 / 实验" },
      },
      general: {
        language: "显示语言",
        languageDesc: "界面显示语言,需要重启生效",
        autoUpdate: "自动检查更新",
        autoUpdateDesc: "新版本可用时通知",
        enableAutoUpdate: "启用自动更新",
        telemetry: "匿名遥测",
        telemetryDesc: "帮助改进 CodeSage(仅发送匿名使用统计)",
        enableTelemetry: "启用遥测",
      },
      models: {
        sectionTitle: "Models",
        sectionDesc: "配置 LLM Provider 与 API Key。每个 Provider 独立管理。",
        defaultModel: "默认模型",
        defaultModelDesc: "新会话开始时使用的模型",
        defaultProvider: "默认 Provider",
        addProvider: "添加 Provider",
        baseUrl: "Base URL",
        apiKey: "API Key",
        notSet: "未配置",
        unconfigured: "未配置",
        modelsCount: "模型 ({count})",
      },
      agent: {
        sectionTitle: "Agent 行为",
        sectionDesc: "子 Agent 行为与循环参数",
        enablePlanning: "Plan 模式",
        enablePlanningLabel: "大任务自动生成 Todo 计划",
        enableStreaming: "流式输出",
        enableStreamingLabel: "启用流式响应(逐字显示)",
      },
      ui: {
        sectionTitle: "UI",
        sectionDesc: "主题、字号、动画",
        theme: "主题",
        themeDesc: "auto = 跟随系统外观",
        themeAuto: "跟随系统",
        themeLight: "浅色",
        themeDark: "深色",
        fontSize: "字号",
        animSpd: "动画速度",
        animSpdDesc: "0 = 禁用动画,1 = 正常,2 = 快速",
        showThink: "思考过程",
        showThinkLabel: "显示 AI 思考过程",
        compact: "紧凑模式",
        compactDesc: "减少间距,信息密度更高",
        compactLabel: "启用紧凑布局",
        liveMd: "流式 Markdown",
        liveMdLabel: "流式过程中实时解析 Markdown",
      },
      shortcuts: {
        sectionTitle: "快捷键",
        sectionDesc: '查看与重绑快捷键。点击"录制"后按下新组合键。',
        record: "录制",
        send: "发送消息",
        newLine: "换行",
        stop: "停止生成",
        commandPalette: "命令面板",
        toggleThinking: "切换思考",
        switchModel: "切换模型",
        // O5.2: 侧边栏改为弹出框,toggleSidebar 改名为 openSessionHistory
        // 保留 toggleSidebar 翻译以防旧 JS 引用
        openSessionHistory: "历史会话",
        toggleSidebar: "切换侧边栏",
        newSession: "新会话",
        recordPrompt: "按下新键...",
        bound: "已绑定: {combo}",
      },
      mcp: {
        sectionTitle: "MCP",
        sectionDesc: "Model Context Protocol 服务器,用于接入外部工具",
        empty: "MCP 服务器配置开发中(Phase 5)",
        emptyHint: "请通过 settings.json 手动编辑 mcp.servers 数组",
      },
      advanced: {
        sectionTitle: "高级",
        sectionDesc: "日志级别、遥测、实验性功能",
        logLevel: "日志级别",
        telemetryEp: "遥测端点",
        telemetryEpDesc: "留空使用默认",
        customCss: "自定义 CSS",
        customCssDesc: "注入到界面底部",
        customCssPlaceholder: "/* 自定义样式 */",
      },
    },
    command: {
      title: "命令",
      search: "输入命令或搜索...",
      noResults: "无匹配命令",
      switchModel: "切换模型",
      modeAgent: "模式:Agent",
      modeAgentHint: "AI 自动规划并使用工具",
      modeAsk: "模式:Ask",
      modeAskHint: "仅对话,不调用工具",
      modeManual: "模式:Manual",
      modeManualHint: "手动选择工具",
      plan: "切换 Plan 模式",
      planHint: "大任务自动生成计划",
      planInfo: "Plan 模式由 Agent 模式自动启用",
      new: "新会话",
      newHint: "创建新会话",
      clear: "清空当前会话",
      clearHint: "清空消息但保留会话",
      themeLight: "主题:浅色",
      themeLightHint: "切换到浅色主题",
      themeDark: "主题:深色",
      themeDarkHint: "切换到深色主题",
      themeAuto: "主题:跟随系统",
      themeAutoHint: "根据系统外观自动切换",
      thinkingToggle: "显示/隐藏思考过程",
      thinkingToggleHint: "切换思考区可见性",
      help: "帮助",
      helpHint: "显示快捷键与命令列表",
      settings: "设置",
      settingsHint: "打开设置(开发中)",
      settingsInfo: "设置中心(Phase 4)开发中",
      reload: "重载界面",
      reloadHint: "刷新 Web UI",
      modelSwitched: "已切换到 {model}",
    },
    tool: {
      running: "执行中...",
      failed: "执行失败",
      args: "入参",
      output: "输出",
      diff: "变更",
      command: "命令",
      code: "代码",
      json: "JSON",
      list: "列表",
      error: "错误",
      subagent: "子 Agent",
      mcpBadge: "MCP: {server}",
      exitCode: "exit {code}",
      copy: "复制",
      apply: "应用到编辑器",
      reject: "回退",
    },
    plan: {
      title: "Plan",
      generated: "计划已生成",
      approved: "计划已批准",
      rejected: "计划被拒绝",
      modified: "计划已修改",
      running: "执行中",
      completed: "已完成",
      stepWaiting: "等待",
      stepRunning: "进行中",
      stepCompleted: "完成",
      stepFailed: "失败",
      stepBlocked: "阻塞",
      approve: "批准",
      edit: "编辑",
      reject: "拒绝",
      progress: "{done}/{total} · {percent}%",
    },
    walkthrough: {
      title: "欢迎使用 CodeSage",
      step1Title: "智能对话",
      step1Desc: "CodeSage 支持多轮对话、工具调用、代码生成、思考过程可视化。",
      step2Title: "工具与文件",
      step2Desc:
        "输入 @ 引用文件,工具调用会显示在助手消息中,可一键应用到编辑器。",
      step3Title: "快捷键",
      step3Desc: "Cmd+K 打开命令面板,Cmd+B 切换侧边栏,Esc 停止生成。",
      step4Title: "设置",
      step4Desc:
        "所有配置存在 ~/codesage/settings.json,可手动编辑或通过设置界面修改。",
      next: "下一步",
      skip: "跳过",
      done: "开始使用",
      openSettingsFolder: "打开设置目录",
    },
    error: {
      title: "错误",
      networkOffline: "界面加载异常,部分资源可能缺失",
      networkOfflineDesc: "CodeSage 界面加载异常,部分资源可能缺失。",
      reload: "重载界面",
      sendFailed: "发送消息失败: {message}",
      commandFailed: "命令执行失败: {message}",
      fileHandle: "文件处理失败: {message}",
      noModel: "暂无可用模型,请先在设置中配置 Provider",
      responseTimeout: "响应超时,请重试",
      apiKey: "未收到AI响应。请检查:1) 是否已配置API Key;2) 网络连接是否正常。",
    },
  },
  "en-US": {
    common: {
      save: "Save",
      cancel: "Cancel",
      confirm: "Confirm",
      delete: "Delete",
      edit: "Edit",
      add: "Add",
      remove: "Remove",
      enable: "Enable",
      disable: "Disable",
      back: "Back",
      reload: "Reload",
      close: "Close",
      yes: "Yes",
      no: "No",
      ok: "OK",
    },
    welcome: {
      title: "CodeSage AI Assistant",
      subtitle:
        "I can help you write code, debug issues, refactor projects, generate tests, and more. Supports multi-turn chat, tool calling, and code generation.",
      promptExplain: "Explain code",
      promptRefactor: "Refactor code",
      promptTest: "Generate test",
      promptFix: "Fix bug",
    },
    status: {
      ready: "Ready",
      thinking: "Thinking...",
      generating: "Generating...",
      error: "Error",
      retry: "Retry",
    },
    input: {
      placeholder:
        "Type a message... (Enter to send, Shift+Enter for newline, @ to mention files, Esc to stop)",
      send: "Send (Enter)",
      stop: "Stop (Esc)",
      hintSend: "Enter to send",
      hintNewLine: "Shift + Enter for newline",
      hintAt: "@ to mention files",
      hintCmdK: "⌘K command palette",
      hintEsc: "Esc to stop",
      charCounter: "{count} / {max}",
    },
    sidebar: {
      newSession: "New Session (Cmd+N)",
      search: "Search sessions...",
      empty: "No sessions yet",
      emptyHint: "Click + to start a new chat",
      today: "Today",
      yesterday: "Yesterday",
      week: "Previous 7 days",
      older: "Older",
      group: "Code Assistant",
      noResults: "No matching results",
      rename: "Rename",
      confirmDelete: "Confirm delete",
      cmdPalette: "Command palette (Cmd+K)",
      settings: "Settings",
    },
    settings: {
      title: "Settings",
      backTooltip: "Back (Esc)",
      reloadTooltip: "Reload from disk",
      openFolder: "Open folder",
      saveSuccess: "Settings saved",
      saveFailed: "Save failed: {message}",
      path: "~/codesage/settings.json",
      groups: {
        general: { label: "General", subtitle: "Language, telemetry, updates" },
        models: { label: "Models", subtitle: "Provider / Model / API Key" },
        agent: {
          label: "Agent",
          subtitle: "Sub-agent behavior and loop parameters",
        },
        ui: { label: "UI", subtitle: "Theme / Font size / Animation" },
        shortcuts: { label: "Shortcuts", subtitle: "View and rebind" },
        mcp: { label: "MCP", subtitle: "External tool servers" },
        advanced: {
          label: "Advanced",
          subtitle: "Logging / Telemetry / Experiments",
        },
      },
      general: {
        language: "Display language",
        languageDesc: "Interface language, requires restart",
        autoUpdate: "Automatic update check",
        autoUpdateDesc: "Notify when a new version is available",
        enableAutoUpdate: "Enable auto-update",
        telemetry: "Anonymous telemetry",
        telemetryDesc:
          "Help improve CodeSage (sends only anonymous usage stats)",
        enableTelemetry: "Enable telemetry",
      },
      models: {
        sectionTitle: "Models",
        sectionDesc:
          "Configure LLM providers and API keys. Each provider is managed independently.",
        defaultModel: "Default model",
        defaultModelDesc: "Model used when starting a new session",
        defaultProvider: "Default provider",
        addProvider: "Add provider",
        baseUrl: "Base URL",
        apiKey: "API Key",
        notSet: "Not set",
        unconfigured: "Unconfigured",
        modelsCount: "Models ({count})",
      },
      agent: {
        sectionTitle: "Agent",
        sectionDesc: "Sub-agent behavior and loop parameters",
        enablePlanning: "Plan mode",
        enablePlanningLabel: "Auto-generate Todo plan for large tasks",
        enableStreaming: "Streaming output",
        enableStreamingLabel: "Enable streaming response (word-by-word)",
      },
      ui: {
        sectionTitle: "UI",
        sectionDesc: "Theme, font size, animations",
        theme: "Theme",
        themeDesc: "auto = follow system appearance",
        themeAuto: "Follow system",
        themeLight: "Light",
        themeDark: "Dark",
        fontSize: "Font size",
        animSpd: "Animation speed",
        animSpdDesc: "0 = disabled, 1 = normal, 2 = fast",
        showThink: "Thinking process",
        showThinkLabel: "Show AI thinking process",
        compact: "Compact mode",
        compactDesc: "Reduce spacing, higher information density",
        compactLabel: "Enable compact layout",
        liveMd: "Streaming Markdown",
        liveMdLabel: "Parse Markdown in real-time during streaming",
      },
      shortcuts: {
        sectionTitle: "Shortcuts",
        sectionDesc:
          'View and rebind shortcuts. Click "Record" then press a new key combination.',
        record: "Record",
        send: "Send message",
        newLine: "Newline",
        stop: "Stop generation",
        commandPalette: "Command palette",
        toggleThinking: "Toggle thinking",
        switchModel: "Switch model",
        openSessionHistory: "Session history",
        toggleSidebar: "Toggle sidebar",
        newSession: "New session",
        recordPrompt: "Press a new key...",
        bound: "Bound: {combo}",
      },
      mcp: {
        sectionTitle: "MCP",
        sectionDesc: "Model Context Protocol servers for external tools",
        empty: "MCP server configuration is in development (Phase 5)",
        emptyHint: "Please edit mcp.servers array in settings.json manually",
      },
      advanced: {
        sectionTitle: "Advanced",
        sectionDesc: "Log level, telemetry, experimental features",
        logLevel: "Log level",
        telemetryEp: "Telemetry endpoint",
        telemetryEpDesc: "Leave empty for default",
        customCss: "Custom CSS",
        customCssDesc: "Injected at the bottom of the interface",
        customCssPlaceholder: "/* Custom styles */",
      },
    },
    command: {
      title: "Commands",
      search: "Type a command or search...",
      noResults: "No matching commands",
      switchModel: "Switch model",
      modeAgent: "Mode: Agent",
      modeAgentHint: "AI auto-plans and uses tools",
      modeAsk: "Mode: Ask",
      modeAskHint: "Chat only, no tool calls",
      modeManual: "Mode: Manual",
      modeManualHint: "Manually select tools",
      plan: "Toggle Plan mode",
      planHint: "Auto-generate plan for large tasks",
      planInfo: "Plan mode is auto-enabled by Agent mode",
      new: "New session",
      newHint: "Create new session",
      clear: "Clear current session",
      clearHint: "Clear messages but keep session",
      themeLight: "Theme: Light",
      themeLightHint: "Switch to light theme",
      themeDark: "Theme: Dark",
      themeDarkHint: "Switch to dark theme",
      themeAuto: "Theme: Follow system",
      themeAutoHint: "Follow system appearance",
      thinkingToggle: "Show/hide thinking process",
      thinkingToggleHint: "Toggle thinking visibility",
      help: "Help",
      helpHint: "Show shortcuts and commands",
      settings: "Settings",
      settingsHint: "Open settings (in development)",
      settingsInfo: "Settings (Phase 4) in development",
      reload: "Reload interface",
      reloadHint: "Refresh Web UI",
      modelSwitched: "Switched to {model}",
    },
    tool: {
      running: "Running...",
      failed: "Failed",
      args: "Inputs",
      output: "Output",
      diff: "Diff",
      command: "Command",
      code: "Code",
      json: "JSON",
      list: "List",
      error: "Error",
      subagent: "Sub-Agent",
      mcpBadge: "MCP: {server}",
      exitCode: "exit {code}",
      copy: "Copy",
      apply: "Apply to editor",
      reject: "Revert",
    },
    plan: {
      title: "Plan",
      generated: "Plan generated",
      approved: "Plan approved",
      rejected: "Plan rejected",
      modified: "Plan modified",
      running: "Running",
      completed: "Completed",
      stepWaiting: "Waiting",
      stepRunning: "Running",
      stepCompleted: "Completed",
      stepFailed: "Failed",
      stepBlocked: "Blocked",
      approve: "Approve",
      edit: "Edit",
      reject: "Reject",
      progress: "{done}/{total} · {percent}%",
    },
    walkthrough: {
      title: "Welcome to CodeSage",
      step1Title: "Smart conversations",
      step1Desc:
        "CodeSage supports multi-turn chat, tool calling, code generation, and visible thinking process.",
      step2Title: "Tools & files",
      step2Desc:
        "Type @ to mention files. Tool calls appear in assistant messages and can be applied to the editor with one click.",
      step3Title: "Shortcuts",
      step3Desc:
        "Cmd+K opens the command palette, Cmd+B toggles the sidebar, Esc stops generation.",
      step4Title: "Settings",
      step4Desc:
        "All config is stored in ~/codesage/settings.json. You can edit it manually or via the settings view.",
      next: "Next",
      skip: "Skip",
      done: "Get started",
      openSettingsFolder: "Open settings folder",
    },
    error: {
      title: "Error",
      networkOffline: "Loading exception, some resources may be missing",
      networkOfflineDesc:
        "CodeSage interface failed to load, some resources may be missing.",
      reload: "Reload",
      sendFailed: "Failed to send message: {message}",
      commandFailed: "Command execution failed: {message}",
      fileHandle: "File handling failed: {message}",
      noModel:
        "No models available. Please configure a provider in settings first.",
      responseTimeout: "Response timeout, please retry",
      apiKey:
        "No AI response received. Please check: 1) Is API Key configured? 2) Is the network connection OK?",
    },
  },
};

let currentLocale = "zh-CN";

export function getLocale() {
  return currentLocale;
}

export function setLocale(locale) {
  if (STRINGS[locale]) {
    currentLocale = locale;
    document.documentElement.setAttribute("lang", locale.split("-")[0]);
    // 持久化到 state,跨重启保留。
    // state.js 末尾的 state:change listener 会自动调 state.persist() 写 localStorage。
    // 走同步拿:bundler 会把 state.js 提前打包并挂到 window.__bundle__.state,
    // 这里直接取同步值,避免动态 import() 在打包后产生孤立的 .then() 链
    // 触发 SyntaxError。原注释说"为防循环依赖"才用动态 import,
    // 但 i18n 跟 state 互不依赖,改成同步即可。
    try {
      window.__bundle__.state.set("language", locale);
    } catch (_) {
      /* state 还没就绪/bundler 未加载 — 跳过持久化,不影响当前切换 */
    }
  }
}

/** t("settings.title") 或 t("greeting", { name: "Alice" }) */
export function t(key, params = {}) {
  const parts = key.split(".");
  let cur = STRINGS[currentLocale];
  for (const p of parts) {
    if (cur == null) return key;
    cur = cur[p];
  }
  if (typeof cur !== "string") return key;
  // 插值 {name}
  return cur.replace(/\{(\w+)\}/g, (_, k) =>
    params[k] != null ? String(params[k]) : `{${k}}`,
  );
}

/** 检查 key 是否存在 */
export function hasKey(key) {
  const parts = key.split(".");
  let cur = STRINGS[currentLocale];
  for (const p of parts) {
    if (cur == null) return false;
    cur = cur[p];
  }
  return typeof cur === "string";
}
