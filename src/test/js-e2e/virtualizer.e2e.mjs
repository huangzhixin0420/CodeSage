/**
 * MessageVirtualizer E2E 测试
 * ===========================
 *
 * 验证 IntersectionObserver + DOM 回收虚拟滚动行为。
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
    { stdio: "ignore" },
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

  const dom = new JSDOM(
    '<!doctype html><html><body><div id="messages" style="height:400px;overflow:auto;"></div></body></html>',
    {
      url: `file://${WEBUI}/index.html`,
      runScripts: "outside-only",
    },
  );
  const w = dom.window;
  globalThis.window = w;
  globalThis.document = w.document;
  globalThis.localStorage = {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
  };
  globalThis.navigator = w.navigator;
  globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 0);
  globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
  globalThis.CustomEvent = w.CustomEvent;

  // 模拟 IntersectionObserver:被观察的元素默认不交叉,可通过 setIntersecting 控制
  const observed = new Map();
  let observerCallback = null;
  globalThis.IntersectionObserver = class MockIntersectionObserver {
    constructor(cb) {
      observerCallback = cb;
    }
    observe(el) {
      observed.set(el, false);
    }
    unobserve(el) {
      observed.delete(el);
    }
    disconnect() {
      observed.clear();
    }
  };
  function setIntersecting(els, intersecting = true) {
    for (const el of els) {
      observed.set(el, intersecting);
    }
    if (observerCallback) {
      const entries = Array.from(observed.entries()).map(
        ([el, isIntersecting]) => ({
          target: el,
          isIntersecting,
        }),
      );
      observerCallback(entries);
    }
  }

  globalThis.ResizeObserver = class MockResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  };

  const { MessageVirtualizer } = await import(
    `file://${E2E_ROOT}/webui/js/message-virtualizer.js`
  );
  const container = w.document.getElementById("messages");

  // ============== 场景 1：未超限时不回收 ==============
  console.log("[1] 少量消息全部在 DOM");
  const v1 = new MessageVirtualizer(container, { limit: 5, overscan: 2 });
  const msgs = [];
  for (let i = 0; i < 3; i++) {
    const el = w.document.createElement("div");
    el.className = "message";
    el.id = `m${i}`;
    el.textContent = `msg${i}`;
    msgs.push(el);
    v1.add(el);
  }
  setIntersecting(msgs, true);
  assert(v1.totalCount === 3, `总数应为 3，实际 ${v1.totalCount}`);
  assert(v1.visibleCount === 3, `可见数应为 3，实际 ${v1.visibleCount}`);
  assert(
    container.querySelectorAll(".message").length === 3,
    "DOM 中应有 3 条消息",
  );

  // ============== 场景 2：超限时只保留可见+overscan ==============
  console.log("\n[2] 超限时回收不可见 DOM");
  container.innerHTML = "";
  const v2 = new MessageVirtualizer(container, { limit: 5, overscan: 2 });
  const msgs2 = [];
  for (let i = 0; i < 20; i++) {
    const el = w.document.createElement("div");
    el.className = "message";
    el.id = `m${i}`;
    el.textContent = `msg${i}`;
    el.style.height = "40px";
    msgs2.push(el);
    container.appendChild(el);
  }
  v2.finishBatch();
  // 默认没有任何元素 intersecting,会退回保留最后 limit 条
  assert(v2.totalCount === 20, `总数应为 20，实际 ${v2.totalCount}`);
  assert(
    v2.visibleCount === 5,
    `未交叉时可见数应为 limit=5，实际 ${v2.visibleCount}`,
  );
  assert(
    container.querySelector(".virtualizer-top-spacer") !== null,
    "应有顶部 spacer",
  );
  assert(
    container.querySelector(".virtualizer-bottom-spacer") !== null,
    "应有底部 spacer",
  );

  // 模拟中间元素可见
  setIntersecting([msgs2[8], msgs2[9], msgs2[10]], true);
  assert(
    v2.visibleCount === 7,
    `中间 3 条 + overscan*2 共 7 条可见，实际 ${v2.visibleCount}`,
  );

  // ============== 场景 3：pinned 元素始终保留 ==============
  console.log("\n[3] pin 元素不参与回收");
  v2.pin(msgs2[0]);
  setIntersecting([msgs2[10]], true);
  assert(v2.visibleCount >= 6, "pin 的元素应保留在 DOM 中");
  assert(container.contains(msgs2[0]), "pin 的元素仍在 DOM");

  v2.unpin(msgs2[0]);
  setIntersecting([msgs2[10]], true);
  assert(!container.contains(msgs2[0]), "unpin 后元素可被回收");

  // ============== 场景 4：clear 清空 ==============
  console.log("\n[4] clear 清空");
  v2.clear();
  assert(v2.totalCount === 0, "clear 后总数应为 0");
  assert(
    container.querySelector(".virtualizer-top-spacer") === null,
    "clear 后顶部 spacer 应消失",
  );

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
