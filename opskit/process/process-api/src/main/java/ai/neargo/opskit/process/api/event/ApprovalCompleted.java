package ai.neargo.opskit.process.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.api.model.SubjectRef;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一轮审批结束：APPROVED / REJECTED / WITHDRAWN。幂等键 {@code approvalNo:roundNo}（21/06 §六）。
 * 带实例的业务对象 subject，接入方回写业务行时不必再反查实例。
 */
public record ApprovalCompleted(TenantContext tenant, String approvalNo, String instanceNo, SubjectRef subject, int roundNo, String result,
        String reason, String decidedBy, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "approval.completed";
    }

    @Override
    public String idemKey() {
        return approvalNo + ":" + roundNo;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("approvalNo", approvalNo);
        d.put("instanceNo", instanceNo);
        if (subject != null) {
            d.put("subject", Map.of("type", String.valueOf(subject.type()), "ref", String.valueOf(subject.ref())));
        }
        d.put("roundNo", roundNo);
        d.put("result", result);
        d.put("reason", reason);
        d.put("decidedBy", decidedBy);
        return d;
    }

    @Override
    public java.util.Set<String> memberFields() {
        return java.util.Set.of("decidedBy");
    }
}
