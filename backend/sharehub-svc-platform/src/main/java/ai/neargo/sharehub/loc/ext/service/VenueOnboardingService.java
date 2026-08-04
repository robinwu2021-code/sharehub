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
     * 审核。通过 → 经 {@link VenueCreator} 建 {@code loc_venue} 并回填 {@code venue_no}；
     * 驳回 → 必须给原因。已审过的进件不允许二次审核（非法迁移抛 {@link IllegalArgumentException}）。
     */
    VenueOnboarding review(String onboardingNo, OnboardingReviewReq req);
}
