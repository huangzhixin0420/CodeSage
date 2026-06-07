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

import { Thinking } from "../components/cs-thinking.js";
import { ToolCall } from "../components/cs-tool-call.js";
import { Plan } from "../components/cs-plan.js";
import { Sidebar } from "../components/cs-sidebar.js";
import { InlineAlert } from "../components/cs-inline-alert.js";
import { toast } from "../components/cs-toast.js";
import { bridge } from "../bridge.js";
import { state } from "../state.js";
import { renderMarkdown, preloadMarkdown } from "../markdown.js";
import {
    escapeHtml,
    scrollToBottom,
    debounce,
    throttle,
    genId,
    formatDuration,
} from "../utils.js";

// ===== 简易 toast 兜底(若 cs-toast 没暴露) =====
const _toast = window.CodeSage?.toast || toast || {
    error: console.error,
    success: console.log,
    info: console.log,
    warn: console.warn,
};

// ===================== ChatView =====================

class ChatView {
    constructor() {
        this.turns = new Map();          // turnId -> Turn
        this.toolCalls = new Map();      // toolId -> ToolCall
        this.plans = new Map();          // planId -> Plan
        this._isGenerating = false;
        this._currentStreamSegment = null;  // 当前 turn 内最后一段 text stream
        this._sidebar = null;
        this._scrollLocked = false;      // 用户手动上滚后锁定自动滚
        this._inputAttachments = [];
        this._chatMode = null;           // null = 后端建议
        this._commandPalette = null;
    }

    init() {
        try {
            this.messagesContainer = document.getElementById("messages-container");
            this.messagesInner = document.getElementById("messages-inner");
            this.welcomeState = document.getElementById("welcome-state");
            this.inputTextarea = document.getElementById("input-textarea");
            this.sendBtn = document.getElementById("send-btn");
            this.statusLine = document.getElementById("status-line");
            this.statusText = document.getElementById("status-text");
            this.inputAttachmentsEl = document.getElementById("input-attachments");
            this.hintModel = document.getElementById("hint-model");
            this.sessionTitleEl = document.getElementById("session-title");
            this.appContainer = document.getElementById("app-container");

            this._initSidebar();
            this._initHeader();
            this._initInput();
            this._initScrollWatcher();
            this._initKeyboard();
            this._initModelSelector();
            this._initModeTabs();

            // 渲染欢迎区
            this._renderWelcomeActions();

            console.info("[chat] initialized v2.0");
        } catch (e) {
            console.error("[chat] init failed:", e);
        }
    }

    // ============ Sidebar ============

    _initSidebar() {
        this._sidebar = new Sidebar({
            container: this.appContainer,
            onNew: () => this.onNewSession(),
            onSelect: (id) => this._switchSession(id),
            onRename: (id) => this._renameSession(id),
            onDelete: (id) => this._deleteSession(id),
        });
        // 默认折叠
        this.appContainer.classList.add("sidebar-collapsed");
        this._sidebar.setCollapsed(true);
        // v2.1: 与 sidebar 一致 — 首次安装默认折叠工件面板。
        // 之前 CSS 默认 grid 是 260 1fr 360,会吃走 360px 屏幕宽度,而工
        // 件后端目前没有调用点(addArtifact 在仓库中未被使用),空面板白
        // 占地方。addArtifact() 会按需自动展开,无需手展开。
        this.appContainer.classList.add("artifacts-collapsed");
    }

    toggleSidebar() {
        const isCollapsed = this.appContainer.classList.toggle("sidebar-collapsed");
        this._sidebar?.setCollapsed(isCollapsed);
    }

    setSessions(sessions) {
        this._sidebar?.setSessions(sessions);
    }

    setCurrentSession(id, name) {
        this._sidebar?.setCurrent(id);
        if (this.sessionTitleEl) {
            this.sessionTitleEl.textContent = name || "";
            this.sessionTitleEl.title = name || "";
        }
    }

    // ============ Header ============

