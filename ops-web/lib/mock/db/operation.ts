// 运营管理域 mock：站点概览、单站统计、站点营业状态。
//
// 聚合口径全部走 lib/operation-overview 的纯函数——与后端将来实现的是同一份规则，
// 单测钉在那边；这里只负责把 db 的各张表喂进去，以及站点状态的写入。
import { SITE_TRANSITIONS } from "../../types";
import { siteStatusLog } from "./site-status";
import type { OperationOverview, SiteStats } from "../../types/operation";
import { notFound, fail } from "@/lib/biz-error";
import type { Site } from "../../types/location";
import { buildOverview, buildSiteStats, type OverviewInput } from "../../rules/operation-overview";
import { sites, locations, contracts } from "./location";
import { cabinets, powerbanks } from "./device";
import { orders } from "./order";
import { planScopes } from "./pricing";
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
    // 命中收费方案：适用范围里有一条 SITE 层指向这个站点，即视为有专属价；否则落默认方案
    pricedSiteNos: new Set(
      planScopes.filter((x) => x.scopeType === "SITE").map((x) => x.scopeRef).filter(Boolean),
    ),
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

/**
 * 暂停营业。已归档的站点不允许改营业状态（先恢复归档再操作）。
 *
 * <p>2026-09-25 站点五态之后，**可用性以 SITE_TRANSITIONS 为准**：
 * 原先只排「已经是 PAUSED」，于是筹备中 / 撤场中 / 已关闭的站点都能被暂停 ——
 * 一个还没开业的站点显示「暂停营业」，谁也说不清它到底是什么状态。
 *
 * @param pauseUntil 留空 = 无限期；填了到那天由定时任务自动恢复。
 */
export function pauseSite(siteNo: string, reason: string, pauseUntil?: string): Site {
  const s = sites.find((x) => x.siteNo === siteNo);
  if (!s) throw notFound("站点", "Site", siteNo);
  if (s.archivedAt) throw fail("已归档的站点不能暂停营业", "An archived site cannot be suspended", "لا يمكن تعليق موقع مؤرشف");
  if (!SITE_TRANSITIONS.pause.from.includes(s.status)) {
    throw fail(
      `「${s.status}」的站点不能暂停营业（只有营业中可以）`,
      `A site in ${s.status} cannot be suspended`,
      `لا يمكن تعليق موقع في حالة ${s.status}`,
    );
  }
  if (!reason.trim()) throw fail("请填写暂停原因", "A reason is required to suspend", "سبب التعليق مطلوب");
  s.status = SITE_TRANSITIONS.pause.to;
  siteStatusLog(s, "PAUSE", "ACTIVE", s.status, reason);
  if (s.ops) { s.ops.pauseReason = reason; s.ops.pauseUntil = pauseUntil ?? null; }
  return s;
}

export function resumeSite(siteNo: string): Site {
  const s = sites.find((x) => x.siteNo === siteNo);
  if (!s) throw notFound("站点", "Site", siteNo);
  if (s.archivedAt) throw fail("已归档的站点不能恢复营业", "An archived site cannot be reopened", "لا يمكن إعادة فتح موقع مؤرشف");
  if (!SITE_TRANSITIONS.resume.from.includes(s.status)) {
    throw fail(
      `「${s.status}」的站点不能恢复营业（只有暂停中可以）`,
      `A site in ${s.status} cannot be reopened`,
      `لا يمكن إعادة فتح موقع في حالة ${s.status}`,
    );
  }
  s.status = SITE_TRANSITIONS.resume.to;
  siteStatusLog(s, "RESUME", "PAUSED", s.status);
  if (s.ops) { s.ops.pauseReason = null; s.ops.pauseUntil = null; }
  return s;
}
