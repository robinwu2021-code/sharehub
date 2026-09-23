// 运营管理 · 站点概览与单站统计的计算（清单 OM-S1 / OM-S2）。
//
// 纯函数：输入是各域的行数据，输出是读模型。mock 层照此算；后端实现聚合接口后，
// 这里仍保留——「待关注站点」的判定规则是产品口径，前后端必须一致，单测钉在这一份上。
//
// 口径约定：
// - 在线率 = 在线且非故障 ÷ 机柜总数，与经营看板一致（看板的口径在 report mock 里，
//   两处同时改才不会出现「概览说 92%、看板说 88%」）
// - 订单归属站点：订单只有机柜号，经「机柜 → 点位 → 站点」反查；机柜没有站点的订单不计入站点维度
// - 时间：入参一律 UTC ISO，天维度按市场时区切分（跨零点的订单要落到当地的那一天）
import type {
  AttentionItem, OperationOverview, OverviewTrendPoint, SiteRankRow, SiteStats, SceneShare,
} from "../types/operation";
import type { Site, SitePoint, Contract } from "../types/location";
import type { Cabinet, Powerbank } from "../types/device";
import type { RentOrder } from "../types/order";
import { formatMarketTime } from "./market-time";

/** 概览与统计的数据来源（mock 传 db 的数组，后端实现时对应各表）。 */
export interface OverviewInput {
  sites: Site[];
  points: SitePoint[];
  cabinets: Cabinet[];
  powerbanks: Powerbank[];
  orders: RentOrder[];
  contracts: Contract[];
  /** 站点是否已配置分成（siteName → 是否有分成方）。分成数据现在按名字关联，见清单 D2。 */
  sharedSiteNames: Set<string>;
  /** 站点是否命中了收费方案（siteNo 集合）。 */
  pricedSiteNos: Set<string>;
  from: Date;
  to: Date;
  now: Date;
  currency: string;
}

const live = <T extends { archivedAt?: string | null }>(xs: T[]) => xs.filter((x) => !x.archivedAt);
const dayOf = (iso: string) => formatMarketTime(iso).slice(0, 10);
const inRange = (iso: string | null | undefined, from: Date, to: Date) => {
  if (!iso) return false;
  const t = new Date(iso).getTime();
  return t >= from.getTime() && t <= to.getTime();
};
const round2 = (n: number) => Math.round(n * 100) / 100;

/** 机柜是否在线：在线且非故障（与经营看板口径一致）。 */
export const cabinetOnline = (c: Cabinet) => c.onlineStatus === "ONLINE" && c.status !== "FAULT";

/** 订单归属站点：机柜 → 点位 → 站点。机柜未归属点位时返回 undefined。 */
export function siteNoOfOrder(o: RentOrder, cabinets: Cabinet[], points: SitePoint[]): string | undefined {
  const cab = cabinets.find((c) => c.cabinetNo === o.cabinetNo);
  if (!cab) return undefined;
  if (cab.siteNo) return cab.siteNo;
  return points.find((p) => p.locationNo === cab.locationNo)?.siteNo;
}

/** 计费的订单：按结束时间落账；未结束的订单不计入经营指标。 */
const billable = (o: RentOrder) => !!o.rentEndAt && o.feeAmount > 0;

