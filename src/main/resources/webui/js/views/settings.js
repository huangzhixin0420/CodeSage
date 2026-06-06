/**
 * Settings View — 6 大分组 + 表单
 *
 * 6 大分组(对齐设计文档 §13.3):
 *   ⚡ General — 通用
 *   🤖 Models — Provider / Model / API Key
 *   ⚙ Agent — Agent 行为与子 Agent
 *   🎨 UI — 主题 / 字号 / 紧凑模式 / 动画
 *   ⌨ Shortcuts — 快捷键(只读 + 重新录制)
 *   🔌 MCP — MCP 服务器
 *   🛠 Advanced — 遥测 / 日志 / 实验性
 *
 * 实时保存:每次修改 debounce 500ms 自动写
 * 重要变更:Provider 增删 / API Key 显示 toast
 */

import { bridge } from "../bridge.js";
import { state } from "../state.js";
import { toast } from "../components/cs-toast.js";
import { Modal } from "../components/cs-modal.js";
import { escapeHtml, debounce } from "../utils.js";
import { setLocale, getLocale } from "../i18n.js";

const PROVIDER_TYPES = [
  { value: "minimax", label: "MiniMax" },
  { value: "kimi", label: "Kimi (Moonshot)" },
  { value: "openai", label: "OpenAI" },
  { value: "openai-compatible", label: "OpenAI 兼容" },
  { value: "anthropic", label: "Anthropic" },
  { value: "google", label: "Google Gemini" },
];

const GROUP_DEFS = [
  {
    id: "general",
    icon: "fa-bolt",
    label: "通用",
    subtitle: "显示语言、用户名、遥测",
  },
  {
    id: "models",
    icon: "fa-robot",
    label: "Models",
    subtitle: "Provider / Model / API Key",
  },
  {
    id: "agent",
    icon: "fa-gears",
    label: "预算 & Agent",
    subtitle: "迭代 / Token / SubAgent",
  },
  { id: "ui", icon: "fa-palette", label: "UI", subtitle: "主题 / 字号 / 动画" },
  {
    id: "shortcuts",
    icon: "fa-keyboard",
    label: "快捷键",
    subtitle: "查看与重绑",
  },
  { id: "mcp", icon: "fa-plug", label: "MCP", subtitle: "外部工具服务器" },
  {
    id: "advanced",
    icon: "fa-screwdriver-wrench",
    label: "高级",
    subtitle: "日志 / 遥测 / 实验",
  },
];

class SettingsView {
  constructor() {
    this.container = null;
    this.currentGroup = "general";
    this.data = null; // 当前 settings 副本
    this._saveDebounced = debounce(() => this._save(), 500);
    this._onSaveError = null;
  }

  // ===== Lifecycle =====
  init(container) {
    this.container = container;
    this._render();
    // 不再直接设 bridge.onMessage（会被 chat 覆盖）
    // 改由 chat.js 委派 _onBridge 调用
    // 保留向后兼容：如果 chat 未设 onMessage，我们自己设
    if (!bridge.onMessage) {
      bridge.onMessage = (msg) => this._onBridge(msg);
    }
  }

  show() {
    if (!this.container) return;
    this.container.style.display = "";
    this._refreshData();
  }

  hide() {
    if (this.container) this.container.style.display = "none";
  }

  destroy() {
    this.container?.remove();
  }

  // ===== Rendering =====
  _render() {
    if (!this.container) return;
    this.container.classList.add("cs-settings");
    this.container.innerHTML = `
      <div class="cs-settings-shell">
        <aside class="cs-settings-sidebar">
          <div class="cs-settings-header">
            <button class="cs-settings-back" data-cs-action="back" data-cs-tooltip="返回对话 (Esc)">
              <i class="fas fa-arrow-left"></i>
            </button>
            <span class="cs-settings-title">设置</span>
            <button class="cs-settings-reload" data-cs-action="reload" data-cs-tooltip="从磁盘重载">
              <i class="fas fa-rotate"></i>
            </button>
          </div>
          <nav class="cs-settings-nav" data-cs-role="nav"></nav>
          <div class="cs-settings-footer">
            <div class="cs-settings-path" data-cs-role="path">~/.codesage/settings.json</div>
            <button class="cs-button size-sm variant-ghost" data-cs-action="open-folder">
              <i class="fas fa-folder-open"></i>&nbsp;打开文件夹
            </button>
          </div>
        </aside>
        <main class="cs-settings-main" data-cs-role="main"></main>
      </div>
    `;
    this._renderNav();
    this._renderMain();

    // Bind header actions
    this.container
      .querySelector('[data-cs-action="back"]')
      .addEventListener("click", () => this.onBack?.());
    this.container
      .querySelector('[data-cs-action="reload"]')
      .addEventListener("click", () => {
        bridge.send({ type: "settings_reload" });
        toast.info("已请求重载设置");
      });
    this.container
      .querySelector('[data-cs-action="open-folder"]')
      .addEventListener("click", () =>
        bridge.send({ type: "settings_open_folder" }),
      );
  }

  _renderNav() {
    const nav = this.container.querySelector('[data-cs-role="nav"]');
    nav.innerHTML = GROUP_DEFS.map(
      (g) => `
      <a href="#${g.id}" class="cs-settings-nav-item ${g.id === this.currentGroup ? "active" : ""}" data-group="${g.id}">
        <i class="fas ${g.icon}"></i>
        <div class="cs-settings-nav-text">
          <div class="cs-settings-nav-label">${escapeHtml(g.label)}</div>
          <div class="cs-settings-nav-subtitle">${escapeHtml(g.subtitle)}</div>
        </div>
      </a>
    `,
    ).join("");
    nav.querySelectorAll(".cs-settings-nav-item").forEach((el) => {
      el.addEventListener("click", (e) => {
        e.preventDefault();
        this._switchGroup(el.dataset.group);
      });
    });
  }

  _switchGroup(groupId) {
    this.currentGroup = groupId;
    this.container.querySelectorAll(".cs-settings-nav-item").forEach((el) => {
      el.classList.toggle("active", el.dataset.group === groupId);
    });
    this._renderMain();
  }

  _renderMain() {
    const main = this.container.querySelector('[data-cs-role="main"]');
    const renderer = GROUP_RENDERERS[this.currentGroup];
    if (!main) return;
    if (!renderer) {
      main.innerHTML = `<div class="cs-settings-empty">未知分组: ${escapeHtml(this.currentGroup)}</div>`;
      return;
    }
    if (!this.data) {
      // 等待数据,显示骨架+onboarding(给用户一个“能点”的东西而不是纯转圈)
      main.innerHTML = `
        <div class="cs-settings-loading">
          <i class="fas fa-circle-notch spin"></i>
          <p>正在加载设置…</p>
        </div>
      `;
      this._renderOnboardingHint(main);
      return;
    }
    main.innerHTML = "";
    renderer(this, main);
  }

