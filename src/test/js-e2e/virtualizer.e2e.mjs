/**
 * MessageVirtualizer E2E 测试
 * ===========================
 *
 * 验证长对话截断 + "加载更早消息" 行为。
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-virtualizer";

function prepareE2E() {
    execSync(`rm -rf ${E2E_ROOT} && mkdir -p ${E2E_ROOT}`, { stdio: "ignore" });
    execSync(
        `rsync -a --delete ${WEBUI}/ ${E2E_ROOT}/webui/ && ` +
        `echo '{"type":"module"}' > ${E2E_ROOT}/package.json`,
        { stdio: "ignore" }
    );
}

let passed = 0;
let failed = 0;
function assert(cond, msg) {
    if (cond) {
        passed++;
        console.log(`  ✓ ${msg}`);
    } else {
        failed++;
        console.error(`  ✗ ${msg}`);
    }
}

async function runE2E() {
    console.log("\n=== CodeSage MessageVirtualizer E2E Test ===\n");
    prepareE2E();

    const dom = new JSDOM('<!doctype html><html><body><div id="messages"></div></body></html>', {
        url: `file://${WEBUI}/index.html`,
        runScripts: "outside-only",
    });
    const w = dom.window;
    globalThis.window = w;
    globalThis.document = w.document;
    globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
    globalThis.navigator = w.navigator;
    globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
    globalThis.CustomEvent = w.CustomEvent;

    const { MessageVirtualizer } = await import(`file://${E2E_ROOT}/webui/js/message-virtualizer.js`);
    const container = w.document.getElementById("messages");

    // ============== 场景 1：未超限时不截断 ==============
    console.log("[1] 未超限时不截断");
    const v1 = new MessageVirtualizer(container, { limit: 5, batch: 5 });
    for (let i = 0; i < 3; i++) {
        const el = w.document.createElement("div");
        el.className = "message";
        el.id = `m${i}`;
        el.textContent = `msg${i}`;
        v1.add(el);
    }
    assert(v1.totalCount === 3, `总数应为 3，实际 ${v1.totalCount}`);
    assert(v1.visibleCount === 3, `可见数应为 3，实际 ${v1.visibleCount}`);
    assert(container.querySelectorAll(".message").length === 3, "DOM 中应有 3 条消息");
    assert(container.querySelector(".message-load-earlier") === null, "不应显示加载按钮");

    // ============== 场景 2：超限时截断，保留最近 limit 条 ==============
    console.log("[2] 超限时截断");
    const v2 = new MessageVirtualizer(container, { limit: 5, batch: 5 });
    container.innerHTML = "";
    const msgs = [];
    for (let i = 0; i < 12; i++) {
        const el = w.document.createElement("div");
        el.className = "message";
        el.id = `m${i}`;
        el.textContent = `msg${i}`;
        msgs.push(el);
        container.appendChild(el);
    }
    v2.finishBatch();
    assert(v2.totalCount === 12, `总数应为 12，实际 ${v2.totalCount}`);
    assert(v2.visibleCount === 5, `可见数应为 5，实际 ${v2.visibleCount}`);
    const visibleIds = Array.from(container.querySelectorAll(".message")).map((el) => el.id);
    assert(
        visibleIds.join(",") === "m7,m8,m9,m10,m11",
        `应保留最近 5 条，实际 [${visibleIds.join(",")}]`
    );
    assert(container.querySelector(".message-load-earlier") !== null, "应显示加载更早按钮");

    // ============== 场景 3：点击加载更早 ==============
    console.log("[3] 点击加载更早");
    const btn = container.querySelector(".message-load-earlier-btn");
    assert(btn !== null, "应存在加载按钮");
    btn.click();
    assert(v2.visibleCount === 10, `点击后可见数应为 10，实际 ${v2.visibleCount}`);
    const visibleIds2 = Array.from(container.querySelectorAll(".message")).map((el) => el.id);
    assert(
        visibleIds2.join(",") === "m2,m3,m4,m5,m6,m7,m8,m9,m10,m11",
        `应加载更早 5 条，实际 [${visibleIds2.join(",")}]`
    );

    // 再次点击加载剩余
    container.querySelector(".message-load-earlier-btn")?.click();
    assert(v2.visibleCount === 12, `全部加载后可见数应为 12，实际 ${v2.visibleCount}`);
    assert(container.querySelector(".message-load-earlier") === null, "全部加载后按钮应消失");

    // ============== 场景 4：clear ==============
    console.log("[4] clear 清空");
    v2.clear();
    assert(v2.totalCount === 0, "clear 后总数应为 0");
    assert(container.querySelector(".message-load-earlier") === null, "clear 后加载按钮应消失");

    // ============== 结果 ==============
    console.log("\n=== 测试结果 ===");
    console.log(`通过: ${passed}`);
    console.log(`失败: ${failed}`);
    process.exit(failed > 0 ? 1 : 0);
}

runE2E().catch((e) => {
    console.error(e);
    process.exit(1);
});
