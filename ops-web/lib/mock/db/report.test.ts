// 报表域（S3）：周期聚合的自洽性测试。
//
// 报表页的假不是「点了没反应」，而是「几个数字互相矛盾」——表格合计 80 万、
// 折线合计 60 万、汇总条又是第三个数，看的人立刻不再信这个系统。所以这里断言的
// 全是**跨视图的等式**，而不是某个具体数字长什么样：
//   ① 表格 Σ = 图表 Σ = 汇总条（三处同源）
//   ② 切周期数字真的变（周期选择器不是装饰）
//   ③ 大屏 KPI = 它下面那条曲线的合计 = 排行榜合计
//   ④ 消费者漏斗锚在人群分层表上，画像各维度合计 = 总人数
import { describe, expect, it } from "vitest";
import {
  bucketsOf, daysOf, normalizePeriod,
  buildReportDevices, buildReportLocations, buildReportFinances, buildReportTrend,
  buildScreenBoard, buildReportCustom, buildConsumerInsight,
  listReportDevice, listReportFinance, listReportCustom, listReportMetrics,
  reportCustoms,
} from "./report";
import { sites } from "./location";
import { cabinets } from "./device";
import { consumerSegments } from "./user";
import { REPORT_METRICS } from "../../types";

const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);

describe("周期切桶", () => {
  it("桶数 = 天数 / 桶宽，无残桶", () => {
    expect(bucketsOf("LAST_7D")).toHaveLength(7);
    expect(bucketsOf("LAST_30D")).toHaveLength(30);
    expect(bucketsOf("LAST_13W")).toHaveLength(13);
    expect(bucketsOf("LAST_12M")).toHaveLength(12);
    for (const p of ["LAST_7D", "LAST_13W", "LAST_12M"]) {
      const b = bucketsOf(p);
      expect(new Set(b.map((x) => x.days.length)).size).toBe(1); // 每桶等宽
    }
  });

  it("非法/缺省周期落回近 30 日，不抛错", () => {
    expect(normalizePeriod(undefined)).toBe("LAST_30D");
    expect(normalizePeriod("LAST_999Y")).toBe("LAST_30D");
    expect(daysOf(undefined)).toHaveLength(30);
  });

  it("桶标签唯一（财务报表拿它当 rowKey）", () => {
    for (const p of ["LAST_7D", "LAST_30D", "LAST_13W", "LAST_12M"]) {
      const labels = bucketsOf(p).map((b) => b.label);
      expect(new Set(labels).size, p).toBe(labels.length);
    }
  });
});

describe("表格与图表同源", () => {
  it("点位坪效 Σ营收 = 趋势 Σ营收 = 汇总条「总营收」", () => {
    for (const p of ["LAST_7D", "LAST_30D", "LAST_13W"]) {
      const rows = buildReportLocations(p);
      const trend = buildReportTrend("LOCATION", p);
      const tableSum = sum(rows.map((r) => r.revenue));
      const chartSum = sum(trend.points.map((x) => x.revenue));
      const summary = trend.summary.find((s) => s.label === "总营收")!.value;
      expect(chartSum, p).toBeCloseTo(tableSum, 0);
      expect(summary, p).toBeCloseTo(tableSum, 0);
    }
  });

  it("财务报表一行就是趋势一个点（逐点相等，不只是合计相等）", () => {
    const rows = buildReportFinances("LAST_13W");
    const trend = buildReportTrend("FINANCE", "LAST_13W");
    expect(rows.map((r) => r.period)).toEqual(trend.points.map((p) => p.bucket));
    rows.forEach((r, i) => {
      expect(r.gmv).toBeCloseTo(trend.points[i].revenue, 2);
      expect(r.share).toBeCloseTo(trend.points[i].share, 2);
      expect(r.net).toBeCloseTo(trend.points[i].net, 2);
    });
  });

  it("设备报表 Σ订单 = 趋势 Σ订单；在线率均值一致", () => {
    const rows = buildReportDevices("LAST_30D");
    const trend = buildReportTrend("DEVICE", "LAST_30D");
    expect(sum(trend.points.map((p) => p.orders))).toBe(sum(rows.map((r) => r.orders)));
    const summaryOnline = trend.summary.find((s) => s.label === "平均在线率")!.value;
    expect(summaryOnline).toBeCloseTo(sum(rows.map((r) => r.onlineRate)) / rows.length, 2);
  });

  it("坪效口径闭合：净收入 = 营收 − 成本，ROI 由两者算出", () => {
    for (const r of buildReportLocations("LAST_30D")) {
      expect(r.roi).toBeCloseTo((r.revenue - r.cost) / r.cost, 2);
    }
    for (const f of buildReportFinances("LAST_30D")) {
      expect(f.settle).toBeCloseTo(f.share * 0.9, 1);
      expect(f.net).toBeLessThan(f.gmv);
    }
  });
});

