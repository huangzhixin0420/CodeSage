/**
 * Chat v2.0 — 核心渲染层
 * =======================
 *
 * 关键设计 (解决 v1 致命问题):
 *
 *   1. 工具调用按时间线 INLINE 插入
 *      - 每个 turn 维护一个 "stream anchor" 指针
 *      - text_delta 直接 append 到当前 stream 段(不再 textContent=整体重写)
 *      - tool_call_start 在**当前 stream anchor 之后**插入 tool card DOM
 *      - anchor 推进到 tool card 之后,后续 text_delta 继续在新 stream 段追加
 *      - 结果: 工具卡出现在产生它的那句话之后,而非堆在回答末尾
 *
 *   2. 思考默认折叠
 *      - 见 cs-thinking.js,complete() 立即折叠
 *      - running 状态默认展开
 *
 *   3. 工具卡完成后默认折叠
 *      - 见 cs-tool-call.js,complete()/fail() 立即 collapse
 *
 *   4. 错误时保留已渲染内容
 *      - _onError 不再 innerHTML = "" ,改为 append error alert
 *
 *   5. 滚动策略
 *      - 文本/思考流式打字时**不**自动滚(用户可能在回看)
 *      - 只在新 turn 开始、新 tool 开始时 scrollToBottom
 *
 * 数据契约 (与 EventRouter / bridge 完全一致):
 *   text_delta            { turnId, delta }
 *   thinking_start/update/complete { turnId, ... }
 *   tool_call_start       { turnId, toolId, toolName, summary, arguments, icon }
 *   tool_call_delta       { turnId, toolId, delta }
 *   tool_call_complete    { turnId, toolId, success, result }
 *   tool_call_error       { turnId, toolId, error }
 *   plan_generated/approved/rejected/modified
 *   context_compressed    { turnId, originalTokens, compressedTokens, strategy }
 *   session_migrated      { messageCount }
 *   error                 { turnId, message }
 *   mode_suggestion       { turnId, effective, suggestion, userExplicit }
 */

import { StructuredThinking } from "../components/cs-thinking-v2.js";
import { CodeBlockCard } from "../components/cs-code-block.js";
import { ToolCall } from "../components/cs-tool-call.js";
import { Plan } from "../components/cs-plan.js";
import { PlanV2 } from "../components/cs-plan-v2.js";
import { AgentDashboard } from "../components/cs-agent-dashboard.js";
import { MentionAutocomplete } from "../components/cs-mention.js";
import { ContextChips } from "../components/cs-context-chips.js";
import { RunLogBuilder } from "../run-log.js";
import { MessageVirtualizer } from "../message-virtualizer.js";
import { SessionPopover } from "../components/cs-session-popover.js";
import { EmptyState } from "../components/cs-empty-state.js";
import { InlineAlert } from "../components/cs-inline-alert.js";
import { toast } from "../components/cs-toast.js";
import { bridge } from "../bridge.js";
import { state } from "../state.js";
import { renderMarkdown, preloadMarkdown } from "../markdown.js";
import { icon } from "../icons.js";
import {
  escapeHtml,
  scrollToBottom,
  debounce,
  throttle,
  genId,
  formatDuration,
} from "../utils.js";

/** 从用户名取首字母;无则默认 U */
function getUserInitial(name = "") {
  return (name.trim()[0] || "U").toUpperCase();
}

/** 将字符串映射为稳定的 HSL 背景色 */
function stringToHslColor(str, s = 65, l = 48) {
  let hash = 0;
  for (let i = 0; i < (str || "").length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash);
  }
  const h = Math.abs(hash % 360);
  return `hsl(${h} ${s}% ${l}%)`;
}

/** 输入区最大字符数 */
const MAX_INPUT_LENGTH = 4000;

// ===== 简易 toast 兜底(若 cs-toast 没暴露) =====
const _toast = window.CodeSage?.toast ||
  toast || {
    error: console.error,
    success: console.log,
    info: console.log,
    warn: console.warn,
  };

// ===================== ChatView =====================

class ChatView {
  constructor() {
    this.turns = new Map(); // turnId -> Turn
    this.toolCalls = new Map(); // toolId -> ToolCall
    this.plans = new Map(); // planId -> Plan | PlanV2
    this.runLogBuilder = new RunLogBuilder(); // RunLog 数据层
    this._isGenerating = false;
    this._currentStreamSegment = null;
    this._messageVirtualizer?.unpin(turn.el); // 当前 turn 内最后一段 text stream
    this._sessionPopover = null;
    this._scrollLocked = false; // 用户手动上滚后锁定自动滚
    this._inputAttachments = [];
    this._contextChips = null;
    this._chatMode = null; // null = 后端建议
    this._commandPalette = null;
  }

  init() {
    try {
      this.messagesContainer = document.getElementById("messages-container");
      this.messagesInner = document.getElementById("messages-inner");
      this._messageVirtualizer = new MessageVirtualizer(this.messagesInner, {
        limit: 50,
        batch: 50,
      });
      this.welcomeState = document.getElementById("welcome-state");
      this.inputTextarea = document.getElementById("input-textarea");
      this.sendBtn = document.getElementById("send-btn");
      this.statusLine = document.getElementById("status-line");
      this.statusText = document.getElementById("status-text");
      this.inputAttachmentsEl = document.getElementById("input-attachments");
      this.contextChipsEl = document.getElementById("context-chips");
      this.inputContainer = document.getElementById("input-container");
      this.charCountEl = document.getElementById("input-char-count");
      this.hintModel = document.getElementById("hint-model");
      this.sessionTitleEl = document.getElementById("session-title");
      this.appContainer = document.getElementById("app-container");

      this._initSessionPopover();
      this._initHeader();
      this._initInput();
      this._initScrollWatcher();
      this._initKeyboard();
      this._initModelSelector();
      this._initModeTabs();
      this._initAgentDashboard();

      // 渲染欢迎区
      this._renderWelcomeActions();

      console.info("[chat] initialized v2.0");
    } catch (e) {
      console.error("[chat] init failed:", e);
    }
  }

  // ============ Session Popover (O5.2) ============

  _initSessionPopover() {
    const anchor = document.getElementById("session-history-btn");
    this._sessionPopover = new SessionPopover({
      anchor,
      onNew: () => {
        this._sessionPopover?.close();
        this.onNewSession();
      },
      onSelect: (id) => {
        this._sessionPopover?.close();
        this._switchSession(id);
      },
      onRename: (id) => this._renameSession(id),
      onDelete: (id) => this._deleteSession(id),
    });
    // 不挂到容器,popover 自己 appendChild 到 body(absolute 定位)
  }

  /**
   * O5.2: 切换历史会话弹出框显示
   * 取代原来的 toggleSidebar
   */
  openSessionHistory() {
    this._sessionPopover?.toggle();
  }

  setSessions(sessions) {
    this._sessionPopover?.setSessions(sessions);
  }

  setCurrentSession(id, name) {
    this._sessionPopover?.setCurrent(id);
    if (this.sessionTitleEl) {
      this.sessionTitleEl.textContent = name || "";
      this.sessionTitleEl.title = name || "";
    }
  }

  // ============ Header ============

  _initHeader() {
    // O5.2: sidebar-toggle-btn 改名为 session-history-btn
    document
      .getElementById("session-history-btn")
      ?.addEventListener("click", () => this.openSessionHistory());
    document
      .getElementById("theme-toggle-btn")
      ?.addEventListener("click", () => this.toggleTheme());
    document
      .getElementById("thinking-toggle-btn")
      ?.addEventListener("click", () => this.toggleThinkingVisibility());
    // O5.3: artifacts-toggle-btn / artifacts-close-btn 已删除
    document
      .getElementById("new-session-btn")
      ?.addEventListener("click", () => this.onNewSession());
    document
      .getElementById("settings-btn")
      ?.addEventListener("click", () => this.showSettings());
  }

  _initAgentDashboard() {
    if (!this.statusLine) return;
    this._agentDashboard = new AgentDashboard({ container: this.statusLine });
    this.statusLine.appendChild(this._agentDashboard.el);
  }

  toggleTheme() {
    const cur = document.documentElement.getAttribute("data-theme");
    const next = cur === "dark" ? "light" : "dark";
    document.documentElement.setAttribute("data-theme", next);
    document.body?.setAttribute("data-theme", next);
    const hljsLight = document.getElementById("hljs-theme-light");
    const hljsDark = document.getElementById("hljs-theme-dark");
    if (hljsLight) hljsLight.disabled = next === "dark";
    if (hljsDark) hljsDark.disabled = next !== "dark";
    const icon = document.getElementById("theme-icon");
    if (icon) icon.className = next === "dark" ? "fas fa-sun" : "fas fa-moon";
    bridge.send({ type: "theme_changed", theme: next });
  }

