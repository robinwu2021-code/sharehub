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
