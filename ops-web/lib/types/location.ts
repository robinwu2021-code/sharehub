// 覆盖范围：场所域——场地方 → 站点 → 点位（ADR-013 两层）、合同、
// BD 线索（CRM）、站点经营分析、门店自助 onboarding、站点生命周期。

import type { Archivable } from "./common";

export interface Site extends Archivable {
  siteNo: string;
  name: string;
  venueName: string;
  agentNo: string | null; // 归属代理，空=平台直营
  /** 区域字典 ID（`regions.regionId`，如 `DU-MAR`）。台账 M11：原先误存区域名。 */
  regionId: string;
  /** 区域展示名（冗余自 `regions.name`）。存 ID 是因为区域名会变、ID 不会；列表要展示故冗余一份。 */
  regionName: string;
  address: string;
  /** 经纬度（地图撒点用）。运营端按**站点**聚合展示，不逐台机柜撒点——上千机柜会卡。 */
  lat: number;
  lng: number;
  sceneType: string;
  pointCount: number;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
/**
 * 站点内的投放点位（Site → SitePoint → 机柜）。
 *
 * ⚠️ 原名 `Location`（2026-07-29 改名，台账 T8）。两个问题：
 *  1. **遮蔽 DOM 全局 `Location`** —— `.tsx` 里忘记 import 时会静默拿到 DOM 类型，tsc 不报错；
 *  2. 与 `Site`（站点）语义打架，读代码时分不清哪个是"场地"哪个是"点位"。
 * 业务号仍为 `locationNo`（后端字段名未动，改名只在前端类型层）。
 */
export interface SitePoint extends Archivable {
  locationNo: string; // 点位
  name: string;
  siteNo: string;
  siteName: string;
  spotDesc: string;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
export interface Venue extends Archivable {
  venueNo: string;
  name: string;
  contact: string;
  industry: string;
  locationCount: number;
}
export interface Contract {
  contractNo: string;
  venueName: string;
  siteName: string; // 合同绑定 场地方 × 站点（ADR-013）
  shareRate: number; // 0..1
  entryFee: number;
  startAt: string;
  endAt: string;
  status: "ACTIVE" | "EXPIRED";
}

// —— 场所 · 待建功能补全（ops 域）——
export interface Lead {
  leadNo: string;
  venueName: string;
  contact: string;
  stage: "NEW" | "CONTACTED" | "NEGOTIATING" | "SIGNED" | "LOST";
  owner: string;
  expectSites: number;
  updatedAt: string;
}
export interface SiteAnalysis {
  siteNo: string;
  siteName: string;
  revenue: number;
  orders: number;
  turnover: number; // 次/日
  paybackDays: number;
  cabinetCount: number;
  currency: string;
}

// —— 门店自助 Onboarding（场地域 · P2）——
export interface VenueOnboarding {
  onboardingNo: string;
  venueName: string;
  contact: string;
  industry: string;
  requestedAt: string;
  status: "PENDING" | "APPROVED" | "REJECTED";
  reviewAt: string | null;
  reviewNote: string | null;
}

// —— 站点生命周期管理（场地域 · P3）——
export interface SiteLifecycle {
  siteNo: string;
  siteName: string;
  stage: "PROSPECTING" | "SIGNED" | "LIVE" | "ACTIVE" | "CHURNED" | "CLOSED";
  stageAt: string;
  owner: string;
  currency: string;
  gmvLtm: number; // 近 12 月 GMV
}
