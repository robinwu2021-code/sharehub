// 覆盖范围：场所域——场地方 → 站点 → 点位（ADR-013 两层）、合同、
// BD 线索（CRM）、站点经营分析、门店自助 onboarding、站点生命周期。

export interface Site {
  siteNo: string;
  name: string;
  venueName: string;
  agentNo: string | null; // 归属代理，空=平台直营
  regionId: string;
  address: string;
  sceneType: string;
  pointCount: number;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
export interface Location {
  locationNo: string; // 点位
  name: string;
  siteNo: string;
  siteName: string;
  spotDesc: string;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}
export interface Venue {
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
