// 覆盖范围：报表域（bi）——设备/点位/财务三张周期报表、实时大屏、自定义报表、
// 消费者分群与洞察（漏斗 / 画像）。
//
// S3 起报表全部带**周期**：周期是报表的第一入参，不是可选筛选项——同一张表不写清
// 「统计的是哪段时间」等于没有口径。周期枚举与桶宽在此单一定义，mock 聚合与页面
// 选择器共用（页面自己拼一套 label 就必然与 mock 的桶数对不上）。

/** 报表周期。大写下划线跟后端枚举约定一致（前端不自造小写枚举）。 */
export type ReportPeriod = "LAST_7D" | "LAST_30D" | "LAST_13W" | "LAST_12M";

/**
 * 周期定义：`days` = 覆盖天数（含今日），`bucketDays` = 图表一个点的天数。
 * 天数刻意取桶宽整数倍（91=13×7、360=12×30），否则最后一个点是残桶，
 * 图表上会莫名矮一截，看着像掉量。
 */
export const REPORT_PERIODS: readonly {
  value: ReportPeriod; label: string; days: number; bucketDays: number;
}[] = [
  { value: "LAST_7D", label: "近 7 日", days: 7, bucketDays: 1 },
  { value: "LAST_30D", label: "近 30 日", days: 30, bucketDays: 1 },
  { value: "LAST_13W", label: "近 13 周", days: 91, bucketDays: 7 },
  { value: "LAST_12M", label: "近 12 月", days: 360, bucketDays: 30 },
];

/** 缺省周期：近 30 日。列表/趋势/汇总在不传 period 时一律落到它。 */
export const REPORT_PERIOD_DEFAULT: ReportPeriod = "LAST_30D";

export interface ReportDevice {
  /** 站点名。主数据里 12 个站点只有 7 个名字，报表按名字聚合，故此处唯一，可作 rowKey。 */
  locationName: string;
  onlineRate: number; // 0..1（周期内按日均值）
  turnover: number; // 翻台率：次/柜/日
  faultRate: number; // 0..1
  cabinetCount: number;
  orders: number; // 周期内订单数
}
export interface ReportLocation {
  siteName: string;
  revenue: number;
  cost: number;
  payback: number; // 回本天数
  roi: number;
  orders: number;
  currency: string;
}
export interface ReportFinance {
  /** 桶标签（日 `2026-07-11` / 周 `07-05~07-11` / 月 `2026-07`）——财务报表一行就是图表一个点。 */
  period: string;
  gmv: number;
  share: number;
  settle: number;
  net: number;
  currency: string;
}
export interface ReportScreen {
  metric: string;
  value: number;
  unit: string;
  /** 环比（0 = 存量口径，mock 无历史快照，页面渲染成「—」而不是假的 0.0%）。 */
  trend: number;
}
export interface ReportCustom {
  dim: string;
  metric: string; // = ReportMetricDef.key
  value: number;
}

// ————————————————————————————————————————————————————————————————
// 趋势 / 汇总（设备 · 点位 · 财务共用一个端点，kind 决定口径）
// ————————————————————————————————————————————————————————————————

export type ReportTrendKind = "DEVICE" | "LOCATION" | "FINANCE";

/**
 * 一个周期桶的汇总。字段是三张报表的并集，取用方按 kind 各取所需——
 * 拆成三个类型会导致三份桶切分逻辑，桶边界一旦不一致，图表与表格就对不上。
 */
export interface ReportTrendPoint {
  bucket: string;
  orders: number;
  revenue: number; // 财务口径即 GMV
  cost: number;
  share: number;
  net: number;
  onlineRate: number; // 桶内日均
  faultRate: number;
  turnover: number;
}

/** 汇总条一项。`format` 决定页面怎么渲染（金额 / 百分比 / 计数），不在页面里按 label 猜。 */
export interface ReportSummaryItem {
  label: string;
  value: number;
  format: "MONEY" | "RATE" | "NUMBER";
}

