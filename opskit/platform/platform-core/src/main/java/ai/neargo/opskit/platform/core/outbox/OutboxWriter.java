package ai.neargo.opskit.platform.core.outbox;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.event.EventPublisher;
import ai.neargo.opskit.commons.id.BizNoGenerator;
import ai.neargo.opskit.platform.store.JsonColumns;
import ai.neargo.opskit.platform.store.dao.MemberDao;
import ai.neargo.opskit.platform.store.dao.OutboxDao;
import ai.neargo.opskit.platform.store.dao.WorkspaceDao;
import ai.neargo.opskit.platform.store.row.MemberRow;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link EventPublisher} 的实现（21/02 §8.7）：先同步通知进程内订阅者，再把对外信封写进发件箱。两步都在调用方的事务里。
 *
 * <p>对外信封比事件本身多两样，都为接入方回写时不必再反查：
 * {@code workspaceExternalRef}（宿主的租户号）；{@link DomainEvent#memberFields()} 声明的字段展开成
 * {@code {ref, externalRef, displayName}}（非成员如 {@code app:…} / {@code system} 只有 ref）。
 */
public final class OutboxWriter implements EventPublisher {

    private final OutboxDao outbox;
    private final List<InProcessListener> listeners;
    private final BizNoGenerator ids;
    private final Clock clock;
    private final WorkspaceDao workspaces;
    private final MemberDao members;

    public OutboxWriter(OutboxDao outbox, List<InProcessListener> listeners, BizNoGenerator ids, Clock clock, WorkspaceDao workspaces,
            MemberDao members) {
        this.outbox = outbox;
        this.listeners = List.copyOf(listeners);
        this.ids = ids;
        this.clock = clock;
        this.workspaces = workspaces;
        this.members = members;
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
        workspaces.findById(event.tenant().accountId(), event.tenant().workspaceId().value())
                .ifPresent(w -> envelope.put("workspaceExternalRef", w.externalRef()));
        envelope.put("occurredAt", event.occurredAt().toEpochMilli());
        envelope.put("idemKey", event.idemKey());
        envelope.put("data", expandMembers(event));
        outbox.enqueue(event.tenant().workspaceId(), event.tenant().accountId().value(), event.eventType(), event.idemKey(),
                JsonColumns.write(envelope), clock.millis());
    }

    private Object expandMembers(DomainEvent event) {
        Object data = event.data();
        if (event.memberFields().isEmpty() || !(data instanceof Map<?, ?> m)) {
            return data;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        List<String> memberIds = event.memberFields().stream().map(out::get).filter(v -> v instanceof String s && s.startsWith(MEMBER))
                .map(v -> ((String) v).substring(MEMBER.length())).distinct().toList();
        Map<String, MemberRow> found = new HashMap<>();
        if (!memberIds.isEmpty()) {
            members.findByIds(event.tenant().workspaceId(), memberIds).forEach(r -> found.put(r.memberId(), r));
        }
        for (String f : event.memberFields()) {
            if (out.get(f) instanceof String ref) {
                Map<String, Object> party = new LinkedHashMap<>();
                party.put("ref", ref);
                MemberRow r = ref.startsWith(MEMBER) ? found.get(ref.substring(MEMBER.length())) : null;
                if (r != null) {
                    party.put("externalRef", r.externalRef());
                    party.put("displayName", r.displayName());
                }
                out.put(f, party);
            }
        }
        return out;
    }

    private static final String MEMBER = "member:";
}
