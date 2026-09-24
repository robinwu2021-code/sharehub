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
