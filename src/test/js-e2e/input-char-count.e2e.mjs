/**
 * Input Character Count & Limit E2E Test
 * ======================================
 *
 * 验证主聊天输入区字符计数、阈值颜色、上限截断与发送拦截。
 */

import { JSDOM } from "jsdom";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-input-char-count";
const E2E_BRIDGE = `${E2E_ROOT}/webui/js/bridge.js`;

const MOCK_BRIDGE_CODE = `
export class Bridge {
  constructor() {
    this.bridgeReady = true;
    this.queryFunc = (req) => { try { req.onSuccess && req.onSuccess("{}"); } catch {} };
    this.onMessage = null;
  }
  send(payload) {
    if (typeof globalThis !== "undefined" && globalThis.__e2e_bridge_sink) {
      globalThis.__e2e_bridge_sink(payload);
    }
    return typeof payload === "string" ? payload : JSON.stringify(payload);
  }
}
export const bridge = new Bridge();
`;

function prepareE2E() {
  execSync(`rm -rf ${E2E_ROOT} && mkdir -p ${E2E_ROOT}`, { stdio: "ignore" });
  execSync(
    `rsync -a --delete ${WEBUI}/ ${E2E_ROOT}/webui/ && ` +
      `echo '{"type":"module"}' > ${E2E_ROOT}/package.json`,
    { stdio: "ignore" },
  );
  writeFileSync(E2E_BRIDGE, MOCK_BRIDGE_CODE);
}