    _initHeader() {
        document
            .getElementById("sidebar-toggle-btn")
            ?.addEventListener("click", () => this.toggleSidebar());
        document
            .getElementById("theme-toggle-btn")
            ?.addEventListener("click", () => this.toggleTheme());
        document
            .getElementById("thinking-toggle-btn")
            ?.addEventListener("click", () => this.toggleThinkingVisibility());
        document
            .getElementById("artifacts-toggle-btn")
            ?.addEventListener("click", () => this.toggleArtifacts());
        // 修复 v2.1:旧实现只给主区头部的 toggle 按钮绑了 click,
        // 工件面板右上角的 X 按钮 (id=artifacts-close-btn) 完全没人监听 — 点 X 无反应。
        // 这里给它挂同一个 toggle 行为,与 toggleArtifacts() 同源,状态保持一致。
        document
            .getElementById("new-session-btn")
            ?.addEventListener("click", () => this.onNewSession());
        document
            .getElementById("settings-btn")
            ?.addEventListener("click", () => this.showSettings());
        document
            .getElementById("artifacts-close-btn")
            ?.addEventListener("click", () => this.toggleArtifacts());
    }

    toggleTheme() {
        const cur = document.documentElement.getAttribute("data-theme");
        const next = cur === "dark" ? "light" : "dark";
        document.documentElement.setAttribute("data-theme", next);
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

    toggleArtifacts() {
        this.appContainer.classList.toggle("artifacts-collapsed");
    }

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
        const html = [`
            <div class="model-search">
                <i class="fas fa-magnifying-glass"></i>
                <input type="text" placeholder="搜索模型…" data-cs-role="search" />
            </div>
        `];
        for (const g of groups || []) {
            html.push(`
                <div class="model-group">
                    <div class="model-group-label">
                        <span class="model-group-dot enabled"></span>
                        ${escapeHtml(g.provider)}
                    </div>
                    ${(g.models || []).map((m) => `
                        <div class="model-option" data-cs-model="${escapeHtml(m)}" data-cs-provider="${escapeHtml(g.provider)}" role="option" tabindex="0">
                            <span class="check"><i class="fas fa-check"></i></span>
                            <span class="model-option-name">${escapeHtml(m)}</span>
                        </div>
                    `).join("")}
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
        if (name) name.textContent = model || "—";
        if (this.hintModel) this.hintModel.textContent = provider ? `${provider} · ${model}` : model || "";

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
        ta.addEventListener("input", autoResize);

        // 提交
        const submit = () => {
            const v = ta.value;
            if (!v.trim() && this._inputAttachments.length === 0) return;
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
        this.inputTextarea.value = "";
        this.inputTextarea.style.height = "auto";
        // attachments 通过 bridge 传给后端,不在前端渲染
        bridge.send({
            type: "send_message",
            message: v,
            images: this._inputAttachments.map((a) => a.data),
            userLanguage: "zh-CN",
        });
        this._inputAttachments = [];
        this._renderInputAttachments();
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
        const images = this._inputAttachments.filter((a) => a && a.type === "image" && a.data);
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
    // Kotlin 在文件选择器选完文件后推 file_references_added,我们在 textarea
    // 光标处插入 `@relativePath `,用户继续输入问题,发送时
    // FileReferenceResolver.resolveReferences() 会读这些文件内容并注入上下文。
    //
    // 设计要点:
    //   - 在光标处插入(不是全选覆盖),不打断用户已输入的内容
    //   - 多个文件用空格分隔,统一加一个尾随空格,用户可直接继续打字
    //   - 插入后聚焦 textarea 并把光标放到插入文本末尾
    //   - 触发一次 autoResize,避免输入框高度不刷新
    //   - toast 反馈:点击反馈立刻可见,免得"没反应"
    _onFileReferencesAdded(refs) {
        if (!refs || refs.length === 0) return;
        // 清掉 _requestAttach 的 8s 超时,避免误报"选择器未响应"
        this._clearPendingAttach();
        const ta = this.inputTextarea;
        if (!ta) return;

        const mentions = refs
            .map((r) => `@${r.relativePath || r.name || r.path || ""}`)
            .filter((s) => s.length > 1) // 跳过空名
            .join(" ");
        if (!mentions) return;
        const insertion = mentions + " ";

        // 在 selectionStart / selectionEnd 处插入(光标或选区都支持)
        const start = ta.selectionStart ?? ta.value.length;
        const end = ta.selectionEnd ?? ta.value.length;
        const before = ta.value.slice(0, start);
        const after = ta.value.slice(end);
        ta.value = before + insertion + after;

        // 光标放到插入文本末尾
        const cursor = start + insertion.length;
        ta.setSelectionRange(cursor, cursor);
        ta.focus();

        // autoResize 同步,否则高度还停在插入前
        ta.style.height = "auto";
        ta.style.height = Math.min(ta.scrollHeight, 240) + "px";

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
                        <div class="input-attachment${isTooLarge ? " too-large" : ""}" title="${escapeHtml(a.name || "image")}${sizeLabel}">
                            <img class="input-attachment-thumb" src="${escapeHtml(a.data)}" alt="" />
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
            } else if (mod && e.key === "b") {
                e.preventDefault();
                this.toggleSidebar();
            } else if (mod && e.key === "i") {
                e.preventDefault();
                this.toggleArtifacts();
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
        this._isGenerating = false;
        // 兼容调用方不传参(旧 loadHistory 路径):默认保留草稿,
        // 由 main.js 的 clear_chat 路由显式调 _resetInput() 完成"新会话 = 干净画布"。
    }

    /** 新会话时重置输入区:清空文字 / 附件 / 高度,关掉状态行。 */
    _resetInput() {
        if (this.inputTextarea) {
            this.inputTextarea.value = "";
            this.inputTextarea.style.height = "auto";
        }
        this._inputAttachments = [];
        this._renderInputAttachments();
        this._setStatus("就绪", "idle");
        this._swapSendButton(false);
    }

    // ============ User Message ============

    addUserMessage(text, attachments = [], fileRefs = []) {
        this.hideWelcome();
        const div = document.createElement("div");
        div.className = "message message-user";
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
        const attHtml = imgs
            ? `<div class="user-attachments">${imgs}</div>`
            : "";
        div.innerHTML = `
            <div class="message-row user">
                <div style="display:flex;flex-direction:column;align-items:flex-end;gap:4px;max-width:85%;">
                    ${refsHtml}
                    <div class="user-bubble">${escapeHtml(text)}</div>
                    ${attHtml}
                </div>
                <div class="avatar avatar-user" aria-hidden="true">
                    <i class="fas fa-user"></i>
                </div>
            </div>
        `;
        this.messagesInner.appendChild(div);
        this._maybeScrollToBottom(true);
    }

    // ============ AI Turn (核心:append-only 渲染) ============

    _startAITurn(turnId) {
        // v2.0 修复:turnId 由后端 Kotlin 在 start_turn 事件中携带过来,
        // 后续 text_delta / tool_call_* / thinking_* 等事件也都带这个 turnId。
        // 旧实现自己用 genId("turn") 生成 id,导致 this.turns Map 里的 key
        // 跟后端实际下发的 turnId 对不上,所有事件被丢弃 → 用户看不到回答。
        const resolvedTurnId = turnId || genId("turn");
        this.hideWelcome();
        this._isGenerating = true;
        this._scrollLocked = false;
        const startTime = Date.now();

        const turn = {
            id: resolvedTurnId,
            startTime,
            el: null,
            body: null,
            content: null,            // assistant-content 容器
            currentStreamSegment: null, // 当前 text stream span
            thinking: null,
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
        el.innerHTML = `
            <div class="message-row">
                <div class="avatar avatar-assistant" aria-hidden="true">
                    <i class="fas fa-leaf"></i>
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
            if (t)
                t.textContent =
                    ((Date.now() - startTime) / 1000).toFixed(1) + "s";
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
        this._setStatus("思考中…", "thinking");
        this._swapSendButton(true);
        this._maybeScrollToBottom(true);
    }

    _endAITurn(turnId) {
        const turn = this.turns.get(turnId);
        if (!turn) return;
        clearInterval(turn.timerInterval);
        this._isGenerating = false;
        this._currentStreamSegment = null;
        this._setStatus("就绪", "idle");
        this._swapSendButton(false);
        // 移除光标
        const cursor = turn.el?.querySelector('[data-cs-role="cursor"]');
        cursor?.remove();
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
            icon.className = isGenerating
                ? "fas fa-stop"
                : "fas fa-arrow-up";
        }
        this.sendBtn.setAttribute(
            "aria-label",
            isGenerating ? "停止生成" : "发送",
        );
    }

    /** append 一段新 stream text span(append-only,不再 textContent=重写) */
    _appendStreamSegment(turn) {
        if (!turn || !turn.content) return null;
        const span = document.createElement("span");
        span.className = "text-stream-segment";
        span.dataset.csRole = "stream";
        // 插到 cursor 之前 (cursor 永远在最后)
        const cursor = turn.content.querySelector(
            '[data-cs-role="cursor"]',
        );
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
        const turn = this.turns.get(turnId);
        if (!turn) return;
        const seg = this._ensureStreamSegment(turn);
        // append-only: 拼接 + 渲染 markdown (轻量)
        seg.dataset.rawText = (seg.dataset.rawText || "") + delta;
        // 渲染:用 marked 渲染 markdown 到 seg.innerHTML
        const text = seg.dataset.rawText;
        seg.innerHTML = renderMarkdown(text);
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

    _onThinkingStart(turnId) {
        const turn = this.turns.get(turnId);
        if (!turn) return;
        if (!turn.thinking) {
            turn.thinking = new Thinking({ collapsed: false });
            // 插入到 content 内、cursor 之前
            const cursor = turn.content.querySelector(
                '[data-cs-role="cursor"]',
            );
            if (cursor) {
                turn.content.insertBefore(turn.thinking.el, cursor);
            } else {
                turn.content.appendChild(turn.thinking.el);
            }
            // 推进 stream anchor 到 thinking 之后(后续 text 在 thinking 之后追加)
            turn.currentStreamSegment = null;
        }
    }

    _onThinkingUpdate(turnId, message) {
        const turn = this.turns.get(turnId);
        if (!turn?.thinking) return;
        turn.thinking.appendContent(message);
    }

    _onThinkingComplete(turnId, elapsedMs) {
        const turn = this.turns.get(turnId);
        if (!turn?.thinking) return;
        turn.thinking.complete(elapsedMs);
        // 思考完成后,创建新的 stream segment(在 thinking 之后)
        turn.currentStreamSegment = null;
        this._ensureStreamSegment(turn);
    }

    _onToolCallStart(turnId, toolId, toolName, summary, args, icon) {
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
        const cursor = turn.content.querySelector(
            '[data-cs-role="cursor"]',
        );
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

    _onToolCallComplete(turnId, toolId, success, result) {
        const tc = this.toolCalls.get(toolId);
        if (!tc) return;
        if (tc.hidden) {
            if (!success) {
                const errMsg =
                    (typeof result === "string"
                        ? result
                        : JSON.stringify(result)) || "未知错误";
                _toast.error(`delegate_task 失败: ${errMsg}`);
            }
            this.toolCalls.delete(toolId);
            return;
        }
        tc.complete(success, result);
    }

    _onToolCallError(turnId, toolId, error) {
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
        const turn = this.turns.get(turnId);
        if (!turn) return;
        const plan = new Plan({
            planId: data.planId,
            description: data.description,
            steps: data.steps,
            onApprove: (p) => bridge.send({ type: "plan_approve", planId: p.planId, turnId }),
            onReject: (p) => bridge.send({ type: "plan_reject", planId: p.planId, turnId }),
            onModify: (p) => bridge.send({ type: "plan_modify", planId: p.planId, turnId }),
        });
        // 插入到 content 内、cursor 之前
        const cursor = turn.content.querySelector(
            '[data-cs-role="cursor"]',
        );
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
        this.plans.get(data.planId)?.setOverallStatus("approved");
    }
    _onPlanRejected(turnId, data) {
        this.plans.get(data.planId)?.setOverallStatus("rejected");
    }
    _onPlanModified(turnId, data) {
        const p = this.plans.get(data.planId);
        if (p) {
            p.steps = (data.steps || []).map((s) => ({
                id: s.id,
                description: s.description || s.text || "",
                status: "pending",
                dependsOn: s.dependsOn || [],
            }));
            p._render();
        }
    }

    _onContextCompressed(turnId, data) {
        const turn = this.turns.get(turnId);
        if (!turn) return;
        const note = document.createElement("div");
        note.className = "context-compress-note";
        note.innerHTML = `<i class="fas fa-compress"></i> 上下文已压缩 ${data.originalTokens} → ${data.compressedTokens} tokens (${escapeHtml(data.strategy)})`;
        const cursor = turn.content.querySelector(
            '[data-cs-role="cursor"]',
        );
        if (cursor) {
            turn.content.insertBefore(note, cursor);
        } else {
            turn.content.appendChild(note);
        }
    }


    _onToolConfirmationNeeded(turnId, data) {
        const turn = this.turns.get(turnId);
        if (!turn) return;
        const reason = data.reason || "需要确认";
        const toolName = data.toolName || data.toolId;
        const note = document.createElement("div");
        note.className = "inline-alert warning";
        note.setAttribute("role", "alert");
        note.innerHTML = `
            <div class="inline-alert-icon"><i class="fas fa-exclamation-triangle"></i></div>
            <div class="inline-alert-body">
                <div class="inline-alert-title">需要确认:${escapeHtml(toolName)}</div>
                <div class="inline-alert-message">${escapeHtml(reason)}</div>
            </div>
        `;
        const cursor = turn.content.querySelector('[data-cs-role="cursor"]');
        if (cursor) {
            turn.content.insertBefore(note, cursor);
        } else {
            turn.content.appendChild(note);
        }
    }

    _onError(turnId, message) {
        this._isGenerating = false;
        this._currentStreamSegment = null;
        const turn = this.turns.get(turnId);
        if (turn) {
            clearInterval(turn.timerInterval);
            // 修复 v1: 不再 innerHTML="" 清空,而是 append error alert
            const alert = new InlineAlert({
                variant: "error",
                title: "出错了",
                message,
            });
            const cursor = turn.content.querySelector(
                '[data-cs-role="cursor"]',
            );
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
        note.style.cssText = "display:flex;justify-content:center;margin:var(--space-3) 0;";
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

    addArtifact(id, title, language, content) {
        const list = document.getElementById("artifacts-list");
        if (!list) return;
        // 自动展开
        this.appContainer.classList.remove("artifacts-collapsed");
        const panel = document.createElement("div");
        panel.className = "cs-artifact-panel";
        panel.id = "artifact-" + id;
        panel.dataset.artifactId = String(id);
        panel.innerHTML = `
            <div class="cs-artifact-header">
                <span class="cs-artifact-title">
                    <i class="fas fa-file-code" style="color:var(--accent);"></i>
                    ${escapeHtml(title)}
                </span>
                <span class="cs-artifact-actions">
                    <button class="icon-btn" data-art-action="copy" title="复制" aria-label="复制">
                        <i class="fas fa-copy"></i>
                    </button>
                    <button class="icon-btn" data-art-action="apply" title="应用到编辑器" aria-label="应用">
                        <i class="fas fa-file-import"></i>
                    </button>
                </span>
            </div>
            <pre style="margin:0;max-height:300px;overflow:auto;background:var(--code-bg);color:var(--code-fg);padding:var(--space-3);font-size:var(--text-xs);"><code class="language-${escapeHtml(language)}">${escapeHtml(content)}</code></pre>
        `;
        list.appendChild(panel);
        if (window.hljs) {
            panel
                .querySelectorAll("pre code")
                .forEach((b) => window.hljs.highlightElement(b));
        }
        panel.querySelectorAll("[data-art-action]").forEach((btn) => {
            btn.addEventListener("click", () => {
                const a = btn.dataset.artAction;
                if (a === "copy") {
                    navigator.clipboard?.writeText(content);
                    _toast.success("已复制");
                } else if (a === "apply") {
                    const artifactId = btn.closest(".cs-artifact-panel")?.dataset?.artifactId || "";
                    bridge.send({ type: "apply_artifact", artifactId, content });
                }
            });
        });
    }

    // ============ History / Sessions ============

    loadHistory(messages) {
        this.clear();
        for (const m of messages || []) {
            if (m.role === "user") {
                this.addUserMessage(m.content, m.images || [], m.fileRefs || []);
            } else if (m.role === "assistant") {
                if (m.thinking) {
                    this._startAITurn();
                    const turn = Array.from(this.turns.values()).pop();
                    this._onThinkingStart(turn.id);
                    this._onThinkingComplete(turn.id, m.thinkingDurationMs || 0);
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