  /**
   * 在加载期间额外渲染一个 onboarding 提示区域 —
   * 告诉用户“文件不存在?点这里从环境里挑一个 Provider 快速配置”
   * 而不是只能等 loading 转完 (JSON decode 挂了就会一直转)
   */
  _renderOnboardingHint(main) {
    const hint = document.createElement("div");
    hint.className = "cs-settings-onboarding";
    hint.innerHTML = `
      <p class="cs-form-field-desc">未检测到 ~/.codesage/settings.json。插件会在首次启动时自动创建默认文件。</p>
      <div class="cs-settings-onboarding-actions">
        <button class="cs-button size-sm variant-secondary" data-cs-action="refresh">
          <i class="fas fa-rotate"></i>&nbsp;重试
        </button>
        <button class="cs-button size-sm variant-ghost" data-cs-action="open-folder-onboard">
          <i class="fas fa-folder-open"></i>&nbsp;打开设置目录
        </button>
      </div>
    `;
    main.appendChild(hint);
    hint
      .querySelector('[data-cs-action="refresh"]')
      .addEventListener("click", () => this._refreshData());
    hint
      .querySelector('[data-cs-action="open-folder-onboard"]')
      .addEventListener("click", () =>
        bridge.send({ type: "settings_open_folder" }),
      );
  }

  _refreshData() {
    bridge.send({ type: "settings_get" });
  }

  // ===== Save =====
  _save() {
    if (!this.data) return;
    bridge.send({ type: "settings_update", settings: this.data });
  }

  _onBridge(msg) {
    if (msg.type === "settings_data") {
      this.data = msg.settings;
      if (this._dataLoadWatchdog) {
        clearTimeout(this._dataLoadWatchdog);
        this._dataLoadWatchdog = null;
      }
      // 更新路径显示 (从 settings.path 字段)
      const pathEl = this.container?.querySelector('[data-cs-role="path"]');
      if (pathEl && msg.settings?.path) {
        const p = String(msg.settings.path);
        pathEl.textContent = p.replace(/\/[^/]+$/, "") || p;
      }
      // 同步 i18n locale:Kotlin settings.json 里的 ui.language 跟当前 currentLocale
      // 不一致时(例:用户切了语言保存后,或外部直接编辑 settings.json)调 setLocale
      // 让 i18n 字符串查找也跟着切。select 显示本身由 this.data 驱动,这一行只
      // 保证 currentLocale 模块变量也跟上,避免下次渲染时 t() 用错的语言。
      const lang = msg.settings?.ui?.language;
      if (lang && lang !== getLocale()) {
        setLocale(lang);
      }
      this._renderMain();
    } else if (msg.type === "settings_saved") {
      toast.success("设置已保存");
    } else if (msg.type === "settings_error") {
      // settings.json 可能“脏”得代码层无法自动修复(例:JSON 错位 / 字段类型错 / 文件过大)
      // 这种情况下,仅 toast 报错不够——需要让用户能“一键打开设置目录”手动清理
      // 判断 “可能是文件问题” 的几个关键词:解析/parse/读/读不到/损坏/格式/size/权限
      const lower = String(msg.message || "").toLowerCase();
      const looksLikeFileIssue =
        lower.includes("解析") ||
        lower.includes("parse") ||
        lower.includes("读") ||
        lower.includes("损坏") ||
        lower.includes("格式") ||
        lower.includes("size") ||
        lower.includes("oom") ||
        lower.includes("权限") ||
        lower.includes("permission");
      if (looksLikeFileIssue && bridge?.send) {
        toast.error("设置文件异常: " + msg.message, {
          variant: "error",
          duration: 12000,
          action: {
            label: "打开设置目录",
            onClick: () => {
              try {
                bridge.send({ type: "settings_open_folder" });
              } catch (e) {
                console.warn(
                  "[settings] failed to send settings_open_folder:",
                  e,
                );
              }
            },
          },
        });
      } else {
        toast.error("保存失败: " + msg.message);
      }
    } else if (msg.type === "set_api_key_result") {
      // 调用由 modal 注册的 handler
      if (typeof this._onSetApiKeyResult === "function") {
        this._onSetApiKeyResult(msg);
        this._onSetApiKeyResult = null;
      }
    } else if (msg.type === "test_provider_result") {
      if (typeof this._onTestProviderResult === "function") {
        this._onTestProviderResult(msg);
        this._onTestProviderResult = null;
      }
    } else if (msg.type === "legacy_migration_preview") {
      this._handleMigrationPreview(msg);
    } else if (msg.type === "legacy_migration_done") {
      toast.success(`迁移完成: ${msg.providerCount} 个 Provider`);
      // 刷新 settings 视图数据
      bridge.send({ type: "settings_get" });
    } else if (msg.type === "legacy_migration_error") {
      toast.error("迁移失败: " + (msg.message || "未知错误"));
    } else if (msg.type === "legacy_migration_skipped") {
      // no-op
    }
  }

  _handleMigrationPreview(msg) {
    if (!msg.hasData) return;
    // 推迟到下一帧，避免与启动逻辑冲突
    setTimeout(() => this._showMigrationWizard(msg), 100);
  }

  _showMigrationWizard(msg) {
    const preview = msg.preview || {};
    const providers = msg.providers || [];
    const newSettings = msg.newSettings || {};
    // 检查用户是否已跳过
    if (localStorage.getItem("codesage_migration_skipped_v1") === "1") return;

    const content = document.createElement("div");
    content.className = "cs-migration-wizard";
    content.innerHTML = `
      <h3><i class="fas fa-box-open"></i>&nbsp;检测到旧 IDE 配置</h3>
      <p style="color:var(--fg-2);font-size:13px;margin:0 0 16px;">CodeSage 4.x 之前的配置存在 <code>CodeSagePlugin.xml</code> 中,可以一次性迁移到新 <code>settings.json</code>:</p>
      <div class="cs-migration-stat"><span class="label">Provider 数量</span><span class="value">${preview.providerCount || 0}</span></div>
      <div class="cs-migration-stat"><span class="label">已配置 API Key</span><span class="value">${preview.providersWithKeys || 0}</span></div>
      <div class="cs-migration-stat"><span class="label">MCP 服务器</span><span class="value">${preview.mcpServerCount || 0}</span></div>
      <div class="cs-migration-stat"><span class="label">默认 Provider</span><span class="value">${escapeHtml(newSettings.defaults?.providerId || "—")}</span></div>
      <div class="cs-migration-stat"><span class="label">默认模型</span><span class="value">${escapeHtml(newSettings.defaults?.model || "—")}</span></div>
      ${
        providers.length
          ? `<div style="margin-top:12px;font-size:12px;color:var(--fg-2);">
              ${providers
                .map(
                  (p) =>
                    `<div>· ${escapeHtml(p.name)} ${p.hasKey ? '<span style="color:var(--success);">✓</span>' : '<span style="color:var(--warning);">无 Key</span>'}</div>`,
                )
                .join("")}
            </div>`
          : ""
      }
      ${
        (preview.warnings || []).length
          ? `<div style="margin-top:12px;padding:8px;background:var(--warning-soft);border-radius:6px;font-size:12px;color:var(--warning);">
              <i class="fas fa-exclamation-triangle"></i>&nbsp;${preview.warnings
                .map((w) => escapeHtml(w))
                .join("<br>")}
            </div>`
          : ""
      }
      <div class="cs-migration-actions">
        <button class="cs-button variant-ghost size-md" data-cs-action="preview-diff">查看差异</button>
        <button class="cs-button variant-secondary size-md" data-cs-action="skip">稍后</button>
        <button class="cs-button variant-primary size-md" data-cs-action="migrate">
          <i class="fas fa-arrow-right"></i>&nbsp;迁移
        </button>
      </div>
    `;
    const modal = new Modal({
      title: "迁移向导",
      content,
      size: "md",
      dismissible: false,
    });
    content
      .querySelector('[data-cs-action="migrate"]')
      .addEventListener("click", () => {
        bridge.send({ type: "legacy_migration_run" });
        modal.close();
      });
    content
      .querySelector('[data-cs-action="skip"]')
      .addEventListener("click", () => {
        try {
          localStorage.setItem("codesage_migration_skipped_v1", "1");
        } catch (e) {}
        bridge.send({ type: "legacy_migration_skip" });
        modal.close();
      });
    content
      .querySelector('[data-cs-action="preview-diff"]')
      .addEventListener("click", () => {
        this._showMigrationDiff(msg);
      });
    modal.open();
  }

