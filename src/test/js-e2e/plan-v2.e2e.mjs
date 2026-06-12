/**
 * PlanV2 E2E 测试
 * ==============
 *
 * 验证 cs-plan-v2.js 的依赖树渲染、step-tool 联动、inline 编辑。
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-plan-v2";

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
  console.log("\n=== CodeSage PlanV2 E2E Test ===\n");
  prepareE2E();

  const dom = new JSDOM("<!doctype html><html><body></body></html>", {
    url: `file://${WEBUI}/index.html`,
    runScripts: "outside-only",
  });
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
    `file://${E2E_ROOT}/webui/js/components/cs-plan-v2.js`
  );
  const { PlanV2 } = mod;

  // ============== 场景 1：依赖树渲染 ==============
  console.log("[1] 依赖树渲染");
  const plan = new PlanV2({
    planId: "p1",
    description: "修复 bug",
    steps: [
      { id: "s1", description: "分析代码", dependsOn: [] },
      { id: "s2", description: "定位 bug", dependsOn: ["s1"] },
      { id: "s3", description: "编写修复", dependsOn: ["s2"] },
      { id: "s4", description: "运行测试", dependsOn: ["s3"] },
    ],
  });
  const el = plan.el;
  assert(el.classList.contains("plan-card"), "应包含 plan-card 类");
  assert(el.querySelectorAll(".plan-step").length === 4, "应渲染 4 个 step");
  assert(el.querySelector('[data-cs-step-id="s1"]') != null, "应渲染 s1");
  assert(el.querySelector('[data-cs-step-id="s4"]') != null, "应渲染 s4");

  // ============== 场景 2：step 状态变更 ==============
  console.log("\n[2] step 状态变更");
  plan.setStepStatus("s1", "completed");
  const s1 = el.querySelector('.plan-step[data-cs-step-id="s1"]');
  assert(s1 != null, "应找到 s1 step 元素");
  assert(s1.classList.contains("completed"), "s1 应标记为 completed");
  plan.setStepStatus("s2", "running");
  const s2 = el.querySelector('.plan-step[data-cs-step-id="s2"]');
  assert(s2 != null, "应找到 s2 step 元素");
  assert(s2.classList.contains("running"), "s2 应标记为 running");

  // ============== 场景 3：tool 联动高亮 ==============
  console.log("\n[3] tool 联动高亮");
  // 在文档中创建两个 tool call 元素
  const toolA = document.createElement("div");
  toolA.setAttribute("data-cs-tool-call", "tc1");
  document.body.appendChild(toolA);
  const toolB = document.createElement("div");
  toolB.setAttribute("data-cs-tool-call", "tc2");
  document.body.appendChild(toolB);

  plan.steps[0].toolCallIds = ["tc1", "tc2"];
  plan._highlightLinkedTools("s1");
  assert(toolA.classList.contains("linked-to-step"), "tc1 应被高亮");
  assert(toolB.classList.contains("linked-to-step"), "tc2 应被高亮");

  // ============== 场景 4：inline 编辑 ==============
  console.log("\n[4] inline 编辑");
  let modifySent = null;
  const plan2 = new PlanV2({
    planId: "p2",
    steps: [
      { id: "s1", description: "第一步" },
      { id: "s2", description: "第二步" },
    ],
    onModify: (p, steps) => {
      modifySent = steps;
    },
  });
  plan2._enterEditMode();
  assert(plan2.editing === true, "应进入编辑模式");
  assert(
    plan2.el.querySelectorAll(".plan-editor-input").length === 2,
    "应有 2 个输入框",
  );

  // 修改第一个输入框
  const input = plan2.el.querySelector(".plan-editor-input");
  input.value = "第一步（已修改）";
  plan2._saveEdit();
  assert(plan2.editing === false, "保存后应退出编辑模式");
  assert(
    plan2.steps[0].description === "第一步（已修改）",
    "step 描述应被更新",
  );
  assert(
    modifySent != null && modifySent[0].description === "第一步（已修改）",
    "onModify 应收到新 steps",
  );

  // ============== 场景 5：添加/删除步骤 ==============
  console.log("\n[5] 添加/删除步骤");
  const plan3 = new PlanV2({
    planId: "p3",
    steps: [{ id: "s1", description: "唯一一步" }],
  });
  plan3._enterEditMode();
  plan3._addStep();
  assert(plan3.steps.length === 2, "添加后应有 2 步");
  plan3._removeStep("s1");
  assert(plan3.steps.length === 1, "删除后应有 1 步");
  assert(plan3.steps[0].id !== "s1", "剩余步骤不应是 s1");

  // ============== 总结 ==============
  console.log(`\n=== 测试结果 ===`);
  console.log(`通过: ${passed}`);
  console.log(`失败: ${failed}`);
  return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
