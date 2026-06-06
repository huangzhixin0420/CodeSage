/**
 * 全局状态管理
 *
 * 集中存放跨组件共享的状态(模式/模型/会话/草稿等)
 * 业务上属于「简单可观察」模式:
 *   - 显式 set / get,避免 Proxy 开销
 *   - 重要变更通过 EventBus 广播
 */

import { EventBus } from "./event-bus.js";

const STORAGE_KEY = "codesage_state_v1";

const DEFAULTS = {
  mode: "agent", // agent | ask | manual
  currentModel: "",
  availableModels: [], // [{ provider, models: [...] }]
  showThinking: true,
  theme: "auto", // light | dark | auto
  currentTurnId: null,
  turns: new Map(), // turnId -> { content, thinking, tools: [...] }
  artifacts: new Map(), // artifactId -> { title, language, content }
  sessions: [], // session list
  currentSessionId: null,
  maxChars: 4000,
  charWarningThreshold: 3600,
  draft: "", // 输入框草稿
  sidebarCollapsed: true, // 会话侧边栏默认折叠
};

export class AppState extends EventBus {
  constructor() {
    super();
    this._state = { ...DEFAULTS };
    this._loadPersisted();
  }

  // === Getters ===
  get(key) {
    return this._state[key];
  }

  getAll() {
    return { ...this._state };
  }

  // === Setters (with broadcast) ===
  set(key, value) {
    const old = this._state[key];
    if (old === value) return;
    this._state[key] = value;
    this.emit("state:" + key, { key, value, old });
    this.emit("state:change", { key, value, old });
  }

  patch(partial) {
    for (const [k, v] of Object.entries(partial)) {
      this.set(k, v);
    }
  }

  // === Persistence ===
  _loadPersisted() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const data = JSON.parse(raw);
      for (const k of [
        "mode",
        "currentModel",
        "showThinking",
        "theme",
        "draft",
        "sidebarCollapsed",
        "language", // i18n locale — 让 setLocale() 持久化能跨重启
      ]) {
        if (data[k] !== undefined) this._state[k] = data[k];
      }
    } catch (e) {
      console.warn("[State] failed to load persisted state:", e);
    }
  }

  persist() {
    try {
      const data = {};
      for (const k of [
        "mode",
        "currentModel",
        "showThinking",
        "theme",
        "draft",
        "sidebarCollapsed",
        "language", // 同上
      ]) {
        data[k] = this._state[k];
      }
      localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch (e) {
      console.warn("[State] failed to persist:", e);
    }
  }

  reset() {
    this._state = { ...DEFAULTS };
    this.emit("state:reset");
  }
}

export const state = new AppState();

// === Auto-persist on change ===
state.on("state:change", () => state.persist());