  _showMigrationDiff(msg) {
    const newSettings = msg.newSettings || {};
    const content = document.createElement("div");
    content.style.cssText = "font-size:12px;line-height:1.5;";
    const providerRows = (newSettings.providers || [])
      .map(
        (p) => `
        <tr>
          <td>${escapeHtml(p.name)}</td>
          <td><code>${escapeHtml(p.type)}</code></td>
          <td><code>${escapeHtml(p.baseUrl || "—")}</code></td>
          <td>${escapeHtml((p.models || []).map((m) => m.id).join(", "))}</td>
          <td>${p.enabled ? "✓" : "—"}</td>
        </tr>`,
      )
      .join("");
    content.innerHTML = `
      <p>新 settings.json 将包含以下 Provider:</p>
      <table style="width:100%;border-collapse:collapse;">
        <thead>
          <tr style="text-align:left;background:var(--bg-1);">
            <th style="padding:6px;">名称</th>
            <th style="padding:6px;">类型</th>
            <th style="padding:6px;">Base URL</th>
            <th style="padding:6px;">模型</th>
            <th style="padding:6px;">启用</th>
          </tr>
        </thead>
        <tbody>${providerRows || '<tr><td colspan="5" style="text-align:center;color:var(--fg-3);">无 Provider</td></tr>'}</tbody>
      </table>
    `;
    const m = new Modal({
      title: "迁移预览",
      content,
      size: "lg",
    });
    m.open();
  }

  // ===== Helpers =====
  _formField({ id, label, description, hint, children }) {
    return `
      <div class="cs-form-field" data-field-id="${id}">
        <div class="cs-form-field-label">
          <label for="sf-${id}">${escapeHtml(label)}</label>
          ${hint ? `<span class="cs-form-field-hint">${escapeHtml(hint)}</span>` : ""}
        </div>
        ${description ? `<div class="cs-form-field-desc">${escapeHtml(description)}</div>` : ""}
        <div class="cs-form-field-control">${children}</div>
      </div>
    `;
  }

  _toggle(name, value, label, desc) {
    return `
      <label class="cs-toggle">
        <input type="checkbox" data-cs-field="${name}" ${value ? "checked" : ""} />
        <span class="cs-toggle-track"><span class="cs-toggle-thumb"></span></span>
        <span class="cs-toggle-label">${escapeHtml(label)}</span>
      </label>
      ${desc ? `<div class="cs-form-field-desc">${escapeHtml(desc)}</div>` : ""}
    `;
  }

  _input(name, value, placeholder = "", type = "text") {
    return `<input type="${type}" data-cs-field="${name}" value="${escapeHtml(String(value))}" placeholder="${escapeHtml(placeholder)}" class="cs-input" />`;
  }

  _select(name, value, options) {
    return `<select data-cs-field="${name}" class="cs-select">${options
      .map(
        (o) =>
          `<option value="${escapeHtml(o.value)}" ${o.value === value ? "selected" : ""}>${escapeHtml(o.label)}</option>`,
      )
      .join("")}</select>`;
  }

  _slider(name, value, min, max, step = 1) {
    return `
      <div class="cs-slider">
        <input type="range" data-cs-field="${name}" min="${min}" max="${max}" step="${step}" value="${value}" class="cs-slider-range" />
        <span class="cs-slider-value" data-cs-display="${name}">${value}</span>
      </div>
    `;
  }

  _bindFields(root) {
    root.querySelectorAll("[data-cs-field]").forEach((el) => {
      const name = el.dataset.csField;
      el.addEventListener("input", (e) => {
        let v = e.target.value;
        if (el.type === "checkbox") v = el.checked;
        else if (el.type === "number" || el.type === "range") v = Number(v);
        this._setField(name, v);
        // update slider display
        const display = root.querySelector(`[data-cs-display="${name}"]`);
        if (display) display.textContent = v;
        // 实时应用设置到主页面 — 不等保存
        this._applySetting(name, v);
        this._saveDebounced();
      });
    });
  }

  /**
   * 实时把单个设置反映到主页面 — 不等 _saveDebounced 500ms
   * 语言/字号/紧凑模式这种改完应该立刻看到效果
   */
  _applySetting(path, value) {
    try {
      if (path === "ui.language") {
        // i18n locale 切换 — setLocale 是同步函数,只更新内存
        setLocale(value);
      } else if (path === "ui.fontSize") {
        // 全站基础字号
        document.documentElement.style.setProperty("--text-base", value + "px");
      } else if (path === "ui.compactMode") {
        document.body.classList.toggle("cs-compact-mode", !!value);
      } else if (path === "ui.theme") {
        // 主题色模式 — 优先调主页面 chat 的 setTheme 让聊天页立即跟随
        if (window.CodeSage?.chat?.setTheme) {
          window.CodeSage.chat.setTheme(value);
        }
        if (this.setTheme) this.setTheme(value);
      } else if (path === "ui.showThinking") {
        // 思考气泡显示 — 聊天页已绑定了 thinkingToggleVisibility, 这里不需要重做
      }
    } catch (e) {
      console.warn("[CodeSage] _applySetting failed for", path, e);
    }
  }

  _setField(path, value) {
    if (!this.data) return;
    const parts = path.split(".");
    let obj = this.data;
    for (let i = 0; i < parts.length - 1; i++) {
      obj = obj[parts[i]];
      if (!obj) return;
    }
    obj[parts[parts.length - 1]] = value;
  }
}

// ==================== Group Renderers ====================

