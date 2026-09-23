// 覆盖范围：场所域——场地方 → 站点 → 点位（ADR-013 两层）、合同、
// BD 线索（CRM）、站点经营分析、门店自助 onboarding、站点生命周期。

import type { Archivable } from "./common";

export interface Site extends Archivable {
  siteNo: string;
  name: string;
  /**
   * 归属场地方编号（`venues.venueNo`）。**分成按它走**，不按 venueName ——
   * 种子里就有同名场地方，按名字连必然把钱分给另一家。
   * 2026-09-23：后端 `loc_site.venue_no` 列早就有、实体与 DTO 此前漏映射，已补。
   */
  venueNo?: string | null;
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
  /** 阿语站点名（后端 `loc_site.name_ar`）。空 = 阿语界面回退中文名。 */
  nameAr?: string;
  /** 营业时间，多段逗号分隔，如 `10:00-14:00,17:00-22:00`（后端 `loc_site.open_hours`）。 */
  openHours?: string;
  pointCount: number;
  cabinetCount: number;
  status: "ACTIVE" | "PAUSED";
}

/**
 * 站点坐标的可信窗口（SSOT）：抽屉里的提示文案与 mock 落库校验共用一份。
 *
 * 为什么要有窗口而不是只判 ±90/±180：经纬度写反（55, 25）在全球范围里完全合法，
 * 但会把点扔到印度洋，地图上表现为「站点凭空消失」，是最难查的一类脏数据。
 * 窗口 = 当前运营国家（UAE）的外接矩形，扩国家时改这里一处。
 */
export const SITE_COORD_BOUNDS = { latMin: 22, latMax: 27, lngMin: 51, lngMax: 57 } as const;

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
  /**
   * 场地方 / 站点编号。合同是**场地方分成的唯一依据**，按编号连；
   * 名字只作展示冗余（同一商场不同楼层会有同名站点，按名字连必然连错）。
   * 后端 `loc_contract.venue_no/site_no` 列与实体都已有，读接口的 DTO 尚未带出（见 contracts/location.ts 缺口）。
   */
  venueNo?: string | null;
  siteNo?: string | null;
  venueName: string;
  siteName: string; // 合同绑定 场地方 × 站点（ADR-013）
  shareRate: number; // 0..1
  entryFee: number;
  startAt: string;
  endAt: string;
  status: "ACTIVE" | "EXPIRED";
  /** 合同扫描件。内嵌而非另开列表接口：一份合同的附件个数是个位数，单独分页没有意义。 */
  attachments: ContractAttachment[];
}

/**
 * 合同附件（扫描件）元数据。
 *
 * **拍板点 #3：mock 阶段做假上传** —— 只存文件名 + 大小，字节流不传、不读。
 * 所以这里故意没有 `url` / `storageKey`：接后端时由服务端返回对象存储地址再补字段，
 * 前端先编一个假地址的话，「点开看不了」会比「明确没有下载入口」更难查。
 */
export interface ContractAttachment {
  attachNo: string;
  fileName: string;
  size: number; // 字节；由 File.size 直取，不做换算，展示层再格式化
  uploadedBy: string;
  uploadedAt: string;
}
/**
 * 附件限制（SSOT）：抽屉的 `accept` 属性与提示文案、mock 落库校验共用一份。
 * 分开写会出现「input 不让选、但接口收」或反过来——两种都会被当成 bug 报上来。
 */
export const ATTACH_EXTS = ["pdf", "jpg", "jpeg", "png"] as const;
/** 单份上限 10MB：合同扫描件超过这个量级基本是没压缩的整本 PDF，先挡住而不是让它进库。 */
export const ATTACH_MAX_SIZE = 10 * 1024 * 1024;

/** 上传入参。`uploadedBy` 留空由服务端取当前登录人（与流转留痕的 operator 同口径）。 */
export interface ContractAttachmentReq {
  fileName: string;
  size: number;
  uploadedBy?: string;
}

// —— 场所 · 待建功能补全（ops 域）——

