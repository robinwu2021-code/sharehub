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