function stubLibraries(window) {
  window.marked = { parse: (text) => String(text).replace(/\n/g, "<br>") };
  window.hljs = { highlightElement: () => {} };
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
  console.log("\n=== CodeSage Input Char Count E2E Test ===\n");
  prepareE2E();

  const html = readFileSync(join(WEBUI, "index.html"), "utf-8");
  const htmlClean = html.replace(
    /<script type="module" src="js\/main\.js"><\/script>/,
    "",
  );

  const dom = new JSDOM(htmlClean, {
    url: `file://${WEBUI}/index.html`,
    runScripts: "outside-only",
    pretendToBeVisual: true,
  });
  const w = dom.window;

  stubLibraries(w);

  const sink = (payload) => {
    w.__e2e_sent = w.__e2e_sent || [];
    w.__e2e_sent.push(payload);
  };
  w.__e2e_bridge_sink = sink;
  globalThis.__e2e_bridge_sink = sink;
  globalThis.__e2e_sent = w.__e2e_sent;

  globalThis.window = w;
  globalThis.document = w.document;
  const _lsStore = new Map();
  globalThis.localStorage = {
    getItem: (k) => (_lsStore.has(k) ? _lsStore.get(k) : null),
    setItem: (k, v) => _lsStore.set(k, String(v)),
    removeItem: (k) => _lsStore.delete(k),
    clear: () => _lsStore.clear(),
    key: (i) => Array.from(_lsStore.keys())[i] || null,
    get length() {
      return _lsStore.size;
    },
  };
  globalThis.navigator = w.navigator;
  globalThis.HTMLElement = w.HTMLElement;
  globalThis.Node = w.Node;
  globalThis.Element = w.Element;
  globalThis.Event = w.Event;
  globalThis.KeyboardEvent = w.KeyboardEvent;
  globalThis.CustomEvent = w.CustomEvent;
  globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);
  globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
  globalThis.fetch = () =>
    Promise.reject(new Error("fetch not available in E2E"));
  globalThis.marked = w.marked;
  globalThis.hljs = w.hljs;

  const chatModule =
    await import("file:///tmp/e2e-input-char-count/webui/js/views/chat.js");
  const chat = chatModule.chat;
  chat.init();

  const ta = w.document.getElementById("input-textarea");
  const counter = w.document.getElementById("input-char-count");
  const sendBtn = w.document.getElementById("send-btn");

  assert(ta !== null, "textarea 应存在");
  assert(counter !== null, "字符计数标签 #input-char-count 应存在");
  assert(
    counter.textContent.trim() === "0 / 4000",
    `初始计数应为 "0 / 4000", 实际 "${counter.textContent.trim()}"`,
  );
  assert(
    !counter.classList.contains("warning") &&
      !counter.classList.contains("error"),
    "初始计数不应含 warning/error 类",
  );

  // 场景 1: 输入 3500 字符 — 默认色
  console.log("\n[1] 输入 3500 字符 — 默认色");
  ta.value = "a".repeat(3500);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  assert(
    counter.textContent.trim() === "3500 / 4000",
    `3500 字符时计数应为 "3500 / 4000", 实际 "${counter.textContent.trim()}"`,
  );
  assert(
    !counter.classList.contains("warning") &&
      !counter.classList.contains("error"),
    "3500 字符时计数应为默认色",
  );

  // 场景 2: 输入 3600 字符 — 警告色
  console.log("\n[2] 输入 3600 字符 — 警告色");
  ta.value = "b".repeat(3600);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  assert(
    counter.classList.contains("warning"),
    "3600 字符时计数应含 warning 类",
  );
  assert(
    !counter.classList.contains("error"),
    "3600 字符时计数不应含 error 类",
  );

  // 场景 3: 输入 4000 字符 — 错误色
  console.log("\n[3] 输入 4000 字符 — 错误色");
  ta.value = "c".repeat(4000);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  assert(
    counter.textContent.trim() === "4000 / 4000",
    `4000 字符时计数应为 "4000 / 4000"`,
  );
  assert(counter.classList.contains("error"), "4000 字符时计数应含 error 类");

  // 场景 4: 输入 4001 字符 — 截断回 4000
  console.log("\n[4] 输入 4001 字符 — 截断回 4000");
  ta.value = "d".repeat(4001);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  assert(
    ta.value.length === 4000,
    `截断后 textarea 长度应为 4000, 实际 ${ta.value.length}`,
  );
  assert(
    counter.textContent.trim() === "4000 / 4000",
    `截断后计数应为 "4000 / 4000"`,
  );

  // 场景 5: 粘贴超过上限 — 截断 + toast
  console.log("\n[5] 粘贴 4500 字符 — 自动截断并提示");
  w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
  ta.value = "";
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  // 模拟粘贴事件 + input 事件
  const pasteData = "e".repeat(4500);
  const pasteEvent = new w.Event("paste", { bubbles: true, cancelable: true });
  pasteEvent.clipboardData = {
    getData: () => pasteData,
    items: [{ type: "text/plain", getAsString: () => pasteData }],
  };
  ta.dispatchEvent(pasteEvent);
  ta.value = pasteData;
  const inputEvent = new w.Event("input", { bubbles: true });
  inputEvent.inputType = "insertFromPaste";
  ta.dispatchEvent(inputEvent);
  assert(
    ta.value.length === 4000,
    `粘贴截断后长度应为 4000, 实际 ${ta.value.length}`,
  );
  const toastMessages = Array.from(
    w.document.querySelectorAll(".cs-toast .cs-toast-message"),
  ).map((el) => el.textContent);
  assert(
    toastMessages.some((m) => m.includes("已自动截断至 4000 字符")),
    `粘贴截断后应弹出 toast 提示, 实际 toasts=${JSON.stringify(toastMessages)}`,
  );

  // 场景 6: 达到上限时点击发送 — 不发送, toast 提示
  console.log("\n[6] 达到上限时点击发送 — 拦截");
  ta.value = "f".repeat(4000);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  w.__e2e_sent = [];
  w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
  sendBtn.click();
  const sendAttempts = (w.__e2e_sent || []).filter(
    (s) => s && s.type === "send_message",
  );
  assert(sendAttempts.length === 0, "达到上限时点击发送不应触发 send_message");
  const limitToasts = Array.from(
    w.document.querySelectorAll(".cs-toast .cs-toast-message"),
  ).map((el) => el.textContent);
  assert(
    limitToasts.some((m) => m.includes("已达到 4000 字符上限")),
    `达到上限时应弹出上限 toast, 实际 toasts=${JSON.stringify(limitToasts)}`,
  );

  // 场景 7: 达到上限时按 Enter — 拦截
  console.log("\n[7] 达到上限时按 Enter — 拦截");
  w.__e2e_sent = [];
  w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
  const enterEvent = new w.KeyboardEvent("keydown", {
    key: "Enter",
    bubbles: true,
    cancelable: true,
  });
  ta.dispatchEvent(enterEvent);
  const enterSends = (w.__e2e_sent || []).filter(
    (s) => s && s.type === "send_message",
  );
  assert(enterSends.length === 0, "达到上限时按 Enter 不应触发 send_message");
  const enterToasts = Array.from(
    w.document.querySelectorAll(".cs-toast .cs-toast-message"),
  ).map((el) => el.textContent);
  assert(
    enterToasts.some((m) => m.includes("已达到 4000 字符上限")),
    "达到上限时按 Enter 应弹出上限 toast",
  );

  // 场景 8: Shift+Enter 仍可换行
  console.log("\n[8] Shift+Enter 仍可换行");
  ta.value = "line1";
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  const shiftEnterEvent = new w.KeyboardEvent("keydown", {
    key: "Enter",
    shiftKey: true,
    bubbles: true,
    cancelable: true,
  });
  ta.dispatchEvent(shiftEnterEvent);
  assert(!shiftEnterEvent.defaultPrevented, "Shift+Enter 不应被阻止默认行为");

  // 场景 9: 正常长度时发送仍工作
  console.log("\n[9] 正常长度时发送仍工作");
  ta.value = "正常消息";
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  w.__e2e_sent = [];
  sendBtn.click();
  const normalSends = (w.__e2e_sent || []).filter(
    (s) => s && s.type === "send_message",
  );
  assert(normalSends.length === 1, "正常长度时点击发送应触发 send_message");
  assert(
    counter.textContent.trim() === "0 / 4000",
    "发送后计数应重置为 0 / 4000",
  );

  console.log("\n=== 测试结果 ===");
  console.log(`通过: ${passed}`);
  console.log(`失败: ${failed}`);
  return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