const GROUP_RENDERERS = {
  general: (view, root) => {
    if (!view.data) return;
    const s = view.data;
    root.innerHTML = `
      <h2 class="cs-settings-h2">通用</h2>
      <p class="cs-settings-section-desc">显示语言、用户名、遥测开关、自动更新</p>
      <div class="cs-settings-section">
        ${view._formField({
          id: "lang",
          label: "显示语言",
          description: "界面显示语言,需要重启生效",
          children: view._select("ui.language", s.ui.language, [
            { value: "zh-CN", label: "简体中文" },
            { value: "en-US", label: "English" },
            { value: "ja-JP", label: "日本語" },
          ]),
        })}
        ${view._formField({
          id: "autoUpdate",
          label: "自动检查更新",
          description: "新版本可用时通知",
          children: view._toggle(
            "advanced.autoUpdate",
            s.advanced.autoUpdate,
            "启用自动更新",
          ),
        })}
        ${view._formField({
          id: "telemetry",
          label: "匿名遥测",
          description: "帮助改进 CodeSage(仅发送匿名使用统计)",
          children: view._toggle(
            "advanced.enableTelemetry",
            s.advanced.enableTelemetry,
            "启用遥测",
          ),
        })}
      </div>
    `;
    view._bindFields(root);
  },

  models: (view, root) => {
    if (!view.data) return;
    const s = view.data;
    root.innerHTML = `
      <h2 class="cs-settings-h2">Models</h2>
      <p class="cs-settings-section-desc">配置 LLM Provider 与 API Key。每个 Provider 独立管理。</p>
      <div class="cs-settings-section">
        ${(s.providers || []).map((p, i) => view._renderProviderCard(p, i)).join("")}
        <button class="cs-button size-md variant-secondary" data-cs-action="add-provider">
          <i class="fas fa-plus"></i>&nbsp;添加 Provider
        </button>
      </div>
      <h2 class="cs-settings-h2" style="margin-top:32px;">默认模型</h2>
      <div class="cs-settings-section">
        ${view._formField({
          id: "defaultModel",
          label: "默认模型",
          description: "新会话开始时使用的模型",
          children: view._input(
            "defaults.model",
            s.defaults.model,
            "MiniMax-M2.7",
          ),
        })}
        ${view._formField({
          id: "defaultProvider",
          label: "默认 Provider",
          children: view._select(
            "defaults.providerId",
            s.defaults.providerId,
            (s.providers || []).map((p) => ({ value: p.id, label: p.name })),
          ),
        })}
      </div>
    `;
    view._bindFields(root);
    view._bindProviderActions(root);
  },

  agent: (view, root) => {
    if (!view.data) return;
    const s = view.data;
    const a = s.agent;
    root.innerHTML = `
      <h2 class="cs-settings-h2">Agent 行为</h2>
      <p class="cs-settings-section-desc">子 Agent 行为与循环参数</p>
      <div class="cs-settings-section">
        ${view._formField({
          id: "enablePlanning",
          label: "Plan 模式",
          children: view._toggle(
            "agent.enablePlanning",
            a.enablePlanning,
            "大任务自动生成 Todo 计划",
          ),
        })}
        ${view._formField({
          id: "enableStreaming",
          label: "流式输出",
          children: view._toggle(
            "agent.enableStreaming",
            a.enableStreaming,
            "启用流式响应(逐字显示)",
          ),
        })}
      </div>
    `;
    view._bindFields(root);
  },

  ui: (view, root) => {
    if (!view.data) return;
    const s = view.data;
    const u = s.ui;
    root.innerHTML = `
      <h2 class="cs-settings-h2">UI</h2>
      <p class="cs-settings-section-desc">主题、字号、动画</p>
      <div class="cs-settings-section">
        ${view._formField({
          id: "theme",
          label: "主题",
          description: "auto = 跟随系统外观",
          children: view._select("ui.theme", u.theme, [
            { value: "auto", label: "跟随系统" },
            { value: "light", label: "浅色" },
            { value: "dark", label: "深色" },
          ]),
        })}
        ${view._formField({
          id: "fontSize",
          label: "字号",
          children: view._slider("ui.fontSize", u.fontSize, 11, 18),
        })}
        ${view._formField({
          id: "animSpd",
          label: "动画速度",
          description: "0 = 禁用动画,1 = 正常,2 = 快速",
          children: view._slider(
            "ui.animationSpeed",
            u.animationSpeed,
            0,
            2,
            0.1,
          ),
        })}
        ${view._formField({
          id: "showThink",
          label: "思考过程",
          children: view._toggle(
            "ui.showThinking",
            u.showThinking,
            "显示 AI 思考过程",
          ),
        })}
        ${view._formField({
          id: "compact",
          label: "紧凑模式",
          description: "减少间距,信息密度更高",
          children: view._toggle(
            "ui.compactMode",
            u.compactMode,
            "启用紧凑布局",
          ),
        })}
        ${view._formField({
          id: "liveMd",
          label: "流式 Markdown",
          children: view._toggle(
            "ui.streamMarkdownLive",
            u.streamMarkdownLive,
            "流式过程中实时解析 Markdown",
          ),
        })}
      </div>
    `;
    view._bindFields(root);
  },

  shortcuts: (view, root) => {
    if (!view.data) return;
    const s = view.data.shortcuts;
    const rows = [
      ["send", "发送消息"],
      ["newLine", "换行"],
      ["stop", "停止生成"],
      ["commandPalette", "命令面板"],
      ["toggleThinking", "切换思考"],
      ["switchModel", "切换模型"],
      ["toggleSidebar", "切换侧边栏"],
      ["newSession", "新会话"],
    ];
    root.innerHTML = `
      <h2 class="cs-settings-h2">快捷键</h2>
      <p class="cs-settings-section-desc">查看与重绑快捷键。点击"录制"后按下新组合键。</p>
      <div class="cs-settings-section">
        <table class="cs-shortcuts-table">
          <thead><tr><th>动作</th><th>当前键</th><th></th></tr></thead>
          <tbody>
            ${rows
              .map(
                ([k, label]) => `
              <tr data-shortcut="${k}">
                <td>${escapeHtml(label)}</td>
                <td><kbd class="cs-shortcut-key">${escapeHtml(s[k] || "")}</kbd></td>
                <td><button class="cs-button size-sm variant-ghost" data-cs-action="record-shortcut" data-key="${k}">录制</button></td>
              </tr>
            `,
              )
              .join("")}
          </tbody>
        </table>
      </div>
    `;
    view._bindShortcutRecord(root);
  },

  mcp: (view, root) => {
    if (!view.data) return;
    const s = view.data;
    const servers = s.mcp?.servers || [];
    root.innerHTML = `
      <h2 class="cs-settings-h2">MCP</h2>
      <p class="cs-settings-section-desc">Model Context Protocol 服务器,用于接入外部工具</p>
      <div class="cs-settings-section">
        <div class="cs-mcp-list">
          ${
            servers.length === 0
              ? `
            <div class="cs-mcp-empty">
              <i class="fas fa-plug"></i>
              <p>还没有 MCP 服务器</p>
              <p class="cs-form-field-desc">点击下方按钮添加第一个</p>
            </div>
          `
              : servers.map((srv, i) => view._renderMcpCard(srv, i)).join("")
          }
        </div>
        <button class="cs-button size-md variant-secondary" data-cs-action="add-mcp" style="margin-top:var(--space-3);">
          <i class="fas fa-plus"></i>&nbsp;添加 MCP 服务器
        </button>
      </div>
    `;
    view._bindMcpActions(root);
  },

  advanced: (view, root) => {
    if (!view.data) return;
    const s = view.data;
    root.innerHTML = `
      <h2 class="cs-settings-h2">高级</h2>
      <p class="cs-settings-section-desc">日志级别、遥测、实验性功能</p>
      <div class="cs-settings-section">
        ${view._formField({
          id: "logLevel",
          label: "日志级别",
          children: view._select("advanced.logLevel", s.advanced.logLevel, [
            { value: "TRACE", label: "TRACE" },
            { value: "DEBUG", label: "DEBUG" },
            { value: "INFO", label: "INFO" },
            { value: "WARN", label: "WARN" },
            { value: "ERROR", label: "ERROR" },
          ]),
        })}
        ${view._formField({
          id: "telemetryEp",
          label: "遥测端点",
          description: "留空使用默认",
          children: view._input(
            "advanced.telemetryEndpoint",
            s.advanced.telemetryEndpoint,
            "https://...",
          ),
        })}
        ${view._formField({
          id: "customCss",
          label: "自定义 CSS",
          description: "注入到界面底部",
          children: `<textarea data-cs-field="advanced.customCss" class="cs-textarea" rows="4" placeholder="/* 自定义样式 */">${escapeHtml(s.advanced.customCss)}</textarea>`,
        })}
      </div>
    `;
    view._bindFields(root);
  },
};

