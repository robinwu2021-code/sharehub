package ai.neargo.opskit.process.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import ai.neargo.opskit.process.api.model.SubjectRef;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进入外部确认门（21/02 §8.4）：接入方收到后做业务校验，再用应用凭证回复 {@code POST …/confirmations/{token}}。
 * 令牌明文只出现在这个事件里（webhook 有签名），服务端只存哈希。幂等键 {@code instanceNo:gateEpoch}。
 */
public record AwaitingConfirmation(TenantContext tenant, String instanceNo, String processCode, String status, String topic, String token,
        SubjectRef subject, Map<String, Object> fields, int gateEpoch, Long expiresAtMs, Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "instance.awaiting_confirmation";
    }

    @Override
    public String idemKey() {
        return instanceNo + ":" + gateEpoch;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("instanceNo", instanceNo);
        d.put("processCode", processCode);
        d.put("status", status);
        d.put("topic", topic);
        d.put("token", token);
        if (subject != null) {
            d.put("subject", Map.of("type", String.valueOf(subject.type()), "ref", String.valueOf(subject.ref())));
        }
        d.put("fields", fields);
        d.put("expiresAtMs", expiresAtMs);
        return d;
    }
}
