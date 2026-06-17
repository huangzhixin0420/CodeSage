/**
 * cs-tool-call parseToolError 单元测试
 * =====================================
 *
 * 方案 B 回归测试: ToolResult.Error 字符串经过 parseToolError 解析后,
 * 返回的 context 字段必须是 string 而不是 object —— 否则下游
 * renderErrorCard → escapeHtml(hint) 会触发 String(object) → "[object Object]",
 * 跟 message 拼成 "Missing 'path' parameter\n[object Object]"。
 *
 * 之前: parseToolError 把 context 收集为 { key: value } object, 然后这个 object
 * 流到 escapeHtml 强制 toString 产生 "[object Object]" 噪音。
 *
 * 现在: context 被序列化为 "k=v\nk2=v2" 字符串, 既保留调试信息, 又彻底消灭
 * [object Object] 渲染。
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-tool-call-parse-error";

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
    console.log("\n=== cs-tool-call parseToolError E2E ===\n");
    prepareE2E();

    const dom = new JSDOM("<!doctype html><html><body></body></html>", {
        url: `file://${WEBUI}/index.html`,
        runScripts: "outside-only",
    });
    const w = dom.window;
    globalThis.window = w;
    globalThis.document = w.document;

    // 动态 import cs-tool-call 模块
    const mod = await import(`${E2E_ROOT}/webui/js/components/cs-tool-call.js`);

    // parseToolError 没有 export, 但 complete(false, jsonString) 间接触发
    // normalizeResult → parseToolError, 然后 this.result 会带上 message + context。
    // 通过 ToolCall 实例 + complete() 间接触达。
    const tc = new mod.ToolCall({
        toolCallId: "tc_test",
        toolName: "read_file",
        arguments: {},
    });

    // 模拟 ToolExecutor.formatResult(ToolResult.Error("Missing 'path' parameter")) 输出:
    // {"success":false,"error":"Missing 'path' parameter","context_cost_estimate":12,"remaining_context_hint":"..."}
    const errorJson = JSON.stringify({
        success: false,
        error: "Missing 'path' parameter",
        context_cost_estimate: 12,
        remaining_context_hint: "100 tokens remaining",
    });

    tc.complete(false, errorJson);

    // 验证: this.result.kind === "error", this.result.message 是 string,
    // this.result.context 必须是 string(不是 object),否则会触发 [object Object]
    assert(tc.result != null, "ToolCall.result 应被设置");
    assert(tc.result.kind === "error", `result.kind 应为 'error', got=${tc.result.kind}`);
    assert(
        typeof tc.result.message === "string" && tc.result.message === "Missing 'path' parameter",
        `result.message 应为 string 'Missing \\'path\\' parameter', got=${JSON.stringify(tc.result.message)}`
    );
    assert(
        typeof tc.result.context === "string",
        `result.context 应为 string(避免 escapeHtml 触发 String(object) -> '[object Object]'), ` +
            `got type=${typeof tc.result.context}, value=${JSON.stringify(tc.result.context)?.slice(0, 80)}`
    );
    assert(
        tc.result.context.includes("context_cost_estimate=12"),
        `result.context 应保留调试信息(context_cost_estimate=12), got='${tc.result.context}'`
    );
    assert(
        tc.result.context.includes("remaining_context_hint="),
        `result.context 应保留 remaining_context_hint 字段, got='${tc.result.context}'`
    );
    // 关键: context 不应是 object, 不能让 escapeHtml 把它强制 toString 成 [object Object]
    assert(
        !String(tc.result.context).includes("[object Object]"),
        `result.context 不应被 toString 成 '[object Object]', got='${tc.result.context}'`
    );

    console.log(`\n=== 测试结果 ===`);
    console.log(`通过: ${passed}`);
    console.log(`失败: ${failed}`);
    return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
