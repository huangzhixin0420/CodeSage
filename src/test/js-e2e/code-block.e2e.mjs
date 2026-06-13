/**
 * Code Block Action Bar E2E 测试
 * ================================
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-code-block";

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
  console.log("\n=== CodeSage Code Block Action Bar E2E Test ===\n");
  prepareE2E();

  const dom = new JSDOM(
    '<!doctype html><html><body><div id="content"></div></body></html>',
    {
      url: `file://${WEBUI}/index.html`,
      runScripts: "outside-only",
    },
  );
  const w = dom.window;
  globalThis.window = w;
  globalThis.document = w.document;
  globalThis.localStorage = { getItem: () => null, setItem: () => {} };
  globalThis.navigator = w.navigator;
  globalThis.HTMLElement = w.HTMLElement;
  globalThis.CustomEvent = w.CustomEvent;

  // mock bridge
  const sent = [];
  globalThis.__e2e_bridge_sink = (payload) => sent.push(payload);

  const { enhanceCodeBlocks } = await import(
    `file://${E2E_ROOT}/webui/js/markdown.js`
  );

  // ============== 场景 1：普通代码块渲染 action bar ==============
  console.log("[1] 普通代码块渲染 action bar");
  const content = w.document.getElementById("content");
  content.innerHTML = `<pre><code class="language-kotlin">fun main() {
    println("hello")
}</code></pre>`;

  enhanceCodeBlocks(content, {
    onAction: (type, payload) => {
      sent.push({ type, ...payload });
    },
  });

  const block = content.querySelector(".code-block");
  assert(block !== null, "应渲染 .code-block 包装");
  assert(block.querySelector(".code-block-header") !== null, "应有 header");
  assert(
    block.querySelector('[data-cs-action="apply"]') !== null,
    "应有 Apply to Editor 按钮",
  );
  assert(
    block.querySelector('[data-cs-action="insert"]') !== null,
    "应有 Insert at Cursor 按钮",
  );
  assert(
    block.querySelector('[data-cs-action="create"]') !== null,
    "应有 Create File 按钮",
  );
  assert(
    block.querySelector('[data-cs-action="copy"]') !== null,
    "应有 Copy 按钮",
  );

  // ============== 场景 2：带文件路径的代码块 ==============
  console.log("\n[2] 带文件路径的代码块");
  content.innerHTML = `<pre data-cs-file-path="src/main/kotlin/Agent.kt"><code class="language-kotlin">class Agent</code></pre>`;
  enhanceCodeBlocks(content, {
    onAction: (type, payload) => sent.push({ type, ...payload }),
  });
  const pathEl = content.querySelector(".code-block-path");
  assert(pathEl !== null, "应显示文件路径");
  assert(
    pathEl.textContent.includes("src/main/kotlin/Agent.kt"),
    "文件路径文本应正确",
  );

  // ============== 场景 3：Apply 按钮发送 apply_code_block ==============
  console.log("\n[3] Apply 按钮发送桥消息");
  content.innerHTML = `<pre><code class="language-kotlin">fun a() {}</code></pre>`;
  sent.length = 0;
  enhanceCodeBlocks(content, {
    onAction: (type, payload) => sent.push({ type, ...payload }),
  });
  const applyBtn = content.querySelector('[data-cs-action="apply"]');
  applyBtn?.click();
  assert(
    sent.some((s) => s.type === "apply_code_block"),
    "应发送 apply_code_block",
  );
  const applyPayload = sent.find((s) => s.type === "apply_code_block");
  assert(
    applyPayload && applyPayload.language === "kotlin",
    "payload 应带 language",
  );
  assert(
    applyPayload && applyPayload.code.includes("fun a()"),
    "payload 应带 code",
  );

  // ============== 场景 4：diff 代码块渲染 diff viewer ==============
  console.log("\n[4] diff 代码块渲染 diff viewer");
  content.innerHTML = `<pre><code class="language-diff">--- a.kt
+++ b.kt
@@ -1,3 +1,3 @@
 fun old() {
-    println("old")
+    println("new")
 }
</code></pre>`;
  sent.length = 0;
  enhanceCodeBlocks(content, {
    onAction: (type, payload) => sent.push({ type, ...payload }),
  });
  const diffBlock = content.querySelector(".code-block");
  assert(diffBlock !== null, "diff 应渲染 .code-block");
  assert(
    diffBlock.querySelector(".cs-diff-viewer") !== null,
    "diff 应渲染 cs-diff-viewer",
  );
  assert(diffBlock.querySelector(".cs-diff-hunk") !== null, "diff 应渲染 hunk");
  const acceptBtn = diffBlock.querySelector(
    '.cs-diff-btn.accept[data-diff-action="accept"]',
  );
  assert(acceptBtn !== null, "diff hunk 应有 Accept 按钮");
  acceptBtn?.click();
  assert(
    sent.some((s) => s.type === "accept_hunk"),
    "点击 Accept 应发送 accept_hunk",
  );

  // ============== 场景 5：Insert / Create File 按钮 ==============
  console.log("\n[5] Insert / Create File 按钮");
  content.innerHTML = `<pre><code class="language-kotlin">fun b() {}</code></pre>`;
  sent.length = 0;
  enhanceCodeBlocks(content, {
    onAction: (type, payload) => sent.push({ type, ...payload }),
  });
  content.querySelector('[data-cs-action="insert"]')?.click();
  content.querySelector('[data-cs-action="create"]')?.click();
  assert(
    sent.some((s) => s.type === "insert_at_cursor"),
    "应发送 insert_at_cursor",
  );
  assert(
    sent.some((s) => s.type === "create_file_from_code"),
    "应发送 create_file_from_code",
  );

  // ============== 总结 ==============
  console.log(`\n=== 测试结果 ===`);
  console.log(`通过: ${passed}`);
  console.log(`失败: ${failed}`);
  return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
