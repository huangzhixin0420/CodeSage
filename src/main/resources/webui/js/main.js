/**
 * CodeSage Web UI 入口 v2.0
 * ==========================
 *
 * 启动顺序:
 *   1. 等待 DOM ready
 *   2. 初始化 i18n
 *   3. 初始化 ChatView
 *   4. 注册全局错误边界
 *   5. 注册事件路由 (bridge.onMessage → chat._onXxx)
 *   6. 首次启动 walkthrough
 *   7. 暴露 CodeSage.* 命名空间
 */

import { chat } from "./views/chat.js";
import { bridge } from "./bridge.js";
import { state } from "./state.js";
import { toast } from "./components/cs-toast.js";
import { preloadMarkdown } from "./markdown.js";
import { setLocale, getLocale, t as i18n } from "./i18n.js";
import { Walkthrough } from "./components/cs-walkthrough.js";
import { CommandPalette } from "./components/cs-command-palette.js";
import { settings } from "./views/settings.js";

// === 全局错误边界 ===
window.addEventListener("error", (e) => {
    console.error("[CodeSage] uncaught error:", e.error || e.message);
    toast.error("界面异常: " + (e.error?.message || e.message || "未知"));
    if (bridge.bridgeReady) {
        bridge.send({
            type: "__client_error__",
            message: String(e.error?.message || e.message || "unknown"),
            stack: String(e.error?.stack || ""),
            source: "window.error",
        });
    }
});

window.addEventListener("unhandledrejection", (e) => {
    console.error("[CodeSage] unhandled rejection:", e.reason);
    toast.error("异步异常: " + (e.reason?.message || String(e.reason)));
    if (bridge.bridgeReady) {
        bridge.send({
            type: "__client_error__",
            message: String(e.reason?.message || e.reason || "unknown"),
            stack: String(e.reason?.stack || ""),
            source: "unhandledrejection",
        });
    }
});

// ===================== 事件路由 =====================
//
// 后端通过 bridge.sendMessage 发来的所有事件都进这里
// 根据 type 分发到 chat view 的对应 handler
//
// 事件清单(与 EventRouter.kt 一致):
//   start_turn / end_turn
//   text_delta
//   thinking_start / thinking_update / thinking_complete
//   tool_call_start / tool_call_delta / tool_call_complete / tool_call_error
//   plan_generated / plan_approved / plan_rejected / plan_modified
//   context_compressed / session_migrated / mode_suggestion
//   error / done
//   artifact_add
//   sessions_updated / session_switched / session_deleted / session_renamed
//   available_models / model_changed
//   settings / theme / show_thinking
//   user_message_ack

function handleBridgeMessage(msg) {
    if (!msg || typeof msg !== "object") return;
    const type = msg.type;
    const turnId = msg.turnId;
    try {
        switch (type) {
            case "start_turn":
                chat._startAITurn(turnId);
                break;
            case "end_turn":
                chat._endAITurn(turnId);
                break;
            case "text_delta":
                chat._onTextDelta(turnId, msg.delta || "");
                break;
            case "thinking_start":
                chat._onThinkingStart(turnId);
                break;
            case "thinking_update":
                chat._onThinkingUpdate(turnId, msg.message || "");
                break;
            case "thinking_complete":
                chat._onThinkingComplete(turnId, msg.elapsedMs || 0);
                break;
            case "tool_call_start":
                chat._onToolCallStart(
                    turnId,
                    msg.toolId,
                    msg.toolName,
                    msg.summary,
                    msg.arguments,
                    msg.icon,
                );
                break;
            case "tool_call_delta":
                chat._onToolCallDelta(turnId, msg.toolId, msg.delta);
                break;
            case "tool_call_complete":
                chat._onToolCallComplete(
                    turnId,
                    msg.toolId,
                    msg.success,
                    msg.result,
                );
                break;
            case "tool_confirmation_needed":
                chat._onToolConfirmationNeeded(turnId, msg);
                break;
            case "tool_call_error":
                chat._onToolCallError(turnId, msg.toolId, msg.error);
                break;
            case "plan_generated":
                chat._onPlanGenerated(turnId, msg);
                break;
            case "plan_approved":
                chat._onPlanApproved(turnId, msg);
                break;
            case "plan_rejected":
                chat._onPlanRejected(turnId, msg);
                break;
            case "plan_modified":
                chat._onPlanModified(turnId, msg);
                break;
            case "context_compressed":
                chat._onContextCompressed(turnId, msg);
                break;
            case "session_migrated":
                chat._onSessionMigrated(msg);
                break;
            case "mode_suggestion":
                chat._onModeSuggestion(turnId, msg);
                break;
            case "error":
                chat._onError(turnId, msg.message || "未知错误");
                break;

            // === 元数据 / 非 turn 类 ===
            case "sessions_updated":
                chat.setSessions(msg.sessions || []);
                break;
            case "session_switched":
                chat.setCurrentSession(msg.sessionId, msg.sessionName);
                break;
            case "session_deleted":
                chat.notifySessionDeleted(msg.sessionId);
                break;
            case "session_renamed":
                chat.notifySessionRenamed(msg.sessionId, msg.name);
                break;
            case "available_models":
                chat.setAvailableModels(msg.models || []);
                break;
            case "model_changed":
                chat.setModelLabel(msg.model, msg.provider);
                break;
            case "theme":
                chat.setTheme(msg.theme);
                break;
            case "show_thinking":
                // 后端主动同步时忽略(本地状态为准)
                break;
            case "history":
                chat.loadHistory(msg.messages || []);
                break;
            case "artifact_add":
                chat.addArtifact(msg.id, msg.title, msg.language, msg.content);
                break;
            case "user_message_ack":
                chat.addUserMessage(
                    msg.text || msg.content || "",
                    msg.images || [],
                    msg.fileRefs || [],
                );
                break;
            case "input_attachments":
                chat.setInputAttachments(msg.attachments || []);
                break;

            // === 新会话:Kotlin 在 JCEFChatPanel.clear() 里 sendToJS 这个 type。
            //    见 JCEFChatPanel.kt:fun clear() → sendToJS("clear_chat")
            //    AgentToolWindowPanel.createNewSession() 在 saveCurrentSession + createSession
            //    之后调 chatPanel.clear(),所以**必须**处理这个 type,否则:
            //      - 主区还显示老消息(用户看到 "点了 + 但没反应")
            //      - 输入框文字 / 附件 / 状态都不重置
            //    chat.clear() 清主区 + turns/toolCalls/plans;
            //    chat._resetInput() 清输入文字 / 附件 / 状态行。
            //    注意:不直接用 chat.clear({resetInput:true}) 形态,避免
            //    loadHistory(切到历史会话)误清掉用户草稿。 ===
            case "clear_chat":
                chat.clear();
                chat._resetInput();
                toast.info("已开启新会话");
                break;
            // Kotlin 通知前端:在 in-web 视图里打开设置
            // (由 SettingsBridgeHandler.handle("settings_open_view") 触发,
            // 见 JCEFChatPanel.kt 的 "open_settings" case)
            case "open_settings_view":
                window.CodeSage.openSettings();
                break;

            default:
                console.log("[bridge] unhandled type:", type, msg);
        }
    } catch (e) {
        console.error("[bridge] handler error for", type, ":", e);
    }
    // 同步把消息转发给 settings view。settings._onBridge 只处理 settings_* 等少量 type,
    // 其他 type 直接忽略,无副作用。好处:chat 视图时 settings.data 也保持最新,
    // 用户打开设置即可直接看到当前配置,不需要额外 refresh。
    try {
        settings._onBridge(msg);
    } catch (e) {
        console.error("[bridge] settings handler error for", type, ":", e);
    }
}

