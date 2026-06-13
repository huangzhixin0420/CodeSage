/**
 * ContextChips E2E 测试
 * =====================
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-context-chips";

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
  console.log("\n=== CodeSage ContextChips E2E Test ===\n");
  prepareE2E();

  const dom = new JSDOM(
    '<!doctype html><html><body><div id="root"></div></body></html>',
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

  const { ContextChips } = await import(
    `file://${E2E_ROOT}/webui/js/components/cs-context-chips.js`
  );

  const root = w.document.getElementById("root");
  const chips = new ContextChips({ container: root, tokenLimit: 1000 });

  // ============== 场景 1：添加 file chip ==============
  console.log("[1] 添加 file chip");
  chips.add({ type: "file", value: "src/main/kotlin/Agent.kt", label: "Agent.kt" });
  assert(
    root.querySelectorAll(".context-chip").length === 1,
    "应渲染 1 个 chip",
  );
  assert(
    root.textContent.includes("Agent.kt"),
    "chip 应显示文件名",
  );

  // ============== 场景 2：添加 context chip ==============
  console.log("\n[2] 添加 context chip");
  chips.add({ type: "context", value: "selection", label: "#selection" });
  assert(
    root.querySelectorAll(".context-chip").length === 2,
    "应渲染 2 个 chip",
  );

  // ============== 场景 3：去重 ==============
  console.log("\n[3] 同值 chip 去重");
  chips.add({ type: "file", value: "src/main/kotlin/Agent.kt", label: "Agent.kt" });
  assert(
    root.querySelectorAll(".context-chip").length === 2,
    "重复 chip 不应添加",
  );

  // ============== 场景 4：删除 chip ==============
  console.log("\n[4] 删除 chip");
  const id = chips.getItems()[0].id;
  chips.remove(id);
  assert(
    root.querySelectorAll(".context-chip").length === 1,
    "删除后剩 1 个 chip",
  );

  // ============== 场景 5：token 超限变红 ==============
  console.log("\n[5] token 超限变红");
  chips.setTokenLimit(10);
  assert(
    root.querySelector(".context-chips-row.over-limit") !== null,
    "超限后应加 over-limit 类",
  );
  assert(
    root.querySelector(".context-chips-hint.over-limit") !== null,
    "提示应加 over-limit 类",
  );

  // ============== 场景 6：解析为 payload ==============
  console.log("\n[6] 解析为发送 payload");
  chips.clear();
  chips.add({ type: "file", value: "src/A.kt", label: "A.kt" });
  chips.add({ type: "context", value: "selection", label: "#selection" });
  const payload = chips.toPayload();
  assert(
    payload.text.includes("@src/A.kt") && payload.text.includes("#selection"),
    "payload.text 应包含 @file 和 #context",
  );
  assert(
    payload.fileRefs.length === 1 && payload.fileRefs[0].path === "src/A.kt",
    "payload.fileRefs 应包含文件引用",
  );

  // ============== 总结 ==============
  console.log(`\n=== 测试结果 ===`);
  console.log(`通过: ${passed}`);
  console.log(`失败: ${failed}`);
  return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
