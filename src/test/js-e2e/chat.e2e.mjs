/**
 * Chat UI E2E Test (JSDOM + 真实 webui 资源)
 * ==========================================
 *
 * 策略:
 *   1. JSDOM 加载真实 index.html
 *   2. 用 mock bridge 替换 webui/js/bridge.js(临时)
 *   3. 通过 file:// 协议 import 真实 chat.js
 *   4. 模拟真实 LLM 事件流, 验证 DOM 渲染
 *
 * 关键回归测试点:
 *   - 工具调用 **inline 插入**到产生它的那句话之后(不是堆底!)
 *   - 思考默认折叠
 *   - 工具完成后默认折叠
 *   - 错误不丢失内容
 *   - Plan 卡独立渲染
 */

import { JSDOM } from "jsdom";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { execSync } from "node:child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-webui";
const E2E_BRIDGE = `${E2E_ROOT}/webui/js/bridge.js`;

// ============== 准备隔离的 ESM 环境 ==============
//
// 关键: 真实 webui/js 是非 ESM(没有 package.json), 直接 file:// import 会用 CJS 解析 chat.js
//        然后 "import statement outside a module" 失败。
// 解决: 物理复制整个 webui 到 /tmp/e2e-webui, 在 /tmp/e2e-webui 放一个 {"type":"module"} 的 package.json。
//        测试结束后自动清掉 /tmp 副本(可以保留供调试)。
//
// 我们只修改 /tmp 副本的 bridge.js 为 mock, **绝不动 真实 webui 目录**。
//
// 同时: 一些 module 顶层副作用(state.js 调 localStorage / bridge.js 调 window 等)需要 polyfill,
//       在测试启动时统一注入到 globalThis。

const MOCK_BRIDGE_CODE = `
// E2E mock bridge — 隔离副本, 不影响真实 webui
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
  // 1. 复制真实 webui 到 /tmp/e2e-webui/webui
  // 2. 在 /tmp/e2e-webui 放 {"type":"module"}
  // 3. 替换 /tmp/e2e-webui/webui/js/bridge.js 为 mock
  execSync(`rm -rf ${E2E_ROOT} && mkdir -p ${E2E_ROOT}`, { stdio: "ignore" });
  execSync(
    `rsync -a --delete ${WEBUI}/ ${E2E_ROOT}/webui/ && ` +
    `echo '{"type":"module"}' > ${E2E_ROOT}/package.json`,
    { stdio: "ignore" }
  );
  writeFileSync(E2E_BRIDGE, MOCK_BRIDGE_CODE);
}

// ============== Stub 全局库 (marked / hljs) ==============

function stubLibraries(window) {
  window.marked = {
    parse: (text) => {
      let html = String(text)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;");
      html = html.replace(/\*\*(.+?)\*\*/g, "<strong>$1</strong>");
      html = html.replace(/`([^`]+)`/g, "<code>$1</code>");
      html = html.replace(/```\\w*\\n([\\s\\S]+?)\\n```/g, "<pre><code>$1</code></pre>");
      html = html.replace(/\\n/g, "<br>");
      return html;
    },
  };
  window.hljs = { highlightElement: () => {} };
}

