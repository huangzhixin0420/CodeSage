/**
 * cs-command-palette 组件 — Cmd+K 命令面板
 *
 * 设计文档 §11.3:
 *   - 顶部搜索框,支持中/英模糊匹配
 *   - 10+ 个内置命令:/model /mode /plan /clear /new /history /settings /help /theme ...
 *   - 上下方向键选择,Enter 执行
 *   - 显示快捷键 hint
 */

import { Modal } from "./cs-modal.js";
import { toast } from "./cs-toast.js";
import { state } from "../state.js";
import { bridge } from "../bridge.js";

const COMMANDS = [
  {
    id: "model",
    label: "切换模型",
    hint: "选择模型",
    shortcut: "Cmd+/",
    keywords: ["model", "切换", "选择模型"],
    run: () => openModelSubmenu(),
  },
  {
    id: "mode-agent",
    label: "模式:Agent",
    hint: "AI 自动规划并使用工具",
    shortcut: "Cmd+1",
    keywords: ["mode", "agent", "模式"],
    run: (ctx) => ctx.chat.setMode("agent"),
  },
  {
    id: "mode-ask",
    label: "模式:Ask",
    hint: "仅对话,不调用工具",
    shortcut: "Cmd+2",
    keywords: ["mode", "ask", "模式"],
    run: (ctx) => ctx.chat.setMode("ask"),
  },
  {
    id: "mode-manual",
    label: "模式:Manual",
    hint: "手动选择工具",
    shortcut: "Cmd+3",
    keywords: ["mode", "manual", "模式"],
    run: (ctx) => ctx.chat.setMode("manual"),
  },
  {
    id: "plan",
    label: "切换 Plan 模式",
    hint: "大任务自动生成计划",
    keywords: ["plan", "计划", "todo"],
    run: () => toast.info("Plan 模式由 Agent 模式自动启用"),
  },
  {
    id: "new",
    label: "新会话",
    hint: "创建新会话",
    shortcut: "Cmd+N",
    keywords: ["new", "session", "新会话", "新建"],
    run: () => bridge.send({ type: "new_session" }),
  },
  {
    id: "clear",
    label: "清空当前会话",
    hint: "清空消息但保留会话",
    keywords: ["clear", "清空", "reset"],
    run: (ctx) => ctx.chat.clearChat(),
  },
  {
    id: "theme-light",
    label: "主题:浅色",
    hint: "切换到浅色主题",
    keywords: ["theme", "light", "浅色", "主题"],
    run: (ctx) => ctx.chat.setTheme("light"),
  },
  {
    id: "theme-dark",
    label: "主题:深色",
    hint: "切换到深色主题",
    keywords: ["theme", "dark", "深色", "主题"],
    run: (ctx) => ctx.chat.setTheme("dark"),
  },
  {
    id: "theme-auto",
    label: "主题:跟随系统",
    hint: "根据系统外观自动切换",
    keywords: ["theme", "auto", "system", "跟随", "主题"],
    run: (ctx) => ctx.chat.setTheme("auto"),
  },
  {
    id: "thinking-toggle",
    label: "显示/隐藏思考过程",
    hint: "切换思考区可见性",
    shortcut: "Cmd+Shift+T",
    keywords: ["thinking", "思考", "toggle"],
    run: (ctx) => ctx.chat.toggleThinkingVisibility(),
  },
  {
    id: "help",
    label: "帮助",
    hint: "显示快捷键与命令列表",
    shortcut: "F1",
    keywords: ["help", "帮助", "??"],
    run: () => showHelp(),
  },
  {
    id: "settings",
    label: "设置",
    hint: "打开设置(开发中)",
    keywords: ["settings", "preferences", "设置", "配置"],
    run: () => toast.info("设置中心(Phase 4)开发中"),
  },
  {
    id: "reload",
    label: "重载界面",
    hint: "刷新 Web UI",
    shortcut: "Cmd+R",
    keywords: ["reload", "refresh", "重载", "刷新"],
    run: () => bridge.reload(),
  },
];

