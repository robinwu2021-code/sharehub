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

    /** 启动期默认值，也是参数表没配 / 来源缺席时的兜底。构造时即校验，非法则启动失败。 */
    private final ZoneId fallbackZone;
    private final Clock clock;
    private final ObjectProvider<BizZoneSource> zoneSource;

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
                    ObjectProvider<Clock> clockProvider,
                    ObjectProvider<BizZoneSource> zoneSource) {
        // 这里不 try/catch：属性写错了就该**启动失败**，而不是带着一个错的时区跑起来
        this.fallbackZone = ZoneId.of(zone);
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        this.zoneSource = zoneSource;
    }

    /** 业务日历上的今天。 */
    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), zone());
    }

    /** 业务时区的此刻。 */
    public LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), zone());
    }

    /**
     * 业务时区本身 —— 需要自己换算时用（如把 UTC 字符串折算成业务日）。
     *
     * <h3>每次都读，不缓存</h3>
     * 管理员在运营台改完，期待的是**立刻生效**，不是「大约一分钟后」。
     * 读一次 {@code sys_param} 是一次主键查询，而业务日期不在热路径上
     * （不是每笔订单都算）。
     *
     * <p>真要加缓存，必须是**带失效事件**的，不能是 TTL ——
     * TTL 会制造「改了但还没生效」的窗口，而那正是时区缺陷最擅长藏身的地方
     * （它本来就每天只在几个小时里成立）。
     *
     * <h3>为什么这里吞异常</h3>
     * 值非法应当在**写入时**被拒（见 SysConfigController 的校验）。
     * 万一库里已经有一个脏值，这里回落到启动期默认值并记一条 WARN ——
     * 让系统继续按一个**确定**的时区跑，好过每次取日期都抛异常。
     */
    public ZoneId zone() {
        BizZoneSource src = zoneSource.getIfAvailable();
        if (src == null) return fallbackZone;
        String id = src.zoneId();
        if (id == null || id.isBlank()) return fallbackZone;
        try {
            return ZoneId.of(id.trim());
        } catch (java.time.DateTimeException e) {
            LOG.warn("业务时区参数非法，回落到 {}：biz.timezone={}", fallbackZone, id);
            return fallbackZone;
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(BizClock.class);

    /**
     * **业务日的起点，换算成「存储时区」的 {@link LocalDateTime}** ——
     * 用来和 {@code created_at} / {@code closed_at} 这类列比大小。
     *
     * <h3>为什么不能直接用 {@code today().atStartOfDay()}</h3>
     * 那些列是 {@code LocalDateTime.now()} 写的，**服务器 JVM 时区**
     * （与 {@code created_at}/{@code updated_at} 同一口径，这是对的，不该改存储语义）。
     * 拿「业务日的零点」去比「服务器时区的时间戳」，会得到一个**不自洽**的窗口：
     * 迪拜 9-25 22:00 关闭的告警（业务日 9-25）会被算进「9-26 今日」，
     * 因为它的服务器时间戳是 {@code 9-26T02:00 ≥ 9-26T00:00}。
     *
     * <p>换句话说：直接替换比不替换**更错** —— 原来至少是「服务器日」这个自洽的口径，
     * 只是那个日不是业务日。所以必须把日界**换算到存储时区**再比。
     *
     * <p>这是 {@code AdjustmentServiceImpl} 那个折算的**反方向**：
     * 那边把存储时间戳折成业务日，这边把业务日界折成存储时间戳。
     *
     * <p>⚠️ 依赖「写入方与本进程同 JVM 时区」（当前是单体，成立）。
     * 拆成独立进程且时区不同时，这些列该带偏移量存，而不是靠这个假设。
     */
    public LocalDateTime startOfDayAsStored(LocalDate bizDay) {
        return bizDay.atStartOfDay(zone()).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    /** {@link #startOfDayAsStored} 的今天。 */
    public LocalDateTime startOfTodayAsStored() {
        return startOfDayAsStored(today());
    }

    /**
     * 同上，但格式化成 {@code yyyy-MM-dd HH:mm:ss} —— 给**字符串列**用
     * （如 {@code dev_alarm.occurred_at}，它是 {@code String} 不是 DATETIME）。
     *
     * <p>字符串列按**字典序**比大小，而这个格式定宽、同前缀，所以字典序与时间序一致。
     * 原先传的是 10 位的 {@code yyyy-MM-dd}，靠「同前缀更短者排前」也能当日界用；
     * 换成定宽全量后语义更明确，且把时区折算带了进去。
     */
    public String startOfDayAsStoredText(LocalDate bizDay) {
        return startOfDayAsStored(bizDay).format(STORED_TS);
    }

    private static final java.time.format.DateTimeFormatter STORED_TS =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}
