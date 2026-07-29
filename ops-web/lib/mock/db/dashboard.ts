// 工作台域：首页概览指标（GMV / 订单 / 在线率 / 待办 / 告警 / 站点排行）。
// 在线柜机数、工单待办数均由 device.ts 的 cabinets 与 workorder.ts 的 workOrders 实时聚合，不写死。
import type { DashboardStats, DashboardAlert } from "../../types";
import { cabinets } from "./device";
import { workOrders } from "./workorder";

const dashboardAlerts: DashboardAlert[] = [
  { id: "ALT001", type: "OFFLINE", cabinetNo: "CAB1003", message: "CAB1003 离线超过 10 分钟", href: "/devices?q=CAB1003" },
  { id: "ALT002", type: "EXCEPTION", cabinetNo: "CAB1007", message: "CAB1007 出现弹仓失败订单", href: "/orders?tab=exceptions" },
  { id: "ALT003", type: "TIMEOUT", cabinetNo: "CAB1011", message: "CAB1011 工单超过 SLA 时限", href: "/work-orders" },
];

export const dashboard: DashboardStats = {
  gmvToday: 4820, ordersToday: 386,
  activeCabinets: cabinets.filter((c) => c.onlineStatus === "ONLINE").length,
  onlineRate: cabinets.filter((c) => c.onlineStatus === "ONLINE").length / cabinets.length,
  openWorkOrders: workOrders.filter((w) => w.status !== "CLOSED" && w.status !== "DONE").length,
  currency: "AED",
  trend: Array.from({ length: 7 }, (_, i) => ({ day: `D-${6 - i}`, gmv: 3000 + ((i * 613) % 2500), orders: 250 + ((i * 71) % 200) })),
  todos: { pendingWorkOrders: workOrders.filter((w) => w.status === "CREATED").length, pendingRefunds: 3, pendingWithdrawals: 2 },
  alerts: dashboardAlerts,
  // 站点名必须是 sites 里真实存在的站点（原先 MOE Floor 2 / DIFC Gate / Karama Center /
  // Global Village E5 都不在 sites 里，点排行榜跳站点详情查无此站点）。
  rankings: [
    { rank: 1, siteName: "Dubai Mall L1", gmv: 4820, orderCount: 386, currency: "AED" },
    { rank: 2, siteName: "Mall of Emirates", gmv: 3150, orderCount: 252, currency: "AED" },
    { rank: 3, siteName: "DXB T3", gmv: 2840, orderCount: 231, currency: "AED" },
    { rank: 4, siteName: "Marina Walk", gmv: 1920, orderCount: 154, currency: "AED" },
    { rank: 5, siteName: "City Centre Deira", gmv: 1540, orderCount: 127, currency: "AED" },
  ],
};
