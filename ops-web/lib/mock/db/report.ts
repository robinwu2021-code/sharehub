// 报表域：设备/点位/财务三张周期报表 + 实时大屏 + 自定义报表 + 消费者洞察。
//
// S3 起整域改成「按日事实 → 周期聚合」，不再是写死的数组。原因：加了周期选择器之后，
// 写死的数字切周期不变；而图表若再各造一份序列，就会出现「表格 12 行合计 80 万、
// 折线合计 60 万」这种一眼假的画面。现在所有口径都从同一份 `dayFact(站点, 日)` 推：
//   · 表格 = 按站点（或桶）聚合   · 图表 = 按桶聚合   · 汇总条 = 全周期聚合
// 三者必然相等，由 report.test.ts 用求和断言兜住。
//
// 两个刻意的口径选择：
//   ① 周期报表统计**到昨日**（T+1 跑批），大屏才是今日实时——否则「今日」在两处
//      分别是整日与到点分时两个数，页面并排放着必然被当成 bug。
//   ② 站点按**名字**聚合：主数据 12 个站点只用了 7 个名字（`sites[].name = p(LOCS, i)`），
//      逐站点出行会出现两行同名不同数，rowKey 也无处可取。名字聚合后行唯一，
//      且 `locationName`/`siteName` 仍是真实站点名（引用完整性测试依赖这点）。
import type {
  ReportDevice, ReportLocation, ReportFinance, ReportScreen, ReportCustom, PageQuery,
  AgentPerformance,
  ReportTrend, ReportTrendKind, ReportTrendPoint, ReportSummaryItem, ReportPeriod,
  ScreenBoard, ScreenBoardPoint, ScreenRankRow, ReportMetricDef,
  ConsumerInsight, ConsumerFunnelStage, ConsumerProfileSlice, SiteAnalysis,
} from "../../types";
import {
  REPORT_PERIODS, REPORT_PERIOD_DEFAULT, REPORT_METRICS, REPORT_METRICS_DEFAULT,
} from "../../types";
import { sites } from "./location";
import { agents } from "./agent";
import { cabinets, powerbanks } from "./device";
import { workOrders } from "./workorder";
import { consumerSegments } from "./user";
import { paginate, kwHit } from "./helpers";

export const REPORT_CURRENCY = "AED";

const DAY_MS = 86400_000;
/** 报表基准「今日」= mock 时间轴基准日（internal.iso 同一天），保证页面数字与测试断言一致。 */
const TODAY = Math.floor(Date.UTC(2026, 6, 11) / DAY_MS);
/** 大屏基准时刻：基准日 12:00 UTC —— 「今日」只统计到这一刻。 */
const BOARD_HOURS = 13; // 00:00 ~ 12:00
const HOURS_PER_DAY = 24;

const r2 = (n: number) => Math.round(n * 100) / 100;
const r4 = (n: number) => Math.round(n * 10000) / 10000;
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
const dayLabel = (d: number) => new Date(d * DAY_MS).toISOString().slice(0, 10);

/** 确定性噪声：同 (站点, 日) 永远同值——否则来回切周期数字会跳，看着像坏了。 */
const noise = (a: number, b: number) => {
  const x = Math.sin((a + 1) * 12.9898 + (b + 1) * 78.233) * 43758.5453;
  return x - Math.floor(x);
};

// 经营口径常量（分润率/结算率/单柜运维成本/单柜投入），坪效与财务报表共用同一套。
const SHARE_RATE = 0.35;
const SETTLE_RATE = 0.9;
const OPEX_PER_CABINET_DAY = 6;
const CABINET_CAPEX = 1800;

// ————————————————————————————————————————————————————————————————
// 事实层：站点 × 日
// ————————————————————————————————————————————————————————————————

interface DayFact {
  orders: number;
  revenue: number;
  opex: number;
  onlineRate: number;
  faultRate: number;
  cabinetCount: number;
}