function trendOf(orders: RentOrder[], from: Date, to: Date): OverviewTrendPoint[] {
  const days: OverviewTrendPoint[] = [];
  const cursor = new Date(from);
  while (cursor.getTime() <= to.getTime()) {
    days.push({ day: formatMarketTime(cursor.toISOString()).slice(0, 10), orders: 0, gmv: 0 });
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  const byDay = new Map(days.map((d) => [d.day, d]));
  for (const o of orders) {
    const d = byDay.get(dayOf(o.rentEndAt!));
    if (d) { d.orders += 1; d.gmv = round2(d.gmv + o.feeAmount); }
  }
  return days;
}

/**
 * 一个站点的待关注项。规则与严重程度是产品口径，改这里等于改运营的处置优先级。
 * 一个站点可能命中多条，全部返回。
 */
export function attentionOf(site: Site, input: OverviewInput): AttentionItem[] {
  const { cabinets, points, orders, contracts, sharedSiteNames, pricedSiteNos, now } = input;
  const out: AttentionItem[] = [];
  const mk = (kind: AttentionItem["kind"], severity: AttentionItem["severity"], detail: string) =>
    out.push({ siteNo: site.siteNo, siteName: site.name, kind, severity, detail });

  const sitePoints = live(points).filter((p) => p.siteNo === site.siteNo);
  const siteCabs = live(cabinets).filter((c) => c.siteNo === site.siteNo || sitePoints.some((p) => p.locationNo === c.locationNo));
  const onlineCount = siteCabs.filter(cabinetOnline).length;

  if (siteCabs.length > 0 && onlineCount === 0) {
    // 全部离线多久：取最近一次心跳，越久越要紧
    const last = siteCabs
      .map((c) => (c.lastHeartbeatAt ? new Date(c.lastHeartbeatAt).getTime() : 0))
      .reduce((a, b) => Math.max(a, b), 0);
    const hours = last ? Math.floor((now.getTime() - last) / 3600_000) : null;
    if (hours === null || hours >= 2) {
      mk("ALL_OFFLINE", "high", `${siteCabs.length} 台机柜全部离线${hours === null ? "" : ` ${hours} 小时`}`);
    }
  }
  if (site.status === "ACTIVE" && siteCabs.length === 0) {
    mk("NO_CABINET", "medium", sitePoints.length ? `有 ${sitePoints.length} 个点位但没有机柜` : "还没有点位和机柜");
  }

  const siteContracts = contracts.filter((c) => c.siteName === site.name);
  const days = (endAt: string) => Math.ceil((new Date(endAt).getTime() - now.getTime()) / 86400_000);
  const expired = siteContracts.filter((c) => days(c.endAt) < 0);
  const soon = siteContracts.filter((c) => { const d = days(c.endAt); return d >= 0 && d <= 30; });
  if (expired.length && site.status === "ACTIVE") {
    mk("CONTRACT_EXPIRED", "high", `合同 ${expired[0].contractNo} 已于 ${expired[0].endAt.slice(0, 10)} 到期，站点仍在营业`);
  } else if (soon.length) {
    mk("CONTRACT_SOON", "medium", `合同 ${soon[0].contractNo} 将在 ${days(soon[0].endAt)} 天后到期`);
  }

  if (!pricedSiteNos.has(site.siteNo)) mk("NO_PRICE_PLAN", "medium", "没有命中任何收费方案，按全平台默认价计费");
  if (!sharedSiteNames.has(site.name)) mk("NO_SHARING", "medium", "没有配置分成方，收入全部留在平台");

  if (site.status === "ACTIVE" && siteCabs.length > 0) {
    const weekAgo = new Date(now.getTime() - 7 * 86400_000);
    const recent = orders.filter((o) => billable(o) && inRange(o.rentEndAt, weekAgo, now)
      && siteNoOfOrder(o, cabinets, points) === site.siteNo);
    if (recent.length === 0) mk("NO_ORDER", "low", "近 7 日没有一单");
  }
  return out;
}

const SEVERITY_ORDER = { high: 0, medium: 1, low: 2 } as const;

export function buildOverview(input: OverviewInput): OperationOverview {
  const { from, to, currency } = input;
  const sites = live(input.sites);
  const points = live(input.points);
  const cabinets = live(input.cabinets);
  const powerbanks = live(input.powerbanks);

  const cabinetOnlineCount = cabinets.filter(cabinetOnline).length;
  const scale = {
    siteTotal: sites.length,
    siteActive: sites.filter((s) => s.status === "ACTIVE").length,
    sitePaused: sites.filter((s) => s.status === "PAUSED").length,
    pointTotal: points.length,
    cabinetTotal: cabinets.length,
    cabinetOnline: cabinetOnlineCount,
    onlineRate: cabinets.length ? round2(cabinetOnlineCount / cabinets.length) : 0,
    powerbankTotal: powerbanks.length,
    powerbankInCabinet: powerbanks.filter((p) => p.status === "IN_CABINET").length,
    powerbankRented: powerbanks.filter((p) => p.status === "RENTED").length,
    powerbankFault: powerbanks.filter((p) => p.status === "FAULT").length,
  };

  const ranged = input.orders.filter((o) => billable(o) && inRange(o.rentEndAt, from, to));
  const gmv = round2(ranged.reduce((s, o) => s + o.feeAmount, 0));
  const spanDays = Math.max(1, Math.round((to.getTime() - from.getTime()) / 86400_000) + 1);
  const business = {
    orders: ranged.length,
    gmv,
    currency,
    avgOrderValue: ranged.length ? round2(gmv / ranged.length) : 0,
    ordersPerCabinetPerDay: cabinets.length ? round2(ranged.length / cabinets.length / spanDays) : 0,
  };

  const bySite = new Map<string, { orders: number; gmv: number }>();
  for (const o of ranged) {
    const no = siteNoOfOrder(o, cabinets, points);
    if (!no) continue;
    const cur = bySite.get(no) ?? { orders: 0, gmv: 0 };
    cur.orders += 1; cur.gmv = round2(cur.gmv + o.feeAmount);
    bySite.set(no, cur);
  }
  const ranking: SiteRankRow[] = sites.map((s) => {
    const sitePoints = points.filter((p) => p.siteNo === s.siteNo);
    const cabs = cabinets.filter((c) => c.siteNo === s.siteNo || sitePoints.some((p) => p.locationNo === c.locationNo));
    const agg = bySite.get(s.siteNo) ?? { orders: 0, gmv: 0 };
    return {
      siteNo: s.siteNo, siteName: s.name, venueName: s.venueName,
      cabinetCount: cabs.length, gmv: agg.gmv, orders: agg.orders,
      perCabinet: cabs.length ? round2(agg.orders / cabs.length / spanDays) : 0,
      onlineRate: cabs.length ? round2(cabs.filter(cabinetOnline).length / cabs.length) : 0,
    };
  });

  const attention = sites.flatMap((s) => attentionOf(s, input))
    .sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] || a.siteName.localeCompare(b.siteName));

  const sceneMap = new Map<string, SceneShare>();
  for (const s of sites) {
    const cur = sceneMap.get(s.sceneType) ?? { sceneType: s.sceneType, siteCount: 0, gmv: 0 };
    cur.siteCount += 1;
    cur.gmv = round2(cur.gmv + (bySite.get(s.siteNo)?.gmv ?? 0));
    sceneMap.set(s.sceneType, cur);
  }

  return {
    scale, business,
    trend: trendOf(ranged, from, to),
    ranking: ranking.sort((a, b) => b.gmv - a.gmv),
    attention,
    scenes: [...sceneMap.values()].sort((a, b) => b.siteCount - a.siteCount),
    geoReady: { withGeo: sites.filter((s) => s.lat && s.lng).length, total: sites.length },
  };
}