  setTheme(theme) {
    document.documentElement.setAttribute("data-theme", theme);
    document.body?.setAttribute("data-theme", theme);
    const hljsLight = document.getElementById("hljs-theme-light");
    const hljsDark = document.getElementById("hljs-theme-dark");
    if (hljsLight) hljsLight.disabled = theme === "dark";
    if (hljsDark) hljsDark.disabled = theme !== "dark";
  }

  toggleThinkingVisibility() {
    const cur = state.get("showThinking", false);
    state.set("showThinking", !cur);
    const icon = document.getElementById("thinking-toggle-icon");
    if (icon) {
      icon.style.color = !cur ? "var(--accent)" : "";
    }
    _toast.info(!cur ? "已开启思考展示" : "已隐藏思考过程");
    bridge.send({ type: "set_show_thinking", enabled: !cur });
  }

  // O5.3: toggleArtifacts 已删除(工件面板不再常驻)

  onNewSession() {
    bridge.send({ type: "new_session" });
  }

  showSettings() {
    // v2.0 修复:原来 bridge.send("open_settings") 经由 Kotlin 中转,
    // 但 JCEFChatPanel.handleJSMessage 调 settingsHandler.handle("open_settings_view", ...)
    // 被 SettingsBridgeHandler.handle() 的 `if (!type.startsWith("settings_")) return false` 直接拒绝。
    // 结果:设置按钮点了完全没反应。
    // 修:直接走前端 window.CodeSage.openSettings(),由 main.js 兜底显示 in-web 设置视图。
    window.CodeSage?.openSettings?.();
  }

  // ============ Model Selector ============

  _initModelSelector() {
    const btn = document.getElementById("model-pill-btn");
    const dropdown = document.getElementById("model-dropdown");
    if (!btn || !dropdown) return;

    btn.addEventListener("click", (e) => {
      e.stopPropagation();
      const isOpen = dropdown.classList.toggle("open");
      btn.classList.toggle("open", isOpen);
      btn.setAttribute("aria-expanded", String(isOpen));
    });

    document.addEventListener("click", (e) => {
      if (!dropdown.contains(e.target) && !btn.contains(e.target)) {
        dropdown.classList.remove("open");
        btn.classList.remove("open");
        btn.setAttribute("aria-expanded", "false");
      }
    });
  }

  setAvailableModels(groups) {
    const dropdown = document.getElementById("model-dropdown");
    if (!dropdown) return;
    // O11: 无模型配置时显示引导空状态
    if (!groups || groups.length === 0) {
      dropdown.innerHTML = "";
      const empty = new EmptyState({
        icon: "fa-plug-circle-bolt",
        title: "尚未配置模型",
        description: "前往设置添加 AI Provider,开始你的第一次对话。",
        variant: "empty",
        actions: [
          {
            label: "打开设置",
            icon: "fa-gear",
            variant: "primary",
            onClick: () => this.showSettings(),
          },
        ],
      });
      dropdown.appendChild(empty.el);
      return;
    }
    const html = [
      `
            <div class="model-search">
                <i class="fas fa-magnifying-glass"></i>
                <input type="text" placeholder="搜索模型…" data-cs-role="search" />
            </div>
        `,
    ];
    for (const g of groups || []) {
      html.push(`
                <div class="model-group">
                    <div class="model-group-label">
                        <span class="model-group-dot enabled"></span>
                        ${escapeHtml(g.provider)}
                    </div>
                    ${(g.models || [])
                      .map(
                        (m) => `
                        <div class="model-option" data-cs-model="${escapeHtml(m)}" data-cs-provider="${escapeHtml(g.provider)}" role="option" tabindex="0">
                            <span class="check"><i class="fas fa-check"></i></span>
                            <span class="model-option-name">${escapeHtml(m)}</span>
                        </div>
                    `,
                      )
                      .join("")}
                </div>
            `);
    }
    dropdown.innerHTML = html.join("");

    // 绑定选择
    dropdown.querySelectorAll(".model-option").forEach((opt) => {
      opt.addEventListener("click", () => {
        const model = opt.dataset.csModel;
        const provider = opt.dataset.csProvider;
        dropdown.classList.remove("open");
        document.getElementById("model-pill-btn")?.classList.remove("open");
        bridge.send({ type: "switch_model", model, provider });
        this.setModelLabel(model, provider);
      });
    });

    // 搜索过滤
    const search = dropdown.querySelector('[data-cs-role="search"]');
    search?.addEventListener("input", (e) => {
      const q = e.target.value.toLowerCase();
      dropdown.querySelectorAll(".model-option").forEach((opt) => {
        const m = (opt.dataset.csModel || "").toLowerCase();
        opt.style.display = m.includes(q) ? "" : "none";
      });
    });
  }

  setModelLabel(model, provider) {
    const name = document.getElementById("model-name");
    // 修复: model 为空时显示明确的中性占位(原 "—" 用户看不懂,"Loading…" 误导)
    if (name) name.textContent = model || "选择模型";
    if (this.hintModel)
      this.hintModel.textContent = provider
        ? `${provider} · ${model}`
        : model || "";

    document.querySelectorAll(".model-option").forEach((opt) => {
      opt.classList.toggle("active", opt.dataset.csModel === model);
    });
  }

  // ============ Mode Tabs ============

  _initModeTabs() {
    document.querySelectorAll("#mode-tabs .mode-tab").forEach((tab) => {
      tab.addEventListener("click", () => {
        const mode = tab.dataset.mode;
        this._setChatMode(mode);
      });
    });
    // 默认 agent (高亮 agent tab)
    this._setChatMode("agent", true);
  }

  _setChatMode(mode, silent = false) {
    this._chatMode = mode;
    document.querySelectorAll("#mode-tabs .mode-tab").forEach((tab) => {
      tab.classList.toggle("active", tab.dataset.mode === mode);
    });
    if (!silent) {
      bridge.send({ type: "switch_chat_mode", mode });
    }
  }

  getCurrentChatMode() {
    return this._chatMode;
  }

  // ============ Input ============

