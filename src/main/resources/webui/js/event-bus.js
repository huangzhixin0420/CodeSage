/**
 * 事件总线
 *
 * 设计:
 *  - 高频事件(text_delta / thinking_update)合并到下一帧一次性 flush,减少重排
 *  - 其余事件立即 flush
 *  - 错误隔离:单个 handler 抛错不影响其他 handler
 */

export class EventBus {
    constructor() {
        this.handlers = new Map();
        this.batchQueue = [];
        this.batchScheduled = false;
    }

    on(type, handler) {
        if (typeof handler !== "function") return;
        if (!this.handlers.has(type)) this.handlers.set(type, []);
        this.handlers.get(type).push(handler);
    }

    off(type, handler) {
        const list = this.handlers.get(type);
        if (!list) return;
        const idx = list.indexOf(handler);
        if (idx >= 0) list.splice(idx, 1);
    }

    emit(type, data) {
        if (type === "text_delta" || type === "thinking_update") {
            this.batchQueue.push({ type, data });
            this.scheduleBatch();
        } else {
            this.flush(type, data);
        }
    }

    scheduleBatch() {
        if (this.batchScheduled) return;
        this.batchScheduled = true;
        requestAnimationFrame(() => {
            this.processBatch();
            this.batchScheduled = false;
        });
    }

    processBatch() {
        if (this.batchQueue.length === 0) return;
        const queue = this.batchQueue.splice(0, this.batchQueue.length);
        // 合并连续的 text_delta
        const merged = [];
        let pendingText = null;
        let pendingTurnId = null;
        for (const item of queue) {
            if (item.type === "text_delta") {
                if (pendingText !== null && pendingTurnId === item.data.turnId) {
                    pendingText += item.data.delta;
                } else {
                    if (pendingText !== null) {
                        merged.push({
                            type: "text_delta",
                            data: { turnId: pendingTurnId, delta: pendingText },
                        });
                    }
                    pendingText = item.data.delta;
                    pendingTurnId = item.data.turnId;
                }
            } else {
                if (pendingText !== null) {
                    merged.push({
                        type: "text_delta",
                        data: { turnId: pendingTurnId, delta: pendingText },
                    });
                    pendingText = null;
                    pendingTurnId = null;
                }
                merged.push(item);
            }
        }
        if (pendingText !== null) {
            merged.push({
                type: "text_delta",
                data: { turnId: pendingTurnId, delta: pendingText },
            });
        }
        for (const item of merged) {
            this.flush(item.type, item.data);
        }
    }

    flush(type, data) {
        const list = this.handlers.get(type);
        if (!list) return;
        // 复制后调用,避免 handler 中 off() 影响遍历
        const snapshot = list.slice();
        for (const h of snapshot) {
            try {
                h(data);
            } catch (e) {
                console.error(`[EventBus] handler error for ${type}:`, e);
            }
        }
    }

    clear() {
        this.handlers.clear();
        this.batchQueue = [];
    }
}
