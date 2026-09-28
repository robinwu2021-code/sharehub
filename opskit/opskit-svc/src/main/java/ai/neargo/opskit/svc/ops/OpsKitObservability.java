package ai.neargo.opskit.svc.ops;

import ai.neargo.opskit.commons.tenancy.AccountId;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import com.github.kagkarlsson.scheduler.Scheduler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测（21/07 O4-3）：
 * <ul>
 *   <li>健康检查 {@code scheduler}：db-scheduler 在跑（定时 —— 领取超时、SLA 到期、外部确认超时、投递 —— 全靠它）。</li>
 *   <li>健康检查 {@code outbox}：已到期未投出的事件积压超过阈值时降为 OUT_OF_SERVICE 前的 UNKNOWN 提示（不拉挂服务）。</li>
 *   <li>指标 {@code opskit.outbox.pending / due / failed}：投递积压（接入方收不到 webhook 时先看它）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class OpsKitObservability {

    /** 到期未投出超过这么多条就在健康检查里提示。 */
    static final long OUTBOX_DUE_WARN = 1000;

    @Bean
    HealthIndicator schedulerHealthIndicator(ObjectProvider<Scheduler> scheduler) {
        return () -> {
            Scheduler s = scheduler.getIfAvailable();
            if (s == null) {
                return Health.down().withDetail("reason", "db-scheduler not created").build();
            }
            var state = s.getSchedulerState();
            if (state.isShuttingDown()) {
                return Health.down().withDetail("reason", "shutting down").build();
            }
            return state.isStarted() ? Health.up().build() : Health.down().withDetail("reason", "not started yet").build();
        };
    }

    @Bean
    HealthIndicator outboxHealthIndicator(OutboxDao outbox, Clock clock) {
        return () -> {
            long[] b = outbox.backlog(AccountId.UNKNOWN, clock.millis());
            Health.Builder h = b[1] > OUTBOX_DUE_WARN ? Health.unknown() : Health.up();
            return h.withDetail("pending", b[0]).withDetail("due", b[1]).withDetail("failed", b[2]).build();
        };
    }

    /** 投递积压指标：采样时查一次库（Prometheus 默认 15 秒拉一次，一条 COUNT 足够便宜）。 */
    @Bean
    MeterBinder outboxBacklogMetrics(OutboxDao outbox, Clock clock) {
        return registry -> bind(registry, outbox, clock);
    }

    private static void bind(MeterRegistry registry, OutboxDao outbox, Clock clock) {
        AtomicLong lastAt = new AtomicLong();
        long[][] cache = {new long[3]};
        java.util.function.IntFunction<Double> read = i -> {
            long now = clock.millis();
            if (now - lastAt.get() > 5_000) {
                cache[0] = outbox.backlog(AccountId.UNKNOWN, now);
                lastAt.set(now);
            }
            return (double) cache[0][i];
        };
        Gauge.builder("opskit.outbox.pending", () -> read.apply(0)).description("未投完的事件").register(registry);
        Gauge.builder("opskit.outbox.due", () -> read.apply(1)).description("已到期未投出的事件").register(registry);
        Gauge.builder("opskit.outbox.failed", () -> read.apply(2)).description("重试耗尽判失败的事件").register(registry);
    }
}
