package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.sharehub.loc.ext.dto.SiteAgentDtos.SiteAgentRow;

import java.util.List;

/**
 * 站点上的伙伴责任（ADR-027 §三）。
 *
 * <p>只有运营方能配 —— 责任直接决定分钱，伙伴自助是 L3 的事。
 */
public interface SiteAgentService {

    /** 某站点的全部责任行，按责任层序稳定排序（同一个站点两次打开顺序一致）。 */
    List<SiteAgentRow> ofSite(String siteNo);

    /**
     * 新增 / 修改一行。
     *
     * @throws IllegalArgumentException 责任值非法；或与既有行冲突（REFER 与 DEVELOP 互斥）；
     *                                  或生效期倒置
     */
    SiteAgentRow upsert(String siteNo, SiteAgentRow in);

    /** 撤销一行责任。**删行而不是置标志** —— 见实体注释：数组列的读-改-写会静默覆盖。 */
    void remove(String siteNo, Long id);
}
