// 运营管理 · 站点概览与单站统计的读模型（清单 OM-S1 / OM-S2）。
// 都是聚合结果，不是新实体：后端对应 GET /api/ops/operation/overview 与 /api/ops/sites/{no}/stats。

/** 站点概览的规模指标（存量，不随时间筛选变化）。 */
export interface OverviewScale {
  siteTotal: number;
  siteActive: number;
  sitePaused: number;
  pointTotal: number;
  cabinetTotal: number;
  cabinetOnline: number;
  /** 在线率 0..1（在线且非故障 ÷ 机柜总数，口径与经营看板一致）。 */
  onlineRate: number;
  powerbankTotal: number;
  powerbankInCabinet: number;
  powerbankRented: number;
  powerbankFault: number;
}

/** 经营指标（随时间筛选变化）。 */
export interface OverviewBusiness {
  orders: number;
  gmv: number;
  currency: string;
  /** 客单价 = gmv / orders，orders=0 时为 0。 */
  avgOrderValue: number;
  /** 单柜日均订单 = orders / 机柜数 / 天数。 */
  ordersPerCabinetPerDay: number;
}

export interface OverviewTrendPoint { day: string; orders: number; gmv: number }

export type SiteRankMetric = "gmv" | "orders" | "perCabinet" | "onlineRate";
export interface SiteRankRow {
  siteNo: string;
  siteName: string;
  venueName: string;
  cabinetCount: number;
  gmv: number;
  orders: number;
  /** 单柜日均订单 */
  perCabinet: number;
  /** 在线率 0..1 */
  onlineRate: number;
}

/** 待关注站点的问题类型（规则见 lib/operation-overview#attentionOf）。 */
export type AttentionKind =
  | "ALL_OFFLINE"      // 有机柜但全部离线
  | "NO_CABINET"       // 营业中但没有机柜
  | "CONTRACT_EXPIRED" // 合同已过期但仍在营业
  | "CONTRACT_SOON"    // 合同 30 天内到期
  | "NO_PRICE_PLAN"    // 没有命中任何收费方案
  | "NO_SHARING"       // 没有任何分成配置
  | "NO_ORDER";        // 近 7 日零订单

export interface AttentionItem {
  siteNo: string;
  siteName: string;
  kind: AttentionKind;
  severity: "high" | "medium" | "low";
  /** 一句话说明，含具体数字（如「3 台机柜全部离线 5 小时」）。 */
  detail: string;
}

export interface SceneShare { sceneType: string; siteCount: number; gmv: number }

export interface OperationOverview {
  scale: OverviewScale;
  business: OverviewBusiness;
  trend: OverviewTrendPoint[];
  ranking: SiteRankRow[];
  attention: AttentionItem[];
  scenes: SceneShare[];
  /** 有经纬度的站点数 / 站点总数——地图能不能开就看它（当前库里恒为 0，页面据此显示原因）。 */
  geoReady: { withGeo: number; total: number };
}

/** 单站统计（站点详情的「统计」页签，对标简电站场管理的「统计」动作）。 */
export interface SiteStats {
  siteNo: string;
  siteName: string;
  from: string;
  to: string;
  orders: number;
  gmv: number;
  currency: string;
  avgOrderValue: number;
  /** 平均租借时长（分钟） */
  avgDurationMin: number;
  ordersPerCabinetPerDay: number;
  onlineRate: number;
  cabinetCount: number;
  trend: OverviewTrendPoint[];
  /** 按点位拆分 */
  byPoint: { locationNo: string; locationName: string; cabinetCount: number; orders: number; gmv: number; perCabinet: number }[];
}

// ——— 预约调价（清单 OM-S4；后端需新表 price_adjustment）———————————————

/** 可调整的计费字段（只列这几个：改适用范围属于改方案本身，不走调价）。 */
export interface PriceAdjustPatch {
  freeMinutes?: number;
  unitMinutes?: number;
  unitPrice?: number;
  capDaily?: number;
  buyoutPrice?: number;
}

/** 与后端 `PriceAdjustmentStatus` 枚举同名同值。**具名不是风格** —— 两端同名词表比对只认
 *  具名 `export type`，内联在 interface 里的联合它一个都发现不了。 */
export type PriceAdjustmentStatus = "SCHEDULED" | "APPLIED" | "CANCELLED" | "REVERTED" | "FAILED";
export interface PriceAdjustment {
  adjustNo: string;
  planNo: string;
  planName: string;
  name: string;
  patch: PriceAdjustPatch;
  /** 生效时写入的变更前快照，用于到期恢复与审计；未生效时为空。 */
  beforeSnapshot: PriceAdjustPatch | null;
  effectiveAt: string;       // UTC ISO
  revertAt: string | null;   // UTC ISO，空 = 不自动恢复
  reason: string;
  status: PriceAdjustmentStatus;
  appliedAt: string | null;
  revertedAt: string | null;
  failReason: string | null;
  createdBy: string;
  createdAt: string;
}

// ——— 分成（清单 OM-S5 / OM-S6）———————————————————————————————
//
// ⚠️ 契约取决于清单 D2（分成比例现在存在分润规则与进场合同两处，且缺少站点维度）。
// 这里的形状是**前端按现状聚合出来的读模型**，后端定案后可能调整。

export interface SitePayee {
  payeeType: "VENUE" | "AGENT";
  payeeNo: string;
  payeeName: string;
  /** 0..1 */
  rate: number;
  mode: string;
  /** 这条比例是从哪里来的：合同 or 分润规则 */
  source: "CONTRACT" | "RULE";
  sourceNo: string;
}

export interface SiteSharingRow {
  siteNo: string;
  siteName: string;
  venueName: string;
  payees: SitePayee[];
  /** 各方合计 0..1 */
  totalRate: number;
  /** 平台留存 = 1 - totalRate */
  platformRate: number;
  contractEndAt: string | null;
  /** 配置状态：已配置 / 缺配置 / 异常（超 100% 或合同过期仍营业） */
  state: "OK" | "MISSING" | "INVALID";
  stateDetail: string;
}

export interface PayeeSharingRow {
  payeeType: "VENUE" | "AGENT";
  payeeName: string;
  siteCount: number;
  minRate: number;
  maxRate: number;
  /** 近 30 日分成金额（来自分润明细；没有真实明细时为 0 并在页面说明） */
  amount30d: number;
  currency: string;
  sites: { siteNo: string; siteName: string; rate: number }[];
}
