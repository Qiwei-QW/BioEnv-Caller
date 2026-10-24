/*
 * 把 ai.html 里的脚本在 Node 里跑一遍，配一套最小的 DOM 桩。
 *
 * 目的：区分两种完全不同的可能，而它们长得一模一样：
 *   A. 脚本自己抛了异常          -> 这里能抓到确切的错
 *   B. 页面里根本没执行脚本       -> 这里跑得好好的，问题在 JxBrowser 的配置
 *
 * 没有显示器也能跑，和项目里其它 check() 一个路子。
 */
"use strict";
const fs = require("fs");

/**
 * 传进来的通常是 ai.html 本身（页面的脚本在 <script> 里），也可能是一份已经
 * 抽好的 .js。两种都收：拿不到 <script> 就把整份当脚本。
 *
 * <p>以前这里是直接 eval 整个文件的 —— 页面还叫 .js 时没问题，改成 .html 之后
 * 每次都在第一步抛 "Unexpected token '<'"，于是自检实际上从来没跑起来过。
 */
const page = fs.readFileSync(process.argv[2], "utf8");
const script = (function (src) {
  const m = src.match(/<script[^>]*>([\s\S]*?)<\/script>/i);
  return m ? m[1] : src;
})(page);

const calls = [];
const errors = [];

function makeEl(id) {
  const el = {
    id: id,
    textContent: "",
    className: "",
    placeholder: "",
    value: "",
    disabled: false,
    onclick: null,
    style: {},
    scrollTop: 0,
    scrollHeight: 100,
    children: [],
    parentNode: null,
    nextSibling: null,
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    appendChild(c) { calls.push("appendChild:" + id); c.parentNode = this; this.children.push(c); return c; },
    insertBefore(n, ref) { calls.push("insertBefore:" + id); n.parentNode = this; this.children.push(n); return n; },
    append(...xs) { calls.push("append:" + id); xs.forEach((x) => { x.parentNode = this; this.children.push(x); }); },
    remove() { calls.push("remove:" + id); },
    addEventListener(t) { calls.push("addEventListener:" + id + ":" + t); },
    querySelector() { return null; },
    setAttribute() {},
    getAttribute() { return null; },
    hasAttribute() { return false; },
    removeAttribute() {},
  };
  // 真浏览器里 innerHTML = "" 会把子节点清掉。桩里如果只存字符串，
  // "清空后还剩几条"这类断言就会永远数到累加值 —— 测出来的东西和实际不一致。
  var html = "";
  Object.defineProperty(el, "innerHTML", {
    get() { return html; },
    set(v) {
      html = String(v == null ? "" : v);
      if (v === "" || v == null) { el.children.length = 0; }
    },
    enumerable: true,
  });
  return el;
}

const byId = {};
function get(id) {
  if (!byId[id]) { byId[id] = makeEl(id); }
  return byId[id];
}

/** 内置模型桥的调用记录：桥那一路的"消息条数预算"就靠它来验。 */
const bridgeCalls = [];

const documentStub = {
  documentElement: makeEl("html"),
  body: makeEl("body"),
  getElementById: get,
  createElement: (tag) => makeEl("<" + tag + ">"),
    createElementNS: (ns, tag) => makeEl("<" + tag + ">"),
  addEventListener() {},
  querySelector() { return null; },
  querySelectorAll() { return []; },
};

const windowStub = {
  // 桥在不在，页面要走两条完全不同的路。这里给一个能回话的桩：
  // 自定义模型那几段仍然走 fetch，只有"内置模型"那一段会用到它。
  tbtools: {
    ai: {
      chat: function (msgs, opts) {
        bridgeCalls.push(msgs.slice());
        var text = "桥的回答";
        if (opts && typeof opts.onDelta === "function") { opts.onDelta(text); }
        return Promise.resolve(text);
      },
      chatImage: function () { return Promise.resolve("(图片)"); },
      abort: function () {},
    },
  },
  location: { href: "file:///.../ai.html" },
  addEventListener() {},
};

// 复现浏览器里 window.history 是只读的那个陷阱：
// 顶层 "var history = []" 在浏览器里会抛
//   TypeError: Cannot set property history of #<Window> which has only a getter
// 而它死得太早，window.onerror 都还没赋值，页面上看不出任何异常。
// 有了这个桩，同样的写法在自检里当场就炸。
Object.defineProperty(global, "history", {
  get() { return {}; },
  configurable: false,
  enumerable: false,
});

// localStorage 桩：塞一段带 Markdown 的历史，用来验证
//   1) 会话能不能恢复  2) Markdown 有没有真的渲染成标签
// 写入要记下来 —— 导出功能（tbv.export）就看这一条，不记录就没法验。
var seed = [{
  id: "s1", title: "测试会话", at: 1,
  turns: [
    { role: "user", content: "orthofinder 的 -v 是什么意思" },
    { role: "assistant", content: "**-v** 是显示版本号。\n\n```bash\northofinder -v\n```\n\n- 第一点\n- 第二点\n" },
  ],
}];
var store = {};
// 当前选中的模型。页面启动时从这里读 selId；没有它就走"内置模型"分支，
// 下面自定义模型的请求测试就发不出去了。
store["tbv.ai.sel"] = "m1";
global.localStorage = {
  getItem: function (k) {
    if (k === "bioenv.ai.sessions.v1") { return JSON.stringify(seed); }
    return store[k] === undefined ? null : store[k];
  },
  setItem: function (k, v) { store[k] = String(v); },
  removeItem: function (k) { delete store[k]; },
};

global.document = documentStub;
global.window = windowStub;
global.location = windowStub.location;
global.requestAnimationFrame = (f) => setTimeout(f, 0);

