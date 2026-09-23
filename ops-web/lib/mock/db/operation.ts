// 运营管理域 mock：站点概览、单站统计、站点营业状态。
//
// 聚合口径全部走 lib/operation-overview 的纯函数——与后端将来实现的是同一份规则，
// 单测钉在那边；这里只负责把 db 的各张表喂进去，以及站点状态的写入。
import type { OperationOverview, SiteStats } from "../../types/operation";
import { notFound, fail } from "@/lib/biz-error";
import type { Site } from "../../types/location";
import { buildOverview, buildSiteStats, type OverviewInput } from "../../operation-overview";
import { sites, locations, contracts } from "./location";
import { cabinets, powerbanks } from "./device";
import { orders } from "./order";
import { pricingDiffs } from "./pricing";
import { shareRules } from "./finance";

const DAY = 86400_000;

/** 默认区间：近 7 日（含今天）。 */
function rangeOf(from?: string, to?: string) {
  const end = to ? new Date(to) : new Date();
  const start = from ? new Date(from) : new Date(end.getTime() - 6 * DAY);
  return { from: start, to: end };
}

function inputOf(from?: string, to?: string, filter?: { regionId?: string; agentNo?: string }): OverviewInput {
  const range = rangeOf(from, to);
  let siteRows = sites;
  if (filter?.regionId) siteRows = siteRows.filter((s) => s.regionId === filter.regionId);
  if (filter?.agentNo) {
    siteRows = filter.agentNo === "PLATFORM"
      ? siteRows.filter((s) => !s.agentNo)
      : siteRows.filter((s) => s.agentNo === filter.agentNo);
  }
  const siteNos = new Set(siteRows.map((s) => s.siteNo));
  const pointRows = locations.filter((p) => siteNos.has(p.siteNo));
  const pointNos = new Set(pointRows.map((p) => p.locationNo));
  const cabRows = cabinets.filter((c) => (c.siteNo && siteNos.has(c.siteNo)) || (c.locationNo && pointNos.has(c.locationNo)));
  const cabNos = new Set(cabRows.map((c) => c.cabinetNo));

  return {
    sites: siteRows,
    points: pointRows,
    cabinets: cabRows,
    powerbanks: powerbanks.filter((p) => cabNos.has(p.cabinetNo)),
    orders: orders.filter((o) => cabNos.has(o.cabinetNo)),
    contracts,
    // 分成现在按「分成方 + 站点名」关联（清单 D2 未定前的现状）：
    // 规则表没有站点列，只能按合同里的站点名判断这个站点有没有分成方
    sharedSiteNames: new Set(
      contracts.filter((c) => shareRules.some((r) => r.payeeName === c.venueName)).map((c) => c.siteName),
    ),
    // 命中收费方案：差异化定价里指定了这个站点，即视为命中；否则落全平台默认
    pricedSiteNos: new Set(pricingDiffs.map((d) => d.siteNo).filter(Boolean) as string[]),
    from: range.from,
    to: range.to,
    now: new Date(),
    currency: "AED",
  };
}

export function getOperationOverview(q: { from?: string; to?: string; regionId?: string; agentNo?: string } = {}): OperationOverview {
  return buildOverview(inputOf(q.from, q.to, q));
}

export function getSiteStats(siteNo: string, q: { from?: string; to?: string } = {}): SiteStats {
  if (!sites.some((s) => s.siteNo === siteNo)) throw notFound("站点", "Site", siteNo);
  return buildSiteStats(siteNo, inputOf(q.from, q.to));
}

/** 暂停营业。已归档的站点不允许改营业状态（先恢复归档再操作）。 */
export function pauseSite(siteNo: string, reason: string): Site {
  const s = sites.find((x) => x.siteNo === siteNo);
  if (!s) throw notFound("站点", "Site", siteNo);
  if (s.archivedAt) throw fail("已归档的站点不能暂停营业", "An archived site cannot be suspended", "لا يمكن تعليق موقع مؤرشف");
  if (s.status === "PAUSED") throw fail("站点已处于暂停营业状态", "This site is already suspended", "هذا الموقع معلّق بالفعل");
  if (!reason.trim()) throw fail("请填写暂停原因", "A reason is required to suspend", "سبب التعليق مطلوب");
  s.status = "PAUSED";
  return s;
}

export function resumeSite(siteNo: string): Site {
  const s = sites.find((x) => x.siteNo === siteNo);
  if (!s) throw notFound("站点", "Site", siteNo);
  if (s.archivedAt) throw fail("已归档的站点不能恢复营业", "An archived site cannot be reopened", "لا يمكن إعادة فتح موقع مؤرشف");
  if (s.status === "ACTIVE") throw fail("站点已在营业中", "This site is already open", "هذا الموقع مفتوح بالفعل");
  s.status = "ACTIVE";
  return s;
}