/** 单站统计（站点详情「统计」页签）。 */
export function buildSiteStats(siteNo: string, input: OverviewInput): SiteStats {
  const { from, to, currency } = input;
  const site = input.sites.find((s) => s.siteNo === siteNo);
  const points = live(input.points).filter((p) => p.siteNo === siteNo);
  const cabinets = live(input.cabinets).filter((c) => c.siteNo === siteNo || points.some((p) => p.locationNo === c.locationNo));
  const orders = input.orders.filter((o) => billable(o) && inRange(o.rentEndAt, from, to)
    && siteNoOfOrder(o, input.cabinets, input.points) === siteNo);

  const gmv = round2(orders.reduce((s, o) => s + o.feeAmount, 0));
  const spanDays = Math.max(1, Math.round((to.getTime() - from.getTime()) / 86400_000) + 1);
  const durations = orders.map((o) => o.durationMin ?? 0).filter((d) => d > 0);

  const byPoint = points.map((p) => {
    const cabs = cabinets.filter((c) => c.locationNo === p.locationNo);
    const rows = orders.filter((o) => cabs.some((c) => c.cabinetNo === o.cabinetNo));
    const pGmv = round2(rows.reduce((s, o) => s + o.feeAmount, 0));
    return {
      locationNo: p.locationNo, locationName: p.name, cabinetCount: cabs.length,
      orders: rows.length, gmv: pGmv,
      perCabinet: cabs.length ? round2(rows.length / cabs.length / spanDays) : 0,
    };
  }).sort((a, b) => b.gmv - a.gmv);

  return {
    siteNo, siteName: site?.name ?? siteNo,
    from: from.toISOString(), to: to.toISOString(),
    orders: orders.length, gmv, currency,
    avgOrderValue: orders.length ? round2(gmv / orders.length) : 0,
    avgDurationMin: durations.length ? Math.round(durations.reduce((a, b) => a + b, 0) / durations.length) : 0,
    ordersPerCabinetPerDay: cabinets.length ? round2(orders.length / cabinets.length / spanDays) : 0,
    onlineRate: cabinets.length ? round2(cabinets.filter(cabinetOnline).length / cabinets.length) : 0,
    cabinetCount: cabinets.length,
    trend: trendOf(orders, from, to),
    byPoint,
  };
}
