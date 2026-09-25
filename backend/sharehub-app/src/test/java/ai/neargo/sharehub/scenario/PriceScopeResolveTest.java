package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.trade.price.engine.PriceMultiplierResolver;
import ai.neargo.sharehub.trade.price.engine.PriceQuery;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import ai.neargo.sharehub.trade.price.entity.PricePlan;
import ai.neargo.sharehub.trade.price.entity.PricePlanScope;
import ai.neargo.sharehub.trade.price.entity.PriceSchedule;
import ai.neargo.sharehub.trade.price.entity.ScopeLevel;
import ai.neargo.sharehub.trade.price.mapper.PricePlanMapper;
import ai.neargo.sharehub.trade.price.mapper.PricePlanScopeMapper;
import ai.neargo.sharehub.trade.price.mapper.PriceScheduleMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 取价裁决（ADR-028 §二）与时段倍率（§四）。
 *
 * <p>本类锁的是**裁决顺序**。顺序错了不会抛异常、不会告警，只会静默按另一个方案收钱 ——
 * 与 {@link ChargeChainTest} 守的是同一类风险：算错钱且无人察觉。
 *
 * <p>不起 Spring：裁决是纯排序逻辑，mock 两个 mapper 就能穷举。
 */
class PriceScopeResolveTest {

    private final AtomicLong seq = new AtomicLong(1);
    private final List<PricePlanScope> rows = new ArrayList<>();

    private PricePlanScope scope(String planNo, ScopeLevel level, String ref) {
        PricePlanScope s = new PricePlanScope();
        s.setId(seq.getAndIncrement());
        s.setPlanNo(planNo);
        s.setScopeType(level.name());
        s.setScopeRef(ref);
        s.setDeviceType("POWERBANK");
        s.setPriority(0);
        rows.add(s);
        return s;
    }

    /** 装一个只认 rows 的 resolver；方案一律 ACTIVE。 */
    private PriceResolver resolver() {
        PricePlanScopeMapper scopes = Mockito.mock(PricePlanScopeMapper.class);
        Mockito.when(scopes.selectList(Mockito.any())).thenReturn(rows);
        PricePlanMapper plans = Mockito.mock(PricePlanMapper.class);
        Mockito.when(plans.selectList(Mockito.any())).thenAnswer(inv ->
                rows.stream().map(PricePlanScope::getPlanNo).distinct().map(no -> {
                    PricePlan p = new PricePlan();
                    p.setPlanNo(no);
                    p.setStatus("ACTIVE");
                    return p;
                }).toList());
        return new PriceResolver(scopes, plans, null, null);
    }

    private PriceQuery fullQuery() {
        return new PriceQuery("POWERBANK", "CAB1", "LOC1", "ST1", "VEN1", "AG1", "商场", "DU-MAR",
                "cd-tech", "X100", null, LocalDateTime.of(2026, 9, 23, 20, 0));
    }

    // ─────────── 层序：越具体越优先 ───────────

    /**
     * 八层全配上，必须选 DEVICE。
     *
     * <p>这是整条裁决的骨架：层序写反了，最具体的那条「单台特价」会被站点级盖掉，
     * 而界面上两条都好端端列着。
     */
    @Test
    void most_specific_level_wins() {
        scope("P-ALL", ScopeLevel.ALL, ScopeLevel.ALL_REF);
        scope("P-REGION", ScopeLevel.REGION, "DU-MAR");
        scope("P-SCENE", ScopeLevel.SCENE, "商场");
        scope("P-AGENT", ScopeLevel.AGENT, "AG1");
        scope("P-VENUE", ScopeLevel.VENUE, "VEN1");
        scope("P-SITE", ScopeLevel.SITE, "ST1");
        scope("P-LOCATION", ScopeLevel.LOCATION, "LOC1");
        scope("P-DEVICE", ScopeLevel.DEVICE, "CAB1");
        assertThat(resolver().match(fullQuery()).planNo()).isEqualTo("P-DEVICE");
    }