describe("周期真的改变数字", () => {
  it("周期越长营收/订单越多（不是把同一份数换个标题）", () => {
    const d7 = sum(buildReportLocations("LAST_7D").map((r) => r.revenue));
    const d30 = sum(buildReportLocations("LAST_30D").map((r) => r.revenue));
    const w13 = sum(buildReportLocations("LAST_13W").map((r) => r.revenue));
    expect(d30).toBeGreaterThan(d7);
    expect(w13).toBeGreaterThan(d30);
  });

  it("同一周期重复取值完全一致（确定性，切来切去数字不跳）", () => {
    expect(buildReportDevices("LAST_7D")).toEqual(buildReportDevices("LAST_7D"));
    expect(buildScreenBoard()).toEqual(buildScreenBoard());
  });

  it("列表入口把周期透传下去（分页仍然是 1 起）", () => {
    const a = listReportFinance({ page: 1, size: 10, period: "LAST_7D" });
    const b = listReportFinance({ page: 1, size: 10, period: "LAST_12M" });
    expect(a.total).toBe(7);
    expect(b.total).toBe(12);
    expect(a.list[0].gmv).not.toBe(b.list[0].gmv);
  });

  it("行主体唯一：站点按名字聚合，不出同名两行", () => {
    const names = buildReportDevices("LAST_7D").map((r) => r.locationName);
    expect(new Set(names).size).toBe(names.length);
    expect(new Set(sites.map((s) => s.name)).size).toBe(names.length); // 覆盖全部站点名
  });

  it("关键词过滤仍生效（周期与搜索可叠加）", () => {
    const one = listReportDevice({ page: 1, size: 10, keyword: "DXB", period: "LAST_7D" });
    expect(one.total).toBe(1);
    expect(one.list[0].locationName).toContain("DXB");
  });
});

describe("实时大屏", () => {
  const b = buildScreenBoard();

  it("KPI 今日 GMV/订单 = 分时曲线合计", () => {
    const gmv = b.kpis.find((k) => k.metric === "今日GMV")!.value;
    const orders = b.kpis.find((k) => k.metric === "今日订单")!.value;
    expect(gmv).toBeCloseTo(sum(b.today.map((p) => p.gmv)), 1);
    expect(orders).toBe(sum(b.today.map((p) => p.orders)));
  });

  it("排行榜合计 = 今日 GMV/订单，排名连续且降序", () => {
    const gmv = b.kpis.find((k) => k.metric === "今日GMV")!.value;
    expect(sum(b.ranking.map((r) => r.gmv))).toBeCloseTo(gmv, 1);
    expect(b.ranking.map((r) => r.rank)).toEqual(b.ranking.map((_, i) => i + 1));
    for (let i = 1; i < b.ranking.length; i++) {
      expect(b.ranking[i - 1].gmv).toBeGreaterThanOrEqual(b.ranking[i].gmv);
    }
  });

  it("设备类 KPI 数的是真机柜（与设备台账点得上）", () => {
    const online = cabinets.filter((c) => c.onlineStatus === "ONLINE").length;
    expect(b.kpis.find((k) => k.metric === "在线柜机")!.value).toBe(online);
    expect(sum(b.cabinetStatus.map((s) => s.value))).toBe(cabinets.length);
    expect(b.cabinetStatus.every((s) => s.value >= 0)).toBe(true);
  });

  it("存量口径 KPI 环比为 0（无历史快照时不编环比）", () => {
    for (const m of ["在线柜机", "在线率", "借出中充电宝", "待处理工单"]) {
      expect(b.kpis.find((k) => k.metric === m)!.trend, m).toBe(0);
    }
  });
});