/** 单站点单日事实。翻台率 2.2~3.8 次/柜/日、客单价 7~12 AED，量级对齐工作台。 */
function dayFact(siteIdx: number, day: number): DayFact {
  const cabinetCount = sites[siteIdx].cabinetCount;
  const orders = Math.round(cabinetCount * (2.2 + noise(siteIdx, day) * 1.6));
  const unitPrice = 7 + noise(siteIdx, day + 31) * 5;
  return {
    orders,
    revenue: r2(orders * unitPrice),
    opex: cabinetCount * OPEX_PER_CABINET_DAY,
    onlineRate: 0.9 + noise(siteIdx, day + 7) * 0.098,
    faultRate: 0.004 + noise(siteIdx, day + 13) * 0.03,
    cabinetCount,
  };
}

/** 同名站点合并成一个报表主体（见文件头 ②）。 */
const SITE_GROUPS = (() => {
  const byName = new Map<string, number[]>();
  sites.forEach((s, i) => byName.set(s.name, [...(byName.get(s.name) ?? []), i]));
  return [...byName.entries()].map(([name, idx]) => ({
    name,
    idx,
    siteNo: sites[idx[0]].siteNo,
    sceneType: sites[idx[0]].sceneType,
    cabinetCount: sum(idx.map((i) => sites[i].cabinetCount)),
  }));
})();

export interface PeriodBucket { label: string; days: number[] }

const periodDef = (period?: string) =>
  REPORT_PERIODS.find((x) => x.value === period) ?? REPORT_PERIODS.find((x) => x.value === REPORT_PERIOD_DEFAULT)!;

/** 归一化周期（页面传了非法值也不该炸，落回缺省）。 */
export const normalizePeriod = (period?: string): ReportPeriod => periodDef(period).value;

/**
 * 周期切桶。结束于**昨日**（T+1 跑批口径），桶宽由周期定义给出。
 * 桶标签随桶宽变：日→`2026-07-10`，周→`07-04~07-10`，月→`2026-07`。
 */
export function bucketsOf(period?: string): PeriodBucket[] {
  const def = periodDef(period);
  const end = TODAY - 1;
  const start = end - def.days + 1;
  const out: PeriodBucket[] = [];
  for (let s = start; s <= end; s += def.bucketDays) {
    const days: number[] = [];
    for (let d = s; d < s + def.bucketDays && d <= end; d++) days.push(d);
    const last = days[days.length - 1];
    const label = def.bucketDays === 1 ? dayLabel(s)
      : def.bucketDays >= 30 ? dayLabel(s).slice(0, 7)
      : `${dayLabel(s).slice(5)}~${dayLabel(last).slice(5)}`;
    out.push({ label, days });
  }
  return out;
}

export const daysOf = (period?: string): number[] => bucketsOf(period).flatMap((b) => b.days);

// ————————————————————————————————————————————————————————————————
// 聚合层：一批 (站点, 日) 事实 → 各口径
// ————————————————————————————————————————————————————————————————

interface Agg {
  orders: number; revenue: number; opex: number;
  onlineRate: number; faultRate: number; // 事实均值
  cabinetCount: number; dayCount: number;
}

function aggOf(siteIdx: number[], days: number[]): Agg {
  const facts = siteIdx.flatMap((i) => days.map((d) => dayFact(i, d)));
  const n = Math.max(1, facts.length);
  return {
    orders: sum(facts.map((f) => f.orders)),
    revenue: r2(sum(facts.map((f) => f.revenue))),
    opex: sum(facts.map((f) => f.opex)),
    onlineRate: r4(sum(facts.map((f) => f.onlineRate)) / n),
    faultRate: r4(sum(facts.map((f) => f.faultRate)) / n),
    cabinetCount: sum(siteIdx.map((i) => sites[i].cabinetCount)),
    dayCount: Math.max(1, days.length),
  };
}

// 派生金额：分润 = GMV×分润率，成本 = 分润 + 运维，净收入 = GMV − 成本。
// 三张报表共用这三个算子，避免「坪效的成本」与「财务的分润」各算一套对不上。
const shareOf = (a: Agg) => r2(a.revenue * SHARE_RATE);
const costOf = (a: Agg) => r2(shareOf(a) + a.opex);
const netOf = (a: Agg) => r2(a.revenue - costOf(a));
const turnoverOf = (a: Agg) => r2(a.orders / a.dayCount / Math.max(1, a.cabinetCount));

const ALL_SITES = sites.map((_, i) => i);

