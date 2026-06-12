/**
 * Structured Thinking E2E 测试
 * =============================
 *
 * 验证 cs-thinking-v2.js 的解析器能正确提取结构化阶段。
 */

import { JSDOM } from "jsdom";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-thinking";

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
    console.log("\n=== CodeSage Structured Thinking E2E Test ===\n");
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

    const mod = await import(`file://${E2E_ROOT}/webui/js/components/cs-thinking-v2.js`);
    const { parseThinkingSections, StructuredThinking } = mod;

    // ============== 场景 1：结构化 heading 解析 ==============
    console.log("[1] 结构化 heading 解析");
    const text1 = `<think>
## 目标理解
需要实现用户登录功能。

## 分析
检查现有 auth 模块。

## 结论
使用 JWT。
</think>`;
    const sections1 = parseThinkingSections(text1);
    assert(sections1.length === 3, `应解析出 3 个 section，实际 ${sections1.length}`);
    assert(sections1[0].key === "goal", "第一段 key 应为 goal");
    assert(sections1[0].title === "目标理解", "第一段 title 应为 目标理解");
    assert(sections1[2].key === "conclusion", "最后一段 key 应为 conclusion");

    // ============== 场景 2：兜底关键词解析 ==============
    console.log("\n[2] 兜底关键词解析");
    const text2 = `先看看需求。\n分析：现有接口不满足并发。\n决定：引入缓存。\n注意：需要加锁。`;
    const sections2 = parseThinkingSections(text2);
    assert(sections2.length >= 2, "应至少解析出 2 个 section");
    assert(sections2.some((s) => s.key === "analysis"), "应识别出 analysis 段");
    assert(sections2.some((s) => s.key === "decision"), "应识别出 decision 段");
    assert(sections2.some((s) => s.key === "note"), "应识别出 note 段");

    // ============== 场景 3：空内容 ==============
    console.log("\n[3] 空内容");
    assert(parseThinkingSections("").length === 0, "空字符串应返回空数组");
    assert(parseThinkingSections(null).length === 0, "null 应返回空数组");

    // ============== 场景 4：DOM 渲染 ==============
    console.log("\n[4] DOM 渲染");
    const thinking = new StructuredThinking();
    thinking.appendContent("## 目标\n修复 bug\n\n## 结论\n完成");
    thinking.complete(1500);
    const el = thinking.el;
    assert(el.classList.contains("thinking-card"), "应包含 thinking-card 类");
    assert(el.querySelector(".thinking-header") != null, "应渲染 header");
    assert(el.querySelector(".thinking-body") != null, "应渲染 body");
    assert(el.querySelector('[data-section-key="goal"]') != null, "应渲染 goal section");
    assert(el.querySelector('[data-section-key="conclusion"]') != null, "应渲染 conclusion section");

    // 模式切换
    thinking.setMode("raw");
    assert(thinking.mode === "raw", "setMode 应切换模式");
    assert(el.querySelector(".thinking-raw") != null, "raw 模式应渲染 thinking-raw");

    // ============== 总结 ==============
    console.log(`\n=== 测试结果 ===`);
    console.log(`通过: ${passed}`);
    console.log(`失败: ${failed}`);
    return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