SettingsView.prototype._renderProviderCard = function (p, index) {
  const isMcp = p.type?.startsWith("mcp");
  return `
    <div class="cs-provider-card" data-provider-id="${escapeHtml(p.id)}">
      <div class="cs-provider-card-header">
        <div class="cs-provider-card-title">
          <span class="cs-provider-card-dot ${p.enabled ? "enabled" : "disabled"}"></span>
          <strong>${escapeHtml(p.name || p.id)}</strong>
        </div>
        <div class="cs-provider-card-actions">
          <button class="cs-button size-sm variant-ghost" data-cs-action="toggle-provider" data-id="${escapeHtml(p.id)}">
            <i class="fas fa-${p.enabled ? "pause" : "play"}"></i>
            ${p.enabled ? "禁用" : "启用"}
          </button>
          <button class="cs-button size-sm variant-ghost" data-cs-action="edit-provider" data-id="${escapeHtml(p.id)}">
            <i class="fas fa-pen"></i>&nbsp;编辑
          </button>
          <button class="cs-button size-sm variant-ghost" data-cs-action="remove-provider" data-id="${escapeHtml(p.id)}" style="color:var(--error);">
            <i class="fas fa-trash"></i>
          </button>
        </div>
      </div>
      <div class="cs-provider-card-body">
        <div class="cs-provider-card-row">
          <span class="cs-provider-card-label">Base URL</span>
          <code class="cs-provider-card-value">${escapeHtml(p.baseUrl || "(未设置)")}</code>
        </div>
        <div class="cs-provider-card-row">
          <span class="cs-provider-card-label">API Key</span>
          <span class="cs-provider-card-value" data-cs-role="api-key">
            <span data-cs-role="api-key-masked" style="font-family:var(--font-mono);letter-spacing:1px;">••••••••••••</span>
            <span data-cs-role="api-key-value" style="display:none;">${escapeHtml(p.apiKeyRef || "")}</span>
            <button class="cs-icon-btn-tiny" data-cs-action="toggle-api-key" aria-label="显示/隐藏 Key" type="button" style="margin-left:6px;border:none;background:transparent;color:var(--fg-2);cursor:pointer;padding:2px 4px;">
              <i class="fas fa-eye"></i>
            </button>
          </span>
        </div>
        <div class="cs-provider-card-row">
          <span class="cs-provider-card-label">模型 (${(p.models || []).length})</span>
          <span class="cs-provider-card-value">${(p.models || [])
            .slice(0, 3)
            .map((m) => escapeHtml(m.id))
            .join(
              ", ",
            )}${(p.models || []).length > 3 ? ` +${(p.models || []).length - 3}` : ""}</span>
        </div>
      </div>
    </div>
  `;
};

SettingsView.prototype._renderMcpCard = function (srv, index) {
  const transportIcon =
    {
      stdio: "fa-terminal",
      http: "fa-globe",
      websocket: "fa-bolt",
    }[srv.transport] || "fa-plug";
  const transportLabel =
    {
      stdio: "stdio",
      http: "HTTP",
      websocket: "WebSocket",
    }[srv.transport] || srv.transport;
  const detail =
    srv.transport === "stdio"
      ? (srv.command || "—") +
        (srv.args?.length ? " " + srv.args.join(" ") : "")
      : srv.url || "—";
  return `
    <div class="cs-provider-card cs-mcp-card" data-mcp-id="${escapeHtml(srv.id)}">
      <div class="cs-provider-card-header">
        <div class="cs-provider-card-title">
          <span class="cs-provider-card-dot ${srv.enabled ? "enabled" : "disabled"}"></span>
          <i class="fas ${transportIcon}"></i>&nbsp;
          <strong>${escapeHtml(srv.name || srv.id)}</strong>
          <span class="cs-provider-card-type">${escapeHtml(transportLabel)}</span>
        </div>
        <div class="cs-provider-card-actions">
          <button class="cs-button size-sm variant-ghost" data-cs-action="toggle-mcp" data-id="${escapeHtml(srv.id)}">
            <i class="fas fa-${srv.enabled ? "pause" : "play"}"></i>
            ${srv.enabled ? "禁用" : "启用"}
          </button>
          <button class="cs-button size-sm variant-ghost" data-cs-action="edit-mcp" data-id="${escapeHtml(srv.id)}">
            <i class="fas fa-pen"></i>&nbsp;编辑
          </button>
          <button class="cs-button size-sm variant-ghost" data-cs-action="remove-mcp" data-id="${escapeHtml(srv.id)}" style="color:var(--error);">
            <i class="fas fa-trash"></i>
          </button>
        </div>
      </div>
      <div class="cs-provider-card-body">
        <div class="cs-provider-card-row">
          <span class="cs-provider-card-label">${srv.transport === "stdio" ? "命令" : "URL"}</span>
          <code class="cs-provider-card-value">${escapeHtml(detail)}</code>
        </div>
        ${
          Object.keys(srv.env || {}).length
            ? `<div class="cs-provider-card-row">
                <span class="cs-provider-card-label">环境变量</span>
                <span class="cs-provider-card-value">${Object.keys(srv.env).length} 个</span>
              </div>`
            : ""
        }
      </div>
    </div>
  `;
};

SettingsView.prototype._bindMcpActions = function (root) {
  const self = this;
  root.querySelectorAll('[data-cs-action="toggle-mcp"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      const srv = this.data.mcp.servers.find((x) => x.id === id);
      if (!srv) return;
      srv.enabled = !srv.enabled;
      this._save();
      this._renderMain();
      toast.success(`${srv.name} 已${srv.enabled ? "启用" : "禁用"}`);
    });
  });
  root.querySelectorAll('[data-cs-action="edit-mcp"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      this._openMcpModal(btn.dataset.id);
    });
  });
  root.querySelectorAll('[data-cs-action="remove-mcp"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      const srv = this.data.mcp.servers.find((x) => x.id === id);
      if (!srv) return;
      this._confirmRemoveMcp(id, srv.name);
    });
  });
  root.querySelectorAll('[data-cs-action="add-mcp"]').forEach((btn) => {
    btn.addEventListener("click", () => this._openMcpModal(null));
  });
};

