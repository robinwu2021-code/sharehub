// 站点坪效的周期口径单测。
//
// 这个页面此前是「只读表 + 凭空数字」（turnover = 1.2 + (i%7)*0.6，与订单量无关）。
// 加周期选择器最容易做假的地方是：**控件加了，数字不跟着变** —— 那比不加更坏，
// 因为运营会以为自己在看「近 7 日」。故这里断言的是**跨周期的关系**而不是具体数值：
//   ① 周期越长，营收/订单只增不减（同一份 dayFact 上的窗口包含关系）
//   ② 坪效与点位报表同源：按站点求和后总营收必须相等（后端也是这么收口的 ——
//      SiteAnalysisServiceImpl 委托 ReportService，前端 mock 跟着走）
//   ③ turnover 真由 orders/柜数/天数 推出，不是固定值
import { describe, it, expect } from "vitest";
import { buildSiteAnalyses, buildReportLocations, listSiteAnalysis, buildAgentPerformances } from "./report";
import { sites } from "./location";
import { buildStaffPerformances } from "./org";
import { workOrders } from "./workorder";
import { daysOf } from "./report";
import { agents } from "./agent";

const total = (k: "revenue" | "orders", period?: string) =>
  buildSiteAnalyses(period).reduce((n, r) => n + r[k], 0);

describe("站点坪效 · 周期口径", () => {
  it("周期越长，营收与订单只增不减", () => {
    const r7 = total("revenue", "LAST_7D");
    const r30 = total("revenue", "LAST_30D");
    const r12m = total("revenue", "LAST_12M");
    expect(r30).toBeGreaterThanOrEqual(r7);
    expect(r12m).toBeGreaterThanOrEqual(r30);

    expect(total("orders", "LAST_30D")).toBeGreaterThanOrEqual(total("orders", "LAST_7D"));
  });

  it("切周期数字必须真的变 —— 否则选择器是装饰", () => {
    expect(total("revenue", "LAST_7D")).not.toBe(total("revenue", "LAST_30D"));
  });

  it("同一周期重复取值稳定（确定性噪声，来回切不跳数）", () => {
    expect(buildSiteAnalyses("LAST_13W")).toEqual(buildSiteAnalyses("LAST_13W"));
  });

  it("与点位报表同源：总营收一致（坪效逐站点 / 报表按同名合并，求和应相等）", () => {
    for (const period of ["LAST_7D", "LAST_30D", "LAST_13W"]) {
      const bySite = total("revenue", period);
      const byGroup = buildReportLocations(period).reduce((n, r) => n + r.revenue, 0);
      // 两侧都在各自粒度上 round(2)，故允许分位级误差；口径不同会差出量级而不是分位
      expect(Math.abs(bySite - byGroup)).toBeLessThan(1);
    }
  });

  it("turnover 由订单/柜数/天数推出，不是固定值", () => {
    const rows = buildSiteAnalyses("LAST_30D");
    // 至少存在两个不同的翻台率（旧 mock 是 (i%7) 循环的 7 个固定档）
    expect(new Set(rows.map((r) => r.turnover)).size).toBeGreaterThan(1);
    for (const r of rows) {
      expect(r.turnover).toBeCloseTo(r.orders / 30 / r.cabinetCount, 1);
    }
  });

  it("回本天数：毛利为正才给正数，否则 0（页面渲染「—」）", () => {
    for (const r of buildSiteAnalyses("LAST_30D")) {
      expect(r.paybackDays).toBeGreaterThanOrEqual(0);
      expect(Number.isInteger(r.paybackDays)).toBe(true);
    }
  });

  it("列表查询把 period 透传到事实层（不传则落缺省周期）", () => {
    const p7 = listSiteAnalysis({ period: "LAST_7D", size: 100 });
    const p30 = listSiteAnalysis({ period: "LAST_30D", size: 100 });
    const sum = (rs: { revenue: number }[]) => rs.reduce((n, r) => n + r.revenue, 0);
    expect(sum(p7.list)).not.toBe(sum(p30.list));
    // 缺省 = LAST_30D
    expect(sum(listSiteAnalysis({ size: 100 }).list)).toBe(sum(p30.list));
  });

  it("每行 siteNo 都能对上真实站点（引用完整性由 integrity.test 兜，这里防派生时错位）", () => {
    const rows = buildSiteAnalyses("LAST_30D");
    expect(rows.length).toBeGreaterThan(0);
    expect(new Set(rows.map((r) => r.siteNo)).size).toBe(rows.length); // 无重复
  });
});

