/**
 * AgentDashboard E2E 测试
 * ======================
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-dashboard";

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
    console.log("\n=== CodeSage AgentDashboard E2E Test ===\n");
    prepareE2E();

    const dom = new JSDOM('<!doctype html><html><body></body></html>', {
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
        get length() { return 0; },
    };
    globalThis.navigator = w.navigator;
    globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);

    const mod = await import(`file://${E2E_ROOT}/webui/js/components/cs-agent-dashboard.js`);
    const { AgentDashboard } = mod;

    // 构造一个 mock RunLog
    const mockRunLog = {
        getSummary: () => ({
            status: "running",
            currentStage: { type: "tool_call" },
            metrics: { tokensIn: 1200, tokensOut: 3400 },
            elapsedMs: 12345,
            toolCount: 3,
            completedTools: 1,
        }),
    };

    const dashboard = new AgentDashboard({});
    dashboard.setRunLog(mockRunLog);
    dashboard._render();

    const el = dashboard.el;
    assert(el.style.display !== "none", "running 状态 dashboard 应显示");
    assert(el.querySelector(".agent-stage") != null, "应渲染当前阶段");
    assert(el.textContent.includes("执行工具"), "应显示当前阶段中文标签");
    assert(el.textContent.includes("↑ 1.2k"), "应显示输入 tokens");
    assert(el.textContent.includes("↓ 3.4k"), "应显示输出 tokens");
    assert(el.textContent.includes("⏱ 12.3s"), "应显示耗时");
    assert(el.querySelector(".agent-progress-fill") != null, "应渲染进度条");

    // 完成状态应隐藏
    dashboard.setRunLog({
        getSummary: () => ({
            status: "completed",
            currentStage: { type: "text" },
            metrics: {},
            elapsedMs: 0,
        }),
    });
    dashboard._render();
    assert(dashboard.el.style.display === "none", "completed 状态 dashboard 应隐藏");

    console.log(`\n=== 测试结果 ===`);
    console.log(`通过: ${passed}`);
    console.log(`失败: ${failed}`);
    return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