SettingsView.prototype._confirmRemoveMcp = function (id, name) {
  // 使用 inline 确认：行内替换按钮文本
  const card = this.container.querySelector(`[data-mcp-id="${id}"]`);
  if (!card) {
    if (!confirm(`确定要删除 MCP 服务器 "${name}" 吗？`)) return;
    this.data.mcp.servers = this.data.mcp.servers.filter((x) => x.id !== id);
    this._save();
    this._renderMain();
    return;
  }
  const actions = card.querySelector(".cs-provider-card-actions");
  if (!actions) return;
  if (actions.dataset.confirming === "1") {
    // 二次点击 → 确认删除
    this.data.mcp.servers = this.data.mcp.servers.filter((x) => x.id !== id);
    this._save();
    this._renderMain();
    toast.success(`已删除 MCP 服务器 ${name}`);
    return;
  }
  actions.dataset.confirming = "1";
  // 隐藏其他按钮,显示“确认删除”
  Array.from(actions.children).forEach((b) => (b.style.display = "none"));
  const confirmBtn = document.createElement("button");
  confirmBtn.className = "cs-button size-sm variant-primary";
  confirmBtn.style.background = "var(--error)";
  confirmBtn.style.color = "white";
  confirmBtn.innerHTML = `<i class="fas fa-check"></i>&nbsp;确认删除?`;
  confirmBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    this.data.mcp.servers = this.data.mcp.servers.filter((x) => x.id !== id);
    this._save();
    this._renderMain();
    toast.success(`已删除 MCP 服务器 ${name}`);
  });
  actions.appendChild(confirmBtn);
  // 3s 后恢复
  setTimeout(() => {
    if (actions.dataset.confirming === "1") {
      actions.dataset.confirming = "0";
      Array.from(actions.children).forEach((b) => (b.style.display = ""));
      confirmBtn.remove();
    }
  }, 3000);
};

SettingsView.prototype._openMcpModal = function (id) {
  const isNew = !id;
  const srv = isNew
    ? {
        id: "mcp-" + Date.now(),
        name: "",
        transport: "stdio",
        command: "",
        args: [],
        url: "",
        enabled: true,
        env: {},
      }
    : this.data.mcp.servers.find((x) => x.id === id);
  if (!srv) return;
  const envText = Object.entries(srv.env || {})
    .map(([k, v]) => `${k}=${v}`)
    .join("\n");
  const content = document.createElement("div");
  content.className = "cs-provider-form";
  content.innerHTML = `
    <div class="cs-form-grid">
      <div class="cs-form-row">
        <label for="mcp-name">名称 <span class="req">*</span></label>
        <input type="text" id="mcp-name" class="cs-input" value="${escapeHtml(srv.name)}" placeholder="如：filesystem" />
      </div>
      <div class="cs-form-row">
        <label for="mcp-transport">传输方式</label>
        <select id="mcp-transport" class="cs-select">
          <option value="stdio" ${srv.transport === "stdio" ? "selected" : ""}>stdio (本地进程)</option>
          <option value="http" ${srv.transport === "http" ? "selected" : ""}>HTTP</option>
          <option value="websocket" ${srv.transport === "websocket" ? "selected" : ""}>WebSocket</option>
        </select>
      </div>
      <div class="cs-form-row" data-cs-role="stdio-fields">
        <label for="mcp-command">命令 <span class="req">*</span></label>
        <input type="text" id="mcp-command" class="cs-input" value="${escapeHtml(srv.command || "")}" placeholder="如：npx" />
        <label for="mcp-args" style="margin-top:8px;">参数 (逗号分隔)</label>
        <input type="text" id="mcp-args" class="cs-input" value="${escapeHtml((srv.args || []).join(", "))}" placeholder="如：-y, @modelcontextprotocol/server-filesystem, /tmp" />
      </div>
      <div class="cs-form-row" data-cs-role="http-fields" style="display:none;">
        <label for="mcp-url">URL <span class="req">*</span></label>
        <input type="text" id="mcp-url" class="cs-input" value="${escapeHtml(srv.url || "")}" placeholder="https://example.com/mcp" />
      </div>
      <div class="cs-form-row">
        <label class="cs-toggle">
          <input type="checkbox" id="mcp-enabled" ${srv.enabled ? "checked" : ""} />
          <span class="cs-toggle-track"><span class="cs-toggle-thumb"></span></span>
          <span class="cs-toggle-label">启用</span>
        </label>
      </div>
      <div class="cs-form-row">
        <label for="mcp-env">环境变量 (KEY=VALUE 每行一个)</label>
        <textarea id="mcp-env" class="cs-textarea" rows="3" placeholder="API_KEY=xxx\nDEBUG=true">${escapeHtml(envText)}</textarea>
      </div>
    </div>
  `;
  const transportSelect = content.querySelector("#mcp-transport");
  const stdioFields = content.querySelector('[data-cs-role="stdio-fields"]');
  const httpFields = content.querySelector('[data-cs-role="http-fields"]');
  const updateVisibility = () => {
    const v = transportSelect.value;
    stdioFields.style.display = v === "stdio" ? "" : "none";
    httpFields.style.display = v === "stdio" ? "none" : "";
  };
  transportSelect.addEventListener("change", updateVisibility);
  updateVisibility();
  const save = () => {
    const name = content.querySelector("#mcp-name").value.trim();
    if (!name) {
      toast.error("名称不能为空");
      return;
    }
    const transport = transportSelect.value;
    const command = content.querySelector("#mcp-command").value.trim();
    const args = content
      .querySelector("#mcp-args")
      .value.split(",")
      .map((s) => s.trim())
      .filter(Boolean);
    const url = content.querySelector("#mcp-url").value.trim();
    const enabled = content.querySelector("#mcp-enabled").checked;
    const envText2 = content.querySelector("#mcp-env").value;
    const env = {};
    envText2.split("\n").forEach((line) => {
      const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
      if (m) env[m[1]] = m[2];
    });
    if (transport === "stdio" && !command) {
      toast.error("stdio 传输必须填写命令");
      return;
    }
    if ((transport === "http" || transport === "websocket") && !url) {
      toast.error(`${transport} 传输必须填写 URL`);
      return;
    }
    const newSrv = {
      id: srv.id,
      name,
      transport,
      command: transport === "stdio" ? command : "",
      args: transport === "stdio" ? args : [],
      url: transport !== "stdio" ? url : "",
      enabled,
      env,
    };
    if (isNew) {
      this.data.mcp.servers.push(newSrv);
    } else {
      const idx = this.data.mcp.servers.findIndex((x) => x.id === id);
      if (idx >= 0) this.data.mcp.servers[idx] = newSrv;
    }
    this._save();
    this._renderMain();
    modal.close();
    toast.success(isNew ? "已添加 MCP 服务器" : "已保存");
  };
  const modal = new Modal({
    title: isNew ? "添加 MCP 服务器" : `编辑 · ${escapeHtml(srv.name)}`,
    content,
    size: "md",
  });
  const body = content.closest(".cs-modal-body");
  const footer = document.createElement("div");
  footer.style.cssText =
    "padding:12px 0 0;display:flex;justify-content:flex-end;gap:8px;border-top:1px solid var(--border-subtle);margin-top:16px;";
  footer.innerHTML = `
    <button class="cs-button variant-ghost size-md" data-cs-action="cancel">取消</button>
    <button class="cs-button variant-primary size-md" data-cs-action="save">保存</button>
  `;
  body.appendChild(footer);
  footer
    .querySelector('[data-cs-action="cancel"]')
    .addEventListener("click", () => modal.close());
  footer
    .querySelector('[data-cs-action="save"]')
    .addEventListener("click", save);
  content.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && e.target.tagName !== "TEXTAREA") {
      e.preventDefault();
      save();
    }
  });
  modal.open();
};