/** 线索阶段取值域（SSOT）：页面徽标/筛选、mock 校验、跟进记录的阶段快照共用一份。 */
export const LEAD_STAGES = ["NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST"] as const;
export type LeadStage = (typeof LEAD_STAGES)[number];

export interface Lead {
  leadNo: string;
  venueName: string;
  contact: string;
  stage: LeadStage;
  owner: string;
  expectSites: number;
  /** 最后一次跟进时间 —— 与 `leadFollowUps` 里最新一条的 `createdAt` 必须一致（列表按它排序）。 */
  updatedAt: string;
}

/** 跟进方式。后端尚无此表，取值域由前端先定（见 contracts/location.ts 的缺口标注）。 */
export const LEAD_FOLLOW_CHANNELS = ["CALL", "VISIT", "WHATSAPP", "EMAIL", "OTHER"] as const;
export type LeadFollowChannel = (typeof LEAD_FOLLOW_CHANNELS)[number];

/**
 * 线索跟进记录（append-only 流水，对应设想中的 `loc_lead_follow_up`）。
 *
 * 阶段快照记 `fromStage`/`toStage` 而不是只记「当时阶段」：CRM 里真正要回答的问题是
 * **哪一次跟进推动了阶段变化**，只存单值的话时间线上看不出推进点。
 */
export interface LeadFollowUp {
  followNo: string;
  leadNo: string;
  channel: LeadFollowChannel;
  fromStage: LeadStage | null; // 首条建档跟进没有来源阶段
  toStage: LeadStage;
  owner: string;
  content: string;
  nextAt: string | null; // 下次跟进计划日（YYYY-MM-DD），空=未约
  createdAt: string;
}
/** 记一条跟进。`stage` 不传=只留痕不动阶段；传了且与当前不同则同时推进线索阶段。 */
export interface LeadFollowUpReq {
  content: string;
  channel: LeadFollowChannel;
  stage?: LeadStage;
  owner?: string;
  nextAt?: string;
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

/** 阶段取值域（SSOT）：与后端 `SiteLifecycleServiceImpl.STAGES`、[db-design §3.4] 逐字一致。 */
export const SITE_STAGES = ["PROSPECTING", "SIGNED", "LIVE", "ACTIVE", "CHURNED", "CLOSED"] as const;
export type SiteStage = (typeof SITE_STAGES)[number];

export interface SiteLifecycle {
  siteNo: string;
  siteName: string;
  stage: SiteStage;
  stageAt: string;
  owner: string;
  currency: string;
  gmvLtm: number; // 近 12 月 GMV
}

/**
 * 阶段流转入参，镜像后端 `StageChangeReq`。
 *
 * `gmvLtm` 是**阶段决策快照**（进入该阶段那一刻的近 12 月 GMV），服务端只落库、不定时回刷；
 * 留空即沿用上一次的值。页面不传——运营手填一个 GMV 只会污染快照，实时值请看「站点坪效」。
 */
export interface SiteStageChangeReq {
  stage: SiteStage;
  reason?: string;
  operator?: string;
  gmvLtm?: number;
  currency?: string;
}

/**
 * 阶段流转合法性（SSOT）：页面按钮可用性与 mock 校验共用同一份，
 * 与结算单 `STL_TRANSITIONS`、工单 `WO_TRANSITIONS` 同一个位置、同一套用法。
 *
 * 但**故意不是一张单向状态机图**：后端 `SiteLifecycleServiceImpl.changeStage` 写明
 * [db-design §9A] 只给 `ord_rent`/`wo_order`/`dev_*` 三处定稿了状态机，门店生命周期不在其列；
 * 现实里「CHURNED 的店重新签回来」「CLOSED 复开」都是正常业务，硬编一条链会当场挡住合法操作。
 * 前端若自己加严，就会出现「接口能做、按钮不给点」的假约束——比放开更难查。
 * 因此这里与后端保持完全一致：只排掉「目标 = 当前」这一种非法，其余交给留痕（每次流转必写 log）。
 */
export const nextSiteStages = (from: SiteStage): SiteStage[] => SITE_STAGES.filter((s) => s !== from);
export const canSiteStageTransition = (from: SiteStage, to: SiteStage) => nextSiteStages(from).includes(to);
