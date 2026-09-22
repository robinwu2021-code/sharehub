// 运营管理域：站点概览、单站统计、站点营业状态。
// 这三个能力后端**尚未实现**（见 TDD-运营管理菜单-前端 §1.2），http 实现按约定端点先写好；
// 就绪度登记在 lib/backend-ready.ts，未就绪时菜单灰显、页内按钮不渲染。
import type { OperationOverview, SiteStats } from "../../types/operation";
import type { Site } from "../../types/location";

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
  pauseSite(siteNo: string, reason: string): Promise<Site>;
  /** 恢复营业。 */
  resumeSite(siteNo: string): Promise<Site>;
}