SettingsView.prototype._bindProviderActions = function (root) {
  root.querySelectorAll('[data-cs-action="toggle-provider"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      const p = this.data.providers.find((x) => x.id === id);
      if (!p) return;
      p.enabled = !p.enabled;
      this._save();
      this._renderMain();
      toast.success(`${p.name} 已${p.enabled ? "启用" : "禁用"}`);
    });
  });
  root.querySelectorAll('[data-cs-action="edit-provider"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      this._editProvider(id);
    });
  });
  // API Key 可见性切换 — 默认隐藏,点眼睛图标才显示
  root.querySelectorAll('[data-cs-action="toggle-api-key"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const wrap = btn.closest('[data-cs-role="api-key"]');
      if (!wrap) return;
      const masked = wrap.querySelector('[data-cs-role="api-key-masked"]');
      const value = wrap.querySelector('[data-cs-role="api-key-value"]');
      const isHidden = value.style.display === "none";
      masked.style.display = isHidden ? "none" : "";
      value.style.display = isHidden ? "" : "none";
      btn.innerHTML = isHidden
        ? '<i class="fas fa-eye-slash"></i>'
        : '<i class="fas fa-eye"></i>';
    });
  });
  root.querySelectorAll('[data-cs-action="remove-provider"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = btn.dataset.id;
      const p = this.data.providers.find((x) => x.id === id);
      if (!p) return;
      // 用自定义 modal 替代 confirm() — JCEF 里 confirm() 经常被禁用
      this._confirmDeleteProvider(p);
    });
  });
  root.querySelectorAll('[data-cs-action="add-provider"]').forEach((btn) => {
    btn.addEventListener("click", () => this._addProvider());
  });
};

/**
 * 删除 Provider 确认弹窗 — 用 Modal 组件而不是原生 confirm()
 * (JCEF 里 confirm() 经常被禁用或表现不一致)
 */
SettingsView.prototype._confirmDeleteProvider = function (p) {
  const content = document.createElement("div");
  content.innerHTML = `
    <p>确定要删除 Provider <strong>${escapeHtml(p.name || p.id)}</strong> 吗？</p>
    <p class="cs-form-field-desc">此操作不可撤销，对应的 API Key 也会从 IDE PasswordSafe 中移除。</p>
  `;
  const modal = new Modal({ title: "删除 Provider", content, size: "sm" });
  const footer = document.createElement("div");
  footer.className = "cs-modal-footer";
  footer.style.cssText =
    "padding:12px 0 0;display:flex;justify-content:flex-end;gap:8px;border-top:1px solid var(--border-subtle);margin-top:16px;";
  footer.innerHTML = `
    <button class="cs-button variant-ghost size-md" data-cs-action="cancel">取消</button>
    <button class="cs-button size-md" data-cs-action="confirm" style="background:var(--error);color:white;">删除</button>
  `;
  content.parentElement.appendChild(footer);
  footer
    .querySelector('[data-cs-action="cancel"]')
    .addEventListener("click", () => modal.close());
  footer
    .querySelector('[data-cs-action="confirm"]')
    .addEventListener("click", () => {
      this.data.providers = this.data.providers.filter((x) => x.id !== p.id);
      this._save();
      this._renderMain();
      modal.close();
      toast.success(`已删除 ${p.name || p.id}`);
    });
  modal.open();
};

SettingsView.prototype._addProvider = function () {
  // 修复:不要立即 push,先打开 modal,用户取消就不加
  const id = "provider-" + Date.now();
  const newProvider = {
    id,
    name: "",
    type: "openai-compatible",
    baseUrl: "",
    enabled: true,
    apiKeyRef: `keychain:${id}`,
    models: [],
  };
  this._openProviderModal(newProvider, { isNew: true });
  toast.info("请填写新 Provider 配置");
};

SettingsView.prototype._editProvider = function (id) {
  const p = this.data.providers.find((x) => x.id === id);
  if (!p) return;
  this._openProviderModal(p);
};

/**
 * 打开 Provider 编辑 modal（支持新增、编辑、API Key 修改、连通性测试）
 */