    /**
     * 场地方（合同承诺）压过伙伴（内部安排）—— ADR-028 §二。
     *
     * <p>机场说「全场 5 块」时，伙伴不能在机场里自己定 8 块。
     */
    @Test
    void venue_outranks_agent() {
        scope("P-AGENT", ScopeLevel.AGENT, "AG1");
        scope("P-VENUE", ScopeLevel.VENUE, "VEN1");
        assertThat(resolver().match(fullQuery()).planNo()).isEqualTo("P-VENUE");
    }

    /** 这一层在本次查询里无从判断（站点没查到）→ 该层不参与，落到 ALL，**不当成通配**。 */
    @Test
    void unknown_ref_does_not_match_as_wildcard() {
        scope("P-SITE", ScopeLevel.SITE, "ST1");
        scope("P-ALL", ScopeLevel.ALL, ScopeLevel.ALL_REF);
        PriceQuery noSite = PriceQuery.ofDeviceType("POWERBANK", LocalDateTime.now());
        PriceResolver.Hit hit = resolver().match(noSite);
        assertThat(hit.planNo()).isEqualTo("P-ALL");
        assertThat(hit.level()).isEqualTo("ALL");   // 如实记 ALL，不伪装成 SITE
    }

    // ─────────── 同层：过滤器命中数 → priority ───────────

    /** 「本站点 × 厂商」比「本站点」更具体，同层内胜出。 */
    @Test
    void more_filters_wins_within_same_level() {
        scope("P-SITE", ScopeLevel.SITE, "ST1");
        PricePlanScope withVendor = scope("P-SITE-VENDOR", ScopeLevel.SITE, "ST1");
        withVendor.setVendorCode("cd-tech");
        assertThat(resolver().match(fullQuery()).planNo()).isEqualTo("P-SITE-VENDOR");
    }

    /** 过滤器与本次查询不符 → 该行出局，不是「更不具体」而是根本不适用。 */
    @Test
    void mismatched_filter_is_excluded_not_demoted() {
        PricePlanScope other = scope("P-OTHER-VENDOR", ScopeLevel.SITE, "ST1");
        other.setVendorCode("sd-power");
        scope("P-ALL", ScopeLevel.ALL, ScopeLevel.ALL_REF);
        assertThat(resolver().match(fullQuery()).planNo()).isEqualTo("P-ALL");
    }

    /** 层与过滤器都并列时才轮到 priority。 */
    @Test
    void priority_breaks_remaining_tie() {
        scope("P-LOW", ScopeLevel.SITE, "ST1").setPriority(1);
        scope("P-HIGH", ScopeLevel.SITE, "ST1").setPriority(9);
        assertThat(resolver().match(fullQuery()).planNo()).isEqualTo("P-HIGH");
    }

    // ─────────── 硬过滤 ───────────

    /** 设备类型不符一律不进候选 —— 修「站点规则把充电宝价给了按摩椅」。 */
    @Test
    void device_type_is_a_hard_filter() {
        scope("P-SITE", ScopeLevel.SITE, "ST1").setDeviceType("MASSAGE_CHAIR");
        assertThat(resolver().match(fullQuery())).isNull();
    }

    /** 生效期未到 / 已过都不进候选。 */
    @Test
    void effective_window_excludes_rows_outside_it() {
        LocalDateTime at = LocalDateTime.of(2026, 9, 23, 20, 0);
        PricePlanScope future = scope("P-FUTURE", ScopeLevel.SITE, "ST1");
        future.setEffectiveFrom(at.plusDays(1));
        PricePlanScope past = scope("P-PAST", ScopeLevel.SITE, "ST1");
        past.setEffectiveTo(at.minusDays(1));
        assertThat(resolver().match(fullQuery())).isNull();
    }

