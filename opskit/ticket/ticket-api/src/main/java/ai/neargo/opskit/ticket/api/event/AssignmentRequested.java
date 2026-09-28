package ai.neargo.opskit.ticket.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 外部指定派单（21/02 §7.1 EXTERNAL）：接入方收到后调 {@code POST /tickets/{no}/assignment} 回填受理人；超时未回填走类型的兜底策略。
 * 幂等键 = 工单号（一张单只请求一次）。
 */
public record AssignmentRequested(TenantContext tenant, String ticketNo, String typeCode, String instanceNo, Map<String, Object> fields,
        long timeoutAtMs, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "ticket.assignment_requested";
    }

    @Override
    public String idemKey() {
        return ticketNo;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ticketNo", ticketNo);
        d.put("typeCode", typeCode);
        d.put("instanceNo", instanceNo);
        d.put("fields", fields);
        d.put("timeoutAtMs", timeoutAtMs);
        return d;
    }
}