// 抓顶层未捕获错误：页面里如果没写 window.onerror，脚本抛出来就等于静默失败。
let topLevel = null;
try {
  // 用 indirect eval 让它跑在全局作用域，和浏览器里 <script> 一样。
  (0, eval)(script);
} catch (e) {
  topLevel = e;
}

console.log("=== 结果 ===");
if (topLevel) {
  console.log("脚本抛异常了：");
  console.log("  " + topLevel);
  console.log(topLevel.stack ? topLevel.stack.split("\n").slice(0, 6).join("\n") : "");
  process.exit(1);
}

console.log("脚本跑完了，没有抛异常。");
console.log("");
console.log("=== 页面被改成了什么样（这就是用户该看到的）===");
console.log("  title      : " + JSON.stringify(byId["title"] ? byId["title"].textContent : "(没碰)"));
console.log("  status     : " + JSON.stringify(byId["status"] ? byId["status"].textContent : "(没碰)"));
console.log("  status.class: " + JSON.stringify(byId["status"] ? byId["status"].className : ""));
console.log("  log 的子节点数: " + (byId["log"] ? byId["log"].children.length : 0));
console.log("  ctxSummary : " + JSON.stringify(byId["ctxSummary"] ? byId["ctxSummary"].textContent : "(没碰)"));
console.log("  send.onclick 接上了吗: " + (byId["send"] && typeof byId["send"].onclick === "function"));
console.log("  ask 监听了 keydown 吗: " + (called("addEventListener:ask:keydown")));
console.log("");
console.log("=== window.tbvAi 暴露了吗（Java 要靠它推数据）===");
console.log("  " + (windowStub.tbvAi ? Object.keys(windowStub.tbvAi).join(", ") : "没有！"));
console.log("");
console.log("");
console.log("=== 会话恢复 + Markdown 渲染 ===");
var log = byId["log"];
console.log("  log 子节点数（应为 2：一问一答）: " + (log ? log.children.length : 0));
log.children.forEach(function (c, i) {
  var html = c.innerHTML || "";
  console.log("  [" + i + "] class=" + c.className);
  console.log("      textContent=" + JSON.stringify(String(c.textContent || "").slice(0, 40)));
  if (html) { console.log("      innerHTML=" + JSON.stringify(html.slice(0, 220))); }
});
var ai = log.children[1] || {};
var html = String(ai.innerHTML || "");
console.log("  —— 渲染判据 ——");
console.log("  代码块 → <pre><code> : " + /<pre><code>/.test(html));
console.log("  粗体   → <b>        : " + /<b>-v<\/b>/.test(html));
console.log("  列表   → <ul><li>   : " + /<ul><li>/.test(html));
console.log("  没有裸反引号        : " + (html.indexOf("```") < 0));
console.log("  没有裸星号          : " + (html.indexOf("**") < 0));
console.log("  HTML 被转义（注入防护）: " + (html.indexOf("&lt;") >= 0 || html.indexOf("<script") < 0));
console.log("  <pre> 没被 <p> 包住      : " + (html.indexOf("<p><pre>") < 0));
console.log("  没有任何裸 <p> 包块元素 : " + !/<p>\s*<(pre|ul|table)/.test(html));
console.log("");
console.log("=== 会话下拉 ===");
var sel = byId["sessSel"];
console.log("  select 里的项数: " + (sel ? sel.children.length : 0));

/* ================================================================
 * 导出：页面拼 Markdown → localStorage 载荷 → Java 去取。
 *
 * 这一段要验的是"拼出来的正文对不对、载荷能不能原样解回来" ——
 * 它错了不会报错，只会导出一个看起来正常但其实缺内容/串行的文件。
 * ================================================================ */
console.log("");
console.log("=== 导出为 Markdown ===");
var problems = [];
var checks = 0;
function want(what, ok, detail) {
  checks++;
  console.log((ok ? "  PASS  " : "  FAIL  ") + what + (ok || detail === undefined ? "" : "  [" + detail + "]"));
  if (!ok) { problems.push(what); }
}

var doc = windowStub.tbvAi && windowStub.tbvAi.markdown ? windowStub.tbvAi.markdown() : null;
want("能拼出文档", doc !== null && typeof doc.text === "string");
if (doc) {
  var md = doc.text;
  want("有标题（会话名）", md.indexOf("# 测试会话") === 0);
  want("有导出时间那一行", md.indexOf("> 导出时间") >= 0);
  want("有我的那一轮", md.indexOf("## 我") >= 0 && md.indexOf("orthofinder 的 -v 是什么意思") >= 0);
  want("有 AI 的那一轮", md.indexOf("## AI") >= 0);
  want("代码块原样保留", md.indexOf("```bash\northofinder -v\n```") >= 0);
  want("没有未渲染的反引号块丢失", md.indexOf("```") >= 0);
  want("文件名带时间戳", /^\S.* \d{8}-\d{4}$/.test(doc.name || ""), doc.name);
}

var exported = windowStub.tbvAi && windowStub.tbvAi.exportNow ? windowStub.tbvAi.exportNow() : "";
want("exportNow 返回正文", typeof exported === "string" && exported.length > 0);

var payload = store["tbv.export"] || "";
want("载荷写进了 localStorage", payload.length > 0);
want("载荷里没有换行", payload.indexOf("\n") < 0, "raw newline in payload");
want("载荷里没有双引号", payload.indexOf('"') < 0);
want("载荷里只有一个分隔竖线", payload.split("|").length === 2);

var bar = payload.indexOf("|");
var name = bar < 0 ? "" : decodeURIComponent(payload.slice(0, bar));
var body = bar < 0 ? "" : decodeURIComponent(payload.slice(bar + 1));
want("名字解得回来", name.length > 0 && name.indexOf("测试会话") >= 0, name);
want("正文解得回来", body === exported);
want("正文里中文没坏", body.indexOf("orthofinder") >= 0);
want("正文里没有 %XX 残留", !/%[0-9A-Fa-f]{2}/.test(body));