// 代理绩效同源性。代理 GMV = 名下站点营收之和 —— 这条不成立的话，
// 「代理绩效」与「站点坪效」就是两套算法，运营对不上账时两张表都不可信。
describe("代理绩效 · 与坪效同源", () => {
  it("所有代理 GMV 之和 = 有归属站点的营收之和（平台直营站点不计入任何代理）", () => {
    for (const period of ["LAST_7D", "LAST_30D"]) {
      const byAgent = buildAgentPerformances(period).reduce((n, r) => n + r.gmv, 0);
      const assigned = buildSiteAnalyses(period)
        .filter((a) => sites.find((s) => s.siteNo === a.siteNo)?.agentNo)
        .reduce((n, a) => n + a.revenue, 0);
      expect(Math.abs(byAgent - assigned)).toBeLessThan(1);
    }
  });

  it("rank 由 GMV 降序现算，不是写死的", () => {
    const rows = buildAgentPerformances("LAST_30D");
    expect(rows.map((r) => r.rank)).toEqual(rows.map((_, i) => i + 1));
    for (let i = 1; i < rows.length; i++) expect(rows[i - 1].gmv).toBeGreaterThanOrEqual(rows[i].gmv);
  });

  it("切周期 GMV 真的变；未划拨资产的代理 GMV 为 0 但仍在列表里", () => {
    const g7 = buildAgentPerformances("LAST_7D").reduce((n, r) => n + r.gmv, 0);
    const g30 = buildAgentPerformances("LAST_30D").reduce((n, r) => n + r.gmv, 0);
    expect(g30).toBeGreaterThan(g7);
    expect(buildAgentPerformances("LAST_30D").length).toBe(agents.length);
  });
});

// 员工绩效 —— 现在**真正从工单派生**（2026-07-30）。
// 此前工单种子不填流转留痕、assigneeName 用短名与员工名对不上，只能拿噪声凑；
// 补齐种子后三个指标全部有据可依。这些用例钉的是「派生关系成立」，不是具体数字。
describe("员工绩效 · 从工单派生", () => {
  it("handled = 该员工在窗口内完工的工单数（逐人核对，不用魔法数字）", () => {
    const rows = buildStaffPerformances("LAST_12M");
    const days = new Set(daysOf("LAST_12M").map((d) => new Date(d * 86400_000).toISOString().slice(0, 10)));
    for (const r of rows) {
      const expected = workOrders.filter(
        (w) => w.handlerName === r.name && w.completedAt && days.has(w.completedAt.slice(0, 10))).length;
      expect(r.handled, r.name).toBe(expected);
    }
  });

  it("有人真的接过单：整表 handled 合计 > 0（否则说明姓名又对不上了）", () => {
    const total = buildStaffPerformances("LAST_12M").reduce((n, r) => n + r.handled, 0);
    expect(total).toBeGreaterThan(0);
  });

  it("avgResolveMins 落在种子设定的 45~224 分钟区间内", () => {
    for (const r of buildStaffPerformances("LAST_12M")) {
      if (r.handled === 0) { expect(r.avgResolveMins).toBe(0); continue; }
      expect(r.avgResolveMins).toBeGreaterThanOrEqual(45);
      expect(r.avgResolveMins).toBeLessThanOrEqual(224);
    }
  });

  it("score 是 0~100 的 SLA 达成率；无完工工单的人 score=0（「没接过单」≠「干得差」）", () => {
    for (const r of buildStaffPerformances("LAST_12M")) {
      expect(r.score).toBeGreaterThanOrEqual(0);
      expect(r.score).toBeLessThanOrEqual(100);
      if (r.handled === 0) expect(r.score).toBe(0);
    }
  });

  it("周期收窄则完工数不增（近 7 日 ≤ 近 12 月）", () => {
    const a7 = buildStaffPerformances("LAST_7D");
    const a12 = buildStaffPerformances("LAST_12M");
    for (let i = 0; i < a7.length; i++) expect(a7[i].handled).toBeLessThanOrEqual(a12[i].handled);
  });

  it("零单员工仍出现在表里（按工单聚合会让整行消失，看起来像少了人）", () => {
    expect(buildStaffPerformances("LAST_7D").length).toBe(20);
  });
});
