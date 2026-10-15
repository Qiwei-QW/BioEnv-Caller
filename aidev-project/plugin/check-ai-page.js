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

const script = fs.readFileSync(process.argv[2], "utf8");

const calls = [];
const errors = [];

function makeEl(id) {
  const el = {
    id: id,
    textContent: "",
    innerHTML: "",
    className: "",
    placeholder: "",
    value: "",
    disabled: false,
    onclick: null,
    style: {},
    scrollTop: 0,
    scrollHeight: 100,
    children: [],
    classList: { add() {}, remove() {}, toggle() {}, contains() { return false; } },
    appendChild(c) { calls.push("appendChild:" + id); this.children.push(c); return c; },
    append(...xs) { calls.push("append:" + id); xs.forEach((x) => this.children.push(x)); },
    remove() { calls.push("remove:" + id); },
    addEventListener(t) { calls.push("addEventListener:" + id + ":" + t); },
    querySelector() { return null; },
    setAttribute() {},
    getAttribute() { return null; },
    removeAttribute() {},
  };
  return el;
}

const byId = {};
function get(id) {
  if (!byId[id]) { byId[id] = makeEl(id); }
  return byId[id];
}

const documentStub = {
  documentElement: makeEl("html"),
  body: makeEl("body"),
  getElementById: get,
  createElement: (tag) => makeEl("<" + tag + ">"),
  addEventListener() {},
  querySelector() { return null; },
  querySelectorAll() { return []; },
};

const windowStub = {
  // 这是要验的一个关键分支：桥不在时页面应该显示"不可用"，而不是卡住。
  tbtools: undefined,
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
var seed = [{
  id: "s1", title: "测试会话", at: 1,
  turns: [
    { role: "user", content: "orthofinder 的 -v 是什么意思" },
    { role: "assistant", content: "**-v** 是显示版本号。\n\n```bash\northofinder -v\n```\n\n- 第一点\n- 第二点\n" },
  ],
}];
global.localStorage = {
  getItem: function (k) { return k === "bioenv.ai.sessions.v1" ? JSON.stringify(seed) : null; },
  setItem: function () {},
  removeItem: function () {},
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

console.log("");
console.log("=== 调用轨迹 ===");
console.log("  " + calls.join("\n  "));

function called(name) { return calls.indexOf(name) >= 0; }
