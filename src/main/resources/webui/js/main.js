/**
 * CodeSage Web UI 入口
 *
 * 启动顺序:
 *   1. 等待 DOM ready
 *   2. 初始化 i18n 区域(从 settings)
 *   3. 初始化 ChatView
 *   4. 注册全局错误边界(P1.6)
 *   5. 首次启动展示 walkthrough
 *   6. 暴露 CodeSage.* 命名空间
 *
 * 兼容:
 *   - 旧 chat.html 通过 window.* 暴露的函数,在 index.html 中已改为
 *     onclick="CodeSage.chat.xxx()" 形式
 *   - 若 JCEF 不可用,chat 视图仍可工作(只是无桥接)
 */

import { chat } from "./views/chat.js";
import { bridge } from "./bridge.js";
import { state } from "./state.js";
import { toast } from "./components/cs-toast.js";
import { preloadMarkdown } from "./markdown.js";
import { setLocale, getLocale, t as i18n } from "./i18n.js";
import { Walkthrough } from "./components/cs-walkthrough.js";
import { CommandPalette } from "./components/cs-command-palette.js";

// === 全局错误边界(P1.6) ===
window.addEventListener("error", (e) => {
  console.error("[CodeSage] uncaught error:", e.error || e.message);
  toast.error("界面异常: " + (e.error?.message || e.message || "未知"));
  // 上报到 Kotlin 日志(如果桥接就绪)
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

// === 启动 ===
function boot() {
  try {
    // 先设置 i18n locale(从 state 读)
    const lang = state.get("language") || "zh-CN";
    setLocale(lang);

    chat.init();
    preloadMarkdown(); // 后台预热 marked

    // 通知 Kotlin 前端就绪
    if (bridge.bridgeReady) {
      bridge.send({ type: "__client_ready__" });
    } else {
      const orig = bridge.onMessage;
      bridge.onMessage = (data) => {
        bridge.send({ type: "__client_ready__" });
        bridge.onMessage = orig;
        if (orig) orig(data);
      };
    }

    // 首次启动 walkthrough
    if (Walkthrough.shouldShow()) {
      setTimeout(() => Walkthrough.show(), 800);
    }

    // 加载 settings（供设置中心使用）
    if (bridge.bridgeReady || bridge.queryFunc) {
      try {
        bridge.send({ type: "settings_get" });
      } catch (e) {
        console.warn("[CodeSage] failed to request settings_get:", e);
      }
    } else {
      // 等待桥接就绪后再请求
      const orig = bridge.onMessage;
      bridge.onMessage = (data) => {
        bridge.send({ type: "settings_get" });
        bridge.onMessage = orig;
        if (orig) orig(data);
      };
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

// === 暴露调试入口 ===
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
