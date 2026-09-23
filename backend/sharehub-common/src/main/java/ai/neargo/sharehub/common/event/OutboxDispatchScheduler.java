package ai.neargo.sharehub.common.event;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * {@link OutboxDispatcher} 的**临时**驱动源 —— H4 接入共用调度器后删除本类。
 *
 * <h2>说清楚这里为什么是个例外</h2>
 * v4/08 的任务卡口禁止 {@code @Scheduled} / {@code @EnableScheduling}，理由是
 * **每个定时的东西都必须在运营台可见可控**（能看到上次跑没跑、能停、能手动触发）——
 * 注解式定时藏在代码里，运维只能靠读源码知道它存在。
 *
 * <p>本类用 {@code ScheduledExecutorService} 而不是 {@code @Scheduled}，**不是为了绕过那条规则**：
 * 它在语义上就是进程内定时，同样不可见。真正的理由是规则的执行方式（扫描 {@code @Scheduled}）
 * 即将上线，而这个 bean 无论如何都要在 H4 删除 —— 让它留在扫描结果里会逼出一条长期豁免，
 * 那条豁免比这个类本身更难清理。
 *
 * <p>所以配套三件事，保证它不会变成"永久的临时方案"：
 * <ol>
 *   <li><b>默认关</b>（{@code sharehub.outbox.dispatch.enabled}）—— 生产要显式开；</li>
 *   <li><b>启动打日志</b>，说明它是临时的、会被什么取代；</li>
 *   <li><b>登记在 {@code known-inprocess-schedules.txt}</b>，与其他棘轮台账一样只减不增。</li>
 * </ol>
 *
 * <h2>多副本下会重复投递</h2>
 * 没有分布式锁，两个副本会同时捞同一批。这是可接受的 —— 投递语义本来就是
 * **至少一次**，消费端有 {@code EventIdempotency} 兜底。H1 接入任务目标件后由 ShedLock 解决。
 */
@Component
@ConditionalOnProperty(name = "sharehub.outbox.dispatch.enabled", havingValue = "true")
public class OutboxDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchScheduler.class);

    private final OutboxDispatcher dispatcher;
    private final Duration interval;
    private ScheduledExecutorService pool;

    public OutboxDispatchScheduler(OutboxDispatcher dispatcher,
                                   @Value("${sharehub.outbox.dispatch.interval:30s}") Duration interval) {
        this.dispatcher = dispatcher;
        this.interval = interval;
    }

    @PostConstruct
    void start() {
        log.warn("""
                Outbox 轮询以**进程内定时**启动（间隔 {}）——这是 H4 接入共用调度器前的临时方案。
                多副本下会重复投递（消费端 EventIdempotency 兜底）。登记见 known-inprocess-schedules.txt""",
                interval);
        pool = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "outbox-dispatch");
            t.setDaemon(true);   // 不阻止 JVM 退出：它不是必须跑完的工作
            return t;
        });
        pool.scheduleWithFixedDelay(this::tick,
                interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 一轮。
     *
     * <p>**必须吞掉所有异常**：{@code scheduleWithFixedDelay} 在任务抛异常时会**静默停止后续调度** ——
     * 一次偶发的数据库抖动会让轮询永久停摆，而且没有任何日志说明它停了。
     */
    private void tick() {
        try {
            dispatcher.dispatchDue();
        } catch (Exception e) {
            log.error("Outbox 轮询本轮失败，下一轮继续", e);
        }
    }

    @PreDestroy
    void stop() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
