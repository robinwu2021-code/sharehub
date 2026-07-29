// 场所域（ADR-013 场地方 → 站点 → 点位 → 合同）：sites / locations / venues / contracts，
// 外加拓展侧的线索 leads、站点效益分析 siteAnalyses、场地入驻审核 venueOnboardings、站点生命周期 siteLifecycles。
import type {
  Site, SitePoint, Venue, Contract, Lead, SiteAnalysis,
  VenueOnboarding, SiteLifecycle, PageQuery,
} from "../../types";
import { LOCS, VENUE_NAMES, p, iso, phone } from "./internal";
import { paginate, kwHit, upsert, nextNo, archiveRow, unarchiveRow } from "./helpers";

// 台账 M11：站点的区域必须挂 regions 字典里真实存在的三级区域 ID（原先存的是"Dubai North"
// 这类字典里根本没有的名字）。ID 与展示名成对，展示名冗余自字典。
// 区域 + 该区域的真实中心坐标。站点坐标由区域中心加小抖动派生——
// 保证「站点落在它自己声明的区域里」，否则地图上会出现滨海区的站点飘到机场。
const REGIONS: { id: string; name: string; lat: number; lng: number }[] = [
  { id: "DU-MAR", name: "Dubai Marina", lat: 25.0805, lng: 55.1403 },
  { id: "DU-DEI", name: "Deira", lat: 25.2697, lng: 55.3095 },
  { id: "DU-DT", name: "Downtown Dubai", lat: 25.1972, lng: 55.2744 },
  { id: "DU-DXB", name: "DXB 机场", lat: 25.2532, lng: 55.3657 },
  { id: "AZ-YAS", name: "Yas Island", lat: 24.4991, lng: 54.6070 },
];
/** 由区域中心 + 确定性抖动派生站点坐标（同一 i 恒等，避免每次渲染点位乱跳）。 */
const jitter = (base: number, i: number, seed: number) => Number((base + (((i * seed) % 17) - 8) * 0.0035).toFixed(6));
export const sites: Site[] = Array.from({ length: 12 }, (_, i) => ({
  siteNo: `ST${300 + i}`, name: p(LOCS, i), venueName: p(VENUE_NAMES, i),
  agentNo: i % 3 === 0 ? null : `AG${String((i % 9) + 1).padStart(3, "0")}`, regionId: p(REGIONS, i).id, regionName: p(REGIONS, i).name,
  address: `${p(LOCS, i)}, Dubai, UAE`,
  lat: jitter(p(REGIONS, i).lat, i, 7), lng: jitter(p(REGIONS, i).lng, i, 11),
  sceneType: p(["商场", "机场", "餐饮", "地铁", "写字楼"], i),
  pointCount: 1 + (i % 4), cabinetCount: 2 + (i * 3) % 10, status: i % 8 === 0 ? "PAUSED" : "ACTIVE",
  archivedAt: null,
}));
export const locations: SitePoint[] = Array.from({ length: 30 }, (_, i) => {
  const site = sites[i % sites.length];
  return {
    locationNo: `LOC${200 + i}`, name: `${site.name} · ${p(["L1东门", "L2中庭", "B1出口", "主入口", "美食广场"], i)}`,
    siteNo: site.siteNo, siteName: site.name, spotDesc: p(["近扶梯", "收银台旁", "入口右侧", "电梯口"], i),
    cabinetCount: 1 + (i % 3), status: i % 9 === 0 ? "PAUSED" : "ACTIVE", archivedAt: null,
  };
});
export const venues: Venue[] = VENUE_NAMES.map((name, i) => ({
  venueNo: `VEN${300 + i}`, name, contact: `+9714${String(2000000 + i * 311).slice(0, 7)}`,
  industry: p(["零售", "航空", "地产", "餐饮"], i), locationCount: 3 + i * 2, archivedAt: null,
}));
export const contracts: Contract[] = Array.from({ length: 18 }, (_, i) => ({
  contractNo: `CT${400 + i}`, venueName: p(VENUE_NAMES, i), siteName: p(LOCS, i),
  shareRate: [0.15, 0.2, 0.25, 0.3][i % 4], entryFee: (i % 4) * 500, startAt: iso(i * 30 * 86400_000),
  endAt: iso(-(365 - i * 10) * 86400_000), status: i % 9 === 0 ? "EXPIRED" : "ACTIVE",
}));

export const leads: Lead[] = Array.from({ length: 20 }, (_, i) => ({
  leadNo: `LD${3000 + i}`, venueName: p([...VENUE_NAMES, "Dubai Marina Mall", "The Dubai Fountain", "Global Village"], i),
  contact: phone(i), stage: p(["NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST"] as const, i),
  owner: p(["BD-Layla", "BD-Yusuf", "BD-Ahmed"], i), expectSites: 1 + (i * 3) % 12,
  updatedAt: iso(i * 21600_000),
}));
export const siteAnalyses: SiteAnalysis[] = Array.from({ length: 12 }, (_, i) => ({
  siteNo: `ST${300 + i}`, siteName: p(LOCS, i), revenue: 8000 + (i * 1337) % 40000,
  orders: 200 + (i * 71) % 1800, turnover: Number((1.2 + (i % 7) * 0.6).toFixed(1)),
  paybackDays: 90 + (i * 17) % 300, cabinetCount: 2 + (i * 3) % 12, currency: "AED",
}));

