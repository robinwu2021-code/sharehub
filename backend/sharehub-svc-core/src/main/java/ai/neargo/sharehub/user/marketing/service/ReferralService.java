package ai.neargo.sharehub.user.marketing.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralVO;

/**
 * 邀请裂变记录（mkt_referral）。运营端只读（{@code GET /api/user/referrals}）——
 * 记录由 C 端注册链路自动产生（[api/README §6A.1] 判据一：外部事实触发的单据系统自动建），
 * **不提供人工补录口**，否则归因数据可被伪造，反作弊无从谈起。
 */
public interface ReferralService {

    /**
     * @param inviterNo 按邀请人过滤（查某人拉了多少）
     * @param status    PENDING / REWARDED
     */
    PageResult<ReferralVO> page(Integer page, Integer size, String keyword, String inviterNo, String status);

    /** 裂变规则列表。 */
    /** 邀请规则分页（{@code mkt_referral_rule}，V31/V33 —— 不再翻邀请记录表，D-3 已修）。 */
    ai.neargo.common.core.PageResult<ai.neargo.sharehub.user.marketing.dto.MarketingDtos.ReferralRuleVO>
            pageRules(Integer page, Integer size);
}
