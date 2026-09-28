package ai.neargo.opskit.process.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.model.SubjectRef;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实例状态变化（含创建：from 为空、transition 为 {@code created}）。幂等键 {@code instanceNo:seq}（21/06 §六），
 * 同一实例内按 seq 判序。进程内订阅者（工单快照同步）与发布方同事务收到它。
 */
public record InstanceTransitioned(TenantContext tenant, String instanceNo, String processCode, SubjectRef subject, String transition,
        String from, String to, StatusCategory toCategory, boolean open, int seq, String actor, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return from == null ? "instance.created" : "instance.transitioned";
    }

    @Override
    public String idemKey() {
        return instanceNo + ":" + seq;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("instanceNo", instanceNo);
        d.put("processCode", processCode);
        if (subject != null) {
            d.put("subject", Map.of("type", String.valueOf(subject.type()), "ref", String.valueOf(subject.ref())));
        }
        d.put("transition", transition);
        d.put("from", from);
        d.put("to", to);
        d.put("toCategory", toCategory.name());
        d.put("open", open);
        d.put("seq", seq);
        d.put("actor", actor);
        return d;
    }

    @Override
    public java.util.Set<String> memberFields() {
        return java.util.Set.of("actor");
    }
}