function score(query, cmd) {
  if (!query) return 1;
  const q = query.toLowerCase();
  // 优先 label 命中
  if (cmd.label.toLowerCase().includes(q)) return 10;
  if (cmd.hint.toLowerCase().includes(q)) return 5;
  for (const k of cmd.keywords || []) {
    if (k.toLowerCase().includes(q)) return 3;
  }
  // fuzzy:每个字符在 label 中按顺序出现
  let i = 0;
  const l = cmd.label.toLowerCase();
  for (const ch of q) {
    const idx = l.indexOf(ch, i);
    if (idx < 0) return 0;
    i = idx + 1;
  }
  return 1;
}

function openModelSubmenu() {
  const groups = state.get("availableModels") || [];
  if (!groups.length) {
    toast.warning("暂无可用模型,请先在设置中配置 Provider");
    return;
  }
  const sub = new CommandPalette({
    placeholder: "搜索模型...",
    commands: groups.flatMap((g) =>
      g.models.map((m) => ({
        id: `model-${g.provider}-${m}`,
        label: m,
        hint: g.provider,
        run: () => {
          state.set("currentModel", m);
          bridge.send({ type: "switch_model", model: m });
          toast.success(`已切换到 ${m}`);
        },
      }))
    ),
  });
  sub.open();
}

function showHelp() {
  const body = document.createElement("div");
  body.style.cssText = "padding:8px 4px;font-size:13px;line-height:1.7;";
  const shortcuts = [
    { k: "Enter / Ctrl+Enter", d: "发送消息" },
    { k: "Shift + Enter", d: "换行" },
    { k: "@", d: "引用文件" },
    { k: "Esc", d: "停止生成 / 关闭面板" },
    { k: "Cmd + K", d: "命令面板" },
    { k: "Cmd + /", d: "切换模型" },
    { k: "Cmd + Shift + C", d: "复制最后一条回复" },
    { k: "Cmd + N", d: "新会话" },
    { k: "Cmd + R", d: "重载界面" },
    { k: "Cmd + 1/2/3", d: "切换 Agent/Ask/Manual 模式" },
  ];
  body.innerHTML = `
    <h3 style="margin:0 0 8px;font-size:14px;">键盘快捷键</h3>
    <table style="width:100%;border-collapse:collapse;">
      ${shortcuts
        .map(
          (s) => `<tr>
            <td style="padding:4px 12px 4px 0;color:var(--fg-1);font-family:var(--font-mono);font-size:12px;white-space:nowrap;"><kbd style="background:var(--bg-1);border:1px solid var(--border);border-radius:4px;padding:2px 6px;">${s.k}</kbd></td>
            <td style="padding:4px 0;color:var(--fg-2);font-size:12px;">${s.d}</td>
        </tr>`
        )
        .join("")}
    </table>
    <h3 style="margin:16px 0 8px;font-size:14px;">斜杠命令</h3>
    <p style="color:var(--fg-2);font-size:12px;margin:0;">在输入框直接输入 <code style="background:var(--bg-1);padding:1px 4px;border-radius:3px;">/help</code>、<code style="background:var(--bg-1);padding:1px 4px;border-radius:3px;">/model</code>、<code style="background:var(--bg-1);padding:1px 4px;border-radius:3px;">/clear</code>、<code style="background:var(--bg-1);padding:1px 4px;border-radius:3px;">/new</code> 等命令(开发中)</p>
  `;
  const m = new Modal({ title: "帮助", content: body, size: "md" });
  m.open();
}

export class CommandPalette {
  constructor(opts = {}) {
    this.commands = opts.commands || COMMANDS;
    this.placeholder = opts.placeholder || "输入命令或搜索...";
    this.context = opts.context || { chat: window.CodeSage?.chat };
    this.selectedIndex = 0;
    this._build();
  }