export interface ReportTrend {
  kind: ReportTrendKind;
  period: ReportPeriod;
  points: ReportTrendPoint[];
  summary: ReportSummaryItem[];
  currency: string;
}

// ————————————————————————————————————————————————————————————————
// 实时大屏（拍板点 #2：做真全屏看板）
// ————————————————————————————————————————————————————————————————

export interface ScreenBoardPoint { hour: string; gmv: number; orders: number }
export interface ScreenRankRow { rank: number; siteNo: string; siteName: string; gmv: number; orders: number }
export interface ScreenStatusSlice { label: string; value: number }

export interface ScreenBoard {
  updatedAt: string;
  currency: string;
  kpis: ReportScreen[];
  /** 今日分时：Σgmv / Σorders **必须**等于 KPI 里的今日 GMV / 今日订单。 */
  today: ScreenBoardPoint[];
  /** 全站点排名（不截断，页面自己决定显示几行）；Σgmv = 今日 GMV。 */
  ranking: ScreenRankRow[];
  /** 柜机在线构成，Σvalue = 机柜总数。 */
  cabinetStatus: ScreenStatusSlice[];
}

// ————————————————————————————————————————————————————————————————
// 自定义报表（原页面只有一个「筛已有指标」的下拉，名不符实；这里给出真的指标目录）
// ————————————————————————————————————————————————————————————————

export interface ReportMetricDef {
  key: string;
  label: string;
  format: "MONEY" | "RATE" | "NUMBER";
}

export type ReportCustomDim = "SITE" | "SCENE" | "MONTH";

export const REPORT_CUSTOM_DIMS: readonly { value: ReportCustomDim; label: string }[] = [
  { value: "SITE", label: "站点" },
  { value: "SCENE", label: "场景类型" },
  { value: "MONTH", label: "月份" },
];

/**
 * 可选指标目录。页面的勾选框、mock 的算子、导出的列头共用这一份——
 * 少了任何一处同步，就会出现「勾了指标但表里没有这列」。
 */
export const REPORT_METRICS: readonly ReportMetricDef[] = [
  { key: "GMV", label: "GMV", format: "MONEY" },
  { key: "ORDERS", label: "订单数", format: "NUMBER" },
  { key: "AOV", label: "客单价", format: "MONEY" },
  { key: "COST", label: "成本", format: "MONEY" },
  { key: "NET", label: "净收入", format: "MONEY" },
  { key: "ONLINE_RATE", label: "在线率", format: "RATE" },
  { key: "TURNOVER", label: "翻台率", format: "NUMBER" },
  { key: "FAULT_RATE", label: "故障率", format: "RATE" },
];

/** 默认勾选的三个指标（首屏不该是空表）。 */
export const REPORT_METRICS_DEFAULT = ["GMV", "ORDERS", "AOV"];

// ————————————————————————————————————————————————————————————————
// 消费者数据分析（报表域 · P3）
// ————————————————————————————————————————————————————————————————

export interface ConsumerSegment {
  segmentNo: string;
  segment: string; // 人群/画像名
  userCount: number;
  repeatRate: number; // 复借率 0~1
  avgOrderValue: number;
  currency: string;
}

export interface ConsumerFunnelStage {
  stage: string;
  users: number;
  /** 相对首环节的整体转化率 0..1。 */
  rate: number;
  /** 相对上一环节的流失率 0..1；首环节为 0。 */
  dropRate: number;
}

/** 画像切片。`dim` 是分组键（AGE / TERMINAL / PERIOD），同组 Σvalue = totalUsers。 */
export interface ConsumerProfileSlice {
  dim: string;
  dimLabel: string;
  label: string;
  value: number;
  share: number;
}

export interface ConsumerInsight {
  funnel: ConsumerFunnelStage[];
  profiles: ConsumerProfileSlice[];
  /** = 人群分层表的用户数合计，也是漏斗「成功借出」环节的人数。 */
  totalUsers: number;
}