// ————————————————————————————————————————————————————————————————
// 三张周期报表
// ————————————————————————————————————————————————————————————————

export function buildReportDevices(period?: string): ReportDevice[] {
  const days = daysOf(period);
  return SITE_GROUPS.map((g) => {
    const a = aggOf(g.idx, days);
    return {
      locationName: g.name,
      cabinetCount: g.cabinetCount,
      orders: a.orders,
      onlineRate: a.onlineRate,
      faultRate: a.faultRate,
      turnover: turnoverOf(a),
    };
  });
}

/**
 * 代理绩效（`AgentPerformance`）—— 逐代理一行，同样取自 `dayFact`。
 *
 * 代理的 GMV = **他名下站点**在该周期的营收之和（`sites[i].agentNo` 是归属真值）。
 * 这样「代理绩效的 GMV」与「站点坪效的营收」加起来必然对得上；原 mock 是
 * `gmv: 40000 + i*7919 % 200000` 的凭空数，与任何其它页面都无关，切周期也不会动。
 *
 * rank 由 GMV 降序现算 —— 名次是派生量，写死在 fixture 里必然与 GMV 漂移。
 * 无站点的代理（未划拨）GMV 为 0，仍出现在列表里：运营需要看到「有档案但没资产」的代理。
 */
export function buildAgentPerformances(period?: string): AgentPerformance[] {
  const days = daysOf(period);
  const idxByAgent = new Map<string, number[]>();
  sites.forEach((st, i) => {
    if (!st.agentNo) return;                     // 平台直营站点不计入任何代理
    idxByAgent.set(st.agentNo, [...(idxByAgent.get(st.agentNo) ?? []), i]);
  });
  const rows = agents.map((ag) => {
    const idx = idxByAgent.get(ag.agentNo) ?? [];
    const a = idx.length ? aggOf(idx, days) : null;
    return {
      agentNo: ag.agentNo,
      agentName: ag.name,
      gmv: a ? a.revenue : 0,
      cabinetCount: a ? a.cabinetCount : 0,
      onlineRate: a ? a.onlineRate : 0,
      rank: 0,
      currency: REPORT_CURRENCY,
    };
  });
  return rows.sort((x, y) => y.gmv - x.gmv).map((r, i) => ({ ...r, rank: i + 1 }));
}

/**
 * 站点坪效（`SiteAnalysis`）—— 逐站点一行，取自**同一份 dayFact**。
 *
 * 为什么放在 report 域而不是 loc 域：坪效本就是读模型，不是站点的领域概念。
 * 后端已经这么收口了（`SiteAnalysisServiceImpl` 委托 `ReportService.siteRollups()`），
 * 前端 mock 跟着收口，否则「坪效页的营收」与「点位报表的营收」会是两套算法、
 * 同一周期给出两个数 —— 那种矛盾一旦被运营发现，两张表都不可信了。
 *
 * 与 `buildReportLocations` 的区别：那个按**同名站点合并**（报表主体），
 * 这里**逐站点**（运营要按 siteNo 决定某个具体点位的存续），故用 `aggOf([i], days)`。
 *
 * `turnover` 由 orders/柜数/天数真实算出（原 mock 是 `1.2+(i%7)*0.6` 的凭空数），
 * 所以切周期它会跟着变；`paybackDays` 与点位报表同一算子，毛利为负时给 0（页面渲染「—」）。
 */
export function buildSiteAnalyses(period?: string): SiteAnalysis[] {
  const days = daysOf(period);
  return sites.map((site, i) => {
    const a = aggOf([i], days);
    const dailyProfit = (a.revenue - costOf(a)) / a.dayCount;
    return {
      siteNo: site.siteNo,
      siteName: site.name,
      revenue: a.revenue,
      orders: a.orders,
      turnover: turnoverOf(a),
      paybackDays: dailyProfit > 0 ? Math.ceil((a.cabinetCount * CABINET_CAPEX) / dailyProfit) : 0,
      cabinetCount: a.cabinetCount,
      currency: REPORT_CURRENCY,
    };
  });
}

