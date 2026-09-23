package ai.neargo.sharehub.common.event;

import ai.neargo.sharehub.common.event.entity.SysOutbox;
import ai.neargo.sharehub.common.event.mapper.SysOutboxMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 轮询重投器 —— 把「投递失败的事件」从死水变成可恢复的队列。
 *
 * <p>在此之前 {@code OutboxEventBus} 的注释写着「交给轮询器重投」，**但轮询器不存在** ——
 * 失败的事件写下 `FAILED` 与 `nextRetryAt` 之后，再也没有人看它们一眼。
 *
 * <h2>四条设计</h2>
 * <ol>
 *   <li><b>只捞到期的</b>：{@code status IN (PENDING, FAILED) AND (next_retry_at IS NULL OR <= now)}，
 *       命中 {@code idx_outbox_poll}；</li>
 *   <li><b>退避是指数的</b>：1m → 5m → 30m → 2h → 6h → 12h。固定间隔会在对方长时间不可用时
 *       把日志刷满，而且恢复瞬间涌入；</li>
 *   <li><b>有死信</b>：超过 {@code max-attempts} 转 {@code DEAD} 并告警，不再重投。
 *       没有死信状态的重投队列，最终会变成一个无人看的失败堆；</li>
 *   <li><b>一条失败不影响其他</b>：每条独立事务（{@code REQUIRES_NEW}），
 *       否则一条毒消息会让整批回滚、然后整批重投、然后再次被同一条毒死。</li>
 * </ol>
 *
 * <h2>驱动源是临时的</h2>
 * 现在由 {@code OutboxDispatchScheduler} 进程内定时驱动。按 v4/07，定时任务应当由**共用调度器**
 * 回调（`outbox-dispatch` 任务，H1/H4），那个接通后把本类的 {@link #dispatchDue()} 挂成
 * `JobHandler` 即可 —— **本类不需要改**，换的只是触发源。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    static final String PENDING = OutboxStatus.PENDING.name();
    static final String SENT = OutboxStatus.SENT.name();
    static final String FAILED = OutboxStatus.FAILED.name();
    /** 超过重试上限的终态：不再重投，等人处理。 */
    static final String DEAD = OutboxStatus.DEAD.name();

    /** 退避梯度；索引 = 已重试次数。超出数组长度取最后一档。 */
    private static final Duration[] BACKOFF = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(12)};

    private final SysOutboxMapper mapper;
    private final List<OutboxConsumer> consumers;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxDispatcher(SysOutboxMapper mapper,
                            List<OutboxConsumer> consumers,
                            @Value("${sharehub.outbox.dispatch.batch-size:200}") int batchSize,
                            @Value("${sharehub.outbox.dispatch.max-attempts:6}") int maxAttempts) {
        this.mapper = mapper;
        this.consumers = consumers;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 投递一批到期的事件。
     *
     * @return 本轮处理条数（用于任务日志与「是否还有积压」的判断）
     */
    public int dispatchDue() {
        LocalDateTime now = LocalDateTime.now();
        List<SysOutbox> due = mapper.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .in(SysOutbox::getStatus, PENDING, FAILED)
                .and(w -> w.isNull(SysOutbox::getNextRetryAt).or().le(SysOutbox::getNextRetryAt, now))
                .orderByAsc(SysOutbox::getId)
                .last("LIMIT " + batchSize));

        int done = 0;
        for (SysOutbox row : due) {
            if (dispatchOne(row)) {
                done++;
            }
        }
        if (done > 0) {
            log.info("Outbox 投递完成 {} 条（本轮到期 {} 条）", done, due.size());
        }
        return done;
    }

    /**
     * 单条投递。**独立事务**：一条毒消息不该让整批回滚重来。
     *
     * @return true = 成功送达
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean dispatchOne(SysOutbox row) {
        List<OutboxConsumer> targets = consumers.stream()
                .filter(c -> c.supports(row.getEventType()))
                .toList();

        if (targets.isEmpty()) {
            // 没有跨进程消费者 = 这个事件只在进程内消费，已由 OutboxEventBus 投过。
            // 标记 SENT 而不是留着 —— 否则轮询器每轮都会重新捞起它，直到表被它塞满。
            markSent(row);
            return true;
        }

        try {
            for (OutboxConsumer c : targets) {
                c.deliver(row);
            }
            markSent(row);
            return true;
        } catch (RuntimeException e) {
            markFailed(row, e);
            return false;
        }
    }

    private void markSent(SysOutbox row) {
        SysOutbox upd = new SysOutbox();
        upd.setId(row.getId());
        upd.setStatus(SENT);
        upd.setSentAt(LocalDateTime.now());
        mapper.updateById(upd);
    }

    private void markFailed(SysOutbox row, RuntimeException e) {
        int attempts = row.getRetryCount() == null ? 0 : row.getRetryCount();
        int next = attempts + 1;

        SysOutbox upd = new SysOutbox();
        upd.setId(row.getId());
        upd.setRetryCount(next);
        upd.setLastError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage()));

        if (next >= maxAttempts) {
            upd.setStatus(DEAD);
            // 用 error 级别：死信是需要人介入的，不能只留在 info 里等人翻
            log.error("Outbox 事件重投达上限转死信：eventNo={} type={} 重试={} 次",
                    row.getEventNo(), row.getEventType(), next, e);
        } else {
            upd.setStatus(FAILED);
            upd.setNextRetryAt(LocalDateTime.now().plus(backoffOf(next)));
            log.warn("Outbox 投递失败，第 {} 次，{} 后重试：eventNo={} type={}",
                    next, backoffOf(next), row.getEventNo(), row.getEventType());
        }
        mapper.updateById(upd);
    }

    static Duration backoffOf(int attempts) {
        int i = Math.min(Math.max(attempts, 1), BACKOFF.length) - 1;
        return BACKOFF[i];
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
