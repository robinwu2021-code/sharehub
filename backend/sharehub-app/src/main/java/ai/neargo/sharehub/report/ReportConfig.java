package ai.neargo.sharehub.report;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 报表域基础设施。
 *
 * <p><b>为什么把「现在」做成 bean</b>：报表的每一条口径都挂在「今日」上（周期报表到昨日、
 * 大屏到当前小时）。直调 {@code LocalDate.now()} 的话，「周期统计到昨日」这条最容易被
 * 后来者顺手改掉的约定就无法被测试钉住 —— 只能等到明天再跑一遍才知道改坏了。
 *
 * <p>{@code @ConditionalOnMissingBean} 留给测试覆盖成固定时钟，也避免与将来可能引入的
 * 全局 {@code Clock} bean 冲突（重复定义会让整个上下文启动失败）。
 *
 * <p>时区固定 <b>UTC</b>：{@code ord_order.rent_start_at} 存的是 UTC ISO-8601 字符串，
 * 报表按 UTC 切业务日。混用本地时区会让跨零点的订单在不同报表里落到不同天。
 */
@Configuration
public class ReportConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock reportClock() {
        return Clock.systemUTC();
    }
}
