// 复刻 chat.e2e.mjs 的基础设施,单独测 settings.js
import { JSDOM } from "jsdom";
import { readFileSync, writeFileSync } from "fs";
import { execSync } from "child_process";

const WEBUI = "/Users/leo/Projects/CodeSage/src/main/resources/webui";
const E2E_ROOT = "/tmp/e2e-settings";
const E2E_BRIDGE = `${E2E_ROOT}/webui/js/bridge.js`;

const MOCK_BRIDGE_CODE = `
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
  execSync(`rm -rf ${E2E_ROOT} && mkdir -p ${E2E_ROOT}`, { stdio: "ignore" });
  execSync(
    `rsync -a --delete ${WEBUI}/ ${E2E_ROOT}/webui/ && ` +
    `echo '{"type":"module"}' > ${E2E_ROOT}/package.json`,
    { stdio: "ignore" }
  );
  writeFileSync(E2E_BRIDGE, MOCK_BRIDGE_CODE);
}

let passed = 0;
let failed = 0;
function assert(cond, msg) {
  if (cond) { passed++; console.log(`  ✓ ${msg}`); }
  else { failed++; console.error(`  ✗ ${msg}`); }
}

async function runE2E() {
  console.log("\n=== CodeSage Settings UI E2E Test (JSDOM) ===\n");

  prepareE2E();

  // 加载所有样式 + 主 HTML
  const html = readFileSync(WEBUI + "/index.html", "utf-8")
    .replace(/<script type="module" src="js\/main\.js"><\/script>/, "");
  const dom = new JSDOM(html, {
    url: `file://${WEBUI}/index.html`,
    runScripts: "outside-only",
    pretendToBeVisual: true,
  });
  const w = dom.window;

  // 桥接 sink
  const sink = (p) => { (w.__e2e_sent = w.__e2e_sent || []).push(p); };
  w.__e2e_bridge_sink = sink;
  globalThis.__e2e_bridge_sink = sink;
  globalThis.__e2e_sent = w.__e2e_sent;

  // 提升 JSDOM window 到 globalThis
  globalThis.window = w;
  globalThis.document = w.document;
  const _ls = new Map();
  globalThis.localStorage = {
    getItem: (k) => _ls.has(k) ? _ls.get(k) : null,
    setItem: (k, v) => _ls.set(k, String(v)),
    removeItem: (k) => _ls.delete(k),
    clear: () => _ls.clear(),
    key: (i) => Array.from(_ls.keys())[i] || null,
    get length() { return _ls.size; },
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
  globalThis.marked = { parse: (s) => String(s) };
  globalThis.hljs = { highlightElement: () => {} };

  // 加载 settings.js
  const settingsMod = await import("file:///tmp/e2e-settings/webui/js/views/settings.js");
  const view = settingsMod.settings;

  // 注入一个 settings 容器,模拟主页面挂载
  const root = w.document.createElement("div");
  root.className = "cs-settings-root";
  root.innerHTML = `<div class="cs-settings-shell">
    <div class="cs-settings-sidebar"><ul class="cs-settings-nav"></ul></div>
    <div class="cs-settings-main" data-cs-role="main"></div>
  </div>`;
  w.document.body.appendChild(root);
  view.container = root;
  view.currentGroup = "models";
  view.data = {
    providers: [
      {
        id: "p1",
        name: "MiniMax",
        type: "minimax",
        baseUrl: "https://api.minimaxi.com",
        enabled: true,
        apiKeyRef: "keychain:p1",
        models: [
          { id: "MiniMax-M2.7", label: "MiniMax-M2.7" },
          { id: "MiniMax-M2.7-lightning", label: "Lightning" },
          { id: "MiniMax-Text-01", label: "Text-01" },
          { id: "abab-6.5s-chat", label: "abab" },
        ],
      },
      {
        id: "p2",
        name: "OpenAI",
        type: "openai",
        baseUrl: "https://api.openai.com",
        enabled: false,
        apiKeyRef: null,
        models: [],
      },
    ],
    defaults: { providerId: "p1", model: "MiniMax-M2.7" },
    agent: { enablePlanning: true, enableStreaming: true },
    ui: { theme: "auto", fontSize: 14, animationSpeed: 1, showThinking: true, compactMode: false },
  };
  // 直接调 GROUP_RENDERERS.models
  // 不行 — view.GROUP_RENDERERS 不暴露。改用 _renderMain 然后访问 data-cs-role="main"
  view._renderMain();
  const main = root.querySelector('[data-cs-role="main"]');

  // ============== 场景 1: Provider 卡片渲染 ==============
  console.log("[1] Provider 卡片渲染");
  const cards = main.querySelectorAll(".cs-provider-card");
  assert(cards.length === 2, `应有 2 张 provider 卡片,实际 ${cards.length}`);

  const card1 = cards[0];
  const title = card1.querySelector(".cs-provider-card-title strong");
  assert(title?.textContent === "MiniMax", `卡片 1 标题应为 "MiniMax",实际 "${title?.textContent}"`);

  const typeBadge = card1.querySelector(".cs-provider-card-type");
  assert(typeBadge?.textContent === "MiniMax", `类型 badge 应显示中文名,实际 "${typeBadge?.textContent}"`);

  // dot 状态
  const dot = card1.querySelector(".cs-provider-card-dot");
  assert(dot?.classList.contains("enabled"), "p1 enabled → dot 应有 enabled 类");
  const card2dot = cards[1].querySelector(".cs-provider-card-dot");
  assert(card2dot?.classList.contains("disabled"), "p2 disabled → dot 应有 disabled 类");

  // ============== 场景 2: 模型 chip 列表 ==============
  console.log("\n[2] 模型 chip 列表");
  const chips = card1.querySelectorAll(".cs-model-chip");
  assert(chips.length === 3, `p1 有 4 个模型,chip 列表应只显示前 3 个,实际 ${chips.length}`);
  assert(chips[0]?.textContent === "MiniMax-M2.7", `第一个 chip 应是 MiniMax-M2.7,实际 "${chips[0]?.textContent}"`);

  const more = card1.querySelector(".cs-model-chip-more");
  assert(more?.textContent === "+1", `剩余 1 个应显示 +1,实际 "${more?.textContent}"`);

  // p2 没模型
  const card2chips = cards[1].querySelectorAll(".cs-model-chip");
  assert(card2chips.length === 0, `p2 无模型,chip 应为 0,实际 ${card2chips.length}`);
  // 模型行的"未配置"提示 — 跟 API Key 行的"未设置"区分开,用 model row 选择器
  const modelRow = cards[1].querySelectorAll(".cs-provider-card-row")[2];
  const modelHint = modelRow?.querySelector(".cs-form-hint");
  assert(modelHint?.textContent === "未配置", `p2 模型区域应显示"未配置",实际 "${modelHint?.textContent}"`);

  // ============== 场景 3: 操作按钮存在 + 事件绑定 ==============
  console.log("\n[3] 操作按钮存在 + 事件绑定");
  const toggleBtn = card1.querySelector('[data-cs-action="toggle-provider"]');
  const editBtn = card1.querySelector('[data-cs-action="edit-provider"]');
  const removeBtn = card1.querySelector('[data-cs-action="remove-provider"]');
  const eyeBtn = card1.querySelector('[data-cs-action="toggle-api-key"]');
  assert(toggleBtn && toggleBtn.dataset.id === "p1", "应能找到 p1 的启用/禁用按钮");
  assert(editBtn && editBtn.dataset.id === "p1", "应能找到 p1 的编辑按钮");
  assert(removeBtn && removeBtn.dataset.id === "p1", "应能找到 p1 的删除按钮");
  assert(eyeBtn, "应能找到 API Key 眼睛按钮");

  // 删除按钮应带 danger 视觉提示
  assert(removeBtn.classList.contains("danger"), "删除按钮应带 .cs-icon-btn-tiny.danger 类");

  // 眼睛按钮:点一下应切换显示(value 用 hidden 属性)
  const masked = card1.querySelector('[data-cs-role="api-key-masked"]');
  const value = card1.querySelector('[data-cs-role="api-key-value"]');
  // 初始:masked 显示, value 隐藏 (hidden 属性)
  assert(!value.hasAttribute("hidden") === false || value.hidden === true,
    `初始状态:value 应隐藏,实际 hidden=${value.hasAttribute("hidden")} hiddenProp=${value.hidden}`);
  eyeBtn.click();
  assert(!value.hasAttribute("hidden"),
    `点眼睛后:API Key 真实值应可见,实际 hidden=${value.hasAttribute("hidden")}`);
  eyeBtn.click();
  assert(value.hasAttribute("hidden"),
    `再点眼睛:应回到 mask 状态,实际 hidden=${value.hasAttribute("hidden")}`);

  // ============== 场景 4: 切换按钮真的改 enabled + 保存 ==============
  console.log("\n[4] 切换 enabled");
  w.__e2e_sent = [];
  toggleBtn.click();
  assert(view.data.providers[0].enabled === false, `点禁用后 p1.enabled 应为 false,实际 ${view.data.providers[0].enabled}`);
  const saved = w.__e2e_sent.filter((s) => s?.type === "settings_update");
  assert(saved.length >= 1, "点禁用应触发 settings_update 桥消息");

  // ============== 场景 5: 添加 Provider 按钮 + 空状态 ==============
  console.log("\n[5] 添加 Provider 按钮 + 空状态");
  const addBtn = main.querySelector('[data-cs-action="add-provider"]');
  assert(addBtn, "应能找到\"添加 Provider\"按钮");
  assert(addBtn.classList.contains("cs-provider-add-btn"), "添加按钮应有 cs-provider-add-btn 类");

  // 模拟无 provider 状态
  view.data.providers = [];
  view._renderMain();
  const empty = main.querySelector(".cs-provider-empty");
  assert(empty !== null, "providers 为空时,应显示 empty state");
  assert(empty?.textContent.includes("还没有配置"), `空状态文案应包含提示,实际 "${empty?.textContent.slice(0, 30)}"`);

  // ============== 场景 6: 编辑 modal 打开 ==============
  console.log("\n[6] 编辑 modal 打开");
  view.data.providers = [
    { id: "p1", name: "MiniMax", type: "minimax", baseUrl: "https://api.minimaxi.com", enabled: true, apiKeyRef: "keychain:p1", models: [{ id: "MiniMax-M2.7" }] },
  ];
  view._renderMain();
  const editBtn2 = main.querySelector('[data-cs-action="edit-provider"]');
  editBtn2.click();
  // 等待 modal 出现
  const modal = w.document.querySelector(".cs-modal");
  assert(modal !== null, "点编辑按钮应打开 modal");

  const formRows = modal.querySelectorAll(".cs-form-row");
  assert(formRows.length === 6, `modal 应有 6 个 form row(名称/类型/URL/Key/模型/测试),实际 ${formRows.length}`);

  const nameInput = modal.querySelector("#pf-name");
  assert(nameInput?.value === "MiniMax", `name 输入框应预填 "MiniMax",实际 "${nameInput?.value}"`);
  const baseUrlInput = modal.querySelector("#pf-baseUrl");
  assert(baseUrlInput?.value === "https://api.minimaxi.com", `baseUrl 应预填`);
  const modelsInput = modal.querySelector("#pf-models");
  assert(modelsInput?.value === "MiniMax-M2.7", `models 应预填(逗号分隔),实际 "${modelsInput?.value}"`);

  // modal footer 应有 取消 + 保存
  const cancelBtn = modal.querySelector('[data-cs-action="cancel"]');
  const saveBtn = modal.querySelector('[data-cs-action="save"]');
  assert(cancelBtn && saveBtn, "modal footer 应有 取消 + 保存 按钮");
  assert(saveBtn.classList.contains("variant-primary"), "保存按钮应是 primary 变体");

  // footer 应有 .cs-modal-footer 类,且不带内联 style
  const footer = modal.querySelector(".cs-modal-footer");
  assert(footer && !footer.getAttribute("style"), "footer 应有 cs-modal-footer 类,且无内联 style");

  // 关闭 modal — 内部 setTimeout 180ms 才真 remove
  cancelBtn.click();
  await new Promise((r) => setTimeout(r, 250));
  assert(w.document.querySelector(".cs-modal") === null, "点取消应关闭 modal");

  // ============== 场景 7: 删除确认 modal ==============
  console.log("\n[7] 删除确认 modal");
  const removeBtn2 = main.querySelector('[data-cs-action="remove-provider"]');
  removeBtn2.click();
  // Modal.close 用 180ms setTimeout — 上一轮 modal 还在 DOM,取最后一个
  await new Promise((r) => setTimeout(r, 50));
  const backdrops = w.document.querySelectorAll(".cs-modal-backdrop");
  const confirmModal = backdrops[backdrops.length - 1];
  assert(confirmModal !== null, "点删除应打开确认 modal");
  assert(confirmModal.textContent.includes("确定要删除"), "确认 modal 应有提示文案");
  assert(confirmModal.textContent.includes("MiniMax"), "确认 modal 应显示 provider 名称");
  const confirmBtn = confirmModal.querySelector('[data-cs-action="confirm"]');
  assert(confirmBtn?.classList.contains("variant-danger"), "确认删除按钮应是 danger 变体");

  // 确认删除后,provider 真的没了
  w.__e2e_sent = [];
  confirmBtn.click();
  await new Promise((r) => setTimeout(r, 250));
  assert(view.data.providers.length === 0, `确认删除后 providers 应为空,实际长度 ${view.data.providers.length}`);

    // ============== 场景 14: 网络设置 — 代理配置 UI ==============
    // v2.2 新增:用户可在设置页 → 网络 分组配置代理。
    //   - 模式:系统默认 / 手动 / 直连
    //   - 手动模式展开:类型、主机、端口、用户名、密码、noProxy 列表、测试连接
    //   - 密码存 PasswordSafe(settings.json 不存)
    console.log("\n[14] 网络设置 — 代理配置 UI");
    // 切到 network 分组
    view.currentGroup = "network";
    view.data.network = {
      proxy: { mode: "system", type: "http", host: "", port: 0, username: "", passwordRef: "", noProxy: [] },
    };
    view._renderMain();
    const main2 = root.querySelector('[data-cs-role="main"]');

    // 1) 渲染 h2 标题 + section 描述
    const h2 = main2.querySelector(".cs-settings-h2");
    assert(h2?.textContent === "网络", `标题应为 "网络",实际 "${h2?.textContent}"`);

    // 2) 模式 select 应存在,3 个选项
    const modeSelect = main2.querySelector('[data-cs-field="network.proxy.mode"]');
    assert(modeSelect, "应能找到代理模式下拉框");
    const modeOptions = Array.from(modeSelect?.options || []).map((o) => o.value);
    assert(
      modeOptions.includes("system") && modeOptions.includes("manual") && modeOptions.includes("direct"),
      `模式选项应包含 system / manual / direct,实际 ${JSON.stringify(modeOptions)}`,
    );

    // 3) system 模式下手动配置区应隐藏
    const manualFields = main2.querySelector("#manual-proxy-fields");
    assert(manualFields, "应存在 #manual-proxy-fields 容器");
    assert(
      manualFields.style.display === "none",
      `默认 system 模式下手配区应隐藏,实际 display=${manualFields.style.display}`,
    );

    // 4) 切到 manual,手动配置区应展开
    modeSelect.value = "manual";
    modeSelect.dispatchEvent(new w.Event("change"));
    assert(
      manualFields.style.display === "flex",
      `切到 manual 模式手配区应展开,实际 display=${manualFields.style.display}`,
    );

    // 5) 手动配置字段应齐
    const typeSel = main2.querySelector('[data-cs-field="network.proxy.type"]');
    const hostInput = main2.querySelector('[data-cs-field="network.proxy.host"]');
    const portInput = main2.querySelector('[data-cs-field="network.proxy.port"]');
    const userInput = main2.querySelector('[data-cs-field="network.proxy.username"]');
    const passInput = main2.querySelector('[data-cs-field="network.proxy.password"]');
    const noProxyInput = main2.querySelector('[data-cs-field="network.proxy.noProxy"]');
    const testBtn = main2.querySelector('[data-cs-action="test-proxy"]');
    assert(typeSel, "应有 type select");
    assert(hostInput, "应有 host input");
    assert(portInput, "应有 port input");
    assert(userInput, "应有 username input");
    assert(passInput, "应有 password input (type=password)");
    assert(passInput.type === "password", `密码 input 应是 type=password,实际 ${passInput.type}`);
    assert(noProxyInput, "应有 noProxy input");
    assert(testBtn, "应有\"测试连接\"按钮");

    // 6) 模拟用户填配置 + 触发 input → 应该发 network_set_proxy 桥消息
    hostInput.value = "proxy.example.com";
    hostInput.dispatchEvent(new w.Event("input"));
    portInput.value = "8080";
    portInput.dispatchEvent(new w.Event("input"));
    userInput.value = "alice";
    userInput.dispatchEvent(new w.Event("input"));
    noProxyInput.value = "localhost, *.internal";
    noProxyInput.dispatchEvent(new w.Event("input"));

    const setSends = w.__e2e_sent.filter((s) => s?.type === "network_set_proxy");
    assert(
      setSends.length >= 1,
      `填字段应触发 network_set_proxy 桥消息,实际 ${setSends.length} 条`,
    );
    const lastSet = setSends[setSends.length - 1];
    assert(
      lastSet.host === "proxy.example.com" && lastSet.port === 8080 && lastSet.username === "alice",
      `payload 应包含用户填的值,实际 host=${lastSet.host} port=${lastSet.port} user=${lastSet.username}`,
    );
    assert(
      Array.isArray(lastSet.noProxy) && lastSet.noProxy.includes("localhost") && lastSet.noProxy.includes("*.internal"),
      `noProxy 应解析为数组,实际 ${JSON.stringify(lastSet.noProxy)}`,
    );

    // 7) 关键回归:填的字段**不应该** 走通用 settings_update(那个不带密码)
    const updSends = w.__e2e_sent.filter((s) => s?.type === "settings_update");
    // settings_update 可能在更早的渲染里被触发了(其他字段)— 我们只关心本次测试期间是否被触发
    // 由于我们替换了 save handler,本次 input 不应新增 settings_update
    const beforeCount = w.__e2e_sent.length;
    // 再触发一次 input
    hostInput.value = "proxy2.example.com";
    hostInput.dispatchEvent(new w.Event("input"));
    await new Promise((r) => setTimeout(r, 50));
    const newUpdSends = w.__e2e_sent.slice(beforeCount).filter((s) => s?.type === "settings_update");
    assert(
      newUpdSends.length === 0,
      `覆盖后,网络字段的 input 不应再触发 settings_update,实际 ${newUpdSends.length} 条`,
    );

    // 8) 测试连接按钮存在 + 点击应发 network_test_proxy
    w.__e2e_sent = [];
    testBtn.click();
    const testSends = w.__e2e_sent.filter((s) => s?.type === "network_test_proxy");
    assert(
      testSends.length === 1,
      `点测试按钮应发 network_test_proxy,实际 ${testSends.length} 条`,
    );
    assert(
      testSends[0]?.host === "proxy2.example.com" && testSends[0]?.port === 8080,
      `测试 payload 应带当前 form 值,实际 host=${testSends[0]?.host}`,
    );

  // ============== 总结 ==============
  console.log(`\n=== 测试结果 ===`);
  console.log(`通过: ${passed}`);
  console.log(`失败: ${failed}`);
  return failed === 0;
}

const ok = await runE2E();
process.exit(ok ? 0 : 1);
