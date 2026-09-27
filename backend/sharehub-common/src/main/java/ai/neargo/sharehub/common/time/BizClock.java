package ai.neargo.sharehub.common.time;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * **业务日期的唯一来源**。要「今天是哪天」就问它，不要直调 {@code LocalDate.now()}。
 *
 * <h2>为什么必须有这个东西</h2>
 * 2026-09-27 01:15 由 10 条合同用例同时变红查出来：这个仓库里「今天」有**三个**答案。
 *
 * <table>
 *   <caption>同一时刻的三个「今天」</caption>
 *   <tr><th>取法</th><th>时区</th><th>2026-09-27 01:21 CST 时</th></tr>
 *   <tr><td>{@code LocalDate.now()} 直调</td><td>服务器 JVM</td><td>09-27</td></tr>
 *   <tr><td>{@code LocalDate.now(bizZone)}</td><td>{@code Asia/Dubai}</td><td>09-26</td></tr>
 *   <tr><td>{@code Clock.systemUTC()}（报表）</td><td>UTC</td><td>09-26</td></tr>
 * </table>
 *
 * <p>**生产服务器实测跑在 {@code Asia/Shanghai}**（{@code -Duser.timezone=Asia/Shanghai}），
 * 而业务在阿联酋（UTC+4）。于是每天有 <b>4 小时</b> —— 迪拜的 20:00 到午夜，
 * 正是租借高峰 —— 各模块对「今天是哪天」的答案不一致。
 *
 * <h2>这类缺陷为什么难发现</h2>
 * 它**每天自己好一次**：过了那 4 小时再跑就全绿。于是它看起来像偶发抖动，
 * 而不是缺陷 —— 最坏的情况是有人为了「修」掉它去放宽服务端校验，
 * 那是把真约束拆掉，换来一个永远不会红的测试。
 *
 * <h2>报表为什么不走这里</h2>
 * {@code ReportConfig} 刻意用 {@code Clock.systemUTC()}，理由正当：
 * {@code ord_order.rent_start_at} 存的是 UTC ISO-8601 字符串，报表按 UTC 切业务日，
 * 混用会让跨零点的订单在不同报表里落到不同天。**所以这不是「统一成一个」那么简单**
 * —— 数据本身就是混的（{@code endedAt} 是服务器时区的 {@code LocalDateTime}，
 * {@code rentEndAt} 是 UTC 字符串）。本类只管「业务日历上的今天」这一种语义。
 *
 * <h2>什么时候该用</h2>
 * 合同生效/终止日、券的有效期、免费额度的自然月、结算归属期间 ——
 * 凡是**会被人在业务日历上核对**的日期。
 *
 * <p>反之，纯粹的时间戳（{@code created_at}、日志时间）不归这里管。
 */
@Component
public class BizClock {

    private final ZoneId zone;
    private final Clock clock;

    /**
     * @param clockProvider 走 {@link Clock} bean 而不是 {@code LocalDate.now(zone)}，
     *                      是为了让「现在」在测试里**可以被钉住**。
     *                      <p>时区缺陷的麻烦在于它每天只在几个小时里成立（服务器时区与业务时区
     *                      跨日的那段），于是负对照白天做不出来 —— 实测栽过一次：退回旧实现后
     *                      用例照样绿，因为那一刻两个实现根本没有可观察差异。
     *                      有了这个缝，测试可以把时刻固定在跨日/跨月的那一瞬，
     *                      让这类缺陷**任何时候都能被照出来**。
     *                      <p>用 {@link ObjectProvider} 而不是直接依赖：{@code Clock} bean 由
     *                      {@code ReportConfig} 提供（{@code @ConditionalOnMissingBean}），
     *                      而不是每个模块都装配它 —— 缺席时回落 {@link Clock#systemUTC()}，
     *                      与此前行为一致。
     */
    public BizClock(@Value("${sharehub.biz-zone:Asia/Dubai}") String zone,
                    ObjectProvider<Clock> clockProvider) {
        this.zone = ZoneId.of(zone);
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    /** 业务日历上的今天。 */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone);
    }

    /** 业务时区的此刻。 */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), zone);
    }

    /** 业务时区本身 —— 需要自己换算时用（如把 UTC 字符串折算成业务日）。 */
    public ZoneId zone() {
        return zone;
    }
}