/* ================================================================
 * token 用量：估算函数 · 请求里的 stream_options · usage 落到页脚
 *
 * 这一段盯的还是同一种形状：值算出来了、传过来了，但真正显示的那一行没读到。
 * 以前页脚是"字符数 ÷ 2"猜的；现在要验三件事：
 *   1. 估算口径（中文按字、英文按 4 字符）
 *   2. 请求里真的带上了 stream_options
 *   3. 服务端回的 prompt_tokens 真的到了页脚，而且去掉了 ≈
 * 另加一条退路：服务端不认 stream_options 时要自动去掉重发，而不是报错。
 * ================================================================ */
console.log("");
console.log("=== token 估算 ===");
var est = windowStub.tbvAi && windowStub.tbvAi.estTokens;
want("暴露了估算函数", typeof est === "function");
if (typeof est === "function") {
  want("空串 0 token", est("") === 0);
  want("null 当空串", est(null) === 0);
  want("中文 1 字 1 token", est("你好") === 2, est("你好"));
  want("英文 4 字符 1 token", est("abcd") === 1, est("abcd"));
  want("英文 8 字符 2 token", est("abcdefgh") === 2, est("abcdefgh"));
  want("同样长的中文 token 更多", est("你好世界") > est("abcdefgh"));
  // 旧口径是 length/2：13 字符 → 7。真实约 4，估 7 就是高估一倍。
  want("英文不再被高估一倍", est("fastp -i a.fq") <= 5, est("fastp -i a.fq"));
}

var requests = [];

function wait(ms) {
  return new Promise(function (res) { setTimeout(res, ms); });
}

/** 一个假的 SSE 响应：把每段文本依次交出去。 */
function sseResponse(chunks) {
  var i = 0;
  return {
    ok: true,
    status: 200,
    body: {
      getReader: function () {
        return {
          read: function () {
            return Promise.resolve(i < chunks.length
              ? { value: new TextEncoder().encode(chunks[i++]), done: false }
              : { value: undefined, done: true });
          }
        };
      }
    }
  };
}

var ASK = "hello";
var MODEL_JSON = JSON.stringify([{
  id: "m1", label: "Test model", baseUrl: "https://example.invalid/v1",
  apiKey: "sk-secret", model: "m1", maxIn: 1048576, maxOut: 2048, ok: true
}]);

function sendOnce(text) {
  byId["ask"].value = text;
  byId["go"].onclick();          // 页面上按的就是这个键
}

/** 会话下拉里第一项的名字。 */
function firstOption() {
  return String((byId["sessSel"].children[0] || {}).textContent || "");
}

/* ---------------- 鲁棒性那段用的小工具 ---------------- */

function enc(s) { return new TextEncoder().encode(s); }

/** n 个 "A" —— 用来喂"超长回答"。 */
function bigText(n) { return new Array(n + 1).join("A"); }

/** 一段 SSE 帧，内容是 n 个 "A"（给超长回答用）。 */
function bigSseFrame(n) {
  return 'data: {"choices":[{"delta":{"content":"' + bigText(n) + '"}}]}\n\n';
}

/** 桩元素的文字：textContent + 子节点的文字（"（已停止）"是子节点）。 */
function nodeText(n) {
  var s = String((n && n.textContent) || "");
  var kids = (n && n.children) || [];
  for (var i = 0; i < kids.length; i++) { s += " " + nodeText(kids[i]); }
  return s;
}

/** 对话区里最后一条<b>回答</b>的文字。 */
function lastAnswerText() {
  var kids = byId["log"].children;
  for (var i = kids.length - 1; i >= 0; i--) {
    if (String(kids[i].className || "") === "m a") { return nodeText(kids[i]); }
  }
  return "";
}

/** 对话区里最后一条回答渲染出来的 HTML（重绘之后正文只在这里）。 */
function lastAnswerHtml() {
  var kids = byId["log"].children;
  for (var i = kids.length - 1; i >= 0; i--) {
    if (String(kids[i].className || "") === "m a") { return String(kids[i].innerHTML || ""); }
  }
  return "";
}

/** 对话区里最后一条<b>错误/停止提示</b>的文字。 */
function lastErrorText() {
  var kids = byId["log"].children;
  for (var i = kids.length - 1; i >= 0; i--) {
    if (String(kids[i].className || "") === "m e") { return String(kids[i].textContent || ""); }
  }
  return "";
}

function isBusy() { return String(byId["go"].className || "").indexOf("busy") >= 0; }

/** 请求体里的 messages（解析不出来就给空数组）。 */
function sentMessages() {
  if (!requests.length) { return []; }
  var body = {};
  try { body = JSON.parse(requests[requests.length - 1].body); } catch (e) {}
  return body.messages || [];
}

/**
 * 一个"响应头立刻回来、body 先给几段、然后一直挂着"的 fetch 桩。
 * 收到 abort 时让 read() 以 AbortError 拒绝 —— 真实 fetch 就是这个行为。
 */
function stuckFetch(chunks) {
  return function (url, opts) {
    var signal = opts && opts.signal;
    var i = 0;
    function abortErr() {
      var e = new Error("The operation was aborted.");
      e.name = "AbortError";
      return e;
    }
    return Promise.resolve({
      ok: true, status: 200,
      body: {
        getReader: function () {
          return {
            read: function () {
              if (i < chunks.length) {
                return Promise.resolve({ value: enc(chunks[i++]), done: false });
              }
              return new Promise(function (res, rej) {
                if (!signal) { return; }
                if (signal.aborted) { rej(abortErr()); return; }
                signal.addEventListener("abort", function () { rej(abortErr()); });
              });
            }
          };
        }
      }
    });
  };
}

/** 一个永远等不到响应头的 fetch 桩（模拟网关接了连接但不说话）。 */
function hangingFetch() {
  return function () { return new Promise(function () { /* 永远不 settle */ }); };
}