// ============== 主测试 ==============

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
  console.log("\n=== CodeSage Chat UI E2E Test (JSDOM) ===\n");
  prepareE2E();

  // 1. 加载真实 index.html
    const html = readFileSync(join(WEBUI, "index.html"), "utf-8");
    const htmlClean = html.replace(/<script type="module" src="js\/main\.js"><\/script>/, "");

    const dom = new JSDOM(htmlClean, {
      url: `file://${WEBUI}/index.html`,
      runScripts: "outside-only",
      pretendToBeVisual: true,
    });
    const w = dom.window;

    stubLibraries(w);

    // 2. 全局 mock bridge sink
    const sink = (payload) => {
      w.__e2e_sent = w.__e2e_sent || [];
      w.__e2e_sent.push(payload);
    };
    w.__e2e_bridge_sink = sink;
    // 关键: 真实 chat.js 调 `import { bridge } from "../bridge.js"`,
    // mock bridge.send() 检查的是 module 求值时的 globalThis(node 全局),
    // 不是 jsdom window。所以 sink 必须同时挂到 globalThis
    globalThis.__e2e_bridge_sink = sink;
    globalThis.__e2e_sent = w.__e2e_sent;

    // 3. 在 node 全局注入 JSDOM 的 window/document/localStorage
    //    真实 chat.js 顶层 import chain 会触发 state.js / toast.js 等的 module 顶层副作用,
    //    这些副作用要 `window` / `document` / `localStorage`, 而 module 是在 node 上下文求值的。
    //    解决: 把 JSDOM window 上的关键 API 提升到 globalThis, 让 module 求值时能找到。
    globalThis.window = w;
    globalThis.document = w.document;
    // JSDOM 在 file:// 协议下 localStorage 可能抛 SecurityError(opaque origin),
    // 用一个内存 stub 替代 — 测试用不到持久化, 只需 getItem/setItem 不崩
    const _lsStore = new Map();
    globalThis.localStorage = {
        getItem: (k) => _lsStore.has(k) ? _lsStore.get(k) : null,
        setItem: (k, v) => _lsStore.set(k, String(v)),
        removeItem: (k) => _lsStore.delete(k),
        clear: () => _lsStore.clear(),
        key: (i) => Array.from(_lsStore.keys())[i] || null,
        get length() { return _lsStore.size; },
    };
    globalThis.navigator = w.navigator;
    globalThis.HTMLElement = w.HTMLElement;
    globalThis.Node = w.Node;
    globalThis.Element = w.Element;
    globalThis.Event = w.Event;
    globalThis.CustomEvent = w.CustomEvent;
    globalThis.requestAnimationFrame = (cb) => setTimeout(cb, 16);
    globalThis.cancelAnimationFrame = (id) => clearTimeout(id);
    globalThis.fetch = () => Promise.reject(new Error("fetch not available in E2E"));
    globalThis.marked = w.marked;
    globalThis.hljs = w.hljs;

    // 4. import chat.js
    // 不需要 chdir — 我们用绝对路径 import
    const chatModule = await import("file:///tmp/e2e-webui/webui/js/views/chat.js");
    const chat = chatModule.chat;
    chat.init();

    // 5. 把"后端事件"路由到 chat._onXxx
    //    模拟 main.js handleBridgeMessage 的 switch 逻辑
    function dispatchEvent(msg) {
      const type = msg.type;
      const turnId = msg.turnId;
      switch (type) {
        case "start_turn": chat._startAITurn(); break;
        case "end_turn": chat._endAITurn(turnId); break;
        case "text_delta": chat._onTextDelta(turnId, msg.delta || ""); break;
        case "thinking_start": chat._onThinkingStart(turnId); break;
        case "thinking_update": chat._onThinkingUpdate(turnId, msg.message || ""); break;
        case "thinking_complete": chat._onThinkingComplete(turnId, msg.elapsedMs || 0); break;
        case "tool_call_start": chat._onToolCallStart(turnId, msg.toolId, msg.toolName, msg.summary, msg.arguments, msg.icon); break;
        case "tool_call_delta": chat._onToolCallDelta(turnId, msg.toolId, msg.delta); break;
        case "tool_call_complete": chat._onToolCallComplete(turnId, msg.toolId, msg.success, msg.result); break;
        case "tool_call_error": chat._onToolCallError(turnId, msg.toolId, msg.error); break;
        case "plan_generated": chat._onPlanGenerated(turnId, msg); break;
        case "plan_approved": chat._onPlanApproved(turnId, msg); break;
        case "plan_rejected": chat._onPlanRejected(turnId, msg); break;
        case "plan_modified": chat._onPlanModified(turnId, msg); break;
        case "context_compressed": chat._onContextCompressed(turnId, msg); break;
        case "error": chat._onError(turnId, msg.message || ""); break;
        case "sessions_updated": chat.setSessions(msg.sessions || []); break;
        case "session_switched": chat.setCurrentSession(msg.sessionId, msg.sessionName); break;
        case "clear_chat":
          // 模拟 main.js 的 clear_chat 路由(包含输入区重置)
          chat.clear();
          chat._resetInput();
          break;
        case "file_references_added":
          // 模拟 main.js 的 file_references_added 路由
          chat._onFileReferencesAdded(msg.references || []);
          break;
        case "input_attachments":
          // 模拟 main.js 的 input_attachments 路由(图片)
          chat.setInputAttachments(msg.attachments || []);
          break;
        default: console.warn(`[e2e] unhandled: ${type}`);
      }
    }

    // ============== 场景 1: 用户消息 ==============
    console.log("[1] 用户发送消息");
    chat.addUserMessage("分析 users 表");
    const userBubble = w.document.querySelector(".user-bubble");
    assert(userBubble !== null, "用户消息气泡应存在");
    assert(userBubble && userBubble.textContent.includes("分析 users 表"), "气泡文本应包含用户消息");
    assert(
      userBubble && userBubble.closest(".message-user") !== null,
      "气泡应在 .message-user 容器内 (右对齐布局)"
    );

    // ============== 场景 2: 思考流 ==============
    console.log("\n[2] LLM 思考中");
    dispatchEvent({ type: "start_turn" });
    const tid = lastTurnId(chat);
    dispatchEvent({ type: "thinking_start", turnId: tid });
    dispatchEvent({ type: "thinking_update", turnId: tid, message: "我先看下表结构..." });
    dispatchEvent({ type: "thinking_update", turnId: tid, message: "看完了" });
    dispatchEvent({ type: "thinking_complete", turnId: tid, elapsedMs: 1500 });

    const thinkingCard = w.document.querySelector(".thinking-card");
    assert(thinkingCard !== null, "思考卡应存在");
    const thinkingBody = thinkingCard && thinkingCard.querySelector(".thinking-body");
    assert(
      thinkingBody && !thinkingBody.classList.contains("open"),
      "思考完成后应默认折叠 (body 不含 .open class)"
    );

    // ============== 场景 3: 工具 inline 插入 (关键回归) ==============
    console.log("\n[3] 文本 + 工具 + 文本 + 工具 + 文本 (验证 inline 顺序)");
    dispatchEvent({ type: "text_delta", turnId: tid, delta: "我来" });

    dispatchEvent({
      type: "tool_call_start", turnId: tid, toolId: "tc1", toolName: "read_file",
      summary: "users.sql", arguments: '{"path":"users.sql"}'
    });
    dispatchEvent({
      type: "tool_call_complete", turnId: tid, toolId: "tc1", success: true,
      result: JSON.stringify({ success: true, data: { content: "CREATE TABLE users (id, name, email)" } })
    });

    dispatchEvent({ type: "text_delta", turnId: tid, delta: "读完了，现在改 schema。" });

    dispatchEvent({
      type: "tool_call_start", turnId: tid, toolId: "tc2", toolName: "run_sql",
      summary: "ALTER TABLE", arguments: '{"sql":"ALTER TABLE users ADD COLUMN email_verified BOOLEAN"}'
    });
    dispatchEvent({
      type: "tool_call_complete", turnId: tid, toolId: "tc2", success: true,
      result: JSON.stringify({ success: true, data: { stdout: "OK", exit_code: 0 } })
    });

    dispatchEvent({ type: "text_delta", turnId: tid, delta: "已添加 email_verified 字段。" });

    // 关键断言: DOM 顺序
    const tools = w.document.querySelectorAll(".tool-card");
    assert(tools.length === 2, `应渲染 2 个 tool card (实际 ${tools.length})`);

    const content = w.document.querySelector(".assistant-content");
    const children = Array.from(content.children);
    const idxText1 = children.findIndex(c => c.classList.contains("text-stream-segment") && c.textContent.includes("我来"));
    const idxTool1 = children.findIndex(c => c.classList.contains("tool-card") && c.querySelector(".tool-name")?.textContent === "read_file");
    const idxText2 = children.findIndex(c => c.classList.contains("text-stream-segment") && c.textContent.includes("读完了"));
    const idxTool2 = children.findIndex(c => c.classList.contains("tool-card") && c.querySelector(".tool-name")?.textContent === "run_sql");
    const idxText3 = children.findIndex(c => c.classList.contains("text-stream-segment") && c.textContent.includes("已添加"));

    console.log(`  DOM 顺序: text1=${idxText1}, tool1=${idxTool1}, text2=${idxText2}, tool2=${idxTool2}, text3=${idxText3}`);
    assert(idxText1 >= 0, "text1 应在 DOM 中");
    assert(idxTool1 > idxText1, `工具1 (${idxTool1}) 必须在 text1 (${idxText1}) 之后 — 关键回归! 修复前: 工具堆在回答最末尾`);
    assert(idxText2 > idxTool1, `text2 (${idxText2}) 必须在工具1 (${idxTool1}) 之后`);
    assert(idxTool2 > idxText2, `工具2 (${idxTool2}) 必须在 text2 (${idxText2}) 之后`);
    assert(idxText3 > idxTool2, `text3 (${idxText3}) 必须在工具2 (${idxTool2}) 之后`);

    // 工具完成后默认折叠
    const tool1Body = tools[0] && tools[0].querySelector(".tool-body");
    assert(tool1Body && !tool1Body.classList.contains("open"), "工具1 完成后应默认折叠");

    // ============== 场景 4: Plan 卡 ==============
    console.log("\n[4] Plan 卡片");
    dispatchEvent({
      type: "plan_generated", turnId: tid, planId: "p1",
      description: "重构用户表",
      steps: [
        { id: "s1", description: "分析现有 schema" },
        { id: "s2", description: "设计新 schema" },
        { id: "s3", description: "写 migration" },
        { id: "s4", description: "更新 DAO" },
      ]
    });
    const planCard = w.document.querySelector(".plan-card");
    assert(planCard !== null, "Plan 卡应存在");
    const planSteps = planCard && planCard.querySelectorAll(".plan-step");
    assert(planSteps && planSteps.length === 4, `plan 应有 4 步 (实际 ${planSteps ? planSteps.length : 0})`);

    // ============== 场景 5: 错误时保留内容 ==============
    console.log("\n[5] 错误时不丢失内容");
    dispatchEvent({ type: "error", turnId: tid, message: "529 服务过载" });
    const inlineAlert = w.document.querySelector(".inline-alert.error");
    assert(inlineAlert !== null, "错误应渲染为 inline alert");
    assert(w.document.querySelectorAll(".tool-card").length === 2, "错误不应清空已渲染的工具卡");
    assert(w.document.querySelector(".plan-card") !== null, "错误不应清空 plan 卡");
    assert(w.document.querySelectorAll(".text-stream-segment").length >= 1, "错误不应清空文本");

    // ============== 场景 6: 用户发送时验证 send_message ==============
    console.log("\n[6] 验证 send_message 契约");
    w.__e2e_sent = [];  // 清空记录
    // 模拟用户在输入框输入并回车
    const ta = w.document.getElementById("input-textarea");
    ta.value = "第二个问题";
    // 直接调 _send,跳过键盘事件
    chat._send("第二个问题");
    const lastSent = w.__e2e_sent[w.__e2e_sent.length - 1];
    assert(lastSent && lastSent.type === "send_message", `bridge.send 应发 send_message, 实际 ${lastSent?.type}`);
    assert(lastSent && lastSent.message === "第二个问题", "消息字段应带 message");
    assert(lastSent && "images" in lastSent, "消息字段应带 images 数组");

    // ============== 场景 7: 头部 + 按钮 → 新会话 ==============
    // 回归 bug:旧版 main.js 没有 case "clear_chat",导致点 + 后主区仍显示老消息,
    // 用户感知"点了没反应"。见 main.js clear_chat 分支。
    console.log("\n[7] 头部 + 按钮(新会话)");
    // 模拟 Kotlin 侧会把当前会话存档后,推送全量列表 + 切到新会话 + clear_chat
    dispatchEvent({
      type: "sessions_updated",
      sessions: [
        { id: "archived", name: "之前的对话", createdAt: 1, lastActivityAt: 2 },
        { id: "new",      name: "",             createdAt: 3, lastActivityAt: 4 },
      ],
    });
    dispatchEvent({ type: "session_switched", sessionId: "new" });
    dispatchEvent({ type: "clear_chat" });
    assert(
      w.document.querySelectorAll(".message").length === 0,
      "clear_chat 后主区应清空所有 message",
    );
    assert(
      w.document.querySelectorAll(".inline-alert").length === 0,
      "clear_chat 后 inline-alert 也应清空",
    );
    const welcome = w.document.getElementById("welcome-state");
    assert(
      welcome && welcome.style.display !== "none",
      "clear_chat 后应显示 welcome 状态",
    );
    const ta2 = w.document.getElementById("input-textarea");
    assert(ta2 && ta2.value === "", `输入框应被清空,实际 "${ta2?.value}"`);
    const active = w.document.querySelector(".sidebar-item.active");
    assert(
      active && active.dataset.csSessionId === "new",
      `sidebar active 应为新会话,实际 ${active?.dataset?.csSessionId}`,
    );

    // 关键回归:之前没有 _resetInput 时,ta2.value 还是 "第二个问题" 残留草稿,
    // 切到新会话却看到旧输入 — 现在必须为空。

    // ============== 场景 8: 回形针按钮 → @file 插入(Cursor 风格) ==============
    // 回归 bug:旧版 attach_file 选完文件后只发 input_attachments,前端渲染一个
    // chip 预览(在输入框上方),但文件内容不会自动进上下文 — 用户感知"点了没反应"。
    // v2.1 改为 file_references_added:把 @相对路径 直接插到 textarea 光标处,
    // 用户继续打字,发送时 FileReferenceResolver 自动读内容注入上下文。
    console.log("\n[8] 回形针按钮 → @file 插入");
    const taFile = w.document.getElementById("input-textarea");
    taFile.value = "请帮我看看 ";
    taFile.setSelectionRange(taFile.value.length, taFile.value.length);

    dispatchEvent({
      type: "file_references_added",
      references: [
        { name: "Foo.kt", path: "/abs/Foo.kt", relativePath: "src/main/kotlin/Foo.kt", size: 1024 },
      ],
    });
    assert(
      taFile.value === "请帮我看看 @src/main/kotlin/Foo.kt ",
      `单个文件 @ 应插到光标后,实际 "${taFile.value}"`,
    );
    assert(
      taFile.selectionStart === taFile.value.length && taFile.selectionEnd === taFile.value.length,
      `光标应停在插入文本末尾,实际 ${taFile.selectionStart}/${taFile.selectionEnd}`,
    );
    assert(
      w.document.activeElement === taFile,
      "插入后 textarea 应自动获得焦点",
    );

    // 多文件:全部插入,中间空格
    taFile.value = "";
    taFile.setSelectionRange(0, 0);
    dispatchEvent({
      type: "file_references_added",
      references: [
        { name: "A.kt", relativePath: "src/A.kt" },
        { name: "B.kt", relativePath: "src/B.kt" },
        { name: "C.kt", relativePath: "src/C.kt" },
      ],
    });
    assert(
      taFile.value === "@src/A.kt @src/B.kt @src/C.kt ",
      `多个文件应空格分隔,实际 "${taFile.value}"`,
    );

    // 关键回归:之前 v2.0 chip 路径下,选完文件后输入框里没东西 — 用户体感"无反馈"。
    // 现在 @ 必须真的出现在 textarea 里,而不是只在预览区。
    assert(
      taFile.value.includes("@src/"),
      "@ 引用应被插入到 textarea (不是 chip 预览区)",
    );
    const chipCount = w.document.querySelectorAll(".input-attachment").length;
    assert(
      chipCount === 0,
      `文件 @ 流程不应渲染 chip 预览 (实际 ${chipCount}) — chip 仅为图片设计`,
    );

    // 兜底:在光标中间插入(不是末尾)
    taFile.value = "前面 后面";
    taFile.setSelectionRange(3, 3);
    dispatchEvent({
      type: "file_references_added",
      references: [{ name: "X.ts", relativePath: "X.ts" }],
    });
    assert(
      taFile.value === "前面 @X.ts 后面",
      `应插入到 selectionStart 处,实际 "${taFile.value}"`,
    );

    // ============== 场景 9: 图片按钮 → 视觉强化 + 反馈 ==============
    // 回归 bug:旧版 attach_image 选完图片后,前端只渲染一个 18x18 的小缩略图,
    // 没有 toast / focus / 焦点变化,用户体感"点了没反应"。
    // v2.1:加大缩略图到 28x28、accent 描边、toast 反馈、焦点回 textarea。
    console.log("\n[9] 图片按钮 → 视觉强化 + 反馈");
    dispatchEvent({
      type: "input_attachments",
      attachments: [
        {
          type: "image",
          name: "screenshot.png",
          data: "data:image/png;base64,iVBORw0KGgo=",
          size: 1024 * 50, // 50KB
        },
      ],
    });
    const imgChip = w.document.querySelector(".input-attachment");
    assert(imgChip !== null, "应渲染图片 chip");
    const thumb = imgChip?.querySelector(".input-attachment-thumb");
    assert(thumb !== null, "图片 chip 应有缩略图");
    // v2.1:缩略图尺寸从 18 → 28(由 input.css .input-attachment-thumb 规则控制,
    // JSDOM 不能完整解析 CSS 变量,所以这里不读 computedStyle,改查 CSS 源码验证)
    const inputCss = readFileSync(WEBUI + "/styles/input.css", "utf-8");
    assert(
      /\.input-attachment-thumb\s*\{[^}]*width:\s*28px/.test(inputCss),
      "input.css 应把 .input-attachment-thumb 缩略图 width 设为 28px",
    );
    const nameEl = imgChip?.querySelector(".input-attachment-name");
    assert(
      nameEl && nameEl.textContent.includes("screenshot.png"),
      `chip 应显示文件名,实际 "${nameEl?.textContent}"`,
    );
    assert(
      nameEl && nameEl.textContent.includes("KB"),
      `chip 应显示文件大小,实际 "${nameEl?.textContent}"`,
    );
    assert(
      w.document.activeElement === w.document.getElementById("input-textarea"),
      "附加图片后 textarea 应自动获得焦点",
    );

    // 关键回归:之前 chip 太小 + 没 toast,用户体感"无反馈"。
    // 现在 chip 至少 28x28 + 显示文件名/大小 + toast,反馈多重可见。

    // 超大图(>8MB)给 too-large 提示,告诉用户可能发不出去
    dispatchEvent({
      type: "input_attachments",
      attachments: [
        {
          type: "image",
          name: "huge.png",
          data: "data:image/png;base64,xx",
          size: 9 * 1024 * 1024, // 9MB
        },
      ],
    });
    const hugeChip = w.document.querySelector(".input-attachment");
    assert(
      hugeChip?.classList.contains("too-large"),
      "超大图(>8MB)chip 应标 too-large 类",
    );
    assert(
      hugeChip?.textContent.includes("可能发不出"),
      `超大图应提示"可能发不出",实际 "${hugeChip?.textContent}"`,
    );

    // ============== 场景 10: 工件面板 — 默认折叠 + X 按钮可关闭 ==============
    // 回归 bug: 旧实现 _initHeader 只给主区头部的 artifacts-toggle-btn 绑了 click,
    // 面板右上角的 X 按钮 (id=artifacts-close-btn) 完全没人监听 — 点 X 无反应。
    // 另: CSS 默认 grid 是 260 1fr 360,首次开 IDE 工件面板默认展开,吃 360px 宽。
    // 修法: init() 默认加 artifacts-collapsed 类 + X 按钮绑 toggleArtifacts。
    console.log("\n[10] 工件面板 — 默认折叠 + X 按钮可关闭");
    assert(
      w.document.getElementById("app-container").classList.contains("artifacts-collapsed"),
      "首次安装 / 打开工具窗口时,工件面板应默认折叠 (节省 360px 宽度)",
    );

    // 模拟用户点 X 按钮 — 应能展开(因为现在是折叠态)
    const closeBtn = w.document.getElementById("artifacts-close-btn");
    assert(closeBtn !== null, "X 按钮 (artifacts-close-btn) 应存在");
    closeBtn.click();
    assert(
      !w.document.getElementById("app-container").classList.contains("artifacts-collapsed"),
      "点 X 后面板应展开(当前是折叠态)",
    );

    // 再点 X — 应能折叠回去(双向)
    closeBtn.click();
    assert(
      w.document.getElementById("app-container").classList.contains("artifacts-collapsed"),
      "再次点 X 后面板应折叠回去",
    );

    // 关键回归: addArtifact() 仍能在面板折叠时自动展开(原有行为不能丢)
    // 这里直接调 chat.addArtifact 模拟后端发 artifact_add 事件
    chat.addArtifact("art-1", "示例.py", "python", "print('hello')");
    assert(
      !w.document.getElementById("app-container").classList.contains("artifacts-collapsed"),
      "addArtifact() 被调用时,即使之前折叠,面板应自动展开",
    );

    // 此时再点 X 应能关闭
    closeBtn.click();
    assert(
      w.document.getElementById("app-container").classList.contains("artifacts-collapsed"),
      "用户主动点 X 应能关掉被 addArtifact 展开的面板",
    );

    // ============== 场景 11: 外部链接拦截 — 点 <a> 不丢聊天页 ==============
    // 回归 bug: 旧实现点对话内容里的 <a href="https://..."> 会让 JCEF webview 自己
    // navigate,直接替换整个聊天页,无法返回 — 聊天状态全部丢失。
    // v2.1: 全局 click 捕获阶段拦截,只放过 http/https/mailto/file 四种 scheme,
    // 其余 (javascript: / data: / #fragment) 不动。拦截后走 bridge.send 让 Kotlin
    // 用 BrowserUtil.browse 走系统默认浏览器。
    console.log("\n[11] 外部链接拦截 — 点 <a> 不丢聊天页");
    w.__e2e_sent = [];

    // 模拟 AI 在回答里输出了一个 markdown 链接,被 marked 渲染为 <a>
    const aiBody = w.document.querySelector(".messages-inner");
    const linkHost = w.document.createElement("div");
    linkHost.className = "text-stream-segment";
    linkHost.innerHTML = `
      <p>看这里 <a href="https://example.com/foo" class="link" data-cs-link="1">示例</a> 了解更多</p>
      <p>邮箱 <a href="mailto:hi@example.com">hi@example.com</a></p>
      <p>项目文件 <a href="file:///tmp/test.kt">/tmp/test.kt</a></p>
      <p>页面内锚点 <a href="#messages-container" class="cs-skip-link">跳过</a> 不应该被拦截</p>
      <p>危险协议 <a href="javascript:alert(1)">js</a> 不应该被拦截</p>
    `;
    aiBody.appendChild(linkHost);

    // 点 https 链接 — 应被拦截,走 bridge.send
    const httpsLink = linkHost.querySelector('a[href^="https://"]');
    httpsLink.click();
    const linkSent = w.__e2e_sent[w.__e2e_sent.length - 1];
    assert(
      linkSent && linkSent.type === "open_external_url",
      `点 https 链接应发 open_external_url,实际 ${linkSent?.type}`,
    );
    assert(
      linkSent && linkSent.url === "https://example.com/foo",
      `应发原始 href,实际 ${linkSent?.url}`,
    );

    // 点 mailto — 同理
    w.__e2e_sent = [];
    linkHost.querySelector('a[href^="mailto:"]').click();
    const mailSent = w.__e2e_sent[w.__e2e_sent.length - 1];
    assert(
      mailSent && mailSent.type === "open_external_url" && mailSent.url === "mailto:hi@example.com",
      `点 mailto 应发 open_external_url,实际 ${JSON.stringify(mailSent)}`,
    );

    // 点 file:// — 同理
    w.__e2e_sent = [];
    linkHost.querySelector('a[href^="file://"]').click();
    const fileSent = w.__e2e_sent[w.__e2e_sent.length - 1];
    assert(
      fileSent && fileSent.type === "open_external_url" && fileSent.url === "file:///tmp/test.kt",
      `点 file:// 应发 open_external_url,实际 ${JSON.stringify(fileSent)}`,
    );

    // 点 #fragment — 不应被拦截(也不应发任何桥消息)
    w.__e2e_sent = [];
    linkHost.querySelector('a[href^="#"]').click();
    assert(
      w.__e2e_sent.length === 0,
      `点 #fragment 不应发任何桥消息,实际 ${w.__e2e_sent.length} 条`,
    );

    // 点 javascript: — 危险协议,我们的拦截器只在白名单里(没 javascript:),
    // 所以应当放过默认行为(浏览器会什么都不做,javascript: 在 jsdom 也无作用)。
    // 关键: 不应被我们主动放行,也不应发桥消息。
    w.__e2e_sent = [];
    linkHost.querySelector('a[href^="javascript:"]').click();
    assert(
      w.__e2e_sent.length === 0,
      `点 javascript: 协议不应被拦截发桥消息,实际 ${w.__e2e_sent.length} 条`,
    );

    // 嵌套 <a><span>x</span></a>: 点 span 应能冒泡到 a,也被拦截
    w.__e2e_sent = [];
    const nested = w.document.createElement("a");
    nested.href = "https://nested.example.com";
    nested.innerHTML = "<span>点这里</span>";
    linkHost.appendChild(nested);
    nested.querySelector("span").click();
    const nestedSent = w.__e2e_sent[w.__e2e_sent.length - 1];
    assert(
      nestedSent && nestedSent.type === "open_external_url" && nestedSent.url === "https://nested.example.com",
      `点 <a> 内部子元素也应被拦截,实际 ${JSON.stringify(nestedSent)}`,
    );

    // ============== 场景 12: 附件按钮 — 反馈链 + 超时保护 ==============
    // 回归 bug: 用户报告"点了附件按钮没反应"。原因有 3:
    //   1. 只 bridge.send 没 toast,用户看不到点击已被处理
    //   2. bridge 未 ready 时 send 排队,也没有错误反馈
    //   3. 选中回调前如果 FileChooser 弹不出来(IDE 窗口遮挡/JCEF 异常),用户无感知
    // 修法: 立刻 toast.info 反馈 + 8s 超时兜底,成功回调时清掉超时。
    console.log("\n[12] 附件按钮 — 即时 toast 反馈 + 8s 超时保护");

    // 场景 8 触发的 attach_file 流程可能还留着 _pendingAttach — 先清掉,避免污染
    chat._clearPendingAttach?.();
    w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());

    const attachBtn = w.document.getElementById("attach-btn");
    const imageBtn = w.document.getElementById("image-btn");
    assert(attachBtn !== null, "应能找到 #attach-btn (回形针按钮)");
    assert(imageBtn !== null, "应能找到 #image-btn (图片按钮)");

    // 把 _pendingAttach 安全地打印出来(timer 是 Node Timeout,会循环引用)
    const dumpPending = () => {
        if (!chat._pendingAttach) return "null";
        return `{type: ${JSON.stringify(chat._pendingAttach.type)}, hasTimer: ${!!chat._pendingAttach.timer}}`;
    };

    // ---- 1) 点回形针 → 桥消息 + toast ----
    w.__e2e_sent = [];
    w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
    attachBtn.click();
    const fileSends = w.__e2e_sent.filter((s) => s && s.type === "attach_file");
    assert(
      fileSends.length === 1,
      `点 attach-btn 应发 1 条 attach_file,实际 ${fileSends.length} 条`,
    );
    let toasts = Array.from(w.document.querySelectorAll(".cs-toast .cs-toast-message"))
      .map((el) => el.textContent);
    assert(
      toasts.some((t) => t.includes("正在打开文件选择器")),
      `点 attach-btn 应立即弹 toast 反馈,实际 toasts=${JSON.stringify(toasts)}`,
    );

    // 关键:_pendingAttach 应被设置(8s 超时启动)
    assert(
      chat._pendingAttach && chat._pendingAttach.type === "attach_file",
      `_pendingAttach 应记录本次类型,实际 ${dumpPending()}`,
    );

    // ---- 2) 点图片 → 桥消息 + toast ----
    w.__e2e_sent = [];
    w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
    imageBtn.click();
    const imgSends = w.__e2e_sent.filter((s) => s && s.type === "attach_image");
    assert(
      imgSends.length === 1,
      `点 image-btn 应发 1 条 attach_image,实际 ${imgSends.length} 条`,
    );
    toasts = Array.from(w.document.querySelectorAll(".cs-toast .cs-toast-message"))
      .map((el) => el.textContent);
    assert(
      toasts.some((t) => t.includes("正在打开图片选择器")),
      `点 image-btn 应立即弹 toast 反馈,实际 toasts=${JSON.stringify(toasts)}`,
    );
    assert(
      chat._pendingAttach && chat._pendingAttach.type === "attach_image",
      `点 image-btn 后 _pendingAttach 应切到 attach_image,实际 ${dumpPending()}`,
    );

    // ---- 3) 文件选中回调 file_references_added 到达 → 超时应清掉 ----
    dispatchEvent({
      type: "file_references_added",
      references: [{ name: "Test.kt", relativePath: "src/Test.kt" }],
    });
    assert(
      chat._pendingAttach === null,
      `file_references_added 回调后 _pendingAttach 应被清掉,实际 ${dumpPending()}`,
    );

    // ---- 4) 图片选中回调 input_attachments 到达 → 超时应清掉 ----
    w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
    imageBtn.click(); // 重新触发一次,_pendingAttach 应被覆盖
    assert(
      chat._pendingAttach && chat._pendingAttach.type === "attach_image",
      `重复点 image-btn 应重启超时,实际 ${dumpPending()}`,
    );
    dispatchEvent({
      type: "input_attachments",
      attachments: [
        {
          type: "image",
          name: "shot.png",
          data: "data:image/png;base64,iVBORw0KGgo=",
          size: 1024,
        },
      ],
    });
    assert(
      chat._pendingAttach === null,
      `input_attachments 回调后 _pendingAttach 应被清掉,实际 ${dumpPending()}`,
    );

    // ---- 5) bridge 未 ready 时: 不发桥消息,只弹 error toast ----
    // mock bridge 在 e2e 隔离副本里 — 我们动态 import 拿引用,临时改 ready 位
    const mockBridgeMod = await import("file:///tmp/e2e-webui/webui/js/bridge.js");
    const mockBridge = mockBridgeMod.bridge;
    w.document.querySelectorAll(".cs-toast").forEach((t) => t.remove());
    w.__e2e_sent = [];
    const _origReady = mockBridge.bridgeReady;
    mockBridge.bridgeReady = false;
    attachBtn.click();
    mockBridge.bridgeReady = _origReady;
    const earlySends = w.__e2e_sent.filter((s) => s && s.type === "attach_file");
    assert(
      earlySends.length === 0,
      `bridge 未 ready 时点 attach-btn 不应发桥消息,实际 ${earlySends.length} 条`,
    );
    toasts = Array.from(w.document.querySelectorAll(".cs-toast .cs-toast-message"))
      .map((el) => el.textContent);
    assert(
      toasts.some((t) => t.includes("插件尚未初始化完成")),
      `bridge 未 ready 时应弹错误 toast,实际 toasts=${JSON.stringify(toasts)}`,
    );
    assert(
      chat._pendingAttach === null,
      `bridge 未 ready 时不应启动超时,实际 ${dumpPending()}`,
    );

    // ============== 场景 13: AI 回答段落间距 — 紧凑好读 ==============
    // 回归 bug: 用户报告"ai 回答的段落间隔很大,不利于阅读"。
    // 历史:  12px(v1) → 6px(v2.0) → 3px(v2.2)
    // v2.2: 3px 接近 GitHub Markdown 渲染的"两个空行≈一个空行"紧凑感。
    //       标题上间距同步收到 8/6/5/4(对应 h1/h2/h3/h4)。
    // 锁住这两个值,以后调样式不至于再让长回答"散"。
    console.log("\n[13] AI 回答段落间距 — 紧凑 3px + 标题上间距 8/6/5/4");
    const mdCss = readFileSync(WEBUI + "/styles/markdown.css", "utf-8");
    const chatCss = readFileSync(WEBUI + "/styles/chat.css", "utf-8");

    // 1) 默认块间距 = 3px
    assert(
      /:\s*where\([^)]*\)\s*>\s*\*\s*\+\s*\*\s*\{[^}]*margin-top:\s*3px/.test(mdCss),
      "markdown.css 默认块间距应为 3px (之前 v2.0 是 6px,用户反馈还太大)",
    );

    // 2) h1/h2/h3/h4 上间距 = 8/6/5/4
    const h1 = (mdCss.match(/>\s*h1\s*\{[^}]*margin-top:\s*(\d+)px/) || [])[1];
    const h2 = (mdCss.match(/>\s*h2\s*\{[^}]*margin-top:\s*(\d+)px/) || [])[1];
    const h3 = (mdCss.match(/>\s*h3\s*\{[^}]*margin-top:\s*(\d+)px/) || [])[1];
    const h4 = (mdCss.match(/>\s*h4\s*\{[^}]*margin-top:\s*(\d+)px/) || [])[1];
    assert(h1 === "8", `h1 上间距应为 8px,实际 ${h1}px`);
    assert(h2 === "6", `h2 上间距应为 6px,实际 ${h2}px`);
    assert(h3 === "5", `h3 上间距应为 5px,实际 ${h3}px`);
    assert(h4 === "4", `h4 上间距应为 4px,实际 ${h4}px`);

    // 3) 同一 turn 内被工具截断的流式段落间距 = 3px(跟 markdown.css 同步)
    const segMatch = (chatCss.match(/\.text-stream-segment\s*\+\s*\.text-stream-segment\s*\{[^}]*margin-top:\s*(\d+)px/) || [])[1];
    assert(segMatch === "3", `text-stream-segment 间距应为 3px,实际 ${segMatch}px`);

    // 4) 实际渲染: 两个 <p> 之间的 getComputedStyle.marginTop 应该是 3px
    const pContainer = w.document.createElement("div");
    pContainer.className = "assistant-content";
    pContainer.innerHTML = "<p>p1</p><p>p2</p><h2>title</h2><p>p3</p>";
    w.document.body.appendChild(pContainer);
    const p2 = pContainer.children[1];
    const title = pContainer.children[2];
    const p3 = pContainer.children[3];
    // JSDOM 不解析 :where(...),但 chat.js 不会走这条; 真实浏览器 :where 不影响选择器匹配,
    // 关键: * + * { margin-top } 应被应用。注意我们的 CSS 是用 :where(.assistant-content, ...) > * + *,
    // JSDOM 不支持 :where(:is),所以跳过运行时检查,只用 CSS 源文本断言。
    // 注释掉运行时检查避免在 JSDOM 假阴性:
    //   assert(w.getComputedStyle(p2).marginTop === "3px", ...);
    pContainer.remove();

    // ============== 总结 ==============
    console.log(`\n=== 测试结果 ===`);
    console.log(`通过: ${passed}`);
    console.log(`失败: ${failed}`);
    console.log(`\n=== 渲染统计 ===`);
    console.log(`  1 用户消息气泡`);
    console.log(`  1 思考卡 (默认折叠为 chip)`);
    console.log(`  2 工具卡 (inline 插入时间线, 完成后折叠)`);
    console.log(`  1 plan 卡 (4 步骤)`);
    console.log(`  3 text-stream-segment`);
    console.log(`  1 错误 inline-alert (保留已有内容)`);
    console.log(`  1 send_message 消息已通过桥发出`);

    return failed === 0;
  }
  // 真实 webui 目录全程未被修改, /tmp 副本保留供调试

function lastTurnId(chat) {
  let id = null;
  for (const [k] of chat.turns) id = k;
  return id;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);  // 真实 webui 未被修改, 无需清理
