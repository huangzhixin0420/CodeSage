/**
 * Artifact / Diff E2E 测试
 * ========================
 *
 * 验证 diff.js、cs-diff-viewer.js、cs-artifact.js 的核心能力：
 *   - 行级 diff 计算
 *   - unified patch 生成
 *   - diff viewer DOM 渲染
 *   - artifact 面板 tab 切换、版本历史、apply/reject 桥消息
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-artifact";

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
  console.log("\n=== CodeSage Artifact & Diff E2E Test ===\n");
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
  globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);
  globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
  globalThis.CustomEvent = w.CustomEvent;
  globalThis.HTMLElement = w.HTMLElement;

  const diffMod = await import(`file://${E2E_ROOT}/webui/js/diff.js`);
  const { diffLines, createPatch, applyPatch } = diffMod;

  // ============== 场景 1：diff.js 行级 diff ==============
  console.log("[1] diff.js 行级 diff");
  const oldText = "line1\nline2\nline3\n";
  const newText = "line1\nline2 modified\nline3\nline4\n";
  const changes = diffLines(oldText, newText);
  assert(
    changes.length >= 3,
    `应至少 3 个 change hunk，实际 ${changes.length}`,
  );
  const addChange = changes.find((c) => c.type === "add");
  const removeChange = changes.find((c) => c.type === "remove");
  const equalChange = changes.find((c) => c.type === "equal");
  const allAdd = changes
    .filter((c) => c.type === "add")
    .map((c) => c.value)
    .join("\n");
  const allRemove = changes
    .filter((c) => c.type === "remove")
    .map((c) => c.value)
    .join("\n");
  assert(allAdd.includes("line4"), "add hunk 应包含 line4");
  assert(allRemove.includes("line2"), "remove hunk 应包含旧 line2");
  assert(equalChange, "应存在 equal hunk");

  // ============== 场景 2：unified patch ==============
  console.log("[2] unified patch");
  const patch = createPatch(oldText, newText, {
    oldHeader: "--- a/Foo.kt\n",
    newHeader: "+++ b/Foo.kt\n",
  });
  assert(patch.includes("@@"), "patch 应包含 hunk header");
  assert(patch.includes("+line4"), "patch 应包含 +line4");
  assert(patch.includes("-line2"), "patch 应包含 -line2");

  // ============== 场景 3：apply patch ==============
  console.log("[3] apply patch");
  const applied = applyPatch(oldText, changes);
  assert(applied.trim() === newText.trim(), "apply patch 后应得到新文本");

  // ============== 场景 4：diff viewer DOM ==============
  console.log("[4] diff viewer DOM 渲染");
  const { CsDiffViewer } = await import(
    `file://${E2E_ROOT}/webui/js/components/cs-diff-viewer.js`
  );
  const container = w.document.getElementById("root");
  container.innerHTML = "";
  const viewer = new CsDiffViewer(container, { showHunkActions: true });
  viewer.setDiff(oldText, newText);
  assert(
    container.querySelector(".cs-diff-viewer") !== null,
    "应渲染 cs-diff-viewer",
  );
  assert(
    container.querySelectorAll(".cs-diff-hunk").length >= 1,
    "应至少有一个 hunk",
  );
  assert(
    container.querySelectorAll(".cs-diff-line.add").length >= 1,
    "应有 add 行",
  );
  assert(
    container.querySelectorAll(".cs-diff-line.remove").length >= 1,
    "应有 remove 行",
  );
  assert(
    container.querySelectorAll('[data-diff-action="accept"]').length >= 1,
    "应渲染 accept 按钮",
  );
  viewer.destroy();

  // ============== 场景 5：artifact 面板 ==============
  console.log("[5] artifact 面板 tab 与版本");
  const { CsArtifact } = await import(
    `file://${E2E_ROOT}/webui/js/components/cs-artifact.js`
  );
  container.innerHTML = "";
  const actions = [];
  const art = new CsArtifact({
    id: "art-1",
    title: "Agent.kt",
    language: "kotlin",
    content: "class Agent\n",
    originalContent: "class AgentOld\n",
    onAction: (a) => actions.push(a),
  });
  art.mount(container);

  assert(
    container.querySelector(".cs-artifact-panel") !== null,
    "应渲染 cs-artifact-panel",
  );
  assert(
    container.querySelector(".cs-artifact-name")?.textContent === "Agent.kt",
    "标题应为 Agent.kt",
  );

  // Tab: code active by default
  const codePanel = container.querySelector('[data-panel="code"]');
  assert(codePanel?.classList.contains("active"), "默认应激活 code tab");

  // Switch to diff tab
  const diffTab = container.querySelector('[data-tab="diff"]');
  diffTab?.click();
  const diffPanel = container.querySelector('[data-panel="diff"]');
  assert(diffPanel?.classList.contains("active"), "点击后 diff tab 应收激活");
  assert(
    diffPanel?.querySelector(".cs-diff-viewer") !== null,
    "diff panel 应渲染 diff viewer",
  );

  // Switch to versions tab
  const versionsTab = container.querySelector('[data-tab="versions"]');
  versionsTab?.click();
  const versionsPanel = container.querySelector('[data-panel="versions"]');
  assert(versionsPanel?.classList.contains("active"), "versions tab 应收激活");
  assert(
    versionsPanel?.querySelector(".cs-artifact-version-item") !== null,
    "versions panel 应渲染版本项",
  );

  // ============== 场景 6：artifact apply / reject ==============
  console.log("[6] artifact apply / reject 桥消息");
  const applyBtn = container.querySelector('[data-art-action="apply"]');
  applyBtn?.click();
  assert(
    actions.length === 1 && actions[0].type === "apply_artifact",
    "点击 apply 应发送 apply_artifact",
  );
  assert(actions[0].artifactId === "art-1", "apply payload 应带 artifactId");
  assert(actions[0].content === "class Agent\n", "apply payload 应带当前内容");

  const rejectBtn = container.querySelector('[data-art-action="reject"]');
  rejectBtn?.click();
  assert(
    actions.length === 2 && actions[1].type === "reject_artifact",
    "点击 reject 应发送 reject_artifact",
  );

  // ============== 场景 7：artifact 多版本 ==============
  console.log("[7] artifact 多版本");
  art.addVersion("class Agent\nfun run() {}\n", { versionNumber: 2 });
  const versionLabel = container.querySelector(".cs-artifact-version");
  assert(versionLabel?.textContent === "v2", "应显示 v2");

  const footerMeta = container.querySelector(".cs-artifact-footer");
  assert(footerMeta?.textContent.includes("2 个版本"), "footer 应显示版本数量");

  art.destroy();

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