SettingsView.prototype._openProviderModal = function (provider, options = {}) {
  // options.isNew 由 _addProvider 传入(避免把空 name 当 "新 Provider" 的字面判错)
  const isNew =
    options.isNew ?? (!provider.name || provider.name === "新 Provider");
  const content = document.createElement("div");
  content.className = "cs-provider-form";
  const typesOptions = PROVIDER_TYPES.map(
    (t) =>
      `<option value="${escapeHtml(t.value)}" ${t.value === provider.type ? "selected" : ""}>${escapeHtml(t.label)}</option>`,
  ).join("");
  const modelsText = (provider.models || []).map((m) => m.id).join(", ");

  content.innerHTML = `
    <div class="cs-form-grid">
      <div class="cs-form-row">
        <label for="pf-name">名称 <span class="req">*</span></label>
        <input type="text" id="pf-name" class="cs-input" value="${escapeHtml(provider.name || "")}" placeholder="如：MiniMax" />
      </div>
      <div class="cs-form-row">
        <label for="pf-type">类型</label>
        <select id="pf-type" class="cs-select">${typesOptions}</select>
      </div>
      <div class="cs-form-row">
        <label for="pf-baseUrl">Base URL</label>
        <input type="text" id="pf-baseUrl" class="cs-input" value="${escapeHtml(provider.baseUrl || "")}" placeholder="https://api.example.com" />
        <small class="cs-form-hint">不要包含 /v1，CodeSage 会自动加上</small>
      </div>
      <div class="cs-form-row">
        <label for="pf-apiKey">API Key</label>
        <div class="cs-password-input">
          <input type="password" id="pf-apiKey" class="cs-input" placeholder="${provider.apiKeyRef ? "（已设置，留空不修改）" : "在此粘贴你的 API Key"}" autocomplete="off" />
          <button type="button" class="cs-password-toggle" data-cs-role="toggle-key" aria-label="显示/隐藏 Key">
            <i class="fas fa-eye"></i>
          </button>
        </div>
        <small class="cs-form-hint">存储在 IntelliJ PasswordSafe，不写入 settings.json</small>
      </div>
      <div class="cs-form-row">
        <label for="pf-models">模型 <span class="req">*</span></label>
        <input type="text" id="pf-models" class="cs-input" value="${escapeHtml(modelsText)}" placeholder="如：gpt-4o, gpt-3.5-turbo" />
        <small class="cs-form-hint">逗号分隔，例如：<code>model-a, model-b, model-c</code></small>
      </div>
      <div class="cs-form-row cs-form-row-actions">
        <button type="button" class="cs-button variant-secondary size-md" data-cs-action="test-connection">
          <i class="fas fa-plug"></i>&nbsp;测试连接
        </button>
        <span class="cs-test-result" data-cs-role="test-result"></span>
      </div>
    </div>
  `;

  // 处理 password 显示/隐藏切换
  const apiKeyInput = content.querySelector("#pf-apiKey");
  const toggleBtn = content.querySelector('[data-cs-role="toggle-key"]');
  toggleBtn.addEventListener("click", () => {
    if (apiKeyInput.type === "password") {
      apiKeyInput.type = "text";
      toggleBtn.innerHTML = '<i class="fas fa-eye-slash"></i>';
    } else {
      apiKeyInput.type = "password";
      toggleBtn.innerHTML = '<i class="fas fa-eye"></i>';
    }
  });

  const testBtn = content.querySelector('[data-cs-action="test-connection"]');
  const testResultEl = content.querySelector('[data-cs-role="test-result"]');
  testBtn.addEventListener("click", () => {
    const baseUrl = content.querySelector("#pf-baseUrl").value.trim();
    const apiKey = apiKeyInput.value.trim();
    const model = (
      content.querySelector("#pf-models").value.split(",")[0] || ""
    ).trim();
    if (!baseUrl) {
      testResultEl.className = "cs-test-result fail";
      testResultEl.innerHTML =
        '<i class="fas fa-exclamation-circle"></i> 请先填写 Base URL';
      return;
    }
    testBtn.disabled = true;
    testBtn.innerHTML = '<i class="fas fa-spinner spin"></i>&nbsp;探测中...';
    testResultEl.className = "cs-test-result pending";
    testResultEl.innerHTML = '<i class="fas fa-spinner spin"></i> 探测中...';
    const requestId = `test-${Date.now()}`;
    const handler = (msg) => {
      if (msg?.type !== "test_provider_result") return;
      if (msg.requestId !== requestId) return;
      window.removeEventListener("__codesage_test_provider__", handler);
      testBtn.disabled = false;
      testBtn.innerHTML = '<i class="fas fa-plug"></i>&nbsp;测试连接';
      if (msg.ok) {
        testResultEl.className = "cs-test-result success";
        const latency = msg.latencyMs != null ? ` (${msg.latencyMs}ms)` : "";
        testResultEl.innerHTML = `<i class="fas fa-check-circle"></i> 连接成功${latency}`;
      } else {
        testResultEl.className = "cs-test-result fail";
        const detail = msg.error || "未知错误";
        const status = msg.httpStatus ? ` (HTTP ${msg.httpStatus})` : "";
        testResultEl.innerHTML = `<i class="fas fa-times-circle"></i> ${escapeHtml(detail)}${status}`;
      }
    };
    // 用一个在 SettingsView 上能识别的通道
    if (typeof this._onTestProviderResult === "function") {
      this._onTestProviderResult = handler;
    } else {
      this._onTestProviderResult = handler;
    }
    bridge.send({
      type: "test_provider",
      requestId,
      providerId: provider.id,
      baseUrl,
      apiKey,
      model,
    });
  });

  // 保存
  const save = () => {
    const name = content.querySelector("#pf-name").value.trim();
    const type = content.querySelector("#pf-type").value;
    const baseUrl = content.querySelector("#pf-baseUrl").value.trim();
    const apiKey = apiKeyInput.value;
    const modelsText2 = content.querySelector("#pf-models").value.trim();
    if (!name) {
      toast.error("名称不能为空");
      return;
    }
    if (!modelsText2) {
      toast.error("至少需要一个模型");
      return;
    }
    // 1) 如果 apiKey 填了,先 set_api_key
    if (apiKey) {
      const setKeyHandler = (msg) => {
        if (msg?.type !== "set_api_key_result") return;
        if (msg.providerId !== provider.id) return;
        window.removeEventListener("__codesage_set_api_key__", setKeyHandler);
        if (!msg.success) {
          toast.error("API Key 保存失败:" + (msg.error || ""));
          return;
        }
        // 2) 写 settings.json (不含 apiKey)
        // 如果是新增,先 push 到 providers (取消的话就不会 push)
        if (isNew) {
          this.data.providers.push(provider);
        }
        provider.name = name;
        provider.type = type;
        provider.baseUrl = baseUrl;
        provider.models = modelsText2
          .split(",")
          .map((m) => m.trim())
          .filter(Boolean)
          .map((id) => ({ id, label: id }));
        provider.apiKeyRef = `keychain:${provider.id}`;
        this._save();
        this._renderMain();
        modal.close();
        toast.success("已保存 Provider 配置");
      };
      if (typeof this._onSetApiKeyResult === "function") {
        this._onSetApiKeyResult = setKeyHandler;
      } else {
        this._onSetApiKeyResult = setKeyHandler;
      }
      bridge.send({
        type: "set_api_key",
        requestId: `setkey-${Date.now()}`,
        providerId: provider.id,
        apiKey,
      });
    } else {
      // 没填 key,直接写其余字段
      // 如果是新增,先 push 到 providers (取消的话就不会 push)
      if (isNew) {
        this.data.providers.push(provider);
      }
      provider.name = name;
      provider.type = type;
      provider.baseUrl = baseUrl;
      provider.models = modelsText2
        .split(",")
        .map((m) => m.trim())
        .filter(Boolean)
        .map((id) => ({ id, label: id }));
      provider.apiKeyRef = `keychain:${provider.id}`;
      this._save();
      this._renderMain();
      modal.close();
      toast.success("已保存 Provider 配置");
    }
  };

  const modal = new Modal({
    title: isNew
      ? "添加 Provider"
      : `编辑 Provider · ${escapeHtml(provider.name || "")}`,
    content,
    size: "md",
  });
  // 底部添加保存 / 取消 按钮（替换默认仅关闭）
  const body = content.closest(".cs-modal-body");
  const footer = document.createElement("div");
  footer.className = "cs-modal-footer";
  footer.style.cssText =
    "padding:12px 0 0;display:flex;justify-content:flex-end;gap:8px;border-top:1px solid var(--border-subtle);margin-top:16px;";
  footer.innerHTML = `
    <button class="cs-button variant-ghost size-md" data-cs-action="cancel">取消</button>
    <button class="cs-button variant-primary size-md" data-cs-action="save">保存</button>
  `;
  body.appendChild(footer);
  footer
    .querySelector('[data-cs-action="cancel"]')
    .addEventListener("click", () => modal.close());
  footer
    .querySelector('[data-cs-action="save"]')
    .addEventListener("click", save);
  // Enter 提交（在 input 内时）
  content.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && e.target.tagName !== "TEXTAREA") {
      e.preventDefault();
      save();
    }
  });
  modal.open();
};

SettingsView.prototype._bindShortcutRecord = function (root) {
  root.querySelectorAll('[data-cs-action="record-shortcut"]').forEach((btn) => {
    btn.addEventListener("click", () => {
      const key = btn.dataset.key;
      const row = btn.closest("tr");
      const kbd = row.querySelector(".cs-shortcut-key");
      const original = kbd.textContent;
      kbd.textContent = "按下新键...";
      const handler = (e) => {
        e.preventDefault();
        document.removeEventListener("keydown", handler);
        const parts = [];
        if (e.metaKey || e.ctrlKey) parts.push(e.metaKey ? "Cmd" : "Ctrl");
        if (e.shiftKey) parts.push("Shift");
        if (e.altKey) parts.push("Alt");
        let main = e.key;
        if (main === " ") main = "Space";
        else if (main.length === 1) main = main.toUpperCase();
        parts.push(main);
        const combo = parts.join("+");
        this._setField(`shortcuts.${key}`, combo);
        kbd.textContent = combo;
        this._save();
        toast.success(`已绑定: ${combo}`);
      };
      document.addEventListener("keydown", handler);
      setTimeout(() => {
        if (kbd.textContent === "按下新键...") kbd.textContent = original;
      }, 10000);
    });
  });
};

export const settings = new SettingsView();
window.CodeSage = window.CodeSage || {};
window.CodeSage.settings = settings;