    /**
     * 一条都没命中 → 抛异常，**绝不返回空规格**。
     *
     * <p>空规格会让引擎算出 0 元，那是静默免单。本项目栽过完全同型的一次
     * （{@code durationMinutes} 的 catch → return 0），静默少收钱比报错难查得多。
     */
    @Test
    void no_match_throws_rather_than_charging_zero() {
        PriceResolver r = resolver();
        PriceQuery q = fullQuery();
        assertThat(rows).isEmpty();
        try {
            r.resolve(q);
            throw new AssertionError("匹配不到方案时必须抛异常，不能静默返回空规格");
        } catch (ai.neargo.sharehub.common.BizException expected) {
            // 2026-09-25：改成 409 + 一句用户看得懂的话（「该点位暂不可借」）。
            // **拒绝结算这一条没变**，变的是谁看到什么：诊断信息（未匹配到哪条 query、
            // 该给哪个设备类型配默认方案）移进了 ERROR 日志，因为站在柜机前的人
            // 既看不懂也做不了什么。message 位置现在是 i18n key。
            assertThat(expected).hasMessage("error.pricing.not_borrowable");
        }
    }

    // ─────────── 时段倍率 ───────────

    private PriceMultiplierResolver multiplier(PriceSchedule... rows) {
        PriceScheduleMapper m = Mockito.mock(PriceScheduleMapper.class);
        Mockito.when(m.selectList(Mockito.any())).thenReturn(List.of(rows));
        return new PriceMultiplierResolver(m);
    }

    private PriceSchedule sched(String days, String from, String to, String mult) {
        PriceSchedule s = new PriceSchedule();
        s.setActive(1);
        s.setDays(days);
        s.setTimeFrom(from);
        s.setTimeTo(to);
        s.setMultiplier(new BigDecimal(mult));
        return s;
    }

    /** 2026-09-23 是周三（3），20:00 落在 18:00-22:00 内。 */
    @Test
    void multiplier_hits_by_weekday_and_time() {
        LocalDateTime wed20 = LocalDateTime.of(2026, 9, 23, 20, 0);
        assertThat(multiplier(sched("3", "18:00", "22:00", "1.5")).resolve(null, wed20))
                .isEqualByComparingTo("1.5");
        assertThat(multiplier(sched("4", "18:00", "22:00", "1.5")).resolve(null, wed20)).isNull();
        assertThat(multiplier(sched("3", "08:00", "12:00", "1.5")).resolve(null, wed20)).isNull();
    }

    /** 跨零点是合法区间（22:00-06:00）。 */
    @Test
    void multiplier_supports_ranges_crossing_midnight() {
        PriceMultiplierResolver r = multiplier(sched(null, "22:00", "06:00", "2"));
        assertThat(r.resolve(null, LocalDateTime.of(2026, 9, 23, 23, 0))).isEqualByComparingTo("2");
        assertThat(r.resolve(null, LocalDateTime.of(2026, 9, 23, 3, 0))).isEqualByComparingTo("2");
        assertThat(r.resolve(null, LocalDateTime.of(2026, 9, 23, 12, 0))).isNull();
    }

    /**
     * 多条命中取**最大**，不相乘。
     *
     * <p>相乘会让「高峰 1.5 × 节假日 1.5 = 2.25」悄悄出现，运营配的时候不会意识到。
     */
    @Test
    void multiple_hits_take_max_not_product() {
        LocalDateTime wed20 = LocalDateTime.of(2026, 9, 23, 20, 0);
        assertThat(multiplier(sched("3", "18:00", "22:00", "1.5"),
                sched(null, "19:00", "21:00", "1.8")).resolve(null, wed20))
                .isEqualByComparingTo("1.8");
    }

    /** 存量行（结构化列全空，只有展示串 period）不命中 —— 宁可不加倍，也不猜错倍率多收钱。 */
    @Test
    void legacy_rows_without_structured_columns_do_not_hit() {
        PriceSchedule legacy = new PriceSchedule();
        legacy.setActive(1);
        legacy.setPeriod("周六-周日 18:00-22:00");
        legacy.setMultiplier(new BigDecimal("1.5"));
        assertThat(multiplier(legacy).resolve(null, LocalDateTime.of(2026, 9, 26, 20, 0))).isNull();
    }

    /** 只填一端的时刻区间视为没配完 → 不命中，而不是按全天生效。 */
    @Test
    void half_filled_time_range_does_not_hit() {
        assertThat(multiplier(sched("3", "18:00", null, "1.5"))
                .resolve(null, LocalDateTime.of(2026, 9, 23, 20, 0))).isNull();
    }
}