export function buildReportLocations(period?: string): ReportLocation[] {
  const days = daysOf(period);
  return SITE_GROUPS.map((g) => {
    const a = aggOf(g.idx, days);
    const cost = costOf(a);
    const dailyProfit = (a.revenue - cost) / a.dayCount;
    // 回本天数 = 单柜投入×柜数 / 日均毛利；毛利为负则不回本，用 0 表示（页面渲染「—」）。
    const payback = dailyProfit > 0 ? Math.ceil((g.cabinetCount * CABINET_CAPEX) / dailyProfit) : 0;
    return {
      siteName: g.name,
      revenue: a.revenue,
      cost,
      orders: a.orders,
      payback,
      roi: r2((a.revenue - cost) / Math.max(1, cost)),
      currency: REPORT_CURRENCY,
    };
  });
}

/** 财务报表一行 = 图表一个点（桶），所以表与图不可能对不上。 */
export function buildReportFinances(period?: string): ReportFinance[] {
  return bucketsOf(period).map((b) => {
    const a = aggOf(ALL_SITES, b.days);
    const share = shareOf(a);
    return {
      period: b.label,
      gmv: a.revenue,
      share,
      settle: r2(share * SETTLE_RATE),
      net: netOf(a),
      currency: REPORT_CURRENCY,
    };
  });
}

// ————————————————————————————————————————————————————————————————
// 趋势 + 汇总条（一个端点服务三张报表，kind 决定汇总口径）
// ————————————————————————————————————————————————————————————————

function trendPoint(b: PeriodBucket): ReportTrendPoint {
  const a = aggOf(ALL_SITES, b.days);
  return {
    bucket: b.label,
    orders: a.orders,
    revenue: a.revenue,
    cost: costOf(a),
    share: shareOf(a),
    net: netOf(a),
    onlineRate: a.onlineRate,
    faultRate: a.faultRate,
    turnover: turnoverOf(a),
  };
}

function summaryOf(kind: ReportTrendKind, a: Agg): ReportSummaryItem[] {
  if (kind === "DEVICE") {
    return [
      { label: "平均在线率", value: a.onlineRate, format: "RATE" },
      { label: "周期订单", value: a.orders, format: "NUMBER" },
      { label: "平均翻台率", value: turnoverOf(a), format: "NUMBER" },
      { label: "平均故障率", value: a.faultRate, format: "RATE" },
    ];
  }
  if (kind === "LOCATION") {
    return [
      { label: "总营收", value: a.revenue, format: "MONEY" },
      { label: "总成本", value: costOf(a), format: "MONEY" },
      { label: "毛利", value: netOf(a), format: "MONEY" },
      { label: "整体 ROI", value: r2((a.revenue - costOf(a)) / Math.max(1, costOf(a))), format: "RATE" },
    ];
  }
  return [
    { label: "GMV", value: a.revenue, format: "MONEY" },
    { label: "分润", value: shareOf(a), format: "MONEY" },
    { label: "应结算", value: r2(shareOf(a) * SETTLE_RATE), format: "MONEY" },
    { label: "净收入", value: netOf(a), format: "MONEY" },
  ];
}

export function buildReportTrend(kind: string | undefined, period?: string): ReportTrend {
  const k: ReportTrendKind = kind === "DEVICE" || kind === "LOCATION" || kind === "FINANCE" ? kind : "FINANCE";
  const buckets = bucketsOf(period);
  return {
    kind: k,
    period: normalizePeriod(period),
    points: buckets.map(trendPoint),
    summary: summaryOf(k, aggOf(ALL_SITES, buckets.flatMap((b) => b.days))),
    currency: REPORT_CURRENCY,
  };
}

// ————————————————————————————————————————————————————————————————
// 实时大屏（拍板点 #2）
// ————————————————————————————————————————————————————————————————

/** 单站点单日的分时权重（24 小时），用于把整日事实摊到小时上。 */
const hourWeights = (siteIdx: number, day: number) =>
  Array.from({ length: HOURS_PER_DAY }, (_, h) => 0.4 + noise(siteIdx, day * 100 + h + 700));

