/**
 * Kotlin ↔ JavaScript 桥
 *
 * 协议:
 *   - Kotlin → JS: window.javaBridge.sendMessage(json)  → window.onJavaMessage(json)
 *   - JS → Kotlin: window.javaBridge.sendMessage(json)  → 注入的 queryFunc(json)
 *
 * 设计原则:
 *   - 单一 window.javaBridge 命名空间(避免污染)
 *   - 消息队列:Kotlin 在 bridge ready 之前发的消息不丢
 *   - 离线检测:5s 后检查关键库,失败显示警告
 */

const BRIDGE_PENDING_KEY = "__codesage_bridge_pending__";

function getPendingQueue() {
    if (!window[BRIDGE_PENDING_KEY]) {
        window[BRIDGE_PENDING_KEY] = [];
    }
    return window[BRIDGE_PENDING_KEY];
}

export class Bridge {
    constructor() {
        this.bridgeReady = false;
        this.queryFunc = null;
    }

    /**
     * 由 Kotlin 注入的初始化函数调用
     * 设置 query 函数,刷新 pending 消息
     */
    onBridgeReady() {
        this.bridgeReady = true;
        const queue = getPendingQueue();
        if (queue.length > 0) {
            for (const json of queue.splice(0, queue.length)) {
                this._sendImmediate(json);
            }
        }
    }

    /**
     * 发送 JSON 消息到 Kotlin 端
     * @param {object} payload
     */
    send(payload) {
        const json = typeof payload === "string" ? payload : JSON.stringify(payload);
        if (!this.bridgeReady || !this.queryFunc) {
            getPendingQueue().push(json);
            return;
        }
        this._sendImmediate(json);
    }

    _sendImmediate(json) {
        try {
            this.queryFunc({
                request: json,
                onSuccess: () => {},
                onFailure: (code, msg) => {
                    console.error("[Bridge] send failure:", code, msg);
                },
            });
        } catch (e) {
            console.error("[Bridge] send exception:", e);
        }
    }

    /**
     * 接收 Kotlin 消息,转给 handler
     * 由 main.js 设置: window.onJavaMessage = (json) => bridge.onJavaMessage(json)
     */
    onJavaMessage(msg) {
        try {
            const data = typeof msg === "string" ? JSON.parse(msg) : msg;
            if (this.onMessage) this.onMessage(data);
        } catch (e) {
            console.error("[Bridge] failed to parse message:", e, msg);
        }
    }

    /**
     * 5s 后检查关键库是否到位,失败显示 offline warning
     */
    scheduleOfflineCheck() {
        setTimeout(() => {
            const ok = this.checkCriticalLibraries();
            if (!ok) this.showOfflineWarning();
        }, 5000);
    }

    checkCriticalLibraries() {
        return (
            typeof window.hljs !== "undefined" &&
            typeof window.javaBridge !== "undefined"
        );
    }

    showOfflineWarning() {
        if (document.getElementById("codesage-offline-warning")) return;
        const div = document.createElement("div");
        div.id = "codesage-offline-warning";
        div.className = "offline-warning";
        div.innerHTML = `
            <div class="offline-warning-icon">
                <i class="fas fa-exclamation-triangle"></i>
            </div>
            <h2>加载异常</h2>
            <p>CodeSage 界面加载异常,部分资源可能缺失。</p>
            <button class="reload-btn" id="codesage-reload-btn">重载界面</button>
        `;
        document.body.appendChild(div);
        document.getElementById("codesage-reload-btn")?.addEventListener("click", () => {
            this.reload();
        });
    }

    reload() {
        this.send({ type: "reload_browser" });
    }
}

export const bridge = new Bridge();

// === Window-level bridge integration ===
// 监听 Kotlin 注入的 queryFunc
function watchBridgeInjection() {
    let attempts = 0;
    const maxAttempts = 50; // 5s
    const interval = setInterval(() => {
        attempts++;
        if (window.javaBridge) {
            clearInterval(interval);
            bridge.queryFunc = window.javaBridge.sendMessage;
            bridge.onBridgeReady();
        } else if (attempts >= maxAttempts) {
            clearInterval(interval);
            console.warn("[Bridge] Java bridge not detected after timeout");
            bridge.showOfflineWarning();
        }
    }, 100);
}

watchBridgeInjection();

// === window.onJavaMessage hook ===
window.onJavaMessage = (msg) => bridge.onJavaMessage(msg);
window.sendMessageToJava = (json) => bridge.send(json);