function runNetwork() {
  // 先开一个新会话：名字是空的才看得出"会话名怎么起"。
  byId["sessNew"].onclick();
  want("空会话先显示「新会话」", firstOption() === "新会话", firstOption());
  windowStub.tbvAi.setModels(MODEL_JSON);
  // 带上上下文再发 —— 这样历史里存的是"整段上下文 + 问题"，
  // 和真机上一样；不然"重绘时只显示问题"那条根本测不到。
  windowStub.tbvAi.setContext(JSON.stringify({
    tool: "orthofinder", env: "orthofinder_env",
    help: "SIMPLE USAGE:\n Run full OrthoFinder analysis ...\n", options: []
  }));
  // 面板开着的时候先开会话、后选工具是常态：这时会话名该跟着变成工具名，
  // 而不是永远"新会话"。
  want("选好工具后，空会话改挂工具名", firstOption() === "orthofinder", firstOption());

  console.log("");
  console.log("=== 发送：请求体里的 stream_options ===");
  requests = [];
  global.fetch = function (url, opts) {
    requests.push({ url: String(url), body: String((opts && opts.body) || "") });
    return Promise.resolve(sseResponse([
      'data: {"choices":[{"delta":{"content":"你好"}}]}\n\n',
      'data: {"choices":[{"delta":{"content":"世界"}}],'
        + '"usage":{"prompt_tokens":12345,"completion_tokens":7}}\n\n',
      "data: [DONE]\n\n"
    ]));
  };
  sendOnce(ASK);
  return wait(120).then(function () {
    want("发出了一个请求", requests.length === 1, "requests=" + requests.length);
    var body = {};
    try { body = JSON.parse(requests[0].body); } catch (e) {}
    want("请求带 stream_options", !!(body.stream_options
        && body.stream_options.include_usage), requests[0] && requests[0].body.slice(0, 80));
    want("模型名对", body.model === "m1");
    want("max_tokens 用设置里的值", body.max_tokens === 2048, body.max_tokens);
    want("消息里有 system", !!(body.messages && body.messages[0]
        && body.messages[0].role === "system"));

    // ★ 这一段是重点：服务端给的 12345 必须真的出现在页脚上。
    var budget = String(byId["budget"].textContent || "");
    want("页脚用了服务端的真实值", budget.indexOf("12.3k") >= 0, budget);
    want("有真值时不带 ≈", budget.indexOf("≈") < 0, budget);
    want("上限显示成 1M", budget.indexOf("1M") >= 0, budget);

    console.log("");
    console.log("=== 回答下面那行按钮 ===");
    var acts = byId["log"].children.filter(function (c) {
      return String(c.className || "") === "acts";
    }).pop();
    var labels = acts ? acts.children.map(function (c) { return String(c.textContent); }) : [];
    // 「采用」把命令塞进命令框，但一次回答可能给好几条命令，而命令框还牵着
    // 路径转换和参数重置 —— 塞进去往往是个半对的状态。去掉，只留复制。
    want("没有「采用」按钮", labels.indexOf("采用") < 0, labels.join(","));
    want("有「复制」按钮", labels.indexOf("复制") >= 0, labels.join(","));
    want("有「重试」按钮", labels.indexOf("重试") >= 0, labels.join(","));

    console.log("");
    console.log("=== 会话名 / 重绘后只显示问题 ===");
    var optText = String((byId["sessSel"].children[0] || {}).textContent || "");
    want("会话名取的是问题本身", optText === ASK, optText);
    want("会话名后面没有 (N) 条数", optText.indexOf("(") < 0
        && optText.indexOf("（") < 0, optText);

    // 模拟"收起再展开"：Java 会重推语言 → applyLang → renderLog 整块重绘。
    windowStub.tbvAi.setLang("zh");
    var users = byId["log"].children.filter(function (c) {
      return String(c.className || "").indexOf("m u") === 0;
    });
    want("重绘后用户气泡还在", users.length > 0, "users=" + users.length);
    want("重绘后用户气泡只有问题，没有上下文",
        users.length > 0
        && users.every(function (c) {
             return String(c.textContent).indexOf("以下是当前状态") < 0;
           }),
        users.length ? String(users[0].textContent).slice(0, 40) : "");
    want("重绘后用户气泡文字就是问题本身",
        users.length > 0 && String(users[0].textContent) === ASK,
        users.length ? String(users[0].textContent).slice(0, 40) : "");

    // 重绘会把整块 log 清空重建 —— 动作按钮也要跟着重建，否则"收起再展开"
    // 之后复制按钮就没了，而那正是最需要它的时候。
    var actsAfter = byId["log"].children.filter(function (c) {
      return String(c.className || "") === "acts";
    });
    want("重绘后动作按钮还在", actsAfter.length === 1, "rows=" + actsAfter.length);
    var labelsAfter = actsAfter.length
      ? actsAfter[0].children.map(function (c) { return String(c.textContent); })
      : [];
    want("重绘后没有「采用」", labelsAfter.indexOf("采用") < 0, labelsAfter.join(","));
    want("重绘后还有「复制」", labelsAfter.indexOf("复制") >= 0, labelsAfter.join(","));

    console.log("");
    console.log("=== 退路：服务端不认 stream_options ===");
    requests = [];
    global.fetch = function (url, opts) {
      var body2 = {};
      try { body2 = JSON.parse((opts && opts.body) || "{}"); } catch (e) {}
      requests.push({ body: String((opts && opts.body) || "") });
      if (body2.stream_options) {
        return Promise.resolve({
          ok: false, status: 400,
          text: function () {
            return Promise.resolve('{"error":{"message":"Unknown field: stream_options"}}');
          }
        });
      }
      return Promise.resolve(sseResponse([
        'data: {"choices":[{"delta":{"content":"ok"}}],'
          + '"usage":{"prompt_tokens":777,"completion_tokens":3}}\n\n',
        "data: [DONE]\n\n"
      ]));
    };
    sendOnce("again");
    return wait(150);
  }).then(function () {
    want("被拒之后自动重发了", requests.length === 2, "requests=" + requests.length);
    var first = {}, second = {};
    try {
      first = JSON.parse(requests[0].body);
      second = JSON.parse(requests[1].body);
    } catch (e) {}
    want("第一次带了 stream_options", !!first.stream_options);
    want("重发时去掉了 stream_options", second.stream_options === undefined);
    want("记住了这家服务器",
        String(store["tbv.ai.nousage"] || "").indexOf("https://example.invalid/v1") >= 0,
        store["tbv.ai.nousage"]);
    var budget2 = String(byId["budget"].textContent || "");
    want("重发成功、页脚用上了第二次的真值", budget2.indexOf("777") >= 0, budget2);
    want("界面上没有报错气泡", !hasErrorBubble());

    // ★ 上下文不能被每一轮都带一遍：历史里的用户消息只该是问题本身，
    //   整份"以下是当前状态 + 帮助原文"只在最后一条里出现一次。
    var fb = {};
    try { fb = JSON.parse(requests[0].body); } catch (e) {}
    var hist = fb.messages || [];
    want("历史里的用户消息只有问题", hist.length > 1
        && String(hist[1].content || "") === ASK,
        hist.length > 1 ? String(hist[1].content).slice(0, 40) : "(没有)");
    var ctxCount = 0;
    for (var hi = 0; hi < hist.length; hi++) {
      if (String(hist[hi].content || "").indexOf("以下是当前状态") >= 0) { ctxCount++; }
    }
    want("整份上下文只在最后一条消息里带一次", ctxCount === 1, "count=" + ctxCount);
  }).then(function () {
    /* 两次都失败：那就不该把这家服务器记成"不支持用量" —— 否则以后永远拿不到
       真数，而界面上一点提示都没有。这就是"静默降级"，在这个项目里出现过好几次。 */
    console.log("");
    console.log("=== 两次都失败时不能冤枉它 ===");
    delete store["tbv.ai.nousage"];
    var before = hasErrorBubble();
    requests = [];
    global.fetch = function (url, opts) {
      requests.push({ body: String((opts && opts.body) || "") });
      return Promise.resolve({
        ok: false, status: 400,
        text: function () {
          return Promise.resolve('{"error":{"message":"model not found"}}');
        }
      });
    };
    sendOnce("third");
    return wait(150).then(function () {
      want("两次都失败了", requests.length === 2, "requests=" + requests.length);
      want("没把服务器记成不支持用量",
          String(store["tbv.ai.nousage"] || "").indexOf("example.invalid") < 0,
          store["tbv.ai.nousage"]);
      want("把真实错误显示了出来", !before && hasErrorBubble());
      // 以前失败的那一轮什么都不存，一收起面板就全没了。现在它留在对话里。
      windowStub.tbvAi.setLang("zh");          // 整块重绘
      want("失败的那一轮重绘之后还在", hasErrorBubble());
    });
  }).then(function () {
    /* 删会话：点一次只出确认条，点「删除」才真删。
       以前这里是 window.confirm —— 在 JxBrowser 里等于"点了没反应 + 整个应用冻住"，
       因为那个弹框永远等不到人点，而 Java 每 700ms 还要来读一次页面。 */
    console.log("");
    console.log("=== 删除会话：页面内确认 ===");
    want("页面里没有 window.confirm / alert（会冻住整个应用）",
        !hasBrowserModal(script));

    var sessionCount = byId["sessSel"].children.length;
    var logBefore = byId["log"].children.length;
    byId["sessDel"].onclick();          // 就是 🗑 按钮
    want("点删除先出确认条", byId["confirm"].hidden === false);
    want("确认条上有字", String(byId["confirmText"].textContent || "").length > 0);
    want("确认条上有两个按钮", String(byId["confirmYes"].textContent || "").length > 0
        && String(byId["confirmNo"].textContent || "").length > 0);
    want("还没真删", byId["log"].children.length === logBefore
        && byId["sessSel"].children.length === sessionCount);

    byId["confirmNo"].onclick();        // 先测取消
    want("点取消就收起来", byId["confirm"].hidden === true);
    want("取消之后什么都没删", byId["log"].children.length === logBefore
        && byId["sessSel"].children.length === sessionCount);

    byId["sessDel"].onclick();
    byId["confirmYes"].onclick();       // 再确认一次，这次真删
    want("确认之后关掉确认条", byId["confirm"].hidden === true);
    // 这里还有一条别的会话，所以删掉的是当前这条、然后切过去 —— 不是清空。
    want("会话真的少了一条", byId["sessSel"].children.length === sessionCount - 1,
        "before=" + sessionCount + " after=" + byId["sessSel"].children.length);
    // 切过去之后，页脚不该还挂着上一条会话的用量。
    want("换会话后页脚用量清零",
        String(byId["budget"].textContent || "").indexOf("12.3k") < 0
        && String(byId["budget"].textContent || "").indexOf("777") < 0,
        String(byId["budget"].textContent || ""));
  });
}