  _initInput() {
    const ta = this.inputTextarea;
    const send = this.sendBtn;
    if (!ta || !send) return;

    // 自动撑高
    const autoResize = () => {
      ta.style.height = "auto";
      ta.style.height = Math.min(ta.scrollHeight, 240) + "px";
    };

    // 字符计数与上限控制
    const updateCharCount = () => {
      const len = ta.value.length;
      if (this.charCountEl) {
        this.charCountEl.textContent = `${len} / ${MAX_INPUT_LENGTH}`;
        this.charCountEl.classList.toggle(
          "warning",
          len >= 3600 && len < MAX_INPUT_LENGTH,
        );
        this.charCountEl.classList.toggle("error", len >= MAX_INPUT_LENGTH);
      }
    };
    this._updateCharCount = updateCharCount;

    const enforceMaxLength = () => {
      if (ta.value.length > MAX_INPUT_LENGTH) {
        const start = ta.selectionStart;
        const end = ta.selectionEnd;
        ta.value = ta.value.substring(0, MAX_INPUT_LENGTH);
        // 尽量保持光标/选区在合理位置
        const newPos = Math.min(start, MAX_INPUT_LENGTH);
        try {
          ta.setSelectionRange(newPos, Math.min(end, MAX_INPUT_LENGTH));
        } catch (e) {
          /* ignore */
        }
        return true;
      }
      return false;
    };

    ta.addEventListener("input", (e) => {
      const truncated = enforceMaxLength();
      autoResize();
      updateCharCount();
      if (
        truncated &&
        e.inputType &&
        e.inputType.startsWith("insertFromPaste")
      ) {
        _toast?.error?.(`已自动截断至 ${MAX_INPUT_LENGTH} 字符`);
      }
    });
    updateCharCount();

    // 上下文 chip 区
    this._contextChips = new ContextChips({
      container: this.contextChipsEl,
      onChange: (summary) => {
        // token 超限视觉反馈由 ContextChips 自己处理
        if (summary.overLimit) {
          _toast?.warning?.("上下文 token 已超出限制,部分引用可能不会被发送");
        }
      },
    });

    // 提交
    const submit = () => {
      const v = ta.value;
      const hasChips =
        this._contextChips && this._contextChips.getItems().length > 0;
      if (!v.trim() && this._inputAttachments.length === 0 && !hasChips) return;
      if (v.length >= MAX_INPUT_LENGTH) {
        _toast?.error?.(`已达到 ${MAX_INPUT_LENGTH} 字符上限`);
        return;
      }
      if (this._isGenerating) {
        this._interrupt();
        return;
      }
      this._send(v);
    };
    send.addEventListener("click", submit);
    ta.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
        e.preventDefault();
        submit();
      }
    });

    // 工具按钮
    document.getElementById("attach-btn")?.addEventListener("click", () => {
      this._requestAttach("attach_file", "文件");
    });
    document.getElementById("image-btn")?.addEventListener("click", () => {
      this._requestAttach("attach_image", "图片");
    });
    document.getElementById("mention-btn")?.addEventListener("click", () => {
      ta.value = ta.value + (ta.value && !ta.value.endsWith(" ") ? " @" : "@");
      ta.focus();
      // 触发 input 让 MentionAutocomplete 弹出
      ta.dispatchEvent(new Event("input", { bubbles: true }));
    });

    // @/# 自动补全 — v2.2 改为 chip 模式
    this._mentionAutocomplete = new MentionAutocomplete({
      textarea: ta,
      insertAsChip: true,
      onSearch: async (query) => {
        // 优先走后端 file_search;bridge 未就绪时返回空,让 fallback 候选生效。
        if (!bridge.bridgeReady) return [];
        bridge.send({ type: "file_search", query });
        if (window.__cs_file_search_results) {
          const results = window.__cs_file_search_results;
          window.__cs_file_search_results = null;
          return results;
        }
        return [];
      },
      onSelect: (item) => {
        if (!this._contextChips) return;
        if (item.type === "file") {
          this._contextChips.add({
            type: "file",
            value: item.value,
            label: item.label || item.value,
            icon: "fa-file-code",
          });
        } else if (item.type === "context") {
          this._contextChips.add({
            type: "context",
            value: item.value,
            label: item.label,
            icon: "fa-i-cursor",
          });
        }
      },
    });

    // 拖拽 / 粘贴增强
    this._initDragAndPaste();
  }

  /**
   * 拖拽 / 粘贴增强
   *   - 拖拽文件进入高亮为 drop zone
   *   - 图片粘贴后显示大图预览
   *   - 拖拽文本(IDE 选区)自动插入 #selection chip
   */
  _initDragAndPaste() {
    const container = this.inputContainer;
    const ta = this.inputTextarea;
    if (!container || !ta) return;

    const setDrop = (active) => {
      container.classList.toggle("drop-active", active);
    };

    container.addEventListener("dragenter", (e) => {
      e.preventDefault();
      e.stopPropagation();
      setDrop(true);
    });
    container.addEventListener("dragover", (e) => {
      e.preventDefault();
      e.stopPropagation();
      // 有文件才显示 drop zone
      if (e.dataTransfer?.types?.includes("Files")) {
        setDrop(true);
      }
    });
    container.addEventListener("dragleave", (e) => {
      e.preventDefault();
      e.stopPropagation();
      if (!container.contains(e.relatedTarget)) setDrop(false);
    });
    container.addEventListener("drop", async (e) => {
      e.preventDefault();
      e.stopPropagation();
      setDrop(false);
      const files = e.dataTransfer?.files;
      if (files && files.length > 0) {
        for (const file of files) {
          if (file.type.startsWith("image/")) {
            const data = await this._readFileAsDataURL(file);
            this._inputAttachments.push({
              type: "image",
              name: file.name,
              data,
              size: data.length,
            });
          } else {
            this._contextChips?.add({
              type: "file",
              value: file.name,
              label: file.name,
              icon: "fa-file",
              size: file.size,
            });
          }
        }
        this._renderInputAttachments();
        _toast?.info?.("已附加拖拽内容");
        return;
      }
      const text = e.dataTransfer?.getData("text/plain");
      if (text) {
        this._contextChips?.add({
          type: "context",
          value: "selection",
          label: "#selection",
          icon: "fa-i-cursor",
        });
        _toast?.info?.("已添加当前选区 #selection");
      }
    });

    ta.addEventListener("paste", (e) => {
      const items = e.clipboardData?.items;
      if (!items) return;
      let hasImage = false;
      for (const item of items) {
        if (item.type.startsWith("image/")) {
          e.preventDefault();
          const file = item.getAsFile();
          if (!file) continue;
          hasImage = true;
          const reader = new FileReader();
          reader.onload = (ev) => {
            const data = ev.target.result;
            this._inputAttachments.push({
              type: "image",
              name: file.name || "pasted-image.png",
              data,
              size: data.length,
            });
            this._renderInputAttachments();
          };
          reader.readAsDataURL(file);
        }
      }
      if (hasImage) {
        _toast?.info?.("已粘贴图片,点击可预览大图");
      }
    });
  }

  _readFileAsDataURL(file) {
    return new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = (e) => resolve(e.target.result);
      reader.readAsDataURL(file);
    });
  }

  /**
   * 附件按钮的统一入口 — 修"点击没反应"反馈链。
   *
   * 旧实现只 `bridge.send(...)` 然后就什么都不做。问题是:
   *   1. 用户点完没有**即时**视觉反馈,要等到文件选择器弹出才知道点上了
   *      (但文件选择器也可能被遮挡 / 弹到其他屏幕 / 在 JCEF 嵌入式环境不出现)
   *   2. bridge 还没 ready 时 send 是排队,Kotlin 收不到也不会报错
   *   3. 选中后回调被 try/catch 吞了,用户也不知道到底成功没
   *
   * 这里: 1) 立刻弹一个"正在打开选择器"toast(让用户确认点上了),
   *      2) 设 8s 超时,超时还没收到回调发 error toast(避免"无声失败"),
   *      3) 把这条 type 存起来,选中后由 _onFileReferencesAdded /
   *         setInputAttachments 主动清掉超时,避免误报。
   */
  _requestAttach(type, kindLabel) {
    if (!bridge.bridgeReady) {
      _toast?.error?.("插件尚未初始化完成,请稍候再试");
      return;
    }
    _toast?.info?.(`正在打开${kindLabel}选择器…`);
    this._pendingAttach = {
      type,
      timer: setTimeout(() => {
        // 8s 还没回调 — 大概率 FileChooser 没弹出来或被挡住
        if (this._pendingAttach && this._pendingAttach.type === type) {
          this._pendingAttach = null;
          _toast?.error?.(
            `${kindLabel}选择器未响应 (8s 超时),可能 IDE 窗口被遮挡或 JCEF 环境受限`,
          );
        }
      }, 8000),
    };
    bridge.send({ type });
  }

  /** 文件 / 图片成功回调时调,清掉超时定时器(避免误报) */
  _clearPendingAttach() {
    if (this._pendingAttach) {
      clearTimeout(this._pendingAttach.timer);
      this._pendingAttach = null;
    }
  }

  _send(text) {
    const v = text.trim();
    // 将上下文 chip 解析为文本引用和 fileRefs
    const chipPayload = this._contextChips?.toPayload() || {
      text: "",
      fileRefs: [],
    };
    const messageText = chipPayload.text ? `${chipPayload.text}\n${v}` : v;
    this.inputTextarea.value = "";
    this.inputTextarea.style.height = "auto";
    this._updateCharCount?.();
    // attachments 通过 bridge 传给后端,不在前端渲染
    bridge.send({
      type: "send_message",
      message: messageText,
      images: this._inputAttachments.map((a) => a.data),
      fileRefs: chipPayload.fileRefs,
      userLanguage: "zh-CN",
    });
    this._inputAttachments = [];
    this._renderInputAttachments();
    this._contextChips?.clear();
  }

  _interrupt() {
    bridge.send({ type: "stop_generation" });
  }

  setInputAttachments(attachments) {
    this._inputAttachments = attachments || [];
    this._renderInputAttachments();
    // 清掉 _requestAttach 的 8s 超时,避免误报"选择器未响应"
    this._clearPendingAttach();
    // v2.1 反馈:用户点图片按钮 → 选完图片后,之前的反馈只有 18x18 的小缩略图,
    // 太隐蔽,用户体感"点了没反应"。这里:
    //   1. 自动 focus textarea,光标停在末尾,方便用户继续打字
    //   2. toast 提示"已添加图片 N 张",明确告诉用户图片已挂上
    // 只在**新增**时弹 toast(非空且实际拿到图片),空数组(取消/超大图丢弃)不弹。
    const images = this._inputAttachments.filter(
      (a) => a && a.type === "image" && a.data,
    );
    if (images.length > 0) {
      this._focusInputAtEnd();
      const name = images[0].name || "图片";
      const more = images.length > 1 ? ` 等 ${images.length} 张` : "";
      _toast?.info?.(`已添加 ${name}${more},可继续输入问题`);
    }
  }

  /** 把焦点放回 textarea,光标停在末尾(供附加文件/图片后使用)。 */
  _focusInputAtEnd() {
    const ta = this.inputTextarea;
    if (!ta) return;
    ta.focus();
    const end = ta.value.length;
    try {
      ta.setSelectionRange(end, end);
    } catch (e) {
      /* 旧浏览器可能不支持,忽略 */
    }
  }

  // ============ 文件引用(Cursor 风格 @file) ============
  //
  // Kotlin 在文件选择器选完文件后推 file_references_added。
  // v2.2:改为渲染成上下文 chip,而不是直接塞进 textarea,避免干扰用户输入
  // 并支持 token 预算可视化。发送时 chip 会解析为 @path 文本。
  _onFileReferencesAdded(refs) {
    if (!refs || refs.length === 0) return;
    // 清掉 _requestAttach 的 8s 超时,避免误报"选择器未响应"
    this._clearPendingAttach();

    for (const r of refs) {
      const path = r.relativePath || r.name || r.path || "";
      if (!path) continue;
      this._contextChips?.add({
        type: "file",
        value: path,
        label: r.name || path,
        icon: "fa-file-code",
        size: r.size || 0,
      });
    }

    // 反馈:首文件 + 数量提示
    const first = refs[0];
    const firstLabel = first?.relativePath || first?.name || "文件";
    const more = refs.length > 1 ? ` 等 ${refs.length} 个文件` : "";
    _toast?.info?.(`已添加 @${firstLabel}${more},可继续输入问题`);
  }

  _renderInputAttachments() {
    if (!this.inputAttachmentsEl) return;
    // v2.1:同步在 chip 上加个 input-attachment-name 包装,让 CSS 负责截断 / 省略号。
    // > 8MB 的图片标 too-large(后端 buildAttachment 会静默丢弃大图,
    // 这里给个视觉提示告诉用户"这张图可能发不出去")。
    const MAX_IMAGE_DATA_URL_SIZE = 8 * 1024 * 1024;
    // 文件体积格式化:B / KB / MB,留 1 位小数。> 8MB 在调用方另外标 too-large。
    const fmtSize = (bytes) => {
      if (!bytes || bytes < 0) return "";
      if (bytes < 1024) return bytes + "B";
      if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + "KB";
      return (bytes / 1024 / 1024).toFixed(1) + "MB";
    };
    this.inputAttachmentsEl.innerHTML = this._inputAttachments
      .map((a, i) => {
        if (a.type === "image" && a.data) {
          const isTooLarge = (a.size || 0) > MAX_IMAGE_DATA_URL_SIZE;
          const sizeLabel = isTooLarge
            ? ` · 超大(可能发不出)`
            : a.size
              ? ` · ${fmtSize(a.size)}`
              : "";
          return `
                        <div class="input-attachment image-attachment${isTooLarge ? " too-large" : ""}" title="${escapeHtml(a.name || "image")}${sizeLabel}" data-cs-idx="${i}">
                            <img class="input-attachment-preview" src="${escapeHtml(a.data)}" alt="" data-cs-action="preview" />
                            <span class="input-attachment-name">${escapeHtml(a.name || "image")}${sizeLabel}</span>
                            <button class="input-attachment-remove" data-cs-idx="${i}" aria-label="移除">
                                <i class="fas fa-xmark"></i>
                            </button>
                        </div>
                    `;
        }
        return `
                    <div class="input-attachment">
                        <i class="fas fa-file" style="color:var(--fg-tertiary);"></i>
                        <span class="input-attachment-name">${escapeHtml(a.name || "file")}</span>
                        <button class="input-attachment-remove" data-cs-idx="${i}" aria-label="移除">
                            <i class="fas fa-xmark"></i>
                        </button>
                    </div>
                `;
      })
      .join("");
    this.inputAttachmentsEl
      .querySelectorAll(".input-attachment-remove")
      .forEach((btn) => {
        btn.addEventListener("click", () => {
          const idx = parseInt(btn.dataset.csIdx, 10);
          this._inputAttachments.splice(idx, 1);
          this._renderInputAttachments();
        });
      });

    // 图片点击切换大图预览
    this.inputAttachmentsEl
      .querySelectorAll('.input-attachment-preview[data-cs-action="preview"]')
      .forEach((img) => {
        img.addEventListener("click", () => {
          const attachment = img.closest(".input-attachment");
          attachment?.classList.toggle("expanded");
        });
      });
  }

  // ============ Scroll Watcher ============

  _initScrollWatcher() {
    if (!this.messagesContainer) return;
    this.messagesContainer.addEventListener("scroll", () => {
      const el = this.messagesContainer;
      const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
      this._scrollLocked = !atBottom;
    });
  }

  _maybeScrollToBottom(force = false) {
    if (this._scrollLocked && !force) return;
    scrollToBottom(this.messagesContainer, true);
  }

  // ============ Keyboard Shortcuts ============

  _initKeyboard() {
    document.addEventListener("keydown", (e) => {
      const isMac = navigator.platform.toUpperCase().includes("MAC");
      const mod = isMac ? e.metaKey : e.ctrlKey;
      if (mod && e.key === "n") {
        e.preventDefault();
        this.onNewSession();
      } else if (mod && e.shiftKey && (e.key === "L" || e.key === "l")) {
        // O5.2: Cmd/Ctrl+Shift+L — 唤出历史会话弹出框
        e.preventDefault();
        this.openSessionHistory();
      } else if (mod && e.key === "k") {
        e.preventDefault();
        window.CodeSage?.openCommandPalette?.();
      } else if (mod && e.key === "/") {
        e.preventDefault();
        this.toggleThinkingVisibility();
      } else if (e.key === "Escape" && this._isGenerating) {
        this._interrupt();
      }
    });
  }

  // ============ Welcome Actions ============

  _renderWelcomeActions() {
    const el = document.getElementById("welcome-actions");
    if (!el) return;
    const actions = [
      {
        icon: "fa-magnifying-glass",
        title: "解释代码",
        desc: "选中代码后让 AI 解释它做了什么",
        prompt: "@selection 请解释这段代码的逻辑",
      },
      {
        icon: "fa-bug",
        title: "调试 bug",
        desc: "把报错信息贴进来,让 AI 帮你定位",
        prompt: "我遇到了一个 bug,运行时报错:",
      },
      {
        icon: "fa-pen-to-square",
        title: "重构优化",
        desc: "让 AI 改进现有代码的结构和可读性",
        prompt: "请帮我重构以下代码,目标是更简洁可读:",
      },
      {
        icon: "fa-vial",
        title: "写单元测试",
        desc: "为现有函数生成测试用例",
        prompt: "请为以下代码生成单元测试:",
      },
    ];
    el.innerHTML = actions
      .map(
        (a) => `
                    <button class="welcome-action" data-cs-prompt="${escapeHtml(a.prompt)}">
                        <div class="welcome-action-title">
                            <i class="fas ${a.icon}"></i>
                            ${escapeHtml(a.title)}
                        </div>
                        <div class="welcome-action-desc">${escapeHtml(a.desc)}</div>
                    </button>
                `,
      )
      .join("");
    el.querySelectorAll(".welcome-action").forEach((btn) => {
      btn.addEventListener("click", () => {
        const prompt = btn.dataset.csPrompt || "";
        this.inputTextarea.value = prompt;
        this.inputTextarea.focus();
        this.inputTextarea.setSelectionRange(prompt.length, prompt.length);
        this._updateCharCount?.();
      });
    });
  }

  hideWelcome() {
    if (this.welcomeState) this.welcomeState.style.display = "none";
  }

  showWelcome() {
    if (this.welcomeState) this.welcomeState.style.display = "";
  }

  clear() {
    // 保留 welcome 区域
    const welcome = this.welcomeState;
    this.messagesInner.innerHTML = "";
    if (welcome) {
      this.messagesInner.appendChild(welcome);
      welcome.style.display = "";
    }
    this.turns.clear();
    this.toolCalls.clear();
    this.plans.clear();
    // O5.3: 工件面板已删除,无需清理 artifacts Map
    this._messageVirtualizer?.clear();
    this._contextChips?.clear();
    this.runLogBuilder = new RunLogBuilder();
    this._isGenerating = false;
    // 兼容调用方不传参(旧 loadHistory 路径):默认保留草稿,
    // 由 main.js 的 clear_chat 路由显式调 _resetInput() 完成"新会话 = 干净画布"。
  }

  /** 新会话时重置输入区:清空文字 / 附件 / 高度,关掉状态行。 */
  _resetInput() {
    if (this.inputTextarea) {
      this.inputTextarea.value = "";
      this.inputTextarea.style.height = "auto";
      this._updateCharCount?.();
    }
    this._inputAttachments = [];
    this._renderInputAttachments();
    this._contextChips?.clear();
    this._setStatus("就绪", "idle");
    this._swapSendButton(false);
  }

  // ============ User Message ============

  _computeStaggerDelay() {
    const count = this.messagesInner?.querySelectorAll(".message").length || 0;
    return `${Math.min(count * 30, 200)}ms`;
  }

  addUserMessage(text, attachments = [], fileRefs = []) {
    this.hideWelcome();
    const div = document.createElement("div");
    div.className = "message message-user";
    div.style.setProperty("--msg-stagger", this._computeStaggerDelay());
    const userName = state.get("userName") || "User";
    const userInitial = getUserInitial(userName);
    const userAvatarBg = stringToHslColor(userName);
    const refsHtml =
      fileRefs && fileRefs.length
        ? `<div class="file-refs">${fileRefs
            .map(
              (r) =>
                `<span class="file-ref"><i class="fas fa-file-code"></i> ${escapeHtml(r.name || r.path || "")}</span>`,
            )
            .join("")}</div>`
        : "";
    const imgs = (attachments || [])
      .filter((a) => a.type === "image")
      .map(
        (a) =>
          `<img class="attachment" src="${escapeHtml(a.data || a.url || "")}" alt="${escapeHtml(a.name || "")}" />`,
      )
      .join("");
    const attHtml = imgs ? `<div class="user-attachments">${imgs}</div>` : "";
    div.innerHTML = `
            <div class="message-row user">
                <div style="display:flex;flex-direction:column;align-items:flex-end;gap:4px;max-width:var(--user-bubble-max-width);">
                    ${refsHtml}
                    <div class="user-bubble">${escapeHtml(text)}</div>
                    ${attHtml}
                </div>
                <div class="avatar avatar-user" aria-hidden="true" style="--user-avatar-bg: ${escapeHtml(userAvatarBg)}">
                    ${escapeHtml(userInitial)}
                </div>
            </div>
        `;
    this.messagesInner.appendChild(div);
    this._messageVirtualizer?.add(div);

    // 等待首字节指示器:在 AI turn 开始前显示
    const indicator = document.createElement("div");
    indicator.className = "loading-indicator";
    indicator.dataset.csRole = "submitted-indicator";
    indicator.innerHTML = `<span class="loading-indicator-dot"></span><span class="loading-indicator-dot"></span><span class="loading-indicator-dot"></span>`;
    this.messagesInner.appendChild(indicator);
    this._pendingIndicator = indicator;
    this._messageVirtualizer?.add(indicator);

    this._maybeScrollToBottom(true);
  }

  // ============ AI Turn (核心:append-only 渲染) ============

  _startAITurn(turnId) {
    // v2.0 修复:turnId 由后端 Kotlin 在 start_turn 事件中携带过来,
    // 后续 text_delta / tool_call_* / thinking_* 等事件也都带这个 turnId。
    // 旧实现自己用 genId("turn") 生成 id,导致 this.turns Map 里的 key
    // 跟后端实际下发的 turnId 对不上,所有事件被丢弃 → 用户看不到回答。
    const resolvedTurnId = turnId || genId("turn");
    this.runLogBuilder.processEvent({
      type: "start_turn",
      turnId: resolvedTurnId,
    });
    this.hideWelcome();
    // 移除等待首字节指示器
    this._pendingIndicator?.remove();
    this._pendingIndicator = null;

    this._isGenerating = true;
    this._scrollLocked = false;
    const startTime = Date.now();

    const turn = {
      id: resolvedTurnId,
      startTime,
      el: null,
      body: null,
      content: null, // assistant-content 容器
      currentStreamSegment: null, // 当前 text stream span
      thinking: null,
      // O5.1: 多轮推理卡片分离 — 改为数组 + 单一当前引用
      modelReasonings: [],
      modelReasoning: null,
      modelReasoningRound: 0,
      plans: new Map(),
      toolCalls: new Map(),
      timerInterval: null,
    };

    // 容器结构:
    // <div.message.message-assistant>
    //   <div.message-row>
    //     <div.avatar.avatar-assistant />
    //     <div.assistant-body>
    //       <div.message-header />
    //       <div.assistant-content />  ← thinking/tool/text 全部 inline 插入
    //       <div.turn-actions />
    //     </div>
    //   </div>
    // </div>
    const el = document.createElement("div");
    el.className = "message message-assistant";
    el.id = resolvedTurnId;
    el.style.setProperty("--msg-stagger", this._computeStaggerDelay());
    el.innerHTML = `
            <div class="message-row">
                <div class="avatar avatar-assistant" aria-hidden="true">
                    ${icon("logo")}
                </div>
                <div class="assistant-body">
                    <div class="message-header">
                        <span class="message-author">CodeSage</span>
                        <span class="message-time" data-cs-role="timer">0.0s</span>
                        <span class="message-status" data-cs-role="status"></span>
                    </div>
                    <div class="assistant-content" data-cs-role="content">
                        <span class="stream-cursor" data-cs-role="cursor"></span>
                    </div>
                    <div class="turn-actions" data-cs-role="actions">
                        <button class="turn-action-btn" data-cs-action="copy" title="复制">
                            <i class="fas fa-copy"></i> 复制
                        </button>
                        <button class="turn-action-btn" data-cs-action="regenerate" title="重新生成">
                            <i class="fas fa-rotate"></i> 重新生成
                        </button>
                    </div>
                </div>
            </div>
        `;
    this.messagesInner.appendChild(el);
    this._messageVirtualizer?.add(el);
    this._messageVirtualizer?.pin(el); // 流式 turn 不回收
    turn.el = el;
    turn.body = el.querySelector(".assistant-body");
    turn.content = el.querySelector('[data-cs-role="content"]');
    // cursor 是 content 里的最后一个子节点(单独留作视觉光标,不参与流式文本)。
    // v2.0 修复:旧实现把 cursor 直接当作 currentStreamSegment,
    // 第一次 text_delta 写入 cursor.innerHTML,end_turn 时 cursor?.remove() 把流式
    // 文本一起删了 — 用户看到 AI 回答出现又立刻消失。改为 null 起步,
    // 让 _ensureStreamSegment 在第一次 text_delta 时创建一个新 span 插到 cursor 之前。
    const cursor = el.querySelector('[data-cs-role="cursor"]');
    turn.currentStreamSegment = null;

    // timer
    turn.timerInterval = setInterval(() => {
      const t = turn.el.querySelector('[data-cs-role="timer"]');
      if (t) t.textContent = ((Date.now() - startTime) / 1000).toFixed(1) + "s";
    }, 100);

    // 按钮
    el.querySelectorAll("[data-cs-action]").forEach((btn) => {
      btn.addEventListener("click", () => {
        const a = btn.dataset.csAction;
        if (a === "copy") this._copyTurn(turn);
        else if (a === "regenerate") {
          bridge.send({ type: "regenerate", turnId });
        }
      });
    });

    this.turns.set(resolvedTurnId, turn);
    this._agentDashboard?.setRunLog(
      this.runLogBuilder.getRunLog(resolvedTurnId),
    );
    this._agentDashboard?.start();
    this._setStatus("思考中…", "thinking");
    this._swapSendButton(true);
    this._maybeScrollToBottom(true);
  }

  _endAITurn(turnId) {
    this.runLogBuilder.processEvent({ type: "end_turn", turnId });
    const turn = this.turns.get(turnId);
    if (!turn) return;
    clearInterval(turn.timerInterval);
    this._isGenerating = false;
    this._currentStreamSegment = null;
    this._agentDashboard?.stop();
    this._agentDashboard?._render();
    this._setStatus("就绪", "idle");
    this._swapSendButton(false);
    // 移除光标
    const cursor = turn.el?.querySelector('[data-cs-role="cursor"]');
    cursor?.remove();
    // 2026-06: 代码块增强已改为流式事件驱动(CodeBlockCard 组件 + code_block_* 事件)
    // endAITurn 不再需要 enhanceCodeBlocks 后处理。
    // 显示 turn actions
    const actions = turn.el?.querySelector('[data-cs-role="actions"]');
    if (actions) actions.style.opacity = "";
  }

  _setStatus(text, kind = "idle") {
    if (!this.statusLine || !this.statusText) return;
    this.statusText.textContent = text;
    this.statusLine.className = "status-line " + kind;
    this.statusLine.hidden = kind === "idle";
  }

  _swapSendButton(isGenerating) {
    if (!this.sendBtn) return;
    this.sendBtn.classList.toggle("stop", isGenerating);
    const icon = this.sendBtn.querySelector("i");
    if (icon) {
      icon.className = isGenerating ? "fas fa-stop" : "fas fa-arrow-up";
    }
    this.sendBtn.setAttribute("aria-label", isGenerating ? "停止生成" : "发送");
  }

  /** append 一段新 stream text span(append-only,不再 textContent=重写) */
  _appendStreamSegment(turn) {
    if (!turn || !turn.content) return null;
    const span = document.createElement("span");
    span.className = "text-stream-segment";
    span.dataset.csRole = "stream";
    // 插到 cursor 之前 (cursor 永远在最后)
    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(span, cursor);
    } else {
      turn.content.appendChild(span);
      // 重新创建 cursor
      const c = document.createElement("span");
      c.className = "stream-cursor";
      c.dataset.csRole = "cursor";
      turn.content.appendChild(c);
    }
    turn.currentStreamSegment = span;
    return span;
  }

  _ensureStreamSegment(turn) {
    if (!turn.currentStreamSegment || !turn.currentStreamSegment.parentNode) {
      return this._appendStreamSegment(turn);
    }
    return turn.currentStreamSegment;
  }

  _copyTurn(turn) {
    const text = turn.el.querySelector(".assistant-content")?.innerText || "";
    navigator.clipboard
      ?.writeText(text)
      .then(() => _toast.success("已复制到剪贴板"))
      .catch(() => _toast.error("复制失败"));
  }

  // ============ 事件处理 ============

  _onTextDelta(turnId, delta) {
    this.runLogBuilder.processEvent({ type: "text_delta", turnId, delta });
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const seg = this._ensureStreamSegment(turn);
    // v2.1: append-only 渲染 — 不再每帧 innerHTML= 整段重渲染。
    // 思路:
    //   1. 把 rawText 按"空行"切块(== markdown 段落分隔)
    //   2. 已稳定的块:seg._csBlocks[i] 已存在且文本一致 → 复用 DOM,不重建
    //   3. 新出现的块:append 渲染结果
    //   4. 未完成的尾段(delta 还没把空行推过来):重新渲染
    // 效果:
    //   - O(当前段长度) 而不是 O(整段) — 流式长文不再 O(n^2)
    //   - 已稳定的段落 DOM 不重建 → 代码块复制按钮 / hljs 高亮 / 滚动
    //     位置都不会被 innerHTML 覆盖打飞
    //   - 跨 delta 的 markdown 状态(粗体闪烁)只发生在"尾段",不再
    //     影响整段历史
    const rawText = (seg.dataset.rawText || "") + delta;
    seg.dataset.rawText = rawText;
    this._renderStreamIncremental(seg, rawText);
    // 高亮代码块
    if (window.hljs) {
      seg.querySelectorAll("pre code").forEach((b) => {
        if (!b.dataset.csHighlighted) {
          try {
            window.hljs.highlightElement(b);
            b.dataset.csHighlighted = "1";
          } catch {}
        }
      });
    }
    // 不强制滚动,让 _maybeScrollToBottom 自己判断
    // 但仍要尊重"用户上滚"判断
  }

  /**
   * 把 rawText 增量渲染到 seg:
   *   - 复用 seg._csBlocks 里"已稳定且文本未变"的块
   *   - 新增的稳定块 append
   *   - 尾段(可能未闭合)重新渲染
   *
   * 稳定块的判定:rawText 按 \n\n+ 切块,凡是末尾非空且已经收到收尾空行
   * 的块视为"已稳定"。下一帧如果该块文本没变,就复用 DOM。
   */
  _renderStreamIncremental(seg, rawText) {
    const endsWithBreak = /(?:^|\n)\n\s*$/.test(rawText);
    const parts = rawText.split(/\n\n+/);
    // split 在末尾空行上会产生 "" 元素,过滤掉(它不是真正的段)
    let blocks = parts.filter((s) => s.length > 0);
    let tail = "";
    if (!endsWithBreak) {
      tail = blocks.pop() || "";
    }

    if (!seg._csBlocks) seg._csBlocks = [];

    for (let i = 0; i < blocks.length; i++) {
      const text = blocks[i];
      const prev = seg._csBlocks[i];
      if (prev && prev.text === text) continue;
      const html = renderMarkdown(text);
      const wrapper = document.createElement("div");
      wrapper.className = "cs-stream-block";
      wrapper.dataset.csBlockText = text;
      wrapper.innerHTML = html;
      seg.appendChild(wrapper);
      seg._csBlocks[i] = { text, el: wrapper };
    }
    while (seg._csBlocks.length > blocks.length) {
      const dead = seg._csBlocks.pop();
      dead?.el?.remove();
    }

    if (!seg._csTailEl) {
      seg._csTailEl = document.createElement("div");
      seg._csTailEl.className = "cs-stream-block cs-stream-tail";
      seg.appendChild(seg._csTailEl);
    }
    seg._csTailEl.dataset.csBlockText = tail;
    seg._csTailEl.innerHTML = tail ? renderMarkdown(tail) : "";
  }

  _onThinkingStart(turnId) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    this.runLogBuilder.processEvent({
      type: "thinking_start",
      turnId,
      thinkingId: "thinking-" + turnId,
    });
    // 2026-06: Thinking 事件是 Agent 框架状态消息,不再创建 UI 卡片,仅记录到 RunLog。
    // 真实推理内容由 _onModelReasoning* 系列方法渲染。
  }

  _onThinkingUpdate(turnId, message) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    this.runLogBuilder.processEvent({
      type: "thinking_update",
      turnId,
      thinkingId: "thinking-" + turnId,
      message,
    });
  }

  _onThinkingComplete(turnId, elapsedMs) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    this.runLogBuilder.processEvent({
      type: "thinking_complete",
      turnId,
      thinkingId: "thinking-" + turnId,
      elapsedMs,
    });
    // 2026-06: Thinking 不再渲染 UI 卡片,此处无需创建 stream segment。
  }

  _onModelReasoningStart(_turnId) {
    // 修正 2026-06:已废弃。卡片创建由 _onModelReasoningRoundStart 单独驱动。
    // 历史:此 handler 之前是"首条 ModelReasoning delta 重命名为 model_reasoning_start"路径,
    //     作用是兜底建卡。但与新 model_reasoning_round_start 共存时,会出现:
    //       1) round_start 建空卡 A
    //       2) start 路径又调 _onModelReasoningRoundStart(round=N+1),看到 A 非空,完成 A 推到数组
    //       3) 后续 delta 进入 round N+1 的新卡
    //       4) 留下空"已思考 0.0s"卡 A(用户报告的 bug)
    // 后端已停止重命名 type,主.js 也不再路由 model_reasoning_start。
    // 此处保留空方法仅为防御:若旧后端意外发来,不创建新卡片(让 round_start 负责)。
  }

  _onModelReasoningRoundStart(turnId, roundIndex) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    this.runLogBuilder.processEvent({
      type: "thinking_start",
      turnId,
      thinkingId: "reasoning-" + turnId + "-" + roundIndex,
    });
    // 若已有当前活跃推理卡片,先归档(以防上游漏发 complete)
    // 后端(EnhancedAgentLoop)保证每段 reasoning 都配对 RoundStart / RoundEnd,
    // 不会出现空卡片;此处直接 complete 归档即可
    if (turn.modelReasoning) {
      turn.modelReasoning.complete(0);
      turn.modelReasonings.push(turn.modelReasoning);
      turn.modelReasoning = null;
    }
    turn.modelReasoningRound = roundIndex;
    const card = new StructuredThinking({});
    card.el.classList.add("model-reasoning");
    turn.modelReasoning = card;
    // 插入到 content 内、cursor 之前
    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(card.el, cursor);
    } else {
      turn.content.appendChild(card.el);
    }
    // 推进 stream anchor 到 modelReasoning 之后
    turn.currentStreamSegment = null;
  }

  _onModelReasoningDelta(turnId, delta) {
    const turn = this.turns.get(turnId);
    if (!turn?.modelReasoning) return;
    this.runLogBuilder.processEvent({
      type: "thinking_update",
      turnId,
      thinkingId: "reasoning-" + turnId,
      message: delta,
    });
    turn.modelReasoning.appendContent(delta);
  }

  _onModelReasoningComplete(turnId, elapsedMs) {
    const turn = this.turns.get(turnId);
    if (!turn?.modelReasoning) return;
    this.runLogBuilder.processEvent({
      type: "thinking_complete",
      turnId,
      thinkingId: "reasoning-" + turnId + "-" + (turn.modelReasoningRound || 0),
      elapsedMs,
    });
    // 后端确保每段 reasoning 在内容边界触发 RoundEnd(对应 model_reasoning_round_end →
    // 本 handler),卡片此时一定有内容;不再需要 isEmpty() 兜底
    turn.modelReasoning.complete(elapsedMs);
    // O5.1: 归档当前卡片,下一轮 RoundStart 时会创建新卡片
    turn.modelReasonings.push(turn.modelReasoning);
    turn.modelReasoning = null;
    // 推理完成后,创建新的 stream segment(在 modelReasoning 之后)
    turn.currentStreamSegment = null;
    this._ensureStreamSegment(turn);
  }

  // ===== 2026-06: 代码块事件 handler =====
  // 由 main.js 路由 code_block_start/delta/end 消息过来。
  // CodeBlockCard 是自管组件,直接挂在 turn.content。
  _onCodeBlockStart(turnId, codeBlockId, language, filePath) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    this.runLogBuilder.processEvent({
      type: "code_block_start",
      turnId,
      codeBlockId,
      language,
      filePath,
    });
    // 结束当前 stream segment,让代码块独立显示
    turn.currentStreamSegment = null;
    const card = new CodeBlockCard({ codeBlockId, language, filePath });
    turn.codeBlocks = turn.codeBlocks || new Map();
    turn.codeBlocks.set(codeBlockId, card);
    // 插入到 content 内、cursor 之前
    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(card.el, cursor);
    } else {
      turn.content.appendChild(card.el);
    }
  }

  _onCodeBlockDelta(turnId, codeBlockId, delta) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const card = turn.codeBlocks?.get(codeBlockId);
    if (!card) return;
    this.runLogBuilder.processEvent({
      type: "code_block_delta",
      turnId,
      codeBlockId,
      delta,
    });
    card.appendContent(delta);
  }

  _onCodeBlockEnd(turnId, codeBlockId, filePath) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const card = turn.codeBlocks?.get(codeBlockId);
    if (!card) return;
    this.runLogBuilder.processEvent({
      type: "code_block_end",
      turnId,
      codeBlockId,
      filePath,
    });
    if (filePath) card.filePath = filePath;
    card.complete();
  }

  _onToolCallStart(turnId, toolId, toolName, summary, args, icon) {
    this.runLogBuilder.processEvent({
      type: "tool_call_start",
      turnId,
      toolId,
      toolName,
      summary,
      arguments: args,
      icon,
    });
    const turn = this.turns.get(turnId);
    if (!turn) return;
    // delegate_task 由 subagent 卡片展示
    if (toolName === "delegate_task") {
      this.toolCalls.set(toolId, {
        toolCallId: toolId,
        name: toolName,
        turnId,
        hidden: true,
      });
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
    // 关键修复:inline 插入到 cursor 之前(产生它的那句话之后),
    // 而非堆到回答末尾
    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(tc.el, cursor);
    } else {
      turn.content.appendChild(tc.el);
    }
    // 推进 stream anchor 到 tool card 之后
    turn.currentStreamSegment = null;
    this._ensureStreamSegment(turn);
    this.toolCalls.set(toolId, tc);
    this._maybeScrollToBottom(true);
  }

  _onToolCallDelta(turnId, toolId, delta) {
    const tc = this.toolCalls.get(toolId);
    if (!tc || tc.hidden) return;
    tc.appendDelta(delta);
  }

  _onCommandOutputDelta(turnId, msg) {
    const tc = this.toolCalls.get(msg.toolId);
    if (!tc || tc.hidden) return;
    tc.appendCommandOutput(msg);
    this._maybeScrollToBottom(true);
  }

  _onToolCallComplete(turnId, toolId, success, result) {
    this.runLogBuilder.processEvent({
      type: "tool_call_complete",
      turnId,
      toolId,
      success,
      result,
    });
    const tc = this.toolCalls.get(toolId);
    if (!tc) return;
    if (tc.hidden) {
      if (!success) {
        const errMsg =
          (typeof result === "string" ? result : JSON.stringify(result)) ||
          "未知错误";
        _toast.error(`delegate_task 失败: ${errMsg}`);
      }
      this.toolCalls.delete(toolId);
      return;
    }
    tc.complete(success, result);
  }

  _onToolCallError(turnId, toolId, error) {
    this.runLogBuilder.processEvent({
      type: "tool_call_error",
      turnId,
      toolId,
      error,
    });
    const tc = this.toolCalls.get(toolId);
    if (!tc) return;
    if (tc.hidden) {
      _toast.error(`delegate_task 错误: ${error}`);
      this.toolCalls.delete(toolId);
      return;
    }
    const errMsg =
      typeof error === "string"
        ? error
        : error?.message || JSON.stringify(error) || "未知错误";
    tc.fail(errMsg);
  }

  _onPlanGenerated(turnId, data) {
    this.runLogBuilder.processEvent({
      type: "plan_generated",
      turnId,
      planId: data.planId,
      description: data.description,
      steps: data.steps,
    });
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const plan = new PlanV2({
      planId: data.planId,
      description: data.description,
      steps: data.steps,
      onApprove: (p) =>
        bridge.send({ type: "plan_approve", planId: p.planId, turnId }),
      onReject: (p) =>
        bridge.send({ type: "plan_reject", planId: p.planId, turnId }),
      onModify: (p, steps) =>
        bridge.send({ type: "plan_modify", planId: p.planId, turnId, steps }),
    });
    // 插入到 content 内、cursor 之前
    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(plan.el, cursor);
    } else {
      turn.content.appendChild(plan.el);
    }
    this.plans.set(data.planId, plan);
    turn.currentStreamSegment = null;
    this._ensureStreamSegment(turn);
    this._maybeScrollToBottom(true);
  }

  _onPlanApproved(turnId, data) {
    this.runLogBuilder.processEvent({
      type: "plan_approved",
      turnId,
      planId: data.planId,
    });
    this.plans.get(data.planId)?.setOverallStatus("approved");
  }
  _onPlanRejected(turnId, data) {
    this.runLogBuilder.processEvent({
      type: "plan_rejected",
      turnId,
      planId: data.planId,
    });
    this.plans.get(data.planId)?.setOverallStatus("rejected");
  }
  _onPlanModified(turnId, data) {
    this.runLogBuilder.processEvent({
      type: "plan_modified",
      turnId,
      planId: data.planId,
      steps: data.steps,
    });
    const p = this.plans.get(data.planId);
    if (p && p.updateSteps) {
      p.updateSteps(data.steps);
    }
  }

  _onContextCompressed(turnId, data) {
    const turn = this.turns.get(turnId);
    if (!turn) return;
    const note = document.createElement("div");
    note.className = "context-compress-note";
    note.innerHTML = `<i class="fas fa-compress"></i> 上下文已压缩 ${data.originalTokens} → ${data.compressedTokens} tokens (${escapeHtml(data.strategy)})`;
    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(note, cursor);
    } else {
      turn.content.appendChild(note);
    }
  }

  _onToolConfirmationNeeded(turnId, data) {
    const turn = this.turns.get(turnId);
    if (!turn) return;

    // 后端可能用旧 type 推 (tool_confirmation_needed) 也可能用新 type (tool_confirmation_request)。
    // 字段命名保持一致(requestId/toolName/operation/reason/riskLevel)。
    const requestId = data.requestId || data.toolCallId || data.toolId;
    if (!requestId) {
      console.warn("[chat] tool confirmation missing requestId", data);
      return;
    }
    const reason = data.reason || "需要确认";
    const toolName = data.toolName || data.toolId;
    const operation = data.operation || toolName;
    const riskLevel = (data.riskLevel || "CAUTION").toUpperCase();

    // 同一 requestId 重复推送时,不要堆叠多个弹窗 — 直接禁用旧按钮组
    const existing = turn.content.querySelector(
      `[data-cs-confirm-id="${requestId}"]`,
    );
    if (existing) {
      existing.querySelectorAll("button").forEach((b) => (b.disabled = true));
      return;
    }

    // 2026-06:根据 riskLevel 切换弹框视觉强度
    //   DANGEROUS → 深红 + 骷髅图标 + 4 按钮 (含"永久允许")
    //   CAUTION   → 黄色警告 + 三角图标 + 3 按钮
    //   SAFE/其它 → 蓝色提示
    const isDangerous = riskLevel === "DANGEROUS";
    const isCaution = riskLevel === "CAUTION";
    const cardVariant = isDangerous
      ? "dangerous"
      : isCaution
        ? "warning"
        : "info";
    const cardIcon = isDangerous
      ? "fa-skull-crossbones"
      : isCaution
        ? "fa-exclamation-triangle"
        : "fa-info-circle";
    const cardTitle = isDangerous
      ? `危险操作需要确认: ${escapeHtml(toolName)}`
      : `需要确认: ${escapeHtml(toolName)}`;
    // DANGEROUS 弹框额外多一个"永久允许"按钮,其余只展示三个
    const permanentButton = isDangerous
      ? `<button type="button" class="cs-btn cs-btn-ghost" data-cs-perm="ALLOW_PERMANENTLY" title="写入全局允许列表,以后该类工具不再询问">永久允许</button>`
      : "";

    const card = document.createElement("div");
    card.className = `inline-alert ${cardVariant}`;
    card.setAttribute("role", "alert");
    card.setAttribute("data-cs-confirm-id", requestId);
    card.setAttribute("data-cs-risk", riskLevel);
    card.innerHTML = `
            <div class="inline-alert-icon"><i class="fas ${cardIcon}"></i></div>
            <div class="inline-alert-body">
                <div class="inline-alert-title">${cardTitle}</div>
                <div class="inline-alert-message">${escapeHtml(reason)}</div>
                <div class="inline-alert-meta">操作: ${escapeHtml(operation)} · 风险: ${escapeHtml(riskLevel)}</div>
                <div class="inline-alert-actions">
                    <button type="button" class="cs-btn cs-btn-ghost" data-cs-perm="DENY">拒绝</button>
                    <button type="button" class="cs-btn cs-btn-ghost" data-cs-perm="ALLOW_ONCE">仅本次允许</button>
                    <button type="button" class="cs-btn cs-btn-primary" data-cs-perm="ALLOW_SESSION">本次会话允许</button>
                    ${permanentButton}
                </div>
            </div>
        `;

    const respond = (permission) => {
      if (typeof window.sendMessageToJava !== "function") {
        console.warn("[chat] bridge not ready, cannot respond");
        return;
      }
      window.sendMessageToJava(
        JSON.stringify({
          type: "tool_confirmation_response",
          requestId,
          permission,
        }),
      );
      // 视觉反馈: 标记已决, 禁用按钮
      card.classList.add("resolved");
      card.setAttribute("data-cs-resolved", permission);
      card.querySelectorAll("button").forEach((b) => (b.disabled = true));
    };

    card.querySelectorAll("button[data-cs-perm]").forEach((btn) => {
      btn.addEventListener("click", () => respond(btn.dataset.csPerm));
    });

    const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
    if (cursor) {
      turn.content.insertBefore(card, cursor);
    } else {
      turn.content.appendChild(card);
    }
  }

  _onError(turnId, message) {
    this.runLogBuilder.processEvent({ type: "error", turnId, message });
    this._isGenerating = false;
    this._currentStreamSegment = null;
    // 清理可能存在的等待首字节指示器
    this._pendingIndicator?.remove();
    this._pendingIndicator = null;
    const turn = this.turns.get(turnId);
    if (turn) {
      clearInterval(turn.timerInterval);
      // 修复 v1: 不再 innerHTML="" 清空,而是 append error alert
      const alert = new InlineAlert({
        variant: "error",
        title: "出错了",
        message,
      });
      const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
      if (cursor) {
        turn.content.insertBefore(alert.el, cursor);
      } else {
        turn.content.appendChild(alert.el);
      }
    }
    this._setStatus("错误", "error");
    this._swapSendButton(false);
  }

  _onSessionMigrated(data) {
    const note = document.createElement("div");
    note.className = "context-compress-note";
    note.style.cssText =
      "display:flex;justify-content:center;margin:var(--space-3) 0;";
    note.innerHTML = `<i class="fas fa-arrow-right-arrow-left"></i> 会话已迁移: ${data.messageCount} 条消息`;
    this.messagesInner.appendChild(note);
  }

  _onModeSuggestion(turnId, data) {
    // 后端建议的 mode 切到对应 tab
    const m = (data.suggestion || "").toLowerCase();
    if (["chat", "agent", "plan"].includes(m)) {
      this._setChatMode(m, true);
      if (!data.userExplicit) {
        _toast.info(`已自动切换到 ${m} 模式`);
      }
    }
  }

  // ============ Artifacts ============

  // O5.3: 工件面板已删除,addArtifact / updateArtifact 不再创建 UI。
  // 但 apply_artifact / reject_artifact bridge 协议保留 — T6 会在
  // 代码块操作栏接入这两个 type,后端 JCEFChatPanel 解析逻辑不变。
  addArtifact(_id, _title, _language, _content, _options = {}) {
    /* no-op: 工件面板 UI 已删除,代码块操作栏 (T6) 承接此能力 */
  }

  updateArtifact(_id, _patch) {
    /* no-op: 工件面板 UI 已删除 */
  }

  /**
   * O5.3 / T6 占位:从代码块操作栏触发的应用/拒绝操作走这里发出
   * bridge 消息,字段名与 JCEFChatPanel.apply_artifact / reject_artifact
   * 期望严格一致(见 JStoKotlinContractTest)。
   *
   * @param action "apply" | "reject"
   */
  emitArtifactAction(action, payload) {
    if (action === "apply") {
      bridge.send({
        type: "apply_artifact",
        artifactId: payload.artifactId,
        content: payload.content,
        version: payload.version,
      });
    } else if (action === "reject") {
      bridge.send({
        type: "reject_artifact",
        artifactId: payload.artifactId,
        content: payload.content,
        version: payload.version,
      });
    }
  }

  // ============ History / Sessions ============

  loadHistory(messages) {
    this.clear();
    for (const m of messages || []) {
      if (m.role === "user") {
        this.addUserMessage(m.content, m.images || [], m.fileRefs || []);
      } else if (m.role === "assistant") {
        if (m.thinking) {
          // 2026-06: Thinking 事件不再创建 UI 卡片,仅记录到 RunLog。
          this._startAITurn();
          const turn = Array.from(this.turns.values()).pop();
          if (turn) {
            this._onThinkingStart(turn.id);
            this.runLogBuilder.processEvent({
              type: "thinking_update",
              turnId: turn.id,
              thinkingId: "thinking-" + turn.id,
              message: m.thinking,
            });
            this._onThinkingComplete(turn.id, m.thinkingDurationMs || 0);
          }
        }
        if (m.content) {
          const turn = Array.from(this.turns.values()).pop();
          if (turn) {
            this._onTextDelta(turn.id, m.content);
          }
        }
        const turn = Array.from(this.turns.values()).pop();
        if (turn) {
          // 处理工具调用
          for (const tc of m.toolCalls || []) {
            this._onToolCallStart(
              turn.id,
              tc.id,
              tc.name,
              tc.summary,
              tc.arguments,
              tc.icon,
            );
            this._onToolCallComplete(
              turn.id,
              tc.id,
              tc.success !== false,
              tc.result,
            );
          }
          this._endAITurn(turn.id);
        }
      }
    }
    this._messageVirtualizer?.finishBatch();
    if (this.messagesInner.children.length === 0) {
      this.showWelcome();
    }
  }

  notifySessionSwitched(sessionId) {
    this.setCurrentSession(sessionId);
  }

  notifySessionDeleted(sessionId) {
    // 简单刷新 sidebar
  }

  notifySessionRenamed(sessionId, name) {
    // 由 sendSessions 重新渲染时带新 name
  }

  // ============ Session 操作(转 bridge) ============

  _switchSession(id) {
    bridge.send({ type: "switch_session", sessionId: id });
  }
  _renameSession(id) {
    const name = prompt("新名称:");
    if (name && name.trim()) {
      bridge.send({ type: "rename_session", sessionId: id, name: name.trim() });
    }
  }
  _deleteSession(id) {
    if (confirm("确定删除此会话?此操作不可恢复。")) {
      bridge.send({ type: "delete_session", sessionId: id });
    }
  }
}

