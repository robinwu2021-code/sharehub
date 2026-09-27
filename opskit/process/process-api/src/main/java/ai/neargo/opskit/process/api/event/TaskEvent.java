package ai.neargo.opskit.process.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务事件：{@code task.created} / {@code task.assigned}（领取、指派、转派、退回池都算）/ {@code task.completed}。
 * 幂等键 {@code taskNo:动作:版本}（21/06 §六），同一任务的版本号单调递增。
 */
public record TaskEvent(TenantContext tenant, String action, String taskNo, String kind, String instanceNo, String assignee, int version,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "task." + action;
    }

    @Override
    public String idemKey() {
        return taskNo + ":" + action + ":" + version;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("taskNo", taskNo);
        d.put("kind", kind);
        d.put("instanceNo", instanceNo);
        d.put("assignee", assignee);
        return d;
    }
}
