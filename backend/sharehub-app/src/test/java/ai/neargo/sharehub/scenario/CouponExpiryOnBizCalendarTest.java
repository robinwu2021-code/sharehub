package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.port.CouponUsePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 券的有效期按**业务日历**判，不按服务器时区。
 *
 * <h2>为什么要把时刻钉死</h2>
 * 这类缺陷每天只在几个小时里成立 —— 服务器时区（生产是 {@code Asia/Shanghai}）
 * 与业务时区（{@code Asia/Dubai}）跨日的那段。别的时候两个实现**没有可观察差异**。
 *
 * <p>2026-09-27 实测吃过这个亏：退回旧实现做负对照，用例照样绿，
 * 因为那一刻上海与迪拜同日。于是「负对照通过」这句话在白天根本做不出来。
 *
 * <p>所以这里用固定 {@link Clock} 把「现在」钉在 <b>UTC 2026-09-30T20:30</b>：
 * <ul>
 *   <li>业务时区（+4）= <b>2026-10-01 00:30</b> —— 业务日历已经翻篇；</li>
 *   <li>服务器时区若是 +8 则是 2026-10-01 04:30，若是别的又不一样 ——
 *       而这条用例**不依赖服务器时区是什么**，因为它比的是业务日。</li>
 * </ul>
 *
 * <h2>两个方向都要断言</h2>
 * 只断言「该过期的过期了」会漏掉反向缺陷：把判定写成 {@code !isAfter} 之类
 * 也能让第一条通过，代价是**有效期最后一天的券当天就用不了**。
 * 而那正是被修掉的那个 bug 的形状 —— 用户看得见、又说不出所以然的损失。
 */
// 不标 @SpringBootTest —— 父类已经标了（含 RANDOM_PORT）。在子类上再标一次会**覆盖**它，
// 退回默认的 MOCK 环境，于是父类的 @LocalServerPort 解析不了，报的是
// 「Could not resolve placeholder 'local.server.port'」—— 看不出与这个注解有关。
class CouponExpiryOnBizCalendarTest extends ai.neargo.sharehub.support.ApiTestSupport {

    /** 业务时区（+4）刚跨入 10-01 的那一瞬。UTC 20:30 → 迪拜 2026-10-01 00:30。 */
    private static final Instant JUST_AFTER_BIZ_MIDNIGHT = Instant.parse("2026-09-30T20:30:00Z");

    /** 业务时区仍在 09-30 的最后半小时。UTC 19:30 → 迪拜 2026-09-30 23:30。 */
    private static final Instant JUST_BEFORE_BIZ_MIDNIGHT = Instant.parse("2026-09-30T19:30:00Z");

    /**
     * 可变的固定时钟 —— 一个上下文里要验两个时刻，不想为此起两个 Spring 上下文。
     *
     * <p>{@code @Primary}：{@code ReportConfig} 那个 {@code Clock} bean 是
     * {@code @ConditionalOnMissingBean}，本来就会让位；标上它是为了**即使将来
     * 有人加了别的 Clock bean，这条用例也仍然钉得住**，而不是悄悄变成测真时钟。
     */
    static final class MutableClock extends Clock {
        volatile Instant at = JUST_AFTER_BIZ_MIDNIGHT;

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return at; }
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CouponUsePort coupons;

    @Autowired
    MutableClock clock;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM usr_coupon WHERE coupon_no LIKE 'CP-TZ-%'");
    }

    @Test
    @DisplayName("★ 业务日历已翻篇 → 有效期到前一天的券过期")
    void expired_when_the_business_calendar_has_moved_on() {
        clock.at = JUST_AFTER_BIZ_MIDNIGHT;                 // 迪拜 2026-10-01 00:30
        String no = seedCoupon("CP-TZ-A", "2026-09-30");
        assertThat(coupons.offerOf(USER, no, "AED"))
                .as("业务日已是 10-01，有效期到 09-30 的券应当已过期")
                .isNull();
    }

    @Test
    @DisplayName("★ 业务日历还没翻篇 → 有效期最后一天的券仍可用（这才是被修掉的那个 bug）")
    void still_usable_on_the_last_business_day() {
        /*
         * 旧实现用服务器时区的「今天」：迪拜 9-30 20:00 之后服务器已是 10-01，
         * 于是这张券当场作废 —— 而在业务日历上它还有 4 个小时。
         * 用户看得见、又说不出所以然的损失，那段代码的注释正是这么写的。
         */
        clock.at = JUST_BEFORE_BIZ_MIDNIGHT;                // 迪拜 2026-09-30 23:30
        String no = seedCoupon("CP-TZ-B", "2026-09-30");
        assertThat(coupons.offerOf(USER, no, "AED"))
                .as("业务日仍是 09-30，有效期到 09-30 的券不该被判过期")
                .isNotNull();
    }

    // ——————————————————————— 夹具 ———————————————————————

    private static final String USER = "CU-TZ-COUPON";

    /**
     * 直接落一张可用券，绕开发放流程 —— 这条用例只关心过期判定。
     *
     * <p>抵扣规格在**模板**上（{@code coupon_tpl.type/value/currency}），券行只有归属与状态，
     * 所以这里借一张现成的 ACTIVE 模板，而不是自己造一张 —— 造模板会把这条用例的
     * 失败面扩大到模板校验上，而那不是它要守的东西。
     */
    private String seedCoupon(String couponNo, String expireAt) {
        String tpl = jdbc.queryForObject(
                "SELECT tpl_no FROM coupon_tpl WHERE status='ACTIVE' AND currency='AED' AND deleted=0 LIMIT 1",
                String.class);
        jdbc.update("DELETE FROM usr_coupon WHERE coupon_no=?", couponNo);
        jdbc.update("""
                INSERT INTO usr_coupon (coupon_no, tenant_id, c_user_no, tpl_no, status, expire_at)
                VALUES (?, 'MAIN', ?, ?, 'UNUSED', ?)
                """, couponNo, USER, tpl, expireAt);
        return couponNo;
    }
}
