// 报表域：设备报表 / 点位收益报表 / 财务报表 / 数据大屏指标 / 自定义报表。
import type {
  ReportDevice, ReportLocation, ReportFinance, ReportScreen, ReportCustom, PageQuery,
} from "../../types";
import { LOCS, p } from "./internal";
import { paginate, kwHit } from "./helpers";

export const reportDevices: ReportDevice[] = LOCS.map((loc, i) => ({
  locationName: loc, onlineRate: Number((0.88 + (i % 6) * 0.02).toFixed(2)),
  turnover: Number((1.5 + (i % 5) * 0.7).toFixed(1)), faultRate: Number((0.01 + (i % 5) * 0.008).toFixed(3)),
  cabinetCount: 3 + (i * 3) % 14,
}));
export const reportLocations: ReportLocation[] = Array.from({ length: 12 }, (_, i) => {
  const revenue = 10000 + (i * 1337) % 50000;
  const cost = 4000 + (i * 733) % 20000;
  return {
    siteName: p(LOCS, i), revenue, cost, payback: 90 + (i * 17) % 240,
    roi: Number(((revenue - cost) / cost).toFixed(2)), currency: "AED",
  };
});
export const reportFinances: ReportFinance[] = Array.from({ length: 12 }, (_, i) => {
  const gmv = 80000 + (i * 6337) % 120000;
  const share = Math.round(gmv * 0.35);
  const settle = Math.round(share * 0.9);
  return {
    period: `2026-${String(i + 1).padStart(2, "0")}`, gmv, share, settle, net: gmv - share, currency: "AED",
  };
});
export const reportScreens: ReportScreen[] = [
  { metric: "今日GMV", value: 4820, unit: "AED", trend: 0.12 },
  { metric: "今日订单", value: 386, unit: "单", trend: 0.08 },
  { metric: "在线柜机", value: 43, unit: "台", trend: -0.02 },
  { metric: "在线率", value: 92, unit: "%", trend: 0.01 },
  { metric: "借出中充电宝", value: 218, unit: "个", trend: 0.05 },
  { metric: "待处理工单", value: 12, unit: "单", trend: -0.15 },
  { metric: "活跃用户", value: 1264, unit: "人", trend: 0.09 },
  { metric: "翻台率", value: 3.4, unit: "次/日", trend: 0.06 },
];
export const reportCustoms: ReportCustom[] = [
  { dim: "Dubai Mall L1", metric: "GMV", value: 42800 },
  { dim: "Mall of Emirates", metric: "GMV", value: 38600 },
  { dim: "DXB T3", metric: "GMV", value: 51200 },
  { dim: "Marina Walk", metric: "GMV", value: 22400 },
  { dim: "City Centre Deira", metric: "GMV", value: 19800 },
  { dim: "Yas Mall", metric: "GMV", value: 33500 },
  { dim: "Ibn Battuta", metric: "GMV", value: 27100 },
];

export const listReportDevice = (q: PageQuery = {}) => paginate(reportDevices, q.page, q.size, (x) => kwHit(q.keyword, x.locationName));
export const listReportLocation = (q: PageQuery = {}) => paginate(reportLocations, q.page, q.size, (x) => kwHit(q.keyword, x.siteName));
export const listReportFinance = (q: PageQuery = {}) => paginate(reportFinances, q.page, q.size, (x) => kwHit(q.keyword, x.period));
export const listReportScreen = (q: PageQuery = {}) => paginate(reportScreens, q.page, q.size, (x) => kwHit(q.keyword, x.metric));
export const listReportCustom = (q: PageQuery = {}) => paginate(reportCustoms, q.page, q.size, (x) => kwHit(q.keyword, x.dim, x.metric));