// 设置主消息回调
bridge.onMessage = (data) => {
    let msg = data;
    if (typeof msg === "string") {
        try {
            msg = JSON.parse(msg);
        } catch {
            console.warn("[bridge] failed to parse string message");
            return;
        }
    }
    handleBridgeMessage(msg);
};

// ===================== 启动 =====================

function boot() {
    try {
        const lang = state.get("language") || "zh-CN";
        setLocale(lang);

        chat.init();
        // 初始化设置视图(独立 DOM 容器,默认隐藏)。_onBridge 由 bridge.onMessage 兜底转发
        const settingsRoot = document.getElementById("cs-settings-root");
        if (settingsRoot) {
            settings.init(settingsRoot);
            settings.onBack = () => {
                window.CodeSage.closeSettings();
            };
        }
        preloadMarkdown();

        // 通知 Kotlin 前端就绪
        if (bridge.bridgeReady) {
            bridge.send({ type: "__client_ready__" });
        }

        // 首次启动 walkthrough
        if (Walkthrough.shouldShow()) {
            setTimeout(() => Walkthrough.show(), 800);
        }

        // 加载 settings
        try {
            bridge.send({ type: "settings_get" });
        } catch (e) {
            console.warn("[CodeSage] failed to request settings_get:", e);
        }

        // 启动 1.5s 后检测旧 IDE 配置迁移
        setTimeout(() => {
            try {
                if (bridge.bridgeReady) {
                    bridge.send({ type: "legacy_migration_check" });
                }
            } catch (e) {
                console.warn("[CodeSage] failed to request migration check:", e);
            }
        }, 1500);

        console.info("[CodeSage] chat view initialized, locale=" + getLocale());
    } catch (e) {
        console.error("[CodeSage] failed to boot:", e);
        toast.error("初始化失败: " + e.message);
    }
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
} else {
    boot();
}

// ===================== 全局命名空间 =====================

window.CodeSage = window.CodeSage || {};
window.CodeSage.bridge = bridge;
window.CodeSage.state = state;
window.CodeSage.toast = toast;
window.CodeSage.i18n = { setLocale, getLocale, t: i18n };
window.CodeSage.walkthrough = {
    show: () => Walkthrough.show({ forceShow: true }),
};
window.CodeSage.openCommandPalette = () => {
    new CommandPalette().open();
};
window.CodeSage.openSettings = () => {
    const root = document.getElementById("cs-settings-root");
    if (!root) {
        console.warn("[CodeSage] settings root not found");
        return;
    }
    if (root.style.display !== "none") return; // 已经在显示
    root.style.display = "";
    document.body.classList.add("cs-settings-open");
    // 向 Kotlin 拉取最新设置(settings._onBridge 会处理 settings_data)
    if (bridge.bridgeReady) {
        try { bridge.send({ type: "settings_get" }); } catch (e) { /* ignore */ }
    }
    settings.show();
};
window.CodeSage.closeSettings = () => {
    const root = document.getElementById("cs-settings-root");
    if (root) root.style.display = "none";
    document.body.classList.remove("cs-settings-open");
    try { settings.hide(); } catch (e) { /* ignore */ }
};
