package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.loc.ext.service.VenueCreator;
import org.springframework.stereotype.Component;

/**
 * {@link VenueCreator} 的**占位实现**：只取号，不落 {@code loc_venue} 行。
 *
 * <p>存在的意义是让「进件审核通过 → 回填 {@code venueNo}」这条链路现在就能端到端跑通并被测试覆盖，
 * 而不必等 {@code loc} 主包开出场地方写入口。
 *
 * <p><b>已知缺口（必须替换后才能上线）</b>：本实现返回的 {@code venue_no} 在
 * {@code loc_venue} 里**查不到对应行** —— 进件通过后场地方列表不会多出这一条。
 * 真实实现请调 {@code loc.LocService} 的场地方写入口（见 {@link VenueCreator} 类注释），
 * 落地时给真实 Bean 标 {@code @Primary}（或删除本类），审核流程一行不用改。
 */
@Component
public class StubVenueCreator implements VenueCreator {

    @Override
    public String create(String venueName, String contact, String industry) {
        // TODO(loc 主包): 改为落 loc_venue 行（name=venueName, contact=contact, industry=industry）
        return IdGenerator.next(BizKey.VENUE);
    }
}
