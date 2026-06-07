/**
 * Chat View — 主对话视图编排
 *
 * 职责:
 *   - 接收 Kotlin 事件,调用对应组件渲染
 *   - 维护消息/工具/计划/会话的状态
 *   - 用户交互(发送/停止/模式/主题等)
 *
 * 状态容器:
 *   - state.turns: turnId -> { thinkingEl, toolCalls: Map, content }
 *   - state.sessions: 会话列表
 *   - state.artifacts: artifact map
 */

import { bridge } from "../bridge.js";
import { state } from "../state.js";
import { toast } from "../components/cs-toast.js";
import { Thinking } from "../components/cs-thinking.js";
import { ToolCall } from "../components/cs-tool-call.js";
import { Plan } from "../components/cs-plan.js";
import { InlineAlert } from "../components/cs-inline-alert.js";
import { CommandPalette } from "../components/cs-command-palette.js";
import { Sidebar } from "../components/cs-sidebar.js";
import { settings } from "./settings.js";
import {
  escapeHtml,
  escapeJs,
  formatRelativeTime,
  highlightAtReferences,
  scrollToBottom,
  debounce,
} from "../utils.js";
import {
  renderMarkdownSync,
  enhanceCodeBlocks,
  highlightCode,
} from "../markdown.js";

const MAX_CHARS = 4000;
const WARNING_THRESHOLD = 3600;

class ChatView {
  constructor() {
    // === DOM references ===
    this.messagesContainer = document.getElementById("messages-container");
    this.welcomeState = document.getElementById("welcome-state");
    this.messageInput = document.getElementById("message-input");
    this.sendBtn = document.getElementById("send-btn");
    this.stopBtn = document.getElementById("stop-btn");
    this.statusLabel = document.getElementById("status-label");
    this.charCounter = document.getElementById("char-counter");
    this.modelSelectBtn = document.getElementById("model-select-btn");
    this.modelDropdown = document.getElementById("model-dropdown");
    this.currentModelName = document.getElementById("current-model-name");
    this.historyDropdown = document.getElementById("history-dropdown");
    this.thinkingToggleBtn = document.getElementById("thinking-toggle-btn");
    this.thinkingToggleIcon = document.getElementById("thinking-toggle-icon");
    this.themeIcon = document.getElementById("theme-icon");
    this.fileAutocomplete = document.getElementById("file-autocomplete");
    this.artifactSidebar = document.getElementById("artifact-sidebar");
    this.artifactList = document.getElementById("artifact-list");

    // === State (per-turn) ===
    /** turnId -> { thinkingEl, toolCalls: Map, content, contentEl, actionsEl, startTime, timers } */
    this.turns = new Map();
    /** toolCallId -> ToolCall instance */
    this.toolCalls = new Map();
    /** planId -> Plan instance */
    this.plans = new Map();
    /** autocomplete state */
    this.autocompleteIndex = -1;
    this.autocompleteSuggestions = [];
    this.autocompleteQuery = "";
    this._inputDebounced = debounce(() => this._handleInputFlush(), 0);

    // P5.4: pending image attachments (从拖拽/粘贴 累计, 发送后清空)
    this._pendingImages = [];

    this._isGenerating = false;
  }

  // ===== Lifecycle =====
  init() {
    this._bindInput();
    this._bindHeaderButtons();
    this._bindBridge();
    this._bindGlobalShortcuts();
    this._bindDragAndDrop();
    this._restoreFromState();
    this._restoreMode();
    this._initInputResize();
    this._bindModeDropdownClickOutside();
    this._initInputBoxFocus();
    this._initTheme();
    this._watchSystemTheme();
    this._initSidebar();
    this._initSettings();
    bridge.scheduleOfflineCheck();
  }

