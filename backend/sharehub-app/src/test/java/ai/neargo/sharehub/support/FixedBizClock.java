package ai.neargo.sharehub.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 把「现在」钉死，让**时区缺陷任何时候都能被照出来**。
 *
 * <h2>为什么需要它</h2>
 * 时区缺陷每天只在几个小时里成立 —— 服务器时区（生产是 {@code Asia/Shanghai}）
 * 与业务时区（{@code Asia/Dubai}）跨日的那段。别的时候正确实现与错误实现
 * **没有可观察差异**，于是负对照做不出来。
 *
 * <p>2026-09-27 实测吃过这个亏：退回 {@code LocalCouponUse} 的修复做负对照，
 * 用例照样绿，因为那一刻上海与迪拜同日。「负对照通过」这句话在白天根本说不出口。
 *
 * <h2>怎么用</h2>
 * <pre>{@code
 * @Import(FixedBizClock.class)
 * class SomethingTest extends ApiTestSupport {
 *     @Autowired FixedBizClock.MutableClock clock;
 *
 *     @Test void x() {
 *         clock.at = FixedBizClock.JUST_AFTER_BIZ_MIDNIGHT;   // 业务日历已翻篇
 *         ...
 *     }
 * }
 * }</pre>
 *
 * <p>{@link BizClockConsumers 谁会受影响}：凡是走 {@code BizClock#today()/now()} 的代码。
 * **不走它的时间戳不受影响** —— 例如 {@code nowUtc()}、{@code LocalDateTime.now()}
 * 写的审计列。所以钉住时钟**不等于**整个流程都可复现，用它之前先确认被测的那一步
 * 真的问 BizClock 要日期，否则会得到一条永远不会红的用例。
 */
@TestConfiguration
public class FixedBizClock {

    /** 业务时区（+4）刚跨入 07-01 的那一瞬：UTC 20:30 → 迪拜 2030-07-01 00:30。跨日**且**跨月。 */
    // 2026-10-29 从 2026-09-30 挪到 2030：用例要求「真实日期」与钉住的业务日期不同月、且钉住的在未来，
    // 真实日历走到 2026-10 那天起 ReferFeePeriod / TodayWindow 的前提就不成立了。挪远一点，几年内不用再动
        public static final Instant JUST_AFTER_BIZ_MIDNIGHT = Instant.parse("2030-06-30T20:30:00Z");

    /** 业务时区仍在 06-30 的最后半小时：UTC 19:30 → 迪拜 2030-06-30 23:30。 */
    public static final Instant JUST_BEFORE_BIZ_MIDNIGHT = Instant.parse("2030-06-30T19:30:00Z");

    /**
     * 可变的固定时钟 —— 一个上下文里要验多个时刻，不想为此起多个 Spring 上下文。
     *
     * <p>{@code volatile}：Spring 上下文里的 bean 可能被别的线程读到（异步事件消费）。
     */
    public static final class MutableClock extends Clock {
        public volatile Instant at = JUST_AFTER_BIZ_MIDNIGHT;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return at; }
    }

    /**
     * {@code @Primary}：{@code ReportConfig} 的 {@code Clock} bean 是
     * {@code @ConditionalOnMissingBean}，本来就会让位；标上它是为了**即使将来有人加了
     * 别的 Clock bean，用了本类的用例也仍然钉得住**，而不是悄悄退化成测真实时钟。
     */
    @Bean
    @Primary
    public MutableClock mutableClock() {
        return new MutableClock();
    }

    /** 仅供 javadoc 引用，无实现意义。 */
    interface BizClockConsumers {
    }
}
