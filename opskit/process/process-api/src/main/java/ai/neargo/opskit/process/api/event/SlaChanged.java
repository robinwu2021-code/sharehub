package ai.neargo.opskit.process.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SLA 计时器的对外事件：{@code sla.warning} · {@code sla.breached} · {@code sla.met}（21/06 §六）。幂等键 {@code clockId:epoch}。
 *
 * @param kind warning / breached / met
 */
public record SlaChanged(TenantContext tenant, String kind, String clockId, String instanceNo, String policyCode, String metricCode, String state,
        Long startedAtMs, Long dueAtMs, long pausedMs, int epoch, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "sla." + kind;
    }

    @Override
    public String idemKey() {
        return clockId + ":" + epoch;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("clockId", clockId);
        d.put("instanceNo", instanceNo);
        d.put("policyCode", policyCode);
        d.put("metricCode", metricCode);
        d.put("state", state);
        d.put("startedAtMs", startedAtMs);
        d.put("dueAtMs", dueAtMs);
        d.put("pausedMs", pausedMs);
        return d;
    }
}
