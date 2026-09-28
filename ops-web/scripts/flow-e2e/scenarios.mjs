// 流程与工单的端到端场景（22/08 联调与测试场景）。
// 每个场景 = 打开一页 → 在页面里按用户的方式操作 → 断言看得见的结果。
// run 的函数体在浏览器里执行（拿到 h = 测试助手、fx = 夹具），所以**只能用 DOM，不能引用外层变量**。
// 夹具（单号、成员）按运行模式取：mock 用种子里的号；对真实 OpsKit 用它的种子（fixtures.opskit.json）。

const ALL = ["*"];
const VIEWER = ["flow:todo:read", "flow:approval:read", "flow:ticket:read"];

export const SCENARIOS = [
  // ── S1 待办 ──────────────────────────────────────────────────────────
  {
    id: "S1-1", title: "待办：四个页签的角标 = 各自列表的条数", perms: ALL, url: () => "/flow/todo/",
    run: async (h) => {
      for (const tab of [/^全部/, /^审批/, /^工单/, /^可抢单/]) {
        await h.click("button", tab, String(tab));
        const n = await h.waitFor(() => { const v = h.tabNum(tab); return Number.isFinite(v) ? v : null; }, "页签数字");
        await h.sleep(250);
        const total = h.total() ?? h.rows().length;
        h.expect(total === n, `${tab} 角标 ${n} = 列表 ${total}`);
      }
    },
  },
  {
    id: "S1-2", title: "工单→待办：退回抢单池的单出现在可抢；抢单后派给我、可抢 −1", perms: ALL,
    url: (fx) => `/flow/tickets/detail/?no=${fx.ticketAssigned}`,
    run: async (h) => {
      // 前提：先造一张池里的单（种子里抢单池是空的）；之后走应用内跳转，状态不丢
      await h.click("button", /改派 \/ 回抢单池/);
      const drawer = await h.waitFor(() => h.one("[role=dialog]"), "改派抽屉");
      const sel = drawer.querySelector("select");
      h.fill(sel, [...sel.options].find((o) => /退回抢单池/.test(o.textContent)).value);
      h.fill(drawer.querySelector("textarea"), "造可抢单（e2e）");
      await h.click("[role=dialog] button", /^(保存|提交|确定|确认)$/, "提交改派");
      await h.toast(/已退回抢单池/, "已退回抢单池");
      await h.click("a", "待办中心", "侧栏的待办中心");
      await h.click("button", /^可抢单/);
      const before = await h.waitFor(() => h.tabNum(/^可抢单/), "可抢数");
      await h.click("button", "抢单");
      await h.toast(/已领取/, "已领取");
      const after = await h.waitFor(() => { const v = h.tabNum(/^可抢单/); return v === before - 1 ? v : null; }, "可抢数减一");
      h.expect(after === before - 1, `可抢 ${before} → ${after}`);
    },
  },

  // ── S2 审批 ──────────────────────────────────────────────────────────
  {
    id: "S2-1", title: "审批：驳回不填原因被拒；通过后进入条件会签步；跨步互斥挡住同一人", perms: ALL,
    url: (fx) => `/flow/approvals/detail/?no=${fx.approvalTwoStep}`,
    run: async (h) => {
      await h.waitFor(() => h.btn("通过"), "通过按钮");
      await h.click("button", "驳回");
      // 前端先拦（输入框下提示），拦不住时服务端 422 也会给提示 —— 两者之一出现即可
      await h.waitFor(() => /驳回必须填写原因/.test(h.bodyText() + h.toasts()), "驳回必须填写原因");
      h.expect(/待审/.test(h.bodyText()), "单子仍是待审");
      await h.click("button", "通过");
      await h.toast(/已进入「.+」步/, "已进入下一步");
      // 等服务给出的 whyNot（错误键挂在 data-why 上），而不是常驻的规则说明 —— 后者一直在，等了等于没等。
      // 按键断言：与语言无关，对 mock 与真实 OpsKit 同样成立
      await h.waitFor(() => h.one('[data-why="opskit.approval.cross_step"]'), "服务给出跨步互斥（opskit.approval.cross_step）");
      const b = h.btn("通过");
      h.expect(!b || b.disabled, "同一人不能再审后一步（通过按钮不可用）");
    },
  },
  {
    id: "S2-2", title: "审批：提交人不能审自己；只有提交人能撤回", perms: ALL,
    url: (fx) => `/flow/approvals/detail/?no=${fx.approvalMine}`,
    run: async (h) => {
      await h.waitFor(() => h.one('[data-why="opskit.approval.self_review"]'), "提交人不能审自己（opskit.approval.self_review）");
      const b = h.btn("通过");
      h.expect(!b || b.disabled, "通过按钮不可用");
      await h.click("button", "撤回");
      await h.click("[role=dialog] button, [role=alertdialog] button", /^(撤回|确认|确定)$/, "确认撤回");
      await h.waitFor(() => /已撤回/.test(h.txt(h.one("main h1")?.parentElement)), "页头状态变为已撤回");
      await h.waitFor(() => !h.btn("撤回"), "撤回按钮消失");
      h.expect(true, "撤回后页头为已撤回、不再能撤回");
    },
  },

  // ── S3 审核队列 ──────────────────────────────────────────────────────
  {
    id: "S3-1", title: "队列：领取进「我在审」、待领 −1；放回后恢复", perms: ALL, url: () => "/flow/queue/",
    run: async (h) => {
      const tab = /待领 \d+$/;
      const before = await h.waitFor(() => h.tabNum(tab), "待领数");
      const mine0 = await h.waitFor(() => { const m = h.bodyText().match(/我在审（(\d+)）/); return m ? Number(m[1]) : null; }, "我在审数");
      await h.click("button", "领取");
      await h.toast(/已领取/, "已领取");
      await h.waitFor(() => h.tabNum(tab) === before - 1, "待领减一");
      await h.waitFor(() => new RegExp(`我在审（${mine0 + 1}）`).test(h.bodyText()), "我在审加一");
      await h.click("button", "放回");
      await h.toast(/已放回/, "已放回");
      await h.waitFor(() => h.tabNum(tab) === before, "待领恢复");
      h.expect(true, `待领 ${before} → ${before - 1} → ${before}`);
    },
  },
  {
    id: "S3-2", title: "队列：通过后从队列消失", perms: ALL, url: () => "/flow/queue/",
    run: async (h) => {
      const title = await h.waitFor(() => h.txt(h.rows()[0]?.querySelector("td")), "我在审第一行");
      await h.click("button", "通过");
      await h.toast(/已通过/, "已通过");
      await h.waitFor(() => !h.rows().some((r) => h.txt(r.querySelector("td")) === title), "该单从列表消失");
      h.expect(true, `「${title}」已办结`);
    },
  },

  // ── S4 审批模板 ──────────────────────────────────────────────────────
  {
    id: "S4-1", title: "模板：内容不变再发布是幂等的；改了才生成新版本；会签对角色不可选", perms: ALL, url: () => "/flow/processes/",
    run: async (h) => {
      await h.click("button", "编辑");
      await h.waitFor(() => h.btn("保存并发布"), "编辑区");
      await h.click("button", "保存并发布");
      await h.toast(/没有变化/, "内容没有变化");
      const kind = document.querySelectorAll("select")[0];
      h.fill(kind, "ROLE"); await h.sleep(150);
      const mode = [...document.querySelectorAll("select")].find((s) => [...s.options].some((o) => o.value === "ALL"));
      h.expect([...mode.options].find((o) => o.value === "ALL").disabled, "按角色时会签选项灰掉");
      h.fill(kind, "PERM"); await h.sleep(100);
      const due = h.all("input[type=number]")[0];
      h.fill(due, String(Number(due.value) + 1));
      await h.click("button", "保存并发布");
      await h.toast(/已发布新版本 [0-9a-f]{8}/, "已发布新版本");
      await h.waitFor(() => /· 当前/.test(h.bodyText()), "版本历史出现当前版本");
    },
  },
  {
    id: "S4-2", title: "模板：校验错误一次全给，挂到对应字段", perms: ALL, url: () => "/flow/processes/",
    run: async (h) => {
      await h.click("button", /新建流程/);
      await h.click("button", "保存并发布");
      await h.toast(/保存被拒/, "保存被拒");
      const t = h.bodyText();
      h.expect(/代号 3–32 位/.test(t), "代号错误挂在代号下");
      h.expect(/模板名称必填/.test(t), "名称错误挂在名称下");
      h.expect(/审批人必填/.test(t), "审批人错误挂在步骤里");
    },
  },

  // ── S5 权限 ──────────────────────────────────────────────────────────
  {
    id: "S5-1", title: "权限：只读角色进配置页看到说明，不白屏", perms: VIEWER, url: () => "/flow/processes/",
    run: async (h) => {
      await h.waitFor(() => /没有「流程配置」的查看权限（flow:process:manage）/.test(h.bodyText()), "缺权限说明");
      h.expect(!h.btn("保存并发布"), "没有编辑入口");
    },
  },
  {
    id: "S5-2", title: "权限：只读角色在工作台不能批量改派", perms: VIEWER, url: () => "/flow/tickets/",
    run: async (h) => {
      await h.waitFor(() => /仅可查看：当前角色无派单与改派权限（flow:ticket:dispatch）/.test(h.bodyText()), "只读说明");
      h.expect(h.all("[data-slot=tbody] input[type=checkbox]").length === 0, "行上没有勾选框");
    },
  },
  {
    id: "S5-3", title: "权限：只读角色在审批详情不能决定", perms: VIEWER, url: (fx) => `/flow/approvals/detail/?no=${fx.approvalTwoStep}`,
    run: async (h) => {
      await h.waitFor(() => /仅可查看：当前角色无审批决定权限/.test(h.bodyText()), "只读说明");
      const b = h.btn("通过");
      h.expect(!b || b.disabled, "通过按钮不可用");
    },
  },

  // ── S6 工单 ──────────────────────────────────────────────────────────
  {
    id: "S6-1", title: "工单：每张统计卡的数字 = 点进去的列表条数", perms: ALL, url: () => "/flow/tickets/",
    run: async (h) => {
      const cards = await h.waitFor(() => { const c = h.all("[data-surface=stat]"); return c.length >= 3 && !c[0].disabled ? c : null; }, "统计卡");
      for (let i = 0; i < cards.length; i++) {
        const card = h.all("[data-surface=stat]")[i];
        const label = h.txt(card.querySelector("div"));
        const n = Number(h.txt(card.querySelector("[data-slot=value]")));
        card.click();
        await h.waitFor(() => card.getAttribute("aria-pressed") === "true", "卡片选中");
        await h.sleep(350);
        const total = h.total() ?? h.rows().length;
        h.expect(total === n, `「${label}」${n} = 列表 ${total}`);
        card.click(); await h.sleep(250);
      }
    },
  },
  {
    id: "S6-2", title: "工单：退回抢单池要原因，退回后受理方清空、时间线留痕", perms: ALL,
    url: (fx) => `/flow/tickets/detail/?no=${fx.ticketAssigned}`,
    run: async (h) => {
      await h.click("button", /改派 \/ 回抢单池/);
      const drawer = await h.waitFor(() => h.one("[role=dialog]"), "改派抽屉");
      const sel = drawer.querySelector("select");
      const pool = [...sel.options].find((o) => /退回抢单池/.test(o.textContent));
      h.fill(sel, pool.value);
      h.fill(drawer.querySelector("textarea"), "受理方联系不上（e2e）");
      await h.click("[role=dialog] button", /^(保存|提交|确定|确认)$/, "提交改派");
      await h.toast(/已退回抢单池/, "已退回抢单池");
      await h.waitFor(() => /受理方联系不上（e2e）/.test(h.bodyText()), "时间线里有原因");
      h.expect(/未派|抢单池/.test(h.bodyText()), "受理方显示为抢单池");
    },
  },

  // ── I 三语与 RTL（界面文字；服务返回的数据不在此列）────────────────────────
  ...["en", "ar"].flatMap((locale) => ["/flow/todo/", "/flow/approvals/", "/flow/queue/", "/flow/tickets/", "/flow/processes/", "/flow/settings/", "/flow/access/"]
    .map((url, i) => ({
      id: `I-${locale}-${i + 1}`, title: `${locale}：${url} 界面文字无中文、方向正确`, perms: ALL, locale, url: () => url,
      run: async (h) => {
        await h.waitFor(() => h.all("main h1").length, "页面标题");
        await h.sleep(400);
        const want = document.documentElement.lang.startsWith("ar") ? "rtl" : "ltr";
        h.expect(document.documentElement.dir === want, `dir=${want}`);
        const hits = h.all("main h1, main th, main label, main [role=tab], main input[placeholder], main [data-slot=notice]")
          .map((e) => (e.placeholder || h.txt(e))).filter((t) => /[一-鿿]/.test(t));
        h.expect(hits.length === 0, `界面文字无中文${hits.length ? "：" + hits.slice(0, 5).join(" / ") : ""}`);
      },
    }))),
];
