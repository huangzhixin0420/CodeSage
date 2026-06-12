/**
 * MentionAutocomplete E2E 测试
 * ============================
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-mention";

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
  console.log("\n=== CodeSage MentionAutocomplete E2E Test ===\n");
  prepareE2E();

  const dom = new JSDOM(
    '<!doctype html><html><body><textarea id="ta"></textarea></body></html>',
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
    clear: () => {},
    key: () => null,
    get length() {
      return 0;
    },
  };
  globalThis.navigator = w.navigator;
  globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);

  const mod = await import(
    `file://${E2E_ROOT}/webui/js/components/cs-mention.js`
  );
  const { MentionAutocomplete } = mod;

  const ta = w.document.getElementById("ta");
  let selected = null;
  const ac = new MentionAutocomplete({
    textarea: ta,
    onSelect: (item) => {
      selected = item;
    },
  });

  // ============== 场景 1：@ 触发文件候选 ==============
  console.log("[1] @ 触发文件候选");
  ta.value = "请看 @A";
  ta.setSelectionRange(ta.value.length, ta.value.length);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));

  const popup = w.document.querySelector(".mention-popup");
  assert(popup != null, "应弹出 mention-popup");
  assert(popup.querySelectorAll(".mention-item").length > 0, "应有候选项");
  assert(popup.textContent.includes("Agent.kt"), "默认文件候选应包含 Agent.kt");

  // ============== 场景 2：# 触发上下文候选 ==============
  console.log("\n[2] # 触发上下文候选");
  ta.value = "参考 #s";
  ta.setSelectionRange(ta.value.length, ta.value.length);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));

  const popup2 = w.document.querySelector(".mention-popup");
  assert(popup2 != null, "# 触发应弹出 mention-popup");
  assert(
    popup2.textContent.includes("#selection"),
    "上下文候选应包含 #selection",
  );

  // ============== 场景 3：Enter 选中 ==============
  console.log("\n[3] Enter 选中");
  ta.value = "请看 @A";
  ta.setSelectionRange(ta.value.length, ta.value.length);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  // 直接 Enter 选择第一个文件候选
  ta.dispatchEvent(
    new w.KeyboardEvent("keydown", { key: "Enter", bubbles: true }),
  );
  assert(ta.value.includes("@src/main/kotlin/Agent.kt"), "选中后应插入 @path");
  assert(
    selected != null && selected.type === "file",
    "onSelect 应被调用并返回 file 类型",
  );

  // ============== 场景 4：Esc 关闭 ==============
  console.log("\n[4] Esc 关闭");
  ta.value = "@A";
  ta.setSelectionRange(ta.value.length, ta.value.length);
  ta.dispatchEvent(new w.Event("input", { bubbles: true }));
  assert(
    w.document.querySelector(".mention-popup") != null,
    "输入 @A 应弹出 popup",
  );
  ta.dispatchEvent(
    new w.KeyboardEvent("keydown", { key: "Escape", bubbles: true }),
  );
  assert(
    w.document.querySelector(".mention-popup") == null,
    "Esc 后应关闭 popup",
  );

  // ============== 总结 ==============
  console.log(`\n=== 测试结果 ===`);
  console.log(`通过: ${passed}`);
  console.log(`失败: ${failed}`);
  return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