/** 某日 0 点到 `hours-1` 点的分站点分时量。整日事实按权重摊分，所以合计不会超过整日。 */
function hourlyOf(day: number, hours: number) {
  return SITE_GROUPS.map((g) => {
    const points = Array.from({ length: hours }, () => ({ gmv: 0, orders: 0 }));
    for (const i of g.idx) {
      const f = dayFact(i, day);
      const w = hourWeights(i, day);
      const wAll = sum(w);
      for (let h = 0; h < hours; h++) {
        points[h].gmv = r2(points[h].gmv + f.revenue * (w[h] / wAll));
        points[h].orders += Math.round(f.orders * (w[h] / wAll));
      }
    }
    return { group: g, points };
  });
}

export function buildScreenBoard(): ScreenBoard {
  const bySite = hourlyOf(TODAY, BOARD_HOURS);
  const today: ScreenBoardPoint[] = Array.from({ length: BOARD_HOURS }, (_, h) => ({
    hour: `${String(h).padStart(2, "0")}:00`,
    gmv: r2(sum(bySite.map((s) => s.points[h].gmv))),
    orders: sum(bySite.map((s) => s.points[h].orders)),
  }));
  // KPI 从分时序列反算，不另算一遍——「大屏 KPI 与它下面的曲线对不上」是最刺眼的假。
  const gmvToday = r2(sum(today.map((p) => p.gmv)));
  const ordersToday = sum(today.map((p) => p.orders));

  // 环比：与昨日**同一时段**比（跟整日比会一直显示掉量）。
  const yByS = hourlyOf(TODAY - 1, BOARD_HOURS);
  const gmvYest = r2(sum(yByS.flatMap((s) => s.points.map((p) => p.gmv))));
  const ordersYest = sum(yByS.flatMap((s) => s.points.map((p) => p.orders)));
  const delta = (now: number, before: number) => (before > 0 ? r4(now / before - 1) : 0);

  // 设备/工单口径直接数真实 mock 主数据，保证与设备台账、工单列表点进去数得上。
  const online = cabinets.filter((c) => c.onlineStatus === "ONLINE").length;
  const fault = cabinets.filter((c) => c.status === "FAULT").length;
  const rented = powerbanks.filter((p) => p.status === "RENTED").length;
  const openWo = workOrders.filter((w) => w.status === "CREATED" || w.status === "DISPATCHED"
    || w.status === "ACCEPTED" || w.status === "PROCESSING").length;
  const activeUsers = Math.round(ordersToday / 1.4); // 人均日均 1.4 单

  const kpis: ReportScreen[] = [
    { metric: "今日GMV", value: gmvToday, unit: REPORT_CURRENCY, trend: delta(gmvToday, gmvYest) },
    { metric: "今日订单", value: ordersToday, unit: "单", trend: delta(ordersToday, ordersYest) },
    { metric: "在线柜机", value: online, unit: "台", trend: 0 },
    { metric: "在线率", value: r2((online / Math.max(1, cabinets.length)) * 100), unit: "%", trend: 0 },
    { metric: "借出中充电宝", value: rented, unit: "个", trend: 0 },
    { metric: "待处理工单", value: openWo, unit: "单", trend: 0 },
    { metric: "活跃用户", value: activeUsers, unit: "人", trend: delta(ordersToday, ordersYest) },
    { metric: "翻台率", value: r2(ordersToday / Math.max(1, online)), unit: "次/柜", trend: 0 },
  ];

  const ranking: ScreenRankRow[] = bySite
    .map((s) => ({
      siteNo: s.group.siteNo,
      siteName: s.group.name,
      gmv: r2(sum(s.points.map((p) => p.gmv))),
      orders: sum(s.points.map((p) => p.orders)),
    }))
    .sort((a, b) => b.gmv - a.gmv || a.siteNo.localeCompare(b.siteNo))
    .map((r, i) => ({ rank: i + 1, ...r }));

  return {
    updatedAt: new Date(Date.UTC(2026, 6, 11, 12, 0, 0)).toISOString(),
    currency: REPORT_CURRENCY,
    kpis,
    today,
    ranking,
    cabinetStatus: [
      { label: "在线", value: online },
      { label: "故障", value: fault },
      { label: "离线", value: cabinets.length - online - fault },
    ],
  };
}

// ————————————————————————————————————————————————————————————————
// 自定义报表：维度 × 自选指标
// ————————————————————————————————————————————————————————————————

