package ai.neargo.sharehub.report.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.report.dto.ReportDtos.SiteAnalysis;

/**
 * 站点坪效（{@code [读] SiteAnalysis}）—— **读模型，不建物理表**。
 *
 * <p>来源：{@code loc_site ⋈ ord_order} 聚合（[db-design §3.4]）：
 * <ul>
 *   <li>{@code revenue} = 期间内该站点订单 {@code fee_amount} 之和（{@code ord_order.site_no} 冗余列直出，
 *       不必回join {@code loc_location}）；</li>
 *   <li>{@code orders} = 订单数；</li>
 *   <li>{@code turnover} = 翻台率 = {@code orders / (cabinet 仓位数 × 天数)}；</li>
 *   <li>{@code paybackDays} = 回本天数 = 累计投入 / 日均 {@code revenue}；</li>
 *   <li>{@code cabinetCount} = 该站点在架机柜数（{@code dev_cabinet.site_no} 计数，
 *       **不取 {@code loc_site.cabinet_count} 列** —— [db-design §1.4]「计数列不是列，是聚合」）。</li>
 * </ul>
 */
public interface SiteAnalysisService {

    /**
     * 坪效列表。
     *
     * @param from 统计起始日 {@code yyyy-MM-dd}（含），空 = 近 30 天
     * @param to   统计截止日 {@code yyyy-MM-dd}（含），空 = 今天
     */
    PageResult<SiteAnalysis> page(Integer page, Integer size, String keyword, String from, String to);
}
