package ai.neargo.sharehub.common.event.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 「同一个事件，同一个消费者，只处理一次」。
 *
 * <p>Outbox 的投递语义是**至少一次** —— 投递成功但回写 `SENT` 之前宕机，下一轮会再投一遍。
 * 做成恰好一次需要分布式事务，代价远大于收益，所以约定是「至少一次 + 消费端去重」。
 *
 * <h2>为什么是「先插入、撞键即跳过」而不是「先查再插」</h2>
 * 先 SELECT 再 INSERT 在并发下**必漏**：两个线程可以同时查到「没消费过」，然后都执行业务。
 * 唯一约束是并发正确性的唯一保证，应用层的判断只是它的快捷路径。
 *
 * <h2>为什么插入与业务在同一个事务里</h2>
 * 两者必须同生共死：
 * <ul>
 *   <li>业务成功、去重记录没写 → 下次重投会**再执行一次业务**；</li>
 *   <li>去重记录写了、业务回滚 → 这个事件**永远不会被处理**，且没有任何症状。</li>
 * </ul>
 * 所以本方法不开新事务（{@code REQUIRED}），跟随调用方的事务边界。
 */
@Component
public class EventIdempotency {

    private static final Logger log = LoggerFactory.getLogger(EventIdempotency.class);

    private final SysEventConsumedMapper mapper;

    public EventIdempotency(SysEventConsumedMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 首次消费则执行 {@code action}；已消费过则跳过。
     *
     * @param eventNo   事件编号（{@code sys_outbox.event_no}）
     * @param handler   消费者标识，一般是实现类名
     * @param eventType 冗余记录，排错时不必回查 outbox
     * @param action    业务动作。与去重记录同事务 —— 它抛异常，去重记录一起回滚
     * @return true = 本次真的执行了；false = 之前已处理过，跳过
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean once(String eventNo, String handler, String eventType, Runnable action) {
        SysEventConsumed row = new SysEventConsumed();
        row.setEventNo(eventNo);
        row.setHandler(handler);
        row.setEventType(eventType);
        row.setConsumedAt(LocalDateTime.now());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 正常路径的一部分，不是错误：至少一次投递下，重复到达是预期的
            log.debug("事件已消费过，跳过：eventNo={} handler={}", eventNo, handler);
            return false;
        }
        action.run();
        return true;
    }
}
