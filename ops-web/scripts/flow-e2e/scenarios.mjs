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
        // 0 是合法的角标（某个页签可能确实为空）—— 别让 waitFor 把 0 当「没取到」
        const n = await h.num(() => h.tabNum(tab), "页签数字");
        await h.sleep(250);
        const total = h.total() ?? h.rows().length;
        h.expect(total === n, `${tab} 角标 ${n} = 列表 ${total}`);
      }
    },
  },
  {
    id: "S1-2", modes: ["mock"], title: "工单→待办：退回抢单池的单出现在可抢；抢单后派给我、可抢 −1", perms: ALL,
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
      const before = await h.num(() => h.tabNum(/^可抢单/), "可抢数");
      await h.click("button", "抢单");
      await h.toast(/已领取/, "已领取");
      const after = await h.waitFor(() => { const v = h.tabNum(/^可抢单/); return v === before - 1 ? v : null; }, "可抢数减一");
      h.expect(after === before - 1, `可抢 ${before} → ${after}`);
    },
  },

  {
    id: "S1-3", modes: ["opskit"], title: "待办：候选但不在我数据范围内的单不进待办（负对照）", perms: ALL, url: () => "/flow/todo/",
    run: async (h, fx) => {
      await h.click("button", /^全部/);
      await h.waitFor(() => h.total() !== null, "列表加载");
      h.expect(h.total() >= 1, "待办非空（确认不是整页跑空）");
      const hrefs = h.all("a").map((a) => a.getAttribute("href") || "");
      h.expect(!hrefs.some((x) => x.includes(fx.approvalHidden)), `${fx.approvalHidden}（site:B）不在待办里`);
      h.expect(hrefs.some((x) => x.includes(fx.approvalTwoStep)), "同一候选、范围内的单在待办里（正对照）");
      // 后端 9-28 统一可见性：列表、详情、决定同一条规则 —— 审批列表里也不该有它
      await h.click("a", "审批列表", "侧栏的审批列表");
      await h.waitFor(() => h.total() !== null && h.rows().length, "审批列表加载");
      const inList = h.all("a").some((a) => (a.getAttribute("href") || "").includes(fx.approvalHidden));
      h.expect(!inList, "审批列表里也看不到它");
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
      const before = await h.num(() => h.tabNum(tab), "待领数");
      const mine0 = await h.num(() => { const m = h.bodyText().match(/我在审（(\d+)）/); return m ? Number(m[1]) : NaN; }, "我在审数");
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
      // 先领一条（真服务种子里「我在审」是空的；mock 里已有一条也无妨），再对「我在审」第一行做决定
      await h.click("button", "领取");
      await h.toast(/已领取/, "已领取");
      const title = await h.waitFor(() => h.txt(h.rows()[0]?.querySelector("td")), "我在审第一行");
      await h.click("button", "通过");
      await h.toast(/已通过/, "已通过");
      await h.waitFor(() => !h.rows().some((r) => h.txt(r.querySelector("td")) === title), "该单从列表消失");
      h.expect(true, `「${title}」已办结`);
    },
  },

  // ── S4 审批模板 ──────────────────────────────────────────────────────
  {
    id: "S4-1", as: "admin", title: "模板：内容不变再发布是幂等的；改了才生成新版本；会签对角色不可选", perms: ALL, url: () => "/flow/processes/",
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
    id: "S4-2", modes: ["mock"], title: "模板：校验错误一次全给，挂到对应字段", perms: ALL, url: () => "/flow/processes/",
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
    id: "S6-1", modes: ["mock"], title: "工单：每张统计卡的数字 = 点进去的列表条数", perms: ALL, url: () => "/flow/tickets/",
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
    id: "S6-2", modes: ["mock"], title: "工单：退回抢单池要原因，退回后受理方清空、时间线留痕", perms: ALL,
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

  {
    id: "S6-3", modes: ["mock"], title: "工单：勾选列按需出现；标签筛选只看一类", perms: ALL, url: () => "/flow/tickets/",
    run: async (h) => {
      await h.waitFor(() => h.rows().length, "列表加载");
      h.expect(h.all("[data-slot=tbody] [role=checkbox]").length === 0, "平时没有勾选列");
      await h.click("button", "批量改派");
      await h.waitFor(() => h.all("[data-slot=tbody] [role=checkbox]").length > 0, "进入选择模式后出现勾选列");
      h.all("[data-slot=tbody] [role=checkbox]")[0].click();
      await h.waitFor(() => /已选 1/.test(h.bodyText()), "批量操作条显示已选 1");
      await h.click("button", /清除|取消/, "退出选择");
      await h.waitFor(() => h.all("[data-slot=tbody] [role=checkbox]").length === 0, "退出后勾选列消失");
      const all = h.total();
      const chip = h.all("[role=radio]").find((e) => e.getAttribute("aria-checked") === "false");
      chip.click();
      await h.waitFor(() => chip.getAttribute("aria-checked") === "true" && h.total() !== all, "按类型筛选后条数变化");
      h.expect(h.total() < all, `「${h.txt(chip)}」${h.total()} < 全部 ${all}`);
    },
  },

  {
    id: "S6-5", title: "工单列表：按单号搜索收窄到这一张；点进详情是同一张", perms: ALL, url: () => "/flow/tickets/",
    run: async (h, fx) => {
      await h.waitFor(() => h.rows().length, "列表加载");
      const all = h.total() ?? h.rows().length;
      h.expect(!!fx.ticketAssigned, `夹具里有工单号（${fx.ticketAssigned || "空"}）`);
      const box = await h.waitFor(() => h.one("[data-surface=toolbar] input"), "搜索框");
      h.fill(box, fx.ticketAssigned);
      await h.waitFor(() => h.rows().length === 1 && h.txt(h.rows()[0]).includes(fx.ticketAssigned), "只剩这一张");
      h.expect(all >= 1, `全部 ${all} 条 → 搜 ${fx.ticketAssigned} 剩 1 条`);
      h.rows()[0].querySelector("a[href*='/flow/tickets/detail/']").click();
      await h.waitFor(() => /tickets\/detail/.test(location.pathname) && h.bodyText().includes(fx.ticketAssigned), "详情是同一张");
    },
  },

  {
    id: "S6-6", title: "工单详情：勾掉「仅内部」发公开备注，进时间线",
    perms: ALL, url: (fx) => `/flow/tickets/detail/?no=${fx.ticketAssigned}`,
    run: async (h) => {
      const ta = await h.waitFor(() => h.one("main textarea"), "备注框");
      const text = `现场已联系（e2e ${Date.now() % 100000}）`;
      const internal = await h.waitFor(() => h.all("main [role=checkbox]").find((x) => /仅内部/.test(h.txt(x.closest("label") ?? x.parentElement))), "仅内部勾选");
      if (internal.getAttribute("aria-checked") === "true") internal.click();
      await h.waitFor(() => internal.getAttribute("aria-checked") === "false", "已切到公开");
      h.fill(ta, text);
      await h.click("main button", "提交备注");
      await h.toast(/备注已提交/, "备注已提交");
      await h.waitFor(() => h.bodyText().includes(text), "时间线里出现这条备注");
      h.expect(true, "公开备注进了时间线");
    },
  },

  {
    id: "S6-4", modes: ["mock", "opskit"], title: "工单：表单按类型出优先级与必填字段；空标题被拒；建单后进详情",
    perms: ALL, url: () => "/flow/tickets/",
    run: async (h) => {
      await h.click("button", /新建工单/);
      const dr = await h.waitFor(() => h.one("[role=dialog]"), "建单抽屉");
      const typeSel = dr.querySelector("select");
      h.fill(typeSel, [...typeSel.options].find((o) => o.value)?.value ?? "");
      // 选了类型才出本类型的优先级选项与字段（mock：来源；真服务 repair：站点、来源）
      await h.waitFor(() => dr.querySelectorAll("select").length >= 3 && [...dr.querySelectorAll("select")][1].options.length > 1, "类型驱动的字段出现");
      for (const sel of [...dr.querySelectorAll("select")].slice(1)) {
        if (sel.value || [...sel.options].some((o) => /不涉及/.test(o.textContent))) continue;
        h.fill(sel, [...sel.options].find((o) => o.value)?.value ?? "");
        await h.sleep(50);
      }
      await h.click("[role=dialog] button", /^(保存|提交|确定|确认)$/, "提交");
      await h.sleep(300);
      h.expect(!!h.one("[role=dialog]"), "标题为空时抽屉不关（前端必填拦下）");
      h.fill(dr.querySelector("input:not([type=number])"), "机柜离线（e2e 建单）");
      await h.click("[role=dialog] button", /^(保存|提交|确定|确认)$/, "提交");
      await h.toast(/已建单/, "已建单");
      await h.waitFor(() => /机柜离线（e2e 建单）/.test(h.bodyText()) && /tickets\/detail/.test(location.pathname), "跳到新单详情");
    },
  },

  // ── S7 流程实例 · 对账 · 统计（后端 O2-10）──────────────────────────────
  {
    id: "S7-1", title: "流程实例：筛未结后每行都在进行中；点进详情有流转历史", perms: ALL, url: () => "/flow/instances/",
    run: async (h) => {
      await h.waitFor(() => h.rows().length, "实例列表加载");
      const sel = h.all("select").find((x) => [...x.options].some((o) => o.value === "yes"));
      h.fill(sel, "yes");
      await h.sleep(400);
      await h.waitFor(() => h.rows().length || h.total() === 0, "筛选后加载");
      const texts = h.rows().map((r) => h.txt(r.querySelectorAll("td")[6]));
      h.expect(texts.every((x) => /进行中/.test(x)), `未结的 ${texts.length} 行都显示「进行中」`);
      h.fill(sel, "");
      await h.sleep(300);
      const link = await h.waitFor(() => h.one("[data-slot=tbody] a[href*='/flow/instances/detail/']"), "实例号链接");
      link.click();
      await h.waitFor(() => /流转历史/.test(h.bodyText()), "进入实例详情");
      await h.waitFor(() => h.all("[data-slot=timeline-item]").length >= 2, "流转历史至少两条");
      h.expect(true, `历史 ${h.all("[data-slot=timeline-item]").length} 条`);
    },
  },
  {
    id: "S7-2", title: "对账：从实例列表进入，能看到该业务对象名下的实例与审批", perms: ALL, url: () => "/flow/instances/",
    run: async (h) => {
      // 列表里既有审批流程也有工单流程（工单名下没有审批）：逐个对象进对账页，找到一个名下有审批的即可
      await h.waitFor(() => h.one("[data-slot=tbody] a[href*='/flow/instances/subject/']"), "对账链接");
      const hrefs = [...new Set(h.all("[data-slot=tbody] a[href*='/flow/instances/subject/']").map((a) => a.getAttribute("href")))].slice(0, 8);
      let hit = "";
      for (const href of hrefs) {
        const a = await h.waitFor(() => h.all("[data-slot=tbody] a").find((x) => x.getAttribute("href") === href), "对账链接");
        a.click();
        await h.waitFor(() => /对账：/.test(h.bodyText()), "进入对账页");
        await h.waitFor(() => h.all("a[href*='/flow/instances/detail/']").length >= 1, "至少一个实例");
        await h.sleep(300);
        if (h.all("a[href*='/flow/approvals/detail/']").length >= 1) { hit = href; break; }
        history.back();
        await h.waitFor(() => /\/flow\/instances\/?$/.test(location.pathname) && h.one("[data-slot=tbody] a"), "回到实例列表");
      }
      h.expect(!!hit, `列出了名下的审批（试了 ${hrefs.length} 个对象）`);
    },
  },
  {
    id: "S7-3", as: "admin", title: "流程统计：汇总卡 = 表格逐行相加；换时间段能重新取数", perms: ALL, url: () => "/flow/stats/",
    run: async (h) => {
      await h.waitFor(() => h.rows().length, "统计表加载");
      const sumCol = (i) => h.rows().reduce((a, r) => a + Number(h.txt(r.querySelectorAll("td")[i]) || 0), 0);
      const card = (label) => Number(h.txt([...document.querySelectorAll("main div")].find((d) => d.children.length >= 2 && h.txt(d.children[0]) === label && /^\d+$/.test(h.txt(d.children[1])))?.children[1]));
      h.expect(card("发起") === sumCol(1), `发起 ${card("发起")} = 表格合计 ${sumCol(1)}`);
      h.expect(card("未结") === sumCol(2), `未结 ${card("未结")} = 表格合计 ${sumCol(2)}`);
      await h.click("[role=radio]", "全部", "时间段：全部");
      await h.waitFor(() => h.one("[role=radio][aria-checked=true]") && h.txt(h.one("[role=radio][aria-checked=true]")) === "全部", "切到全部");
      await h.waitFor(() => h.rows().length, "重新取数");
      h.expect(sumCol(1) >= 1, `全部时间段共 ${sumCol(1)} 个实例`);
    },
  },

  // ── S8 SLA 策略（后端 O2-8，通用策略形状）──────────────────────────────
  {
    id: "S8-1", title: "SLA 策略：不改再发布幂等；改时限生成新版本；兜底行不在最后被拒并指到字段", perms: ALL, as: "admin", url: () => "/flow/settings/",
    run: async (h) => {
      await h.waitFor(() => h.all("main table").length && h.btn("编辑"), "SLA 策略列表");
      h.btn("编辑").click();
      const ed = await h.waitFor(() => h.one("[data-slot=sla-editor]"), "编辑器");
      await h.click("[data-slot=sla-editor] button", "保存并发布");
      await h.toast(/没有变化/, "内容没有变化");
      const minute = ed.querySelector("tbody input[type=number]");
      h.fill(minute, String(Number(minute.value || 60) + 1));
      await h.click("[data-slot=sla-editor] button", "保存并发布");
      await h.toast(/已发布新版本/, "已发布新版本");
      // 把第一行改成兜底（不带条件）且不在最后 —— 服务端 / mock 都应按字段路径拒绝
      const rows = ed.querySelectorAll("tbody tr");
      if (rows.length >= 2) {
        h.fill(rows[0].querySelector("select"), "*");
        await h.click("[data-slot=sla-editor] button", "保存并发布");
        // 服务把错误挂在兜底行后面那一行（永远匹配不到的是它），mock 同口径
        await h.waitFor(() => /targets\[1\]/.test(h.txt(ed)), "错误挂到 targets[1]");
        h.expect(true, "兜底行不在最后被拒");
      } else {
        h.step("该策略只有一行，跳过兜底行校验");
      }
    },
  },

  // ── I 三语与 RTL（界面文字；服务返回的数据不在此列）────────────────────────
  ...["en", "ar"].flatMap((locale) => ["/flow/todo/", "/flow/approvals/", "/flow/queue/", "/flow/tickets/", "/flow/processes/", "/flow/settings/", "/flow/access/", "/flow/instances/", "/flow/stats/"]
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
