package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.SiteAgentBrief;

import java.util.List;

/**
 * 站点伙伴责任查询 —— platform 暴露给 finance 的**只读**面（同 {@link SiteSharingQueryPort} 一族）。
 *
 * <p>分润生成住在 finance，责任行住在 platform。finance 不直连 {@code loc_site_agent}：
 * 跨模块读别人的表，表一改就是两边同时坏，而坏法是「分账少了一条」——不报错。
 */
public interface SiteAgentQueryPort {

    /**
     * 某站点在**指定日期**生效的责任行。
     *
     * @param siteNo 站点业务键
     * @param onDate 生效期判定日（{@code YYYY-MM-DD}）。传结算日而不是「今天」——
     *               补算历史订单时用今天会漏掉当时生效、现已到期的责任
     * @return 无责任行时返回空列表（不是 null）。调用方据此回落到旧口径
     */
    List<SiteAgentBrief> agentsOf(String siteNo, String onDate);
}
