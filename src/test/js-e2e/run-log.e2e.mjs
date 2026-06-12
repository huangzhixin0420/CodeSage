/**
 * RunLog 数据层 E2E 测试
 * ======================
 *
 * 验证 run-log.js 能够正确累积事件、生成结构化记录、支持序列化与聚合 API。
 */

import { JSDOM } from "jsdom";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-runlog";

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
    console.log("\n=== CodeSage RunLog E2E Test ===\n");
    prepareE2E();

    // 创建最小 JSDOM，只用于提供 window / localStorage 等全局 API
    const dom = new JSDOM('<!doctype html><html><body></body></html>', {
        url: `file://${WEBUI}/index.html`,
        runScripts: "outside-only",
    });
    const w = dom.window;

    const _lsStore = new Map();
    globalThis.window = w;
    globalThis.document = w.document;
    globalThis.localStorage = {
        getItem: (k) => (_lsStore.has(k) ? _lsStore.get(k) : null),
        setItem: (k, v) => _lsStore.set(k, String(v)),
        removeItem: (k) => _lsStore.delete(k),
        clear: () => _lsStore.clear(),
        key: (i) => Array.from(_lsStore.keys())[i] || null,
        get length() { return _lsStore.size; },
    };
    globalThis.navigator = w.navigator;
    globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);

    const mod = await import(`file://${E2E_ROOT}/webui/js/run-log.js`);
    const { RunLogBuilder, RunLog } = mod;

    // ============== 场景 1：基本事件累积 ==============
    console.log("[1] 基本事件累积");
    const builder = new RunLogBuilder();
    builder.processEvent({ type: "start_turn", turnId: "t1" });
    builder.processEvent({ type: "thinking_start", turnId: "t1", thinkingId: "th1" });
    builder.processEvent({ type: "thinking_update", turnId: "t1", thinkingId: "th1", message: "分析中" });
    builder.processEvent({ type: "thinking_complete", turnId: "t1", thinkingId: "th1" });
    builder.processEvent({ type: "text_delta", turnId: "t1", delta: "你好" });
    builder.processEvent({ type: "tool_call_start", turnId: "t1", toolId: "tc1", toolName: "read_file", summary: "Foo.kt" });
    builder.processEvent({ type: "tool_call_complete", turnId: "t1", toolId: "tc1", success: true, result: "content" });
    builder.processEvent({ type: "end_turn", turnId: "t1" });

    const runLog = builder.getRunLog("t1");
    assert(runLog instanceof RunLog, "应返回 RunLog 实例");
    assert(runLog.status === "completed", "end_turn 后状态应为 completed");
    assert(runLog.stages.length === 3, `应有 3 个 stage（thinking/text/tool_call），实际 ${runLog.stages.length}`);
    assert(runLog.textSegments.length === 1, "应有 1 个 text segment");
    assert(runLog.textSegments[0].content === "你好", "文本内容应为 你好");
    assert(runLog.toolCalls.length === 1, "应有 1 个 tool call");
    assert(runLog.toolCalls[0].name === "read_file", "工具名应为 read_file");
    assert(runLog.toolCalls[0].status === "completed", "工具应已完成");

    // ============== 场景 2：聚合 API ==============
    console.log("\n[2] 聚合 API");
    const summary = runLog.getSummary();
    assert(summary.turnId === "t1", "summary.turnId 应为 t1");
    assert(summary.status === "completed", "summary.status 应为 completed");
    assert(summary.toolCount === 1, "summary.toolCount 应为 1");
    assert(summary.completedTools === 1, "summary.completedTools 应为 1");
    assert(summary.currentStage != null, "summary.currentStage 不应为空");
    assert(summary.currentStage.type === "tool_call", "最后一个 running/completed stage 类型应为 tool_call");

    // ============== 场景 3：序列化/反序列化 ==============
    console.log("\n[3] 序列化/反序列化");
    const json = runLog.toJSON();
    const restored = RunLog.fromJSON(json);
    assert(restored.turnId === runLog.turnId, "反序列化后 turnId 应一致");
    assert(restored.stages.length === runLog.stages.length, "反序列化后 stages 数量应一致");
    assert(restored.toolCalls.length === runLog.toolCalls.length, "反序列化后 toolCalls 数量应一致");
    assert(restored.textSegments[0].content === "你好", "反序列化后文本内容应一致");

    // ============== 场景 4：Plan 事件 ==============
    console.log("\n[4] Plan 事件");
    const builder2 = new RunLogBuilder();
    builder2.processEvent({ type: "start_turn", turnId: "t2" });
    builder2.processEvent({
        type: "plan_generated",
        turnId: "t2",
        planId: "p1",
        description: "重构",
        steps: [
            { id: "s1", description: "分析", dependsOn: [] },
            { id: "s2", description: "修改", dependsOn: ["s1"] },
        ],
    });
    builder2.processEvent({ type: "plan_approved", turnId: "t2", planId: "p1" });
    builder2.processEvent({ type: "end_turn", turnId: "t2" });

    const runLog2 = builder2.getRunLog("t2");
    assert(runLog2.plan != null, "应有 plan");
    assert(runLog2.plan.overall === "approved", "plan 应被 approved");
    assert(runLog2.plan.steps.length === 2, "plan 应有 2 步");

    const tree = runLog2.plan.buildTree();
    assert(tree.length === 1, "依赖树应只有一个根");
    assert(tree[0].children.length === 1, "根应有一个子节点");
    assert(tree[0].children[0].id === "s2", "子节点应为 s2");

    // ============== 场景 5：错误处理 ==============
    console.log("\n[5] 错误处理");
    const builder3 = new RunLogBuilder();
    builder3.processEvent({ type: "start_turn", turnId: "t3" });
    builder3.processEvent({ type: "tool_call_start", turnId: "t3", toolId: "tc2", toolName: "bash" });
    builder3.processEvent({ type: "tool_call_error", turnId: "t3", toolId: "tc2", error: "命令失败" });
    builder3.processEvent({ type: "error", turnId: "t3", message: "服务异常" });

    const runLog3 = builder3.getRunLog("t3");
    assert(runLog3.status === "failed", "收到 error 事件后 runLog 状态应为 failed");
    assert(runLog3.toolCalls[0].status === "failed", "tool call 应失败");
    assert(runLog3.toolCalls[0].error === "命令失败", "tool call error 应保留");

    // ============== 场景 6：AG-UI 别名兼容 ==============
    console.log("\n[6] AG-UI 别名兼容");
    const builder4 = new RunLogBuilder();
    builder4.processEvent({ type: "RUN_START", turnId: "t4" });
    builder4.processEvent({ type: "REASONING_START", turnId: "t4", thinkingId: "th4" });
    builder4.processEvent({ type: "REASONING_MESSAGE_CONTENT", turnId: "t4", thinkingId: "th4", message: "reasoning" });
    builder4.processEvent({ type: "REASONING_END", turnId: "t4", thinkingId: "th4" });
    builder4.processEvent({ type: "TEXT_MESSAGE_CONTENT", turnId: "t4", delta: "hi" });
    builder4.processEvent({ type: "RUN_END", turnId: "t4" });

    const runLog4 = builder4.getRunLog("t4");
    assert(runLog4.stages.some((s) => s.type === "thinking"), "AG-UI REASONING 事件应生成 thinking stage");
    assert(runLog4.textSegments[0].content === "hi", "AG-UI TEXT_MESSAGE_CONTENT 应累积文本");
    assert(runLog4.status === "completed", "AG-UI RUN_END 应完成 run");

    // ============== 场景 7：从历史消息重建 ==============
    console.log("\n[7] 从历史消息重建");
    const builder5 = new RunLogBuilder();
    builder5.rebuildFromMessages([
        {
            role: "assistant",
            turnId: "h1",
            content: "历史回答",
            thinking: "历史思考",
            thinkingDurationMs: 1200,
            toolCalls: [
                { id: "htc1", name: "read_file", summary: "A.kt", success: true, result: "data" },
            ],
        },
    ]);
    const runLog5 = builder5.getRunLog("h1");
    assert(runLog5 != null, "应能从历史消息重建 RunLog");
    assert(runLog5.textSegments[0].content === "历史回答", "历史文本应被重建");
    assert(runLog5.toolCalls.length === 1, "历史 tool call 应被重建");
    assert(runLog5.status === "completed", "重建后状态应为 completed");

    // ============== 总结 ==============
    console.log(`\n=== 测试结果 ===`);
    console.log(`通过: ${passed}`);
    console.log(`失败: ${failed}`);
    return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