const metricValue = (key: string, a: Agg): number => {
  switch (key) {
    case "GMV": return a.revenue;
    case "ORDERS": return a.orders;
    case "AOV": return r2(a.revenue / Math.max(1, a.orders));
    case "COST": return costOf(a);
    case "NET": return netOf(a);
    case "ONLINE_RATE": return a.onlineRate;
    case "TURNOVER": return turnoverOf(a);
    case "FAULT_RATE": return a.faultRate;
    default: return 0;
  }
};

/** 维度分组：站点名 / 场景类型 / 月份桶。三者都落在既有主数据或周期桶上，不另造维度。 */
function customGroups(dim: string | undefined, period?: string): { dim: string; siteIdx: number[]; days: number[] }[] {
  const days = daysOf(period);
  if (dim === "SCENE") {
    const byScene = new Map<string, number[]>();
    sites.forEach((s, i) => byScene.set(s.sceneType, [...(byScene.get(s.sceneType) ?? []), i]));
    return [...byScene.entries()].map(([scene, idx]) => ({ dim: scene, siteIdx: idx, days }));
  }
  if (dim === "MONTH") {
    return bucketsOf(period).map((b) => ({ dim: b.label, siteIdx: ALL_SITES, days: b.days }));
  }
  return SITE_GROUPS.map((g) => ({ dim: g.name, siteIdx: g.idx, days }));
}

/** 长表形态（dim × metric × value）：指标是自选的，列数不定，宽表在页面按勾选透视。 */
export function buildReportCustom(dim?: string, period?: string, metrics?: string[]): ReportCustom[] {
  const keys = (metrics?.length ? metrics : REPORT_METRICS_DEFAULT)
    .filter((k) => REPORT_METRICS.some((m) => m.key === k));
  return customGroups(dim, period).flatMap((g) => {
    const a = aggOf(g.siteIdx, g.days);
    return keys.map((k) => ({ dim: g.dim, metric: k, value: metricValue(k, a) }));
  });
}

export const listReportMetrics = (): ReportMetricDef[] => [...REPORT_METRICS];

// ————————————————————————————————————————————————————————————————
// 消费者洞察：漏斗 + 画像
// ————————————————————————————————————————————————————————————————

/** 上游环节的转化率（下游是既有事实，只能往上反推，不能改人群分层表去凑漏斗）。 */
const FUNNEL_UP = [
  { stage: "扫码进入", to: 0.66 }, // 扫码 → 授权登录
  { stage: "授权登录", to: 0.82 }, // 授权 → 下单支付
  { stage: "下单支付", to: 0.93 }, // 支付 → 成功借出
];

const PROFILE_DIMS: { dim: string; dimLabel: string; slices: { label: string; share: number }[] }[] = [
  {
    dim: "AGE", dimLabel: "年龄段",
    slices: [{ label: "18-24", share: 0.22 }, { label: "25-34", share: 0.41 }, { label: "35-44", share: 0.24 }, { label: "45+", share: 0.13 }],
  },
  {
    dim: "TERMINAL", dimLabel: "终端",
    slices: [{ label: "iOS", share: 0.46 }, { label: "Android", share: 0.44 }, { label: "小程序", share: 0.1 }],
  },
  {
    dim: "PERIOD", dimLabel: "借出时段",
    slices: [{ label: "早高峰", share: 0.18 }, { label: "日间", share: 0.33 }, { label: "晚间", share: 0.36 }, { label: "夜间", share: 0.13 }],
  },
];

