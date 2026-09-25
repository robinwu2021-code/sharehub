// 运营管理域：站点概览、单站统计、站点营业状态。
// 这三个能力后端**尚未实现**（见 TDD-运营管理菜单-前端 §1.2），http 实现按约定端点先写好；
// 就绪度登记在 lib/backend-ready.ts，未就绪时菜单灰显、页内按钮不渲染。
import type {
  OperationOverview, SiteStats, PriceAdjustment, SiteSharingRow, PayeeSharingRow,
} from "../../types/operation";
import type { Site } from "../../types/location";
import type { PageResult } from "../../types/common";
import type { PageQ } from "../query";

/** 概览查询：时间区间 + 区域 / 代理过滤。 */
export interface OverviewQ {
  from?: string;  // UTC ISO
  to?: string;    // UTC ISO
  regionId?: string;
  agentNo?: string;
}

/** 单站统计查询：时间区间。 */
export interface SiteStatsQ { from?: string; to?: string }

export interface OperationApi {
  /** 站点概览（规模 / 经营 / 趋势 / 排行 / 待关注 / 场景分布）。 */
  getOperationOverview(q?: OverviewQ): Promise<OperationOverview>;
  /** 单站统计（站点详情「统计」页签）。 */
  getSiteStats(siteNo: string, q?: SiteStatsQ): Promise<SiteStats>;
  /** 暂停营业：C 端隐藏该站点、站内机柜不允许新借，**已借出的仍可归还**。 */
  /** 暂停营业。`pauseUntil` 留空 = 无限期；填了到那天由定时任务自动恢复。 */
  pauseSite(siteNo: string, reason: string, pauseUntil?: string): Promise<Site>;
  /** 恢复营业。 */
  resumeSite(siteNo: string): Promise<Site>;

  // —— 预约调价（OM-S4；后端需新表 + 定时任务）——
  listPriceAdjustments(q?: PageQ & { planNo?: string; status?: string }): Promise<PageResult<PriceAdjustment>>;
  savePriceAdjustment(x: Partial<PriceAdjustment> & { adjustNo?: string }): Promise<PriceAdjustment>;
  /** 撤销待生效的调价单，必须填原因。 */
  cancelPriceAdjustment(adjustNo: string, reason: string): Promise<PriceAdjustment>;
  /** 提前恢复原价（不等自动恢复时间）。 */
  revertPriceAdjustment(adjustNo: string): Promise<PriceAdjustment>;
  /** 重试执行失败的调价单。 */
  retryPriceAdjustment(adjustNo: string): Promise<PriceAdjustment>;

  // —— 分成两视角（OM-S5 / OM-S6；契约取决于清单 D2）——
  listSiteSharing(q?: PageQ & { state?: string; venueName?: string }): Promise<PageResult<SiteSharingRow>>;
  getSiteSharingStats(): Promise<{ total: number; ok: number; missing: number; invalid: number }>;
  listPayeeSharing(q?: PageQ & { payeeType?: string }): Promise<PageResult<PayeeSharingRow>>;
}