export const venueOnboardings: VenueOnboarding[] = [
  { onboardingNo: "OB0001", venueName: "Al Barsha Mall", contact: "Ahmed +971501110001", industry: "购物中心", requestedAt: "2026-07-10T10:00:00Z", status: "PENDING", reviewAt: null, reviewNote: null },
  { onboardingNo: "OB0002", venueName: "Dragon Mart 2", contact: "Lin +971501110002", industry: "商贸城", requestedAt: "2026-07-08T09:00:00Z", status: "APPROVED", reviewAt: "2026-07-09T14:00:00Z", reviewNote: "资料齐全，已通过" },
  { onboardingNo: "OB0003", venueName: "Dune Hotel", contact: "Sara +971501110003", industry: "酒店", requestedAt: "2026-07-05T11:00:00Z", status: "REJECTED", reviewAt: "2026-07-06T10:00:00Z", reviewNote: "流量不足，建议重新评估" },
  { onboardingNo: "OB0004", venueName: "City Walk Shops", contact: "Omar +971501110004", industry: "零售街区", requestedAt: "2026-07-12T08:00:00Z", status: "PENDING", reviewAt: null, reviewNote: null },
];

// 生命周期挂在**真实存在的站点**上（台账 M5：原先是 SITE001–005 / DIFC Gate 等，
// 既不在 sites 的 ST3xx 号段里，站点名也不在 LOCS 里，点进去查无此站点）。
// siteName 一律由 sites 反查，不再手写。
const SITE_LIFECYCLE_SEED: Omit<SiteLifecycle, "siteName">[] = [
  { siteNo: "ST300", stage: "ACTIVE", stageAt: "2026-01-10", owner: "Ali Hassan", currency: "AED", gmvLtm: 28400 },
  { siteNo: "ST301", stage: "LIVE", stageAt: "2026-06-01", owner: "Ali Hassan", currency: "AED", gmvLtm: 3200 },
  { siteNo: "ST302", stage: "SIGNED", stageAt: "2026-07-01", owner: "Sara Ops", currency: "AED", gmvLtm: 0 },
  { siteNo: "ST303", stage: "CHURNED", stageAt: "2026-05-15", owner: "BD Team", currency: "AED", gmvLtm: 410 },
  { siteNo: "ST304", stage: "PROSPECTING", stageAt: "2026-07-10", owner: "BD Team", currency: "AED", gmvLtm: 0 },
];
export const siteLifecycles: SiteLifecycle[] = SITE_LIFECYCLE_SEED.map((s) => ({
  ...s, siteName: sites.find((x) => x.siteNo === s.siteNo)!.name,
}));

export const listLeads = (q: PageQuery = {}) => paginate(leads, q.page, q.size, (x) => kwHit(q.keyword, x.leadNo, x.venueName, x.owner));
export const listSiteAnalysis = (q: PageQuery = {}) => paginate(siteAnalyses, q.page, q.size, (x) => kwHit(q.keyword, x.siteNo, x.siteName));
export const listVenueOnboardings = (q: PageQuery = {}) => paginate(venueOnboardings, q.page, q.size, (x) => kwHit(q.keyword, x.onboardingNo, x.venueName, x.contact));
export const listSiteLifecycles = (q: PageQuery = {}) => paginate(siteLifecycles, q.page, q.size, (x) => kwHit(q.keyword, x.siteNo, x.siteName, x.owner));

export const saveLead = (x: Partial<Lead>) => upsert(leads, x, "leadNo", () => nextNo("LD", leads));
export const saveVenue = (x: Partial<Venue>) => upsert(venues, x, "venueNo", () => nextNo("VEN", venues));
export const saveContract = (x: Partial<Contract>) => upsert(contracts, x, "contractNo", () => nextNo("CT", contracts));
export const saveVenueOnboarding = (x: Partial<VenueOnboarding>) => upsert(venueOnboardings, x, "onboardingNo", () => nextNo("OB", venueOnboardings));

// —— G1 软删除：站点 / 点位 / 场地方 ——
export const archiveSite = (no: string) => archiveRow(sites, "siteNo", no);
export const unarchiveSite = (no: string) => unarchiveRow(sites, "siteNo", no);
export const archivePoint = (no: string) => archiveRow(locations, "locationNo", no);
export const unarchivePoint = (no: string) => unarchiveRow(locations, "locationNo", no);
export const archiveVenue = (no: string) => archiveRow(venues, "venueNo", no);
export const unarchiveVenue = (no: string) => unarchiveRow(venues, "venueNo", no);
