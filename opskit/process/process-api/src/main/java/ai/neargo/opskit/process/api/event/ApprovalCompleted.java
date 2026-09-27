package ai.neargo.opskit.process.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一轮审批结束：APPROVED / REJECTED / WITHDRAWN。幂等键 {@code approvalNo:roundNo}（21/06 §六）。 */
public record ApprovalCompleted(TenantContext tenant, String approvalNo, String instanceNo, int roundNo, String result, String reason,
        String decidedBy, Instant occurredAt) implements DomainEvent {

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
        d.put("roundNo", roundNo);
        d.put("result", result);
        d.put("reason", reason);
        d.put("decidedBy", decidedBy);
        return d;
    }
}