describe("自定义报表：自选指标", () => {
  it("只返回勾选的指标，且指标必须在目录内（乱传被丢掉）", () => {
    const rows = buildReportCustom("SITE", "LAST_7D", ["GMV", "FAULT_RATE", "NOT_A_METRIC"]);
    expect(new Set(rows.map((r) => r.metric))).toEqual(new Set(["GMV", "FAULT_RATE"]));
  });

  it("未指定指标时给默认三项，不返回空表", () => {
    const rows = buildReportCustom("SITE", "LAST_7D");
    expect(new Set(rows.map((r) => r.metric))).toEqual(new Set(["GMV", "ORDERS", "AOV"]));
  });

  it("三种维度都有分组，且维度值不重复", () => {
    for (const dim of ["SITE", "SCENE", "MONTH"]) {
      const rows = buildReportCustom(dim, "LAST_12M", ["GMV"]);
      const dims = rows.map((r) => r.dim);
      expect(new Set(dims).size, dim).toBe(dims.length);
      expect(dims.length, dim).toBeGreaterThan(1);
    }
  });

  it("自定义报表的 GMV 与坪效报表的营收对得上（同一批事实）", () => {
    const custom = sum(buildReportCustom("SITE", "LAST_30D", ["GMV"]).map((r) => r.value));
    const loc = sum(buildReportLocations("LAST_30D").map((r) => r.revenue));
    expect(custom).toBeCloseTo(loc, 0);
    // 换维度只是重新分组，合计不变
    const byScene = sum(buildReportCustom("SCENE", "LAST_30D", ["GMV"]).map((r) => r.value));
    expect(byScene).toBeCloseTo(loc, 0);
  });

  it("csv 形式的 metrics 参数被拆开（与 http qs 序列化形态一致）", () => {
    const r = listReportCustom({ page: 1, size: 200, dim: "SITE", metrics: "GMV, ORDERS" });
    expect(new Set(r.list.map((x) => x.metric))).toEqual(new Set(["GMV", "ORDERS"]));
  });

  it("指标目录与 types 常量同源（页面勾选框与算子不会走偏）", () => {
    expect(listReportMetrics().map((m) => m.key)).toEqual(REPORT_METRICS.map((m) => m.key));
  });

  it("缺省快照 reportCustoms 的维度值仍是真实站点名（引用完整性依赖）", () => {
    const names = new Set(sites.map((s) => s.name));
    expect(reportCustoms.every((r) => names.has(r.dim))).toBe(true);
  });
});

describe("消费者洞察", () => {
  const insight = buildConsumerInsight();

  it("漏斗锚在人群分层表：成功借出 = Σ人群用户数", () => {
    const total = sum(consumerSegments.map((s) => s.userCount));
    expect(insight.totalUsers).toBe(total);
    expect(insight.funnel.find((f) => f.stage === "成功借出")!.users).toBe(total);
  });

  it("漏斗逐级递减，转化率/流失率与人数自洽", () => {
    for (let i = 1; i < insight.funnel.length; i++) {
      const prev = insight.funnel[i - 1];
      const cur = insight.funnel[i];
      expect(cur.users).toBeLessThan(prev.users);
      expect(cur.dropRate).toBeCloseTo(1 - cur.users / prev.users, 3);
      expect(cur.rate).toBeCloseTo(cur.users / insight.funnel[0].users, 3);
    }
    expect(insight.funnel[0].dropRate).toBe(0);
  });

  it("画像每个维度合计 = 总人数，份额合计 = 1", () => {
    const dims = [...new Set(insight.profiles.map((p) => p.dim))];
    expect(dims.length).toBeGreaterThan(1);
    for (const d of dims) {
      const slices = insight.profiles.filter((p) => p.dim === d);
      expect(sum(slices.map((s) => s.value)), d).toBe(insight.totalUsers);
      expect(sum(slices.map((s) => s.share)), d).toBeCloseTo(1, 2);
    }
  });
});
