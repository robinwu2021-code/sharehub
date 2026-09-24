package ai.neargo.sharehub.loc.ext.service;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.OnboardingReviewReq;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.VenueOnboarding;

/**
 * 门店自助进件（审核有业务规则 → 手写，不继承通用 CRUD）。
 *
 * <p>关键规则：**审核通过必须先建场地方再回填 {@code venueNo}**，二者要么都成、要么都不成
 * （所以 {@link #review} 是 {@code @Transactional} 的）。
 */
public interface VenueOnboardingService {

    PageResult<VenueOnboarding> page(Integer page, Integer size, String keyword, String status);

    VenueOnboarding get(String onboardingNo);

    /**
     * 新建 / 修改进件（运营代录）。
     *
     * <p>自助进件本该由门店自己提交，但**渠道还没开**，在那之前运营得能替客户把单子录进来 ——
     * 否则「门店 Onboarding」这个菜单在真后端下只能看不能用。
     *
     * <p><b>已审核的进件不可再改</b>：审核结论是对**当时那份内容**做的，
     * 事后改内容等于让结论对不上它审的东西。要改只能重新提一单。
     */
    VenueOnboarding save(String onboardingNo, VenueOnboarding in);

    /**
     * 审核。通过 → 经 {@link VenueCreator} 建 {@code loc_venue} 并回填 {@code venue_no}；
     * 驳回 → 必须给原因。已审过的进件不允许二次审核（非法迁移抛 {@link IllegalArgumentException}）。
     */
    VenueOnboarding review(String onboardingNo, OnboardingReviewReq req);
}