  _initSettings() {
    const container = document.getElementById("cs-settings-container");
    if (!container) return;
    settings.init(container);
    settings.onBack = () => this._hideSettings();
    // Esc 关闭 settings — settings.show() 会把 inline display 置为 ""
    // 让 CSS 的 display:flex 生效,所以判断 !== "none" 才准确
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && container.style.display !== "none") {
        e.preventDefault();
        this._hideSettings();
      }
    });
  }

  showSettings() {
    const container = document.getElementById("cs-settings-container");
    if (container) {
      container.style.display = "flex";
      settings.show();
      // 防御:若 1.5s 内 settings_data 还没到,自动重发一次 settings_get
      // (settings.show() 内部已经发了一次;这里防的是丢包/bridge 时序问题)
      if (settings._dataLoadWatchdog) clearTimeout(settings._dataLoadWatchdog);
      settings._dataLoadWatchdog = setTimeout(() => {
        if (!settings.data) {
          console.warn(
            "[CodeSage] settings_data timeout, resending settings_get",
          );
          bridge.send({ type: "settings_get" });
        }
      }, 1500);
    }
  }

  _hideSettings() {
    const container = document.getElementById("cs-settings-container");
    if (container) {
      container.style.display = "none";
      settings.hide();
    }
  }

  _initSidebar() {
    this.sidebar = new Sidebar({
      container: document.querySelector(".app-container"),
      initialCollapsed: state.get("sidebarCollapsed") !== false,
    });
    this.sidebar.onNewSession = () => bridge.send({ type: "new_session" });
    this.sidebar.onSwitchSession = (sid) =>
      bridge.send({ type: "switch_session", sessionId: sid });
    this.sidebar.onDeleteSession = (sid) =>
      bridge.send({ type: "delete_session", sessionId: sid });
    this.sidebar.onRenameSession = (sid, name) =>
      bridge.send({ type: "rename_session", sessionId: sid, name });
    this.sidebar.onSettings = () => {
      this.showSettings();
    };
    state.on("state:sidebarCollapsed", (e) =>
      this.sidebar?.setCollapsed(e.value),
    );
  }

  _bindGlobalShortcuts() {
    document.addEventListener("keydown", (e) => {
      // Cmd/Ctrl + K -> command palette
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        new CommandPalette().open();
        return;
      }
      // Cmd/Ctrl + / -> command palette (model focus)
      if ((e.metaKey || e.ctrlKey) && e.key === "/") {
        e.preventDefault();
        new CommandPalette().open();
        return;
      }
      // Cmd/Ctrl + N -> new session
      if (
        (e.metaKey || e.ctrlKey) &&
        e.key.toLowerCase() === "n" &&
        !e.shiftKey
      ) {
        e.preventDefault();
        bridge.send({ type: "new_session" });
        return;
      }
      // Cmd/Ctrl + Shift + T -> toggle thinking
      if (
        (e.metaKey || e.ctrlKey) &&
        e.shiftKey &&
        e.key.toLowerCase() === "t"
      ) {
        e.preventDefault();
        this.toggleThinkingVisibility();
        return;
      }
      // Cmd/Ctrl + B -> toggle sidebar
      if (
        (e.metaKey || e.ctrlKey) &&
        e.key.toLowerCase() === "b" &&
        !e.shiftKey
      ) {
        e.preventDefault();
        this.sidebar?.toggle();
        return;
      }
    });
  }

  _bindDragAndDrop() {
    const input = this.messageInput;
    if (!input) return;
    // 拖拽文件
    ["dragenter", "dragover"].forEach((evt) => {
      input.addEventListener(evt, (e) => {
        e.preventDefault();
        e.stopPropagation();
        input.closest(".input-box")?.classList.add("drag-active");
      });
    });
    ["dragleave", "drop"].forEach((evt) => {
      input.addEventListener(evt, (e) => {
        e.preventDefault();
        e.stopPropagation();
        if (evt === "dragleave" && e.target !== input) return;
        input.closest(".input-box")?.classList.remove("drag-active");
      });
    });
    input.addEventListener("drop", (e) => {
      const files = Array.from(e.dataTransfer?.files || []);
      if (!files.length) return;
      e.preventDefault();
      this._handleDroppedFiles(files);
    });
    // 粘贴图片
    input.addEventListener("paste", (e) => {
      const items = Array.from(e.clipboardData?.items || []);
      const imageItems = items.filter((it) => it.type.startsWith("image/"));
      if (!imageItems.length) return;
      e.preventDefault();
      const files = imageItems.map((it) => it.getAsFile()).filter(Boolean);
      this._handleDroppedFiles(files);
    });
  }

  async _handleDroppedFiles(files) {
    for (const file of files) {
      try {
        if (file.type.startsWith("image/")) {
          // P5.4: 读取为 dataUrl 并加入待发送列表
          const dataUrl = await this._readAsDataURL(file);
          const id = `img-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
          this._pendingImages.push({
            id,
            mime: file.type,
            dataUrl,
            name: file.name,
          });
          this._appendImagePreview(dataUrl, file.name, id);
          toast.info(`已附加图片: ${file.name}`);
        } else {
          // 文本类:插入 @filename 引用
          this._insertAtReference(file.name);
          toast.info(`已引用文件: ${file.name}`);
        }
      } catch (e) {
        console.error("[Chat] failed to handle file:", e);
        toast.error("文件处理失败: " + e.message);
      }
    }
  }

  _readAsDataURL(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(reader.error || new Error("read failed"));
      reader.readAsDataURL(file);
    });
  }

  _appendImagePreview(dataUrl, name, id) {
    const input = this.messageInput;
    const preview = document.createElement("div");
    preview.className = "image-preview";
    preview.dataset.imageId = id || "";
    preview.style.cssText =
      "padding:6px 10px;background:var(--bg-1);border-radius:var(--radius-sm);margin-bottom:6px;display:flex;align-items:center;gap:8px;font-size:12px;";
    preview.innerHTML = `
      <img src="${dataUrl}" alt="${escapeHtml(name)}" style="max-width:80px;max-height:60px;border-radius:4px;object-fit:cover;" />
      <span style="color:var(--fg-2);">${escapeHtml(name)}</span>
      <span style="color:var(--fg-3);font-size:10px;">(图片附件)</span>
      <button class="image-preview-remove" data-cs-action="remove-image" data-id="${escapeHtml(id || "")}" style="margin-left:auto;background:transparent;border:none;color:var(--fg-3);cursor:pointer;font-size:14px;padding:0 4px;" title="移除">&times;</button>
    `;
    input.parentElement?.insertBefore(preview, input);
    // 绑定移除按钮
    preview
      .querySelector('[data-cs-action="remove-image"]')
      ?.addEventListener("click", (e) => {
        e.stopPropagation();
        const targetId = e.currentTarget.dataset.id;
        this._removePendingImage(targetId);
      });
  }

  _removePendingImage(id) {
    this._pendingImages = this._pendingImages.filter((img) => img.id !== id);
    const preview = document.querySelector(
      `.image-preview[data-image-id="${id}"]`,
    );
    preview?.remove();
  }

  _clearPendingImages() {
    this._pendingImages = [];
    document.querySelectorAll(".image-preview").forEach((el) => el.remove());
  }

  _insertAtReference(name) {
    const input = this.messageInput;
    const pos = input.selectionStart;
    const before = input.value.substring(0, pos);
    const after = input.value.substring(pos);
    input.value = before + "@" + name + " " + after;
    input.selectionStart = input.selectionEnd = pos + name.length + 2;
    input.dispatchEvent(new Event("input"));
  }

  _bindInput() {
    this.messageInput.addEventListener("input", (e) => this._onInput(e));
    this.messageInput.addEventListener("keydown", (e) => this._onKeydown(e));
    document.addEventListener("click", (e) => this._onDocClick(e));
  }

  _bindHeaderButtons() {
    // Model dropdown — JS listener ONLY (HTML 上不能同时有 onclick,
    // 否则会双触发:onclick 打开 + addEventListener 立即关闭,导致下拉被吞)
    this.modelSelectBtn?.addEventListener("click", (e) => {
      e.stopPropagation();
      this.toggleModelDropdown();
    });
    // Stop 按钮 — JS listener ONLY (同上,onclick + Escape keydown 会双触发)
    this.stopBtn?.addEventListener("click", (e) => {
      e.stopPropagation();
      this.stopGeneration();
    });
    // History dropdown click outside
    document.addEventListener("click", (e) => {
      const hist = document.getElementById("history-selector");
      if (hist && !hist.contains(e.target)) {
        this._closeHistoryDropdown();
      }
    });
  }

  _bindBridge() {
    bridge.onMessage = (data) => this._handleBridgeMessage(data);
  }

  _restoreFromState() {
    if (state.get("theme")) this.setTheme(state.get("theme"));
    if (state.get("currentModel"))
      this._setCurrentModel(state.get("currentModel"));
    if (!state.get("showThinking")) {
      this.thinkingToggleIcon?.style.setProperty("color", "");
      this.thinkingToggleBtn?.style.setProperty("background", "");
    }
    if (state.get("draft")) {
      this.messageInput.value = state.get("draft");
      this._updateCharCount();
      this._autoResize();
    }
  }

  // ===== Input handlers =====
  _onInput(e) {
    this._autoResize();
    this._updateCharCount();
    this._handleAutocomplete();
    this._saveDraft();
  }

  _onKeydown(e) {
    if (this._handleAutocompleteKey(e)) return;
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      this.sendMessage();
    } else if (e.key === "Escape" && this._isGenerating) {
      e.preventDefault();
      this.stopGeneration();
    } else if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === "c") {
      e.preventDefault();
      this._copyLastTurn();
    }
  }

  _onDocClick(e) {
    // 原代码查的是 model-selector(不存在),导致 model dropdown 点外面永远关不掉
    // 改成 model-selector-bottom 才会命中
    const sel = document.getElementById("model-selector-bottom");
    if (sel && !sel.contains(e.target)) this._closeModelDropdown();
  }

  // ===== Autocomplete =====
  _handleAutocomplete() {
    const text = this.messageInput.value;
    const cursor = this.messageInput.selectionStart;
    const before = text.substring(0, cursor);
    const lastAt = before.lastIndexOf("@");
    if (lastAt >= 0) {
      const after = before.substring(lastAt + 1);
      if (!after.includes(" ") && !after.includes("\n")) {
        this.autocompleteQuery = after;
        bridge.send({ type: "file_search", query: after });
        return;
      }
    }
    this._hideAutocomplete();
  }

  _showAutocomplete(suggestions) {
    if (!suggestions?.length) {
      this._hideAutocomplete();
      return;
    }
    this.autocompleteSuggestions = suggestions;
    this.autocompleteIndex = 0;
    this.fileAutocomplete.innerHTML = suggestions
      .map(
        (s, i) => `
        <div class="file-suggestion ${i === 0 ? "selected" : ""}" data-index="${i}">
            <span class="file-suggestion-icon">${escapeHtml(s.icon || "📄")}</span>
            <div class="file-suggestion-info">
                <div class="file-suggestion-name">${escapeHtml(s.name)}</div>
                <div class="file-suggestion-path">${escapeHtml(s.relativePath || "")}</div>
            </div>
        </div>`,
      )
      .join("");
    this.fileAutocomplete.classList.add("visible");
    this.fileAutocomplete.querySelectorAll(".file-suggestion").forEach((el) => {
      el.addEventListener("click", () => {
        const idx = parseInt(el.dataset.index, 10);
        this._selectAutocomplete(idx);
      });
    });
  }

  _hideAutocomplete() {
    this.fileAutocomplete?.classList.remove("visible");
    this.autocompleteSuggestions = [];
    this.autocompleteIndex = -1;
  }

  _handleAutocompleteKey(e) {
    if (!this.fileAutocomplete?.classList.contains("visible")) return false;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      this.autocompleteIndex = Math.min(
        this.autocompleteIndex + 1,
        this.autocompleteSuggestions.length - 1,
      );
      this._refreshAutocompleteSelection();
      return true;
    }
    if (e.key === "ArrowUp") {
      e.preventDefault();
      this.autocompleteIndex = Math.max(this.autocompleteIndex - 1, -1);
      this._refreshAutocompleteSelection();
      return true;
    }
    if (e.key === "Enter" || e.key === "Tab") {
      if (this.autocompleteIndex >= 0) {
        e.preventDefault();
        this._selectAutocomplete(this.autocompleteIndex);
        return true;
      }
    }
    if (e.key === "Escape") {
      e.preventDefault();
      this._hideAutocomplete();
      return true;
    }
    return false;
  }

  _refreshAutocompleteSelection() {
    this.fileAutocomplete
      ?.querySelectorAll(".file-suggestion")
      .forEach((el, i) => {
        el.classList.toggle("selected", i === this.autocompleteIndex);
      });
  }

  _selectAutocomplete(index) {
    const s = this.autocompleteSuggestions[index];
    if (!s) return;
    const text = this.messageInput.value;
    const cursor = this.messageInput.selectionStart;
    const before = text.substring(0, cursor);
    const lastAt = before.lastIndexOf("@");
    if (lastAt < 0) return;
    const refText = "@" + (s.relativePath || s.name);
    this.messageInput.value =
      text.substring(0, lastAt) + refText + " " + text.substring(cursor);
    this.messageInput.selectionStart = this.messageInput.selectionEnd =
      lastAt + refText.length + 1;
    this._autoResize();
    this._updateCharCount();
    this._hideAutocomplete();
    this.messageInput.focus();
  }

  // ===== Sending =====
  sendMessage() {
    const text = this.messageInput.value.trim();
    if ((!text && this._pendingImages.length === 0) || this._isGenerating)
      return;
    // P5.4: 检查当前模型是否支持 vision(通过 model 名字启发式)
    if (this._pendingImages.length > 0) {
      const model = (state.get("currentModel") || "").toLowerCase();
      const supportsVision = this._modelSupportsVision(model);
      if (!supportsVision) {
        toast.warning("当前模型不支持图片,可能会被忽略");
      }
    }
    this._hideAutocomplete();
    this._hideWelcome();
    this._addUserMessage(text);
    this.messageInput.value = "";
    this._autoResize();
    this._updateCharCount();
    state.set("draft", "");
    this._startAITurn();
    const images = this._pendingImages.slice();
    this._clearPendingImages();
    // 当前 i18n locale(例 "zh-CN" / "en-US") → 后端会拼进 system prompt,要求模型用相同语言回答
    let userLanguage = null;
    try {
      const fn = window.CodeSage?.i18n?.getLocale;
      if (typeof fn === "function") userLanguage = fn() || null;
    } catch (e) {
      userLanguage = null;
    }
    bridge.send({
      type: "send_message",
      content: text,
      turnId: state.get("currentTurnId"),
      model: state.get("currentModel"),
      images,
      userLanguage,
    });
  }

  _modelSupportsVision(modelLower) {
    return (
      modelLower.includes("vision") ||
      modelLower.includes("gpt-4o") ||
      modelLower.includes("gpt-4-vision") ||
      modelLower.includes("claude-3") ||
      (modelLower.includes("gemini") &&
        (modelLower.includes("1.5") || modelLower.includes("2"))) ||
      modelLower.includes("kimi-vl") ||
      modelLower.includes("minimax-vl")
    );
  }

  sendQuickPrompt(prompt) {
    this.messageInput.value = prompt;
    this.sendMessage();
  }

  stopGeneration() {
    if (!this._isGenerating) return;
    console.log(`[chat] stopGeneration: turnId=${state.get("currentTurnId")}, isGenerating=${this._isGenerating}`);
    // 1) 立刻告诉 Kotlin 停 — 协程会被 cancel,后面 text/thinking 等事件不会再到
    bridge.send({ type: "stop_generation" });
    // 2) 本地立刻重置 UI,避免“状态显示就绪但按钮还是停止”的不一致
    //    (之前只 _setStatus,导致按钮 / turn 计时器 / 状态全错位)
    this._isGenerating = false;
    this._swapButtons(false);
    this._setStatus("已停止", "");
    // 3) 清掉当前 turn 的计时器,标上"已停止"提示
    const turnId = state.get("currentTurnId");
    if (turnId) {
      const turn = this.turns.get(turnId);
      if (turn) {
        clearInterval(turn.timer);
        // 替换闪烁光标为"已停止"小标签,避免用户以为还在输出
        const streamEl = turn.contentEl;
        if (streamEl && streamEl.parentElement) {
          const old = streamEl.parentElement.querySelector(".cursor-blink");
          if (old) old.remove();
        }
        const stopped = document.createElement("span");
        stopped.className = "cs-stream-stopped";
        stopped.textContent = " · 已停止";
        const meta = turn.el?.querySelector(".assistant-time");
        if (meta) meta.after(stopped);
        // 清扫本 turn 仍处于 running 的 tool 卡 — 用户点停意味着所有进行中的
        // 工具也应该被中断,而不是“文字已停但工具还在转圈”
        this._markRunningToolsStopped(turn, "工具未完成:用户已停止生成");
      }
      this.turns.delete(turnId);
      state.set("currentTurnId", null);
    }
  }

  /**
   * 把指定 turn 下所有 status === "running" 的 tool 卡标为 "stopped"。
   * 复用 ToolCall.stop() — 会调 _clearWatchdog + 重新渲染 header/body + 自动展开。
   */
  _markRunningToolsStopped(turn, reason) {
    if (!turn?.toolsSlot) return;
    const toolEls = turn.toolsSlot.querySelectorAll("[data-cs-tool]");
    let stoppedCount = 0;
    toolEls.forEach((toolEl) => {
      const toolId = toolEl.dataset.csTool;
      const tc = this.toolCalls.get(toolId);
      if (tc && tc.status === "running") {
        tc.stop(reason);
        stoppedCount++;
      }
    });
    if (stoppedCount > 0) {
      console.info(
        `[chat] marked ${stoppedCount} tool(s) as stopped in turn (reason: ${reason})`,
      );
    }
  }

  // ===== Mode (Agent / Ask / Manual) =====
  /** 从 state / localStorage 恢复当前 mode, 高亮下拉项 */
  _restoreMode() {
    const saved =
      state.get("mode") ||
      (() => {
        try {
          return JSON.parse(localStorage.getItem("codesage_state_v1") || "{}")
            .mode;
        } catch (e) {
          return null;
        }
      })() ||
      "agent";
    this._applyMode(saved);
  }

  _applyMode(mode) {
    state.set("mode", mode);
    const label = document.getElementById("current-mode-label");
    if (label) label.textContent = mode.charAt(0).toUpperCase() + mode.slice(1);
    document.querySelectorAll("#mode-dropdown .mode-option").forEach((btn) => {
      btn.classList.toggle("active", btn.dataset.mode === mode);
    });
  }

  /** 保留 setMode 旧 API 以避免调用方报错 (e.g. command palette) */
  setMode(mode) {
    this._applyMode(mode);
  }

  /** 点击下拉按钮 — 切换显示 */
  toggleModeDropdown() {
    const dd = document.getElementById("mode-dropdown");
    const btn = document.getElementById("mode-dropdown-btn");
    if (!dd || !btn) return;
    const open = dd.classList.toggle("open");
    btn.classList.toggle("open", open);
    if (open) this._closeModelDropdown();
  }

  /** 从下拉里选一项 */
  selectMode(mode) {
    this._applyMode(mode);
    this._closeModeDropdown();
  }

  _closeModeDropdown() {
    const dd = document.getElementById("mode-dropdown");
    const btn = document.getElementById("mode-dropdown-btn");
    if (dd) dd.classList.remove("open");
    if (btn) btn.classList.remove("open");
  }

  switchChatMode(mode) {
    state.set("currentChatMode", mode);
    document.querySelectorAll(".mode-btn").forEach((b) => {
      b.classList.toggle("active", b.dataset.mode === mode);
    });
    bridge.send({ type: "switch_chat_mode", mode });
  }

  // ===== Theme =====
  toggleTheme() {
    // 之前是 auto→light→dark→auto 的 3 段循环,导致 dark→light 要点 2 次
    // 改成简单双段:dark ↔ light,auto 视为 light(让用户点一下就有反应)
    const cur = state.get("theme") || "auto";
    const next = cur === "dark" ? "light" : "dark";
    this.setTheme(next);
    bridge.send({ type: "theme_changed", theme: next });
  }

  setTheme(theme) {
    state.set("theme", theme);
    this._applyTheme(theme);
  }

  _applyTheme(theme) {
    let resolved = theme;
    if (theme === "auto") {
      resolved = window.matchMedia?.("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
    }
    // 设在 documentElement 上才能在首个 CSS 加载后立即生效，
    // 与 index.html <head> 里 inline script 的设置位置保持一致
    document.documentElement.setAttribute("data-theme", resolved);
    document.documentElement.setAttribute("data-theme-pref", theme);
    // 图标反映实际主题
    if (this.themeIcon) {
      this.themeIcon.className =
        resolved === "dark" ? "fas fa-sun" : "fas fa-moon";
    }
    // hljs 主题
    const lightLink = document.getElementById("hljs-theme-light");
    const darkLink = document.getElementById("hljs-theme-dark");
    if (lightLink) lightLink.disabled = resolved === "dark";
    if (darkLink) darkLink.disabled = resolved !== "dark";
  }

  _watchSystemTheme() {
    if (!window.matchMedia) return;
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    const handler = () => {
      if (state.get("theme") === "auto") this._applyTheme("auto");
    };
    if (mq.addEventListener) mq.addEventListener("change", handler);
    else mq.addListener(handler);
  }

  /**
   * 输入框拖拽改高度(顶部 grip handle)
   *  - 只能向上拖(增加高度)
   *  - 上限 60vh(防遮消息区)
   *  - 下限 56px(默认 1 行)
   *  - 拖动期间 textarea 高度跟着变
   */
  _initInputResize() {
    const handle = document.getElementById("input-resize-handle");
    const box = document.getElementById("input-box");
    const textarea = this.messageInput;
    if (!handle || !box || !textarea) return;
    let dragging = false;
    let startY = 0;
    let startHeight = 0;
    let rafId = null;
    let nextHeight = 0;

    const applyHeight = () => {
      rafId = null;
      box.style.height = nextHeight + "px";
      // textarea 自适应到 box 减去底部行
    };
    const onMove = (e) => {
      if (!dragging) return;
      const dy = startY - e.clientY; // 向上 = 正
      const maxPx = window.innerHeight * 0.6;
      const minPx = 56;
      nextHeight = Math.max(minPx, Math.min(maxPx, startHeight + dy));
      if (!rafId) rafId = requestAnimationFrame(applyHeight);
    };
    const onUp = () => {
      if (!dragging) return;
      dragging = false;
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
      document.body.style.userSelect = "";
      document.body.style.cursor = "";
    };
    handle.addEventListener("mousedown", (e) => {
      e.preventDefault();
      dragging = true;
      startY = e.clientY;
      startHeight = box.getBoundingClientRect().height;
      nextHeight = startHeight;
      document.addEventListener("mousemove", onMove);
      document.addEventListener("mouseup", onUp);
      document.body.style.userSelect = "none";
      document.body.style.cursor = "ns-resize";
    });
    // 双击重置默认高度
    handle.addEventListener("dblclick", () => {
      box.style.height = "";
    });
  }

  /** 点击下拉外区域时关闭 mode dropdown */
  _bindModeDropdownClickOutside() {
    document.addEventListener("click", (e) => {
      const wrapper = document.getElementById("mode-selector-bottom");
      if (!wrapper) return;
      if (wrapper.contains(e.target)) return;
      this._closeModeDropdown();
    });
  }

  /**
   * 点击 input-box 任意位置都聚焦到 textarea (除了 mode dropdown 和 send 按钮等)
   * 避免出现“点了输入框但没反应”的踩坑
   */
  _initInputBoxFocus() {
    const box = document.getElementById("input-box");
    if (!box) return;
    box.addEventListener("mousedown", (e) => {
      const target = e.target;
      // 不抢夺 mode dropdown / send / 拖拽 handle 等交互控件的点击
      if (
        target.closest(
          "button, a, .mode-dropdown, .input-resize-handle, textarea",
        )
      )
        return;
      // 避免在选区中拖选时被抢焦点
      if (window.getSelection()?.toString()) return;
      // 延迟聚焦,防止 mousedown 抢焦点导致 selectstart 取消
      setTimeout(() => this.messageInput?.focus(), 0);
    });
    // 初始自动 focus(延迟 0ms 避开模块加载)
    setTimeout(() => this.messageInput?.focus(), 0);
  }

  _initTheme() {
    try {
      const saved = localStorage.getItem("codesage_state_v1");
      const parsed = saved ? JSON.parse(saved) : null;
      if (parsed?.theme) this.setTheme(parsed.theme);
    } catch (e) {
      // ignore
    }
  }

  // ===== Thinking toggle =====
  toggleThinkingVisibility() {
    const cur = state.get("showThinking", true);
    const next = !cur;
    state.set("showThinking", next);
    if (next) {
      this.thinkingToggleIcon?.style.setProperty("color", "var(--accent)");
      this.thinkingToggleBtn?.style.setProperty(
        "background",
        "var(--accent-soft)",
      );
    } else {
      this.thinkingToggleIcon?.style.setProperty("color", "");
      this.thinkingToggleBtn?.style.setProperty("background", "");
    }
    // 同步所有已存在的 thinking 元素的显示状态(切换后要立刻看到效果)
    this.turns.forEach((turn) => {
      if (turn?.thinking?.el) {
        turn.thinking.el.style.display = next ? "" : "none";
      }
    });
  }

  // ===== Model =====
  toggleModelDropdown() {
    if (this.modelDropdown.classList.contains("open")) {
      this._closeModelDropdown();
    } else {
      this._renderModelDropdown();
      this.modelDropdown.classList.add("open");
      this.modelSelectBtn.classList.add("open");
    }
  }

  _closeModelDropdown() {
    this.modelDropdown?.classList.remove("open");
    this.modelSelectBtn?.classList.remove("open");
  }

  _renderModelDropdown() {
    const groups = state.get("availableModels") || [];
    if (!groups.length) {
      this.modelDropdown.innerHTML = `
        <div class="model-cmd-search">
          <i class="fas fa-search"></i>
          <input type="text" data-cs-role="model-search" placeholder="搜索模型..." />
        </div>
        <div class="model-group-label">暂无可用模型</div>
      `;
      this._bindModelSearch();
      return;
    }
    this.modelDropdown.innerHTML = `
      <div class="model-cmd-search">
        <i class="fas fa-search"></i>
        <input type="text" data-cs-role="model-search" placeholder="搜索模型..." />
      </div>
      <div data-cs-role="model-list">${this._renderModelList(groups)}</div>
      <div class="model-dropdown-footer">
        <button class="cs-button size-sm variant-ghost" data-cs-action="configure-providers">
          <i class="fas fa-cog"></i>&nbsp;配置 Provider
        </button>
      </div>
    `;
    this._bindModelSearch();
    this._bindModelList();
  }

  _renderModelList(groups) {
    return groups
      .map((g) => {
        const opts = g.models
          .map((m) => {
            const isActive = m === state.get("currentModel");
            return `<div class="model-option ${isActive ? "active" : ""}" data-model="${escapeJs(m)}">
                <span class="dot"></span>
                <span class="model-option-name">${escapeHtml(m)}</span>
                ${isActive ? '<span class="model-option-check"><i class="fas fa-check"></i></span>' : ""}
            </div>`;
          })
          .join("");
        return `
            <div class="model-group-label" data-provider="${escapeJs(g.provider)}"><span class="model-group-dot enabled"></span>${escapeHtml(g.provider)}</div>
            ${opts}
        `;
      })
      .join("");
  }

  _bindModelSearch() {
    const input = this.modelDropdown.querySelector(
      '[data-cs-role="model-search"]',
    );
    if (!input) return;
    input.addEventListener("input", (e) => {
      const q = e.target.value.trim().toLowerCase();
      const list = this.modelDropdown.querySelector(
        '[data-cs-role="model-list"]',
      );
      if (!list) return;
      if (!q) {
        list.innerHTML = this._renderModelList(
          state.get("availableModels") || [],
        );
        this._bindModelList();
        return;
      }
      const filtered = (state.get("availableModels") || []).flatMap((g) => {
        const matched = g.models.filter(
          (m) =>
            m.toLowerCase().includes(q) || g.provider.toLowerCase().includes(q),
        );
        return matched.length
          ? [{ provider: g.provider, models: matched }]
          : [];
      });
      if (!filtered.length) {
        list.innerHTML = `<div class="model-group-label">无匹配</div>`;
        return;
      }
      list.innerHTML = this._renderModelList(filtered);
      this._bindModelList();
    });
    // 自动 focus
    setTimeout(() => input.focus(), 0);
  }

  _bindModelList() {
    this.modelDropdown.querySelectorAll(".model-option").forEach((el) => {
      el.addEventListener("click", () => {
        const m = el.dataset.model;
        this._setCurrentModel(m);
        this._closeModelDropdown();
        bridge.send({ type: "switch_model", model: m });
        toast.success(`已切换到 ${m}`);
      });
    });
    const cfgBtn = this.modelDropdown.querySelector(
      '[data-cs-action="configure-providers"]',
    );
    if (cfgBtn) {
      cfgBtn.addEventListener("click", () => {
        this._closeModelDropdown();
        new CommandPalette().open();
      });
    }
  }

  _setCurrentModel(model) {
    state.set("currentModel", model);
    if (this.currentModelName) {
      this.currentModelName.textContent = model || "选择模型";
    }
  }

  // ===== Sessions =====
  toggleHistoryDropdown() {
    if (this.historyDropdown.classList.contains("open")) {
      this._closeHistoryDropdown();
    } else {
      this.historyDropdown.classList.add("open");
      document.getElementById("history-btn")?.classList.add("open");
      bridge.send({ type: "request_sessions" });
    }
  }

  _closeHistoryDropdown() {
    this.historyDropdown?.classList.remove("open");
    document.getElementById("history-btn")?.classList.remove("open");
  }

  onNewSession() {
    bridge.send({ type: "new_session" });
  }

  // ===== Chat operations =====
  clearChat() {
    // 1) 如果正在生成,先停掉 — 否则 turn 计时器 / stop 按钮 / status 状态
    //    都会卡在"生成中"但消息区已被清空
    if (this._isGenerating) {
      this.stopGeneration();
    }
    this.messagesContainer.innerHTML = "";
    this._showWelcome();
    this.turns.clear();
    this.toolCalls.clear();
    this.plans.clear();
    state.set("currentTurnId", null);
    bridge.send({ type: "clear_session" });
  }

  closeArtifactSidebar() {
    this.artifactSidebar?.classList.remove("open");
  }

  // ===== Welcome =====
  _hideWelcome() {
    if (this.welcomeState) this.welcomeState.style.display = "none";
  }
  _showWelcome() {
    if (this.welcomeState) this.welcomeState.style.display = "";
  }

  // ===== User message =====
  _addUserMessage(content) {
    const div = document.createElement("div");
    div.className = "message-enter message-user";
    div.innerHTML = `<div class="message-user-inner">${highlightAtReferences(content)}</div>`;
    this.messagesContainer.appendChild(div);
    scrollToBottom(this.messagesContainer);
  }

  _addFileReferences(turnId, references) {
    const div = document.createElement("div");
    div.className = "message-enter flex justify-end";
    div.innerHTML = `
        <div style="max-width:85%;margin-bottom:4px;">
            <div class="file-reference-tags">
                ${references.map((r) => `<span class="file-reference-tag"><i class="fas fa-file-code"></i> ${escapeHtml(r.name || r.path)}</span>`).join("")}
            </div>
        </div>`;
    this.messagesContainer.appendChild(div);
    scrollToBottom(this.messagesContainer);
  }

  // ===== AI turn =====
  _startAITurn() {
    this._isGenerating = true;
    const turnId =
      "turn_" + Date.now() + "_" + Math.random().toString(36).slice(2, 6);
    state.set("currentTurnId", turnId);
    const startTime = Date.now();

    const div = document.createElement("div");
    div.id = turnId;
    div.className = "message-enter message-assistant";
    div.innerHTML = `
        <div class="assistant-avatar"><i class="fas fa-robot"></i></div>
        <div class="assistant-body">
            <div class="assistant-meta">
                <span class="assistant-name">CodeSage</span>
                <span class="assistant-time" data-cs-role="timer">0.0s</span>
            </div>
            <div data-cs-role="thinking-slot"></div>
            <div data-cs-role="content" class="assistant-content markdown-content">
                <span class="stream-text" data-cs-role="stream"></span><span class="cursor-blink">▌</span>
                <div data-cs-role="tools-slot" class="tool-list-inline"></div>
            </div>
            <div class="stream-loading" data-cs-role="loading"><div class="stream-loading-bar"></div></div>
            <div class="turn-actions" data-cs-role="actions" style="opacity:0;">
                <button class="turn-action-btn" data-cs-action="copy"><i class="fas fa-copy"></i>&nbsp;复制</button>
                <button class="turn-action-btn" data-cs-action="regenerate"><i class="fas fa-redo"></i>&nbsp;重新生成</button>
            </div>
        </div>
    `;
    this.messagesContainer.appendChild(div);

    const timerInterval = setInterval(() => {
      const el = div.querySelector('[data-cs-role="timer"]');
      if (el)
        el.textContent = ((Date.now() - startTime) / 1000).toFixed(1) + "s";
    }, 100);

    this.turns.set(turnId, {
      el: div,
      startTime,
      content: "",
      // content 区域内的 stream span — 文本 delta 只动这里,不动 tools-slot
      contentEl: div.querySelector('[data-cs-role="stream"]'),
      // tools-slot 现在嵌在 content 内部 — 工具按到达顺序 inline 插入
      toolsSlot: div.querySelector('[data-cs-role="tools-slot"]'),
      thinkingSlot: div.querySelector('[data-cs-role="thinking-slot"]'),
      actionsEl: div.querySelector('[data-cs-role="actions"]'),
      timer: timerInterval,
    });

    // Wire up action buttons
    div.querySelectorAll("[data-cs-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.dataset.csAction;
        if (action === "copy") this._copyTurn(turnId);
        else if (action === "regenerate")
          bridge.send({ type: "regenerate", turnId });
      });
    });

    this._setStatus("思考中...", "thinking");
    this._swapButtons(true);
    scrollToBottom(this.messagesContainer);
  }

  _endAITurn(turnId) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    clearInterval(turn.timer);
    this._isGenerating = false;
    state.set("currentTurnId", null);

    // 清扫本 turn 仍处于 running 的 tool 卡 — 避免“AI 答完但工具还在转圈”
    // (后端可能丢 tool_call_complete/error,或 AI 在工具未结束时就被中断)
    this._markRunningToolsStopped(turn, "工具未完成:AI 已结束回答");

    // Render final markdown — 但要保留嵌在 content 内的工具卡
    const contentEl = turn.el.querySelector(".assistant-content");
    if (contentEl) {
      // 工具卡先 detach 出来(避免被 innerHTML 覆盖)
      const tools = Array.from(contentEl.querySelectorAll("[data-cs-tool]"));
      tools.forEach((t) => t.remove());
      contentEl.innerHTML = renderMarkdownSync(turn.content);
      enhanceCodeBlocks(contentEl);
      highlightCode(contentEl);
      // 把工具卡重新追加到 content 末尾(位置由 innerHTML 之后的兄弟元素决定)
      const toolsSlot = contentEl.querySelector('[data-cs-role="tools-slot"]');
      if (toolsSlot) {
        tools.forEach((t) => toolsSlot.appendChild(t));
      }
      // 移除闪烁光标
      contentEl.querySelector(".cursor-blink")?.remove();
    }

    if (turn.actionsEl) {
      turn.actionsEl.style.opacity = "";
      turn.actionsEl.classList.add("visible");
    }

    this._setStatus("就绪", "");
    this._swapButtons(false);
    scrollToBottom(this.messagesContainer, true);
  }

  _swapButtons(isGenerating) {
    this.sendBtn?.classList.toggle("hidden", isGenerating);
    this.stopBtn?.classList.toggle("hidden", !isGenerating);
  }

  _setStatus(text, cls) {
    if (!this.statusLabel) return;
    this.statusLabel.textContent = text;
    this.statusLabel.className =
      "status-text status-inline" + (cls ? " " + cls : "");
  }

  // ===== Per-turn operations =====
  _onTextDelta(turnId, delta) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    turn.content += delta;
    // 修复:之前 turn.contentEl 是 .assistant-content 整个容器, textContent=
    // 会把里面嵌入的工具卡(innerHTML)全部干掉。现在只更新 stream-text span,
    // 让工具卡保留在 content 内部按时间顺序排列
    if (turn.contentEl) turn.contentEl.textContent = turn.content;
    // Hide loading bar
    const loading = turn.el?.querySelector('[data-cs-role="loading"]');
    loading?.classList.add("hidden");
    scrollToBottom(this.messagesContainer);
  }

  _onThinkingStart(turnId, message) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    if (!turn.thinking) {
      turn.thinking = new Thinking();
      // 思考默认折叠(如果 showThinking 也为 false,直接不显示)
      if (!state.get("showThinking", true)) {
        turn.thinking.el.style.display = "none";
      }
      turn.thinkingSlot?.appendChild(turn.thinking.el);
    }
    turn.thinking.appendContent(message + "\n");
  }

  _onThinkingUpdate(turnId, message) {
    const turn = this.turns.get(turnId);
    if (!turn?.thinking) return;
    turn.thinking.appendContent(message + "\n");
  }

  _onThinkingComplete(turnId, elapsedMs) {
    const turn = this.turns.get(turnId);
    if (!turn?.thinking) return;
    turn.thinking.complete(elapsedMs);
  }

  _onToolCallStart(turnId, toolId, toolName, summary, args, icon) {
    console.log(`[chat] _onToolCallStart: turnId=${turnId}, toolId=${toolId}, name=${toolName}`);
    const turn = this.turns.get(turnId);
    if (!turn) {
      console.warn(`[chat] _onToolCallStart: unknown turnId=${turnId}, toolId=${toolId}`);
      return;
    }
    const tc = new ToolCall({
      toolCallId: toolId,
      turnId,
      name: toolName,
      summary,
      arguments: args,
      icon,
    });
    // 工具卡插到 content 内部 (按流式时间顺序 inline 排列 — 解决之前
    // "所有工具堆在回答最上方"的问题)
    turn.toolsSlot?.appendChild(tc.el);
    this.toolCalls.set(toolId, tc);
    scrollToBottom(this.messagesContainer);
  }

  _onToolCallDelta(turnId, toolId, delta) {
    // 不打 log:ToolCallDelta 高频
    const tc = this.toolCalls.get(toolId);
    if (tc) tc.appendDelta(delta);
  }

  _onToolCallComplete(turnId, toolId, success, result) {
    console.log(`[chat] _onToolCallComplete: turnId=${turnId}, toolId=${toolId}, success=${success}`);
    const tc = this.toolCalls.get(toolId);
    if (!tc) {
      // 工具卡片还没创建 (理论不会发生) — 记录一下
      console.warn(`[chat] _onToolCallComplete: unknown toolId=${toolId} (turnId=${turnId})`);
      return;
    }
    // result 可能是完整 result 对象或 null
    tc.complete(success, result);
  }

  _onToolCallError(turnId, toolId, error) {
    console.warn(`[chat] _onToolCallError: turnId=${turnId}, toolId=${toolId}, error=${error}`);
    const tc = this.toolCalls.get(toolId);
    if (!tc) {
      console.warn(`[chat] _onToolCallError: unknown toolId=${toolId} (turnId=${turnId})`);
      return;
    }
    // error 可能是 string, 也可能是 { message: string, stack: string } 这样的对象
    const errMsg =
      typeof error === "string"
        ? error
        : error?.message || JSON.stringify(error) || "未知错误";
    tc.fail(errMsg);
  }

  _onPlanGenerated(turnId, data) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const plan = new Plan({
      planId: data.planId,
      description: data.description,
      steps: data.steps || [],
    });
    turn.toolsSlot?.appendChild(plan.el);
    this.plans.set(data.planId, plan);
    scrollToBottom(this.messagesContainer);
  }

  _onPlanApproved(turnId, data) {
    const plan = this.plans.get(data.planId);
    if (plan) plan.setOverallStatus("approved");
  }

  _onPlanRejected(turnId, data) {
    const plan = this.plans.get(data.planId);
    if (plan) plan.setOverallStatus("rejected");
  }

  _onError(turnId, message) {
    this._isGenerating = false;
    state.set("currentTurnId", null);
    const turn = this.turns.get(turnId);
    if (turn) {
      clearInterval(turn.timer);
      const contentEl = turn.el.querySelector(".assistant-content");
      if (contentEl) {
        const alert = new InlineAlert({
          variant: "error",
          title: "错误",
          message,
        });
        contentEl.innerHTML = "";
        contentEl.appendChild(alert.el);
      }
    }
    this._setStatus("错误", "error");
    this._swapButtons(false);
  }

  _onContextCompressed(turnId, data) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const note = document.createElement("div");
    note.style.cssText = "color:var(--fg-3);font-size:12px;margin-top:8px;";
    note.innerHTML = `<i class="fas fa-compress-arrows-alt"></i>&nbsp;上下文已压缩: ${data.originalTokens} → ${data.compressedTokens} tokens (${escapeHtml(data.strategy)})`;
    turn.el.querySelector(".assistant-content")?.appendChild(note);
  }

  _onSessionMigrated(data) {
    const div = document.createElement("div");
    div.className = "message-enter";
    div.style.cssText =
      "text-align:center;color:var(--fg-3);font-size:12px;padding:8px;";
    div.innerHTML = `<i class="fas fa-exchange-alt"></i>&nbsp;会话已迁移: ${data.messageCount} 条消息`;
    this.messagesContainer.appendChild(div);
    scrollToBottom(this.messagesContainer);
  }

  // ===== Artifacts =====
  _addArtifact(id, title, language, content) {
    state.get("artifacts").set(id, { title, language, content });
    this.artifactSidebar?.classList.add("open");
    const panel = document.createElement("div");
    panel.id = "artifact-" + id;
    panel.className = "artifact-panel";
    panel.innerHTML = `
        <div class="artifact-header">
            <span class="artifact-title"><i class="fas fa-file-code"></i>&nbsp;${escapeHtml(title)}</span>
            <div class="artifact-actions">
                <button class="turn-action-btn" data-art-action="copy"><i class="fas fa-copy"></i></button>
                <button class="turn-action-btn" data-art-action="apply"><i class="fas fa-file-import"></i></button>
                <button class="turn-action-btn" data-art-action="create"><i class="fas fa-plus"></i></button>
            </div>
        </div>
        <pre style="margin:0;max-height:300px;overflow:auto;"><code class="language-${escapeHtml(language)}">${escapeHtml(content)}</code></pre>
    `;
    this.artifactList?.appendChild(panel);
    if (window.hljs) {
      panel
        .querySelectorAll("pre code")
        .forEach((b) => window.hljs.highlightElement(b));
    }
    panel.querySelectorAll("[data-art-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const action = btn.dataset.artAction;
        if (action === "copy") {
          navigator.clipboard?.writeText(content);
          toast.success("已复制");
        } else if (action === "apply") {
          bridge.send({ type: "apply_artifact", artifactId: id, content });
        } else if (action === "create") {
          bridge.send({
            type: "create_file_from_artifact",
            artifactId: id,
            title,
            content,
          });
        }
      });
    });
  }

  _copyTurn(turnId) {
    const turn = this.turns.get(turnId);
    if (!turn?.content) return;
    navigator.clipboard?.writeText(turn.content);
    toast.success("已复制");
  }

  _copyLastTurn() {
    const ids = Array.from(this.turns.keys());
    const last = ids[ids.length - 1];
    if (last) this._copyTurn(last);
  }

  // ===== Bridge message router =====
  _handleBridgeMessage(data) {
    const turnId = data.turnId || state.get("currentTurnId");
    switch (data.type) {
      case "text_delta":
        this._onTextDelta(turnId, data.delta);
        break;
      case "thinking_start":
        this._onThinkingStart(turnId, data.message);
        break;
      case "thinking_update":
        this._onThinkingUpdate(turnId, data.message);
        break;
      case "thinking_complete":
        this._onThinkingComplete(turnId, data.elapsedMs);
        break;
      case "tool_call_start":
        this._onToolCallStart(
          turnId,
          data.toolId,
          data.toolName,
          data.summary,
          data.arguments,
          data.icon,
        );
        break;
      case "tool_call_delta":
        this._onToolCallDelta(turnId, data.toolId, data.delta);
        break;
      case "tool_call_complete":
        this._onToolCallComplete(
          turnId,
          data.toolId,
          data.success,
          data.result,
        );
        break;
      case "tool_call_error":
        this._onToolCallError(turnId, data.toolId, data.error);
        break;
      case "tool_confirmation_needed":
        toast.warning("工具需要确认: " + data.toolName);
        break;
      case "mode_suggestion":
        this._handleModeSuggestion(data);
        break;
      case "turn_complete":
        this._endAITurn(turnId);
        break;
      case "error":
        this._onError(turnId, data.message);
        break;
      case "artifact":
        this._addArtifact(
          data.artifactId,
          data.title,
          data.language,
          data.content,
        );
        break;
      case "set_theme":
        this.setTheme(data.theme);
        break;
      case "set_model":
        this._setCurrentModel(data.model);
        break;
      case "set_models":
        state.set("availableModels", data.models || []);
        this._renderModelDropdown();
        break;
      case "add_user_message":
        this._addUserMessage(data.content);
        break;
      case "clear":
        this.clearChat();
        break;
      case "file_suggestions":
        this._showAutocomplete(data.suggestions);
        break;
      case "file_references":
        this._addFileReferences(turnId, data.references);
        break;
      case "plan_generated":
        this._onPlanGenerated(turnId, data);
        break;
      case "plan_approved":
        this._onPlanApproved(turnId, data);
        break;
      case "plan_rejected":
        this._onPlanRejected(turnId, data);
        break;
      case "plan_modified":
        // TBD
        break;
      case "context_compressed":
        this._onContextCompressed(turnId, data);
        break;
      case "session_migrated":
        this._onSessionMigrated(data);
        break;
      case "set_sessions":
        this._renderHistoryDropdown(data.sessions || []);
        this.sidebar?.setSessions(
          data.sessions || [],
          state.get("currentSessionId"),
        );
        break;
      case "session_created":
        this._onSessionCreated(data.session);
        this.sidebar?.addSession(data.session);
        break;
      case "session_switched":
        state.set("currentSessionId", data.sessionId);
        this.sidebar?.setCurrentSession(data.sessionId);
        break;
      case "session_deleted":
        this.sidebar?.removeSession(data.sessionId);
        break;
      case "session_renamed":
        this.sidebar?.updateSession(data.sessionId, { name: data.name });
        break;
      case "load_history":
        this._loadHistory(data.messages || []);
        break;
      default:
        // 委派 settings / provider / migration 消息给 SettingsView
        if (data?.type?.startsWith("settings_")) {
          window.CodeSage?.settings?._onBridge?.(data);
        } else if (
          data?.type === "set_api_key_result" ||
          data?.type === "test_provider_result"
        ) {
          window.CodeSage?.settings?._onBridge?.(data);
        } else if (data?.type?.startsWith("legacy_migration_")) {
          window.CodeSage?.settings?._onBridge?.(data);
        } else {
          console.warn("Unknown message type:", data.type);
        }
    }
  }

  _handleModeSuggestion(data) {
    if (!data?.effective) return;
    const effective = data.effective;
    const userExplicit = !!data.userExplicit;
    document.querySelectorAll(".mode-btn").forEach((b) => {
      if (b.dataset.mode === effective) b.classList.add("active");
    });
    if (!userExplicit) {
      const label =
        { GENERAL: "通用", CODING: "编程", REASONING: "推理", VISION: "视觉" }[
          effective
        ] || effective;
      toast.info(
        `已根据消息内容自动选择 ${label} 模式(点击顶部门手动切换)`,
        4000,
      );
    }
  }

  _renderHistoryDropdown(sessions) {
    state.set("sessions", sessions);
    if (!sessions.length) {
      this.historyDropdown.innerHTML =
        '<div class="history-empty">暂无会话</div>';
      return;
    }
    const sorted = [...sessions].sort(
      (a, b) => (b.lastActivityAt || 0) - (a.lastActivityAt || 0),
    );
    this.historyDropdown.innerHTML = `
        <div class="history-header">Recent Conversations</div>
        ${sorted
          .map(
            (s) => `
            <div class="history-item" data-session="${escapeJs(s.id)}">
                <i class="fas fa-comment" style="font-size:12px;color:var(--fg-3);"></i>
                <span class="history-item-name">${escapeHtml(s.name || "新会话")}</span>
                <span class="history-item-time">${escapeHtml(formatRelativeTime(s.lastActivityAt || s.createdAt))}</span>
            </div>`,
          )
          .join("")}
    `;
    this.historyDropdown.querySelectorAll(".history-item").forEach((el) => {
      el.addEventListener("click", () => {
        const sid = el.dataset.session;
        bridge.send({ type: "switch_session", sessionId: sid });
        this._closeHistoryDropdown();
      });
    });
  }

  _onSessionCreated(session) {
    if (!session) return;
    const sessions = state.get("sessions") || [];
    sessions.push(session);
    state.set("sessions", sessions);
    this._renderHistoryDropdown(sessions);
  }

  _loadHistory(messages) {
    this.clearChat();
    if (!messages.length) return;
    this._hideWelcome();
    for (const msg of messages) {
      if (msg.role === "user") {
        this._addUserMessage(msg.content);
      } else if (msg.role === "assistant") {
        // Render historical assistant turn
        const turnId =
          "hist_" + Date.now() + "_" + Math.random().toString(36).slice(2, 5);
        const div = document.createElement("div");
        div.id = turnId;
        div.className = "message-enter message-assistant";
        div.innerHTML = `
            <div class="assistant-avatar"><i class="fas fa-robot"></i></div>
            <div class="assistant-body">
                <div class="assistant-meta">
                    <span class="assistant-name">CodeSage</span>
                </div>
                <div class="assistant-content markdown-content">${renderMarkdownSync(msg.content)}</div>
                <div class="turn-actions visible" style="opacity:0;">
                    <button class="turn-action-btn"><i class="fas fa-copy"></i>&nbsp;复制</button>
                </div>
            </div>`;
        this.messagesContainer.appendChild(div);
        const contentEl = div.querySelector(".assistant-content");
        enhanceCodeBlocks(contentEl);
        highlightCode(contentEl);
        const copyBtn = div.querySelector(".turn-action-btn");
        copyBtn?.addEventListener("click", () => {
          navigator.clipboard?.writeText(msg.content);
          toast.success("已复制");
        });
      }
    }
    scrollToBottom(this.messagesContainer, true);
  }

  // ===== UI helpers =====
  _autoResize() {
    this.messageInput.style.height = "auto";
    this.messageInput.style.height =
      Math.min(this.messageInput.scrollHeight, 200) + "px";
  }

  _updateCharCount() {
    const len = this.messageInput.value.length;
    if (this.charCounter) {
      this.charCounter.textContent = `${len} / ${MAX_CHARS}`;
      this.charCounter.className = "char-counter";
      if (len >= MAX_CHARS) this.charCounter.classList.add("danger");
      else if (len >= WARNING_THRESHOLD)
        this.charCounter.classList.add("warning");
    }
  }

  _saveDraft() {
    state.set("draft", this.messageInput.value);
  }

  _handleInputFlush() {
    // hook for future: e.g. submit on idle
  }
}

export const chat = new ChatView();
window.CodeSage = window.CodeSage || {};
window.CodeSage.chat = chat;
