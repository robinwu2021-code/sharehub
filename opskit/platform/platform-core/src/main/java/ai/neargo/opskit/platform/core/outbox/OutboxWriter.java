package ai.neargo.opskit.platform.core.outbox;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link EventPublisher} 的实现（21/02 §8.7）：先同步通知进程内订阅者，再把对外信封写进发件箱。两步都在调用方的事务里。
 */
public final class OutboxWriter implements EventPublisher {

    private final OutboxDao outbox;
    private final List<InProcessListener> listeners;
    private final BizNoGenerator ids;
    private final Clock clock;

    public OutboxWriter(OutboxDao outbox, List<InProcessListener> listeners, BizNoGenerator ids, Clock clock) {
        this.outbox = outbox;
        this.listeners = List.copyOf(listeners);
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public void publish(DomainEvent event) {
        // 事件必须和它描述的变化同成同败；事务外发布 = 变化回滚了事件却已发出
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("events must be published inside the business transaction: " + event.eventType());
        }
        listeners.forEach(l -> l.on(event));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", ids.next("EV"));
        envelope.put("eventType", event.eventType());
        envelope.put("accountId", event.tenant().accountId().value());
        envelope.put("workspaceId", event.tenant().workspaceId().value());
        envelope.put("occurredAt", event.occurredAt().toEpochMilli());
        envelope.put("idemKey", event.idemKey());
        envelope.put("data", event.data());
        outbox.enqueue(event.tenant().workspaceId(), event.tenant().accountId().value(), event.eventType(), event.idemKey(),
                JsonColumns.write(envelope), clock.millis());
    }
}