/** 对话区里有没有一条报错气泡（class 含 "m e"）。 */
function hasErrorBubble() {
  var kids = byId["log"].children;
  for (var i = 0; i < kids.length; i++) {
    if (String(kids[i].className || "").indexOf("m e") >= 0) { return true; }
  }
  return false;
}

/**
 * 页面里有没有"浏览器模态框"的调用。
 *
 * <p>这些调用在 JxBrowser 里会把渲染进程停在"等一个没人点的对话框"上，
 * 而 Java 每 700ms 要读一次页面 → Swing 事件线程跟着堵死，整个界面冻住。
 * 所以它们在这个页面里是被禁止的；只认真正的调用（带括号），
 * 注释里提到这几个词不算。
 */
function hasBrowserModal(src) {
  return /(^|[^.\w])(confirm|alert|prompt)\s*\(/.test(src);
}

console.log("");
console.log("=== 模型菜单 / 附件开关 ===");
windowStub.tbvAi.setAttach(false);
want("关掉附件时 ⊕ 被藏起来", byId["pick"].style.display === "none");
windowStub.tbvAi.setAttach(true);
want("打开附件时 ⊕ 回来", byId["pick"].style.display !== "none");
windowStub.tbvAi.setModels("[]");
var popKids = byId["modelPop"].children;
var popLast = popKids.length ? popKids[popKids.length - 1] : null;
want("模型菜单最后一行是「去设置里管理」",
    popLast && String(popLast.textContent).indexOf("设置") >= 0,
    popLast ? String(popLast.textContent) : "(没有)");
if (popLast && typeof popLast.onclick === "function") { popLast.onclick(); }
want("点了会请求 Java 打开设置", store["tbv.openSettings"] === "1", store["tbv.openSettings"]);

console.log("");
console.log("=== Markdown 表格 ===");
var tableSrc = "| 参数 | 含义 |\n|------|------|\n| -t | 线程数 |\n| -M | 建树方法 |";
var tableHtml = String(windowStub.tbvAi.render(tableSrc));
want("表格渲染成 <table>", tableHtml.indexOf("<table>") >= 0, tableHtml.slice(0, 80));
want("表头是 <th>", tableHtml.indexOf("<th>参数</th>") >= 0);
want("单元格是 <td>", tableHtml.indexOf("<td>-t</td>") >= 0);
want("表格没被包进 <p>", tableHtml.indexOf("<p><div class='tbl'>") < 0);
// 第二行不是分隔行 -> 不是表格，不能被吃掉
var notTable = String(windowStub.tbvAi.render("| a | b |\n| 1 | 2 |"));
want("没有分隔行就不当表格", notTable.indexOf("<table>") < 0, notTable.slice(0, 80));

/* ================================================================
 * 鲁棒性：接口地址 / CRLF 流 / 停止语义 / 空闲超时 / 桥的消息预算
 *
 * 这一段验的都是"不报错但会安静地坏掉"的地方：每个断言都盯着用户看得见的
 * 结果（气泡里有什么字、busy 有没有清、请求里到底装了什么）。
 * ================================================================ */
function runHardening() {
  var p = Promise.resolve();

  // ---------- 接口地址 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 接口地址：归一化 ===");
    byId["sessNew"].onclick();
    requests = [];
    global.fetch = function (url, opts) {
      requests.push({ url: String(url), body: String((opts && opts.body) || "") });
      return Promise.resolve(sseResponse([
        'data: {"choices":[{"delta":{"content":"ok"}}]}\n\n',
        "data: [DONE]\n\n"
      ]));
    };
    // 1) 没写 scheme：立刻报错，一个请求都不该发出去。
    windowStub.tbvAi.setModels(JSON.stringify([{
      id: "m1", label: "No scheme", baseUrl: "api.example.invalid/v1",
      apiKey: "k", model: "x", maxIn: 0, maxOut: 0, ok: true
    }]));
    sendOnce("bad endpoint");
    return wait(80).then(function () {
      want("没写 http(s):// 时不发请求", requests.length === 0, "requests=" + requests.length);
      want("报错说明要补 http(s):// 并点名地址",
          lastErrorText().indexOf("http") >= 0
          && lastErrorText().indexOf("api.example.invalid") >= 0,
          lastErrorText());
      // 2) 贴了完整地址：/chat/completions 只能出现一次。
      windowStub.tbvAi.setModels(JSON.stringify([{
        id: "m1", label: "Full", baseUrl: "https://example.invalid/v1/chat/completions/",
        apiKey: "k", model: "x", maxIn: 0, maxOut: 0, ok: true
      }]));
      sendOnce("full endpoint");
      return wait(100).then(function () {
        want("末尾的 /chat/completions 只保留一份",
            requests.length === 1
            && requests[0].url === "https://example.invalid/v1/chat/completions",
            requests.length ? requests[0].url : "(没发)");
        want("归一化之后正常收尾", !isBusy(), byId["go"].className);
      });
    });
  });

  // ---------- CRLF 的 SSE ----------
  p = p.then(function () {
    console.log("");
    console.log("=== CRLF 的 SSE：流没结束就要渲染 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    var reads = 0;
    global.fetch = function () {
      return Promise.resolve({
        ok: true, status: 200,
        body: { getReader: function () { return { read: function () {
          reads++;
          if (reads === 1) {
            return Promise.resolve({
              value: enc('data: {"choices":[{"delta":{"content":"CRLF 到了"}}]}\r\n\r\n'),
              done: false
            });
          }
          if (reads === 2) {
            // 先挂一会儿：这段时间里页面上必须已经能看到第一段。
            return new Promise(function (res) {
              setTimeout(function () {
                res({ value: enc("data: [DONE]\r\n\r\n"), done: false });
              }, 250);
            });
          }
          return Promise.resolve({ value: undefined, done: true });
        } }; } }
      });
    };
    sendOnce("crlf");
    return wait(90).then(function () {
      want("流还没结束就已经渲染出内容",
          lastAnswerText().indexOf("CRLF 到了") >= 0, lastAnswerText());
      return wait(350);
    }).then(function () {
      want("流结束之后内容仍在", lastAnswerText().indexOf("CRLF 到了") >= 0, lastAnswerText());
      want("CRLF 的流结束后也清掉 busy", !isBusy(), byId["go"].className);
    });
  });

  // ---------- 停止：半截回答 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 停止：保留半截、不留空回答 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    global.fetch = stuckFetch(['data: {"choices":[{"delta":{"content":"半截回答"}}]}\n\n']);
    sendOnce("半截问题");
    return wait(80).then(function () {
      want("停止前已经显示半截", lastAnswerText().indexOf("半截回答") >= 0, lastAnswerText());
      byId["go"].onclick();            // 忙的时候这个按钮就是停止
      return wait(150);
    }).then(function () {
      want("停止后 busy 清掉", !isBusy(), byId["go"].className);
      want("半截文字还在", lastAnswerText().indexOf("半截回答") >= 0, lastAnswerText());
      want("标成了已停止", lastAnswerText().indexOf("已停止") >= 0, lastAnswerText());
      // 收起再展开（Java 重推语言）走的就是整块重绘 —— 标记必须存进历史里。
      windowStub.tbvAi.setLang("zh");
      want("重绘后仍带着「已停止」",
          lastAnswerText().indexOf("已停止") >= 0, lastAnswerText());
      want("重绘后半截正文还在", lastAnswerHtml().indexOf("半截回答") >= 0,
          lastAnswerHtml());
      // 下一问：被停掉的半截回答不能进模型历史。
      requests = [];
      global.fetch = function (url, opts) {
        requests.push({ url: String(url), body: String((opts && opts.body) || "") });
        return Promise.resolve(sseResponse([
          'data: {"choices":[{"delta":{"content":"下一个回答"}}]}\n\n',
          "data: [DONE]\n\n"
        ]));
      };
      sendOnce("下一个问题");
      return wait(120).then(function () {
        var msgs = sentMessages();
        var hasPartial = msgs.some(function (m) {
          return m.role === "assistant" && String(m.content || "").indexOf("半截回答") >= 0;
        });
        var hasEmpty = msgs.some(function (m) {
          return m.role === "assistant" && String(m.content || "") === "";
        });
        var hasStoppedQuestion = msgs.some(function (m) {
          return m.role === "user" && String(m.content || "").indexOf("半截问题") >= 0;
        });
        want("被停掉的半截回答没进模型历史", !hasPartial, JSON.stringify(msgs));
        want("历史里没有空的 assistant 消息", !hasEmpty, JSON.stringify(msgs));
        want("被停掉的那一问也不重发", !hasStoppedQuestion, JSON.stringify(msgs));
        want("下一问正常收尾", !isBusy() && lastAnswerText().indexOf("下一个回答") >= 0,
            lastAnswerText());
      });
    });
  });

  // ---------- 停止：一个字都没来 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 停止：一个字都没来 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    global.fetch = stuckFetch([]);      // 响应头回来了，body 一个字节都不给
    sendOnce("空白问题");
    return wait(50).then(function () {
      byId["go"].onclick();
      return wait(150);
    }).then(function () {
      want("停止后 busy 清掉", !isBusy(), byId["go"].className);
      want("一个字都没来时显示已停止", lastErrorText().indexOf("已停止") >= 0, lastErrorText());
      windowStub.tbvAi.setLang("zh");   // 整块重绘
      want("重绘后仍是已停止，而不是空回答",
          lastErrorText().indexOf("已停止") >= 0, lastErrorText());
      requests = [];
      global.fetch = function (url, opts) {
        requests.push({ url: String(url), body: String((opts && opts.body) || "") });
        return Promise.resolve(sseResponse([
          'data: {"choices":[{"delta":{"content":"好"}}]}\n\n',
          "data: [DONE]\n\n"
        ]));
      };
      sendOnce("再问一次");
      return wait(120).then(function () {
        var msgs = sentMessages();
        want("没有把空的 assistant 再发出去", msgs.every(function (m) {
          return !(m.role === "assistant" && String(m.content || "") === "");
        }), JSON.stringify(msgs));
      });
    });
  });

  // ---------- 空闲超时 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 空闲超时：不能永远 busy ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    windowStub.tbvAi.idleTimeout(80);   // 自检里把 60 秒改短
    global.fetch = hangingFetch();
    var t0 = Date.now();
    sendOnce("卡死的请求");
    return wait(400).then(function () {
      var ms = Date.now() - t0;
      windowStub.tbvAi.idleTimeout();   // 恢复默认 60 秒
      want("超时后 busy 清掉", !isBusy(), byId["go"].className);
      want("说明没有收到任何数据",
          lastErrorText().indexOf("没有收到任何数据") >= 0, lastErrorText());
      want("超时是自己结束的，不是一直挂着", ms < 3000, "after " + ms + "ms");
      // 超时那一轮进了历史，而且还能再发下一问。
      requests = [];
      global.fetch = function (url, opts) {
        requests.push({ url: String(url), body: String((opts && opts.body) || "") });
        return Promise.resolve(sseResponse([
          'data: {"choices":[{"delta":{"content":"恢复"}}]}\n\n',
          "data: [DONE]\n\n"
        ]));
      };
      sendOnce("超时之后");
      return wait(120).then(function () {
        want("超时之后还能正常提问", !isBusy() && lastAnswerText().indexOf("恢复") >= 0,
            lastAnswerText());
      });
    });
  });

  // ---------- 桥的消息预算 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 桥那一路：消息预算 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels("[]");   // 没有自定义模型 → 走内置桥
    bridgeCalls.length = 0;
    var chain = Promise.resolve();
    for (var n = 1; n <= 11; n++) {
      (function (k) {
        chain = chain.then(function () {
          sendOnce("桥问题" + k);
          return wait(30);
        });
      })(n);
    }
    return chain.then(function () {
      want("桥被调用了 11 次", bridgeCalls.length === 11, "calls=" + bridgeCalls.length);
      var last = bridgeCalls[bridgeCalls.length - 1] || [];
      want("最后一次请求不超过 20 条消息", last.length <= 20 && last.length > 1,
          "len=" + last.length);
      var hasNotice = false;
      byId["log"].children.forEach(function (c) {
        if (String(c.className || "") === "m s"
            && String(c.textContent || "").indexOf("已省略") >= 0) { hasNotice = true; }
      });
      want("提示了省略多少轮", hasNotice, "没有提示");
      // 裁的只是请求：面板和导出必须还是完整的。
      windowStub.tbvAi.setLang("zh");
      var users = byId["log"].children.filter(function (c) {
        return String(c.className || "") === "m u";
      });
      want("重绘后面板仍保留最早一问",
          users.length > 0 && nodeText(users[0]).indexOf("桥问题1") >= 0,
          users.length ? nodeText(users[0]) : "(没有)");
      var doc = windowStub.tbvAi.markdown();
      want("导出里仍保留最早一问",
          doc !== null && String(doc.text || "").indexOf("桥问题1") >= 0,
          doc ? doc.name : "(没有)");
    });
  });

  // ---------- 回答上限：真的灌超过 1000000 字 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 回答上限：超过 1000000 字符 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    // 两个 SSE 帧合计 1000100 字，刚好超过默认上限。
    global.fetch = function () {
      return Promise.resolve(sseResponse([
        bigSseFrame(600000),
        bigSseFrame(400100),
        "data: [DONE]\n\n"
      ]));
    };
    sendOnce("超长回答");
    return wait(1200).then(function () {
      var html = lastAnswerHtml();
      want("气泡正文被截到上限", html.length <= 1000000 + 64, "len=" + html.length);
      want("显示了截断说明", lastAnswerText().indexOf("回答过长") >= 0,
          lastAnswerText().slice(-60));
      want("截断之后 busy 清掉", !isBusy(), byId["go"].className);
      want("截断没有变成错误气泡", lastErrorText() === "", lastErrorText());
      // 收起再展开（Java 重推语言）走的是整块重绘：说明和上限都要保持。
      windowStub.tbvAi.setLang("zh");
      want("重绘后截断说明还在", lastAnswerText().indexOf("回答过长") >= 0,
          lastAnswerText().slice(-60));
      want("重绘后正文仍在上限内", lastAnswerHtml().length <= 1000000 + 64,
          "len=" + lastAnswerHtml().length);
      // 历史里也不能留一个更大的字符串：下一问的请求体里看它有多大。
      requests = [];
      global.fetch = function (url, opts) {
        requests.push({ url: String(url), body: String((opts && opts.body) || "") });
        return Promise.resolve(sseResponse([
          'data: {"choices":[{"delta":{"content":"ok"}}]}\n\n',
          "data: [DONE]\n\n"
        ]));
      };
      sendOnce("上限之后");
      return wait(200);
    }).then(function () {
      var msgs = sentMessages();
      var asst = null;
      for (var i = 0; i < msgs.length; i++) {
        if (msgs[i].role === "assistant") { asst = msgs[i]; }
      }
      want("历史里的回答也是截断后的",
          asst !== null && String(asst.content || "").length === 1000000,
          asst ? "len=" + String(asst.content || "").length : "(没有)");
    });
  });

  // ---------- 回答上限 + 停止 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 回答上限：截断之后停止 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    windowStub.tbvAi.answerCap(500);     // 自检里把上限改小
    global.fetch = stuckFetch([bigSseFrame(700)]);
    sendOnce("截断后停止");
    return wait(100).then(function () {
      want("截断说明先出现", lastAnswerText().indexOf("回答过长") >= 0,
          lastAnswerText().slice(-40));
      byId["go"].onclick();
      return wait(150);
    }).then(function () {
      want("停止后 busy 清掉", !isBusy(), byId["go"].className);
      var t = lastAnswerText();
      want("半截回答还在", t.indexOf("A") >= 0, t.slice(0, 20));
      want("截断说明还在", t.indexOf("回答过长") >= 0, t.slice(-80));
      want("截断说明写出了上限", t.indexOf("前 500 个字符") >= 0, t.slice(-80));
      want("已停止说明还在", t.indexOf("已停止") >= 0, t.slice(-40));
      windowStub.tbvAi.setLang("zh");    // 整块重绘
      var redraw = lastAnswerText();
      want("重绘后两条说明都在",
          redraw.indexOf("回答过长") >= 0 && redraw.indexOf("已停止") >= 0,
          redraw.slice(-80));
      windowStub.tbvAi.answerCap();      // 恢复默认 1000000
    });
  });

  // ---------- 回答上限 + 超时 ----------
  p = p.then(function () {
    console.log("");
    console.log("=== 回答上限：截断之后超时 ===");
    byId["sessNew"].onclick();
    windowStub.tbvAi.setModels(MODEL_JSON);
    windowStub.tbvAi.answerCap(500);
    windowStub.tbvAi.idleTimeout(80);
    global.fetch = stuckFetch([bigSseFrame(700)]);
    sendOnce("截断后超时");
    return wait(400).then(function () {
      windowStub.tbvAi.idleTimeout();
      windowStub.tbvAi.answerCap();
      want("超时后 busy 清掉", !isBusy(), byId["go"].className);
      want("超时说明照旧", lastErrorText().indexOf("没有收到任何数据") >= 0,
          lastErrorText());
      want("截断没有把超时变成错误", lastErrorText().indexOf("脚本错误") < 0,
          lastErrorText());
    });
  });

  return p;
}

console.log("");
runNetwork().then(runHardening).then(function () {
  console.log("");
  console.log(problems.length === 0
    ? "PASS " + checks + " checks"
    : "FAIL " + problems.length + " of " + checks);

  console.log("");
  console.log("=== 调用轨迹 ===");
  console.log("  " + calls.join("\n  "));

  if (problems.length > 0) { process.exit(1); }
}, function (e) {
  console.log("网络段自己抛了：" + (e && e.stack || e));
  process.exit(1);
});

function called(name) { return calls.indexOf(name) >= 0; }