export function buildConsumerInsight(): ConsumerInsight {
  // 锚点：漏斗「成功借出」= 人群分层表的用户数合计。同一 tab 的表和图必须是同一批人。
  const borrowed = sum(consumerSegments.map((s) => s.userCount));
  const repeat = Math.round(sum(consumerSegments.map((s) => s.userCount * s.repeatRate)));

  const upstream: number[] = [];
  let cur = borrowed;
  for (let i = FUNNEL_UP.length - 1; i >= 0; i--) {
    cur = Math.round(cur / FUNNEL_UP[i].to);
    upstream.unshift(cur);
  }
  const raw = [
    ...FUNNEL_UP.map((f, i) => ({ stage: f.stage, users: upstream[i] })),
    { stage: "成功借出", users: borrowed },
    { stage: "复借", users: repeat },
  ];
  const funnel: ConsumerFunnelStage[] = raw.map((s, i) => ({
    stage: s.stage,
    users: s.users,
    rate: r4(s.users / raw[0].users),
    dropRate: i === 0 ? 0 : r4(1 - s.users / raw[i - 1].users),
  }));

  // 画像：份额乘总人数，**最后一片取余数**，保证同一维度合计严格等于 totalUsers。
  const profiles: ConsumerProfileSlice[] = PROFILE_DIMS.flatMap((d) => {
    let left = borrowed;
    return d.slices.map((s, i) => {
      const value = i === d.slices.length - 1 ? left : Math.round(borrowed * s.share);
      left -= value;
      return { dim: d.dim, dimLabel: d.dimLabel, label: s.label, value, share: r4(value / borrowed) };
    });
  });

  return { funnel, profiles, totalUsers: borrowed };
}

// ————————————————————————————————————————————————————————————————
// 列表入口（分页 + 关键词 + 周期）
//
// 下面五个 `reportXxx` 导出是**缺省周期的快照**：barrel（db/index.ts）与引用完整性
// 测试按名字引用它们，故名字与形状保持不变；真正随周期变的是 listXxx 里的重算。
// ————————————————————————————————————————————————————————————————

// 默认周期快照 + 列表查询。都从 location.ts 迁来：那边留着会与本文件 import sites 互引，
// 模块初始化期即炸。快照供 integrity.test 做引用完整性断言，不是第二份真相。
export const siteAnalyses: SiteAnalysis[] = buildSiteAnalyses();
/** 代理绩效列表。period 透传到事实层，同 listSiteAnalysis。 */
export const listAgentPerformance = (q: PageQuery & { period?: string } = {}) =>
  paginate(buildAgentPerformances(q.period), q.page, q.size, (x) => kwHit(q.keyword, x.agentNo, x.agentName));

/** 站点坪效列表。period 透传到事实层 —— 切周期数字必须真变，否则选择器是装饰。 */
export const listSiteAnalysis = (q: PageQuery & { period?: string } = {}) =>
  paginate(buildSiteAnalyses(q.period), q.page, q.size, (x) => kwHit(q.keyword, x.siteNo, x.siteName));
export const reportDevices: ReportDevice[] = buildReportDevices();
export const reportLocations: ReportLocation[] = buildReportLocations();
export const reportFinances: ReportFinance[] = buildReportFinances();
export const reportScreens: ReportScreen[] = buildScreenBoard().kpis;
export const reportCustoms: ReportCustom[] = buildReportCustom("SITE");

type ReportQuery = PageQuery & { period?: string };

export const listReportDevice = (q: ReportQuery = {}) =>
  paginate(buildReportDevices(q.period), q.page, q.size, (x) => kwHit(q.keyword, x.locationName));
export const listReportLocation = (q: ReportQuery = {}) =>
  paginate(buildReportLocations(q.period), q.page, q.size, (x) => kwHit(q.keyword, x.siteName));
export const listReportFinance = (q: ReportQuery = {}) =>
  paginate(buildReportFinances(q.period), q.page, q.size, (x) => kwHit(q.keyword, x.period));
export const listReportScreen = (q: PageQuery = {}) =>
  paginate(buildScreenBoard().kpis, q.page, q.size, (x) => kwHit(q.keyword, x.metric));

/** `metrics` 走 csv（qs 序列化后是 `metrics=GMV,ORDERS`），与后端约定的多值形式一致。 */
export const listReportCustom = (q: ReportQuery & { dim?: string; metrics?: string } = {}) => {
  const keys = q.metrics ? q.metrics.split(",").map((s) => s.trim()).filter(Boolean) : undefined;
  return paginate(buildReportCustom(q.dim, q.period, keys), q.page, q.size, (x) => kwHit(q.keyword, x.dim, x.metric));
};

export const getReportTrend = (q: ReportQuery & { kind?: string } = {}) => buildReportTrend(q.kind, q.period);
export const getScreenBoard = () => buildScreenBoard();
export const getConsumerInsight = () => buildConsumerInsight();
