// 注入页面的测试助手（window.__e2e）。只用 DOM，不依赖应用内部模块 —— 对 mock 与真实服务同样有效。
(() => {
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const txt = (e) => (e?.innerText ?? e?.textContent ?? "").replace(/\s+/g, " ").trim();
  const match = (e, m) => (m instanceof RegExp ? m.test(txt(e)) : txt(e) === m);
  const visible = (e) => !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length));
  const log = [];
  const h = {
    log, sleep, txt,
    step(msg) { log.push(msg); },
    expect(cond, msg) { if (!cond) throw new Error(msg); log.push("✓ " + msg); },
    async waitFor(fn, msg, ms = 5000) {
      const t0 = Date.now();
      for (;;) {
        let v; try { v = await fn(); } catch { v = undefined; }
        if (v) return v;
        if (Date.now() - t0 > ms) throw new Error("超时：" + msg);
        await sleep(80);
      }
    },
    /** 等一个数字出现（0 也算）。waitFor 以真值为准，直接等数字会把 0 当「没取到」一直等到超时 —— 对真服务时踩过两次 */
    async num(fn, msg, ms) { return (await h.waitFor(() => { const v = fn(); return Number.isFinite(v) ? { v } : null; }, msg, ms)).v; },
    all(sel, m) { return [...document.querySelectorAll(sel)].filter((e) => visible(e) && (m == null || match(e, m))); },
    one(sel, m) { return h.all(sel, m)[0]; },
    btn(m) { return h.one("button", m); },
    async click(sel, m, what) {
      const e = await h.waitFor(() => h.one(sel, m), "找不到 " + (what ?? m ?? sel));
      e.scrollIntoView({ block: "center" }); e.click(); await sleep(60); return e;
    },
    /** React 受控输入：走原生 setter 再派发事件，否则 state 不变 */
    fill(el, value) {
      const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : el.tagName === "SELECT" ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(el, value);
      el.dispatchEvent(new Event(el.tagName === "SELECT" ? "change" : "input", { bubbles: true }));
    },
    /** 右下角提示条的全部文字 */
    toasts() { return h.all("div.fixed.bottom-5 span").map(txt).join(" | "); },
    async toast(re, what) { return h.waitFor(() => re.test(h.toasts()) && h.toasts(), "提示「" + (what ?? re) + "」没有出现；当前提示：" + h.toasts()); },
    /** 分页脚「共 N 条」里的 N（语言无关：取数字） */
    total() { const f = h.all("span").map(txt).find((t) => /^(共 \d+ 条|\d+ items|\d+ عنصر.*|.*\d+.*)$/.test(t) && /\d/.test(t) && t.length < 24 && /共|items|عنصر/.test(t)); return f ? Number(f.match(/\d+/)[0]) : null; },
    rows() { return h.all("[data-slot=tbody] tr").filter((r) => r.querySelectorAll("td").length > 1); },
    /** 页签上的数字（「全部 12」「商户资质 · 待领 12」） */
    tabNum(m) { const e = h.one("button", m); return e ? Number((txt(e).match(/(\d+)\D*$/) || [])[1]) : NaN; },
    bodyText() { return txt(document.body); },
  };
  window.__e2e = h;
})();
