// 覆盖范围：工作台/首页看板——统计卡、趋势、待办、告警提醒条、站点排行。

export interface DashboardAlert {
  id: string;
  type: "OFFLINE" | "EXCEPTION" | "TIMEOUT";
  cabinetNo: string;
  message: string;
  href: string;
}
export interface DashboardRankItem {
  rank: number;
  siteName: string;
  gmv: number;
  orderCount: number;
  currency: string;
}
export interface DashboardStats {
  gmvToday: number;
  ordersToday: number;
  activeCabinets: number;
  onlineRate: number; // 0..1
  openWorkOrders: number;
  currency: string;
  trend: { day: string; gmv: number; orders: number }[];
  /**
   * 待办三格。`pendingRefunds` **可能为 null**：ord_refund 没有归属列，
   * 那个数是全平台的，所以后端只发给全域主体（代理等受限主体拿到 null）。
   * 另两格被数据范围管住，各人看各人的。
   */
  todos: { pendingWorkOrders: number; pendingRefunds: number | null; pendingWithdrawals: number };
  alerts: DashboardAlert[];
  rankings: DashboardRankItem[];
}

/**
 * 运营核心流程指标（后端 `OpsFlowMetricsService.OpsFlowMetrics`，`GET /api/ops/ops-flow-metrics`）。
 *
 * 窗口 `[from, to)`，缺省最近 30 天。**比率为 null = 分母为 0（无数据），不是 0%** ——
 * 「这 30 天没有合同到期」与「到期的合同一份都没续」是两回事，页面必须分开写。
 * 每个比率都带着它的分母（`woWithSla` / `contractsEnded` …），样本太小时看的人能自己判断。
 * 口径写死在后端服务类注释里；前端 HelpNote 的说明与之逐条对应，改口径两边一起改。
 */
export interface OpsFlowMetrics {
  from: string;
  to: string;
  /** 工单 SLA：窗口内创建、已完工且有 SLA 计时的工单数 / 其中未超解决时限的占比 */
  woWithSla: number;
  woSlaRate: number | null;
  /** MTTR：已完工故障单数 / 开单到最后一次处理记录的平均分钟 */
  faultResolved: number;
  mttrMinutes: number | null;
  /** 合同续约：窗口内到期的主合同数 / 有续签的数 / 续约率 / 到期未续占比（= 1 − 续约率） */
  contractsEnded: number;
  contractsRenewed: number;
  renewalRate: number | null;
  expiredNotRenewedRatio: number | null;
  /** 装机时效：窗口内首次上线的站点数 / 合同生效到首次上线的平均天数 */
  sitesWentLive: number;
  installLeadDaysAvg: number | null;
  /** 首次试借还：做第一次试借还的机柜数 / 第一次就通过的占比 */
  firstTrials: number;
  firstTrialPassRate: number | null;
  /** 线索：新建数 / 已签约数 / 转化率 / 建档到合同生效的平均天数 */
  leadsCreated: number;
  leadsSigned: number;
  leadConversionRate: number | null;
  signingCycleDaysAvg: number | null;
  /** 撤场回收：现场清点数 / 系统应在柜数 / 回收率 */
  removalCounted: number;
  removalExpected: number;
  removalRecoveryRate: number | null;
}

/**
 * 商家营收首页汇总（后端 `MerchantRevenueController.RevenueSummary`，`GET /api/ops/merchant-revenue/summary`）。
 *
 * 口径（商家一期 §10.1）：交易额 / 退款 / 净交易额按**订单**（已结算、按结束时间落窗口）；
 * 税前营收与我的分成按**分成明细**；`pending` / `settledUnpaid` / `paid` 是**现在**的钱的状态，不随窗口变。
 * `prev*` 是等长的上一个窗口，用来算环比 —— 前端只在上期非 0 时显示百分比。
 * 商家会话由服务端数据范围收窄到自己；运营看到的是全平台。
 */
export interface RevenueSummary {
  from: string;
  to: string;
  orderCount: number;
  gross: number;
  refund: number;
  net: number;
  preTax: number;
  myShare: number;
  pending: number;
  settledUnpaid: number;
  paid: number;
  prevOrderCount: number;
  prevGross: number;
  prevMyShare: number;
}
/** 营收趋势的一天。缺天由后端补 0，折线不断。 */
export interface RevenueDay {
  date: string;
  orderCount: number;
  gross: number;
}
/** 按设备的营收排行（默认前 10）。 */
export interface RevenueByDevice {
  cabinetNo: string;
  orderCount: number;
  gross: number;
}

/**
 * 营收首页设备提醒（后端 `DeviceAlertQuery.DeviceAlerts`，`GET /api/ops/merchant-revenue/alerts`）。
 * 只数在用设备；离线与「在线无可借」互斥。样本最多 20 个柜号。按「现在」，不随时间窗口变。
 */
export interface DeviceAlerts {
  deployed: number;
  offline: number;
  noBorrowable: number;
  offlineSample: string[];
  noBorrowableSample: string[];
}