export const chat = new ChatView();
window.CodeSage = window.CodeSage || {};
window.CodeSage.chat = chat;

// ===================== 外部链接拦截 =====================
//
// 用户反馈: 在对话界面点 markdown 里的链接,会在 JCEF webview 内**替换**当前页,
// 直接显示被点击的网页,无法返回对话 — 整个聊天状态丢失。
//
// 修法: 全局 click 捕获阶段拦截,凡是 href 是外部 URL (http/https/mailto/file)
// 的 <a> 都 preventDefault 掉,改成走 bridge.send 让 Kotlin 侧用 BrowserUtil
// 打开系统默认浏览器。In-page 锚点 (#fragment) 和 <button> 不动。
//
// 不做 JCEF 内嵌网页查看:1) 跟 JCEF 单页架构冲突,2) IDE 文档类链接习惯走外
// 部浏览器(Cursor / Continue 等都是这样),3) 用户体验更稳。
//
// 放在 chat.js 底部而非 main.js: e2e 测试只 import chat.js 不 import main.js,
// 放这里能保证测试场景也走到。真实运行时 main.js 会 import chat.js,IIFE
// 照样执行。重复注册用 guard 兜底。
if (!window.__codesage_external_link_interceptor_installed__) {
  window.__codesage_external_link_interceptor_installed__ = true;
  // 这些是允许的"外部"scheme,会被拦截并通过 bridge 发给 Kotlin。
  // 其它 (javascript: / data: / vbscript: 等) 直接忽略,让默认行为生效 ——
  // 通常就是什么都不做,但起码不会被我们主动放行。
  const EXTERNAL_SCHEMES = new Set(["http:", "https:", "mailto:", "file:"]);

  document.addEventListener(
    "click",
    (e) => {
      // closest('a') 处理 <a><span>x</span></a> 这种带子元素的情况
      const a = e.target && e.target.closest && e.target.closest("a");
      if (!a) return;
      const href = a.getAttribute("href");
      if (!href) return;

      // 内部锚点 (#xxx) 放过,留给浏览器默认处理(滚动到对应位置)
      if (href.startsWith("#")) return;

      // 协议未知 / 解析抛:放行默认(不进白名单就不会被我们主动放行)
      let scheme = "";
      try {
        scheme = new URL(href, window.location.href).protocol.toLowerCase();
      } catch (_) {
        return;
      }
      if (!EXTERNAL_SCHEMES.has(scheme)) return;

      // 拦截 + 转交 Kotlin
      e.preventDefault();
      e.stopPropagation();
      bridge.send({ type: "open_external_url", url: href });
    },
    true, // capture: 在 bubble 阶段的任何 onclick 之前先抓
  );
}