  _build() {
    this.el = document.createElement("div");
    this.el.className = "cs-command-palette";
    this.el.setAttribute("role", "listbox");
    this.el.innerHTML = `
      <div class="cs-cmd-search">
        <i class="fas fa-search"></i>
        <input type="text" data-cs-role="input" placeholder="${this.placeholder}" autocomplete="off" spellcheck="false" />
        <kbd data-cs-role="hint" style="font-size:10px;color:var(--fg-3);">ESC</kbd>
      </div>
      <ul class="cs-cmd-list" data-cs-role="list" role="listbox"></ul>
      <div class="cs-cmd-footer">
        <span><kbd>↑</kbd><kbd>↓</kbd> 导航</span>
        <span><kbd>↵</kbd> 执行</span>
        <span><kbd>ESC</kbd> 关闭</span>
      </div>
    `;
    this.input = this.el.querySelector('[data-cs-role="input"]');
    this.list = this.el.querySelector('[data-cs-role="list"]');
    this._filtered = this.commands;
    this._render();

    this.input.addEventListener("input", () => {
      this._updateFiltered();
    });
    this.el.addEventListener("keydown", (e) => this._onKey(e));
  }

  _updateFiltered() {
    const q = this.input.value.trim();
    this._filtered = this.commands
      .map((c) => ({ c, s: score(q, c) }))
      .filter((x) => x.s > 0)
      .sort((a, b) => b.s - a.s)
      .map((x) => x.c);
    this.selectedIndex = 0;
    this._render();
  }

  _render() {
    this.list.innerHTML = this._filtered.length
      ? this._filtered
          .map(
            (c, i) => `
            <li class="cs-cmd-item ${i === this.selectedIndex ? "selected" : ""}" data-index="${i}" role="option">
              <div class="cs-cmd-item-body">
                <div class="cs-cmd-item-label">${escapeHtml(c.label)}</div>
                ${c.hint ? `<div class="cs-cmd-item-hint">${escapeHtml(c.hint)}</div>` : ""}
              </div>
              ${c.shortcut ? `<kbd class="cs-cmd-item-shortcut">${escapeHtml(c.shortcut)}</kbd>` : ""}
            </li>`
          )
          .join("")
      : `<li class="cs-cmd-empty">无匹配命令</li>`;
    this.list.querySelectorAll(".cs-cmd-item").forEach((el) => {
      el.addEventListener("click", () => {
        const idx = parseInt(el.dataset.index, 10);
        this.selectedIndex = idx;
        this._execute();
      });
      el.addEventListener("mouseenter", () => {
        this.selectedIndex = parseInt(el.dataset.index, 10);
        this._highlight();
      });
    });
  }

  _highlight() {
    this.list.querySelectorAll(".cs-cmd-item").forEach((el, i) => {
      el.classList.toggle("selected", i === this.selectedIndex);
    });
    const sel = this.list.querySelector(".cs-cmd-item.selected");
    sel?.scrollIntoView({ block: "nearest" });
  }

  _onKey(e) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      this.selectedIndex = Math.min(this.selectedIndex + 1, this._filtered.length - 1);
      this._highlight();
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      this.selectedIndex = Math.max(this.selectedIndex - 1, 0);
      this._highlight();
    } else if (e.key === "Enter") {
      e.preventDefault();
      this._execute();
    } else if (e.key === "Tab") {
      e.preventDefault();
      this.selectedIndex = e.shiftKey
        ? Math.max(this.selectedIndex - 1, 0)
        : Math.min(this.selectedIndex + 1, this._filtered.length - 1);
      this._highlight();
    }
  }

  _execute() {
    const cmd = this._filtered[this.selectedIndex];
    if (!cmd) return;
    try {
      cmd.run(this.context);
    } catch (e) {
      console.error("[CommandPalette] run failed:", e);
      toast.error("命令执行失败: " + e.message);
    }
    this.close();
  }

  open() {
    document.body.appendChild(this.el);
    requestAnimationFrame(() => {
      this.el.classList.add("open");
      this.input.focus();
    });
    return this;
  }

  close() {
    this.el.classList.remove("open");
    setTimeout(() => this.el.remove(), 180);
  }

  handleEscape() {
    if (this.input.value) {
      this.input.value = "";
      this._updateFiltered();
      return true;
    }
    return false;
  }
}

function escapeHtml(s) {
  if (s == null) return "";
  return String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
