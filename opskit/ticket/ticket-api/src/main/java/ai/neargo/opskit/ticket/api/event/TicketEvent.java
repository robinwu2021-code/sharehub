package ai.neargo.opskit.ticket.api.event;

import ai.neargo.opskit.commons.event.DomainEvent;
import ai.neargo.opskit.commons.tenancy.TenantContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工单生命周期事件（21/06 §六）：{@code ticket.created} · {@code ticket.transitioned} · {@code ticket.closed} · {@code ticket.commented}（仅 PUBLIC 评论）。
 * 幂等键：前三种 {@code ticketNo:seq}（seq = 实例的流转序号，同一张单内单调递增，接收方据此判序）；评论 {@code ticketNo:comment:评论序号}。
 * {@code actor} 在信封里展开成 {@code {ref, externalRef?, displayName?}}（与 instance.* 同形）。
 *
 * @param action     created / transitioned / closed / commented
 * @param transition 迁移代号（transitioned / closed）
 * @param from       迁移前状态（transitioned / closed）
 * @param seq        实例流转序号；评论时为评论序号
 * @param body       评论正文（commented）
 */
public record TicketEvent(TenantContext tenant, String action, String ticketNo, String typeCode, String subjectType, String subjectRef, String status,
        String statusCategory, boolean open, String resolution, String transition, String from, int seq, String actor, String body, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String eventType() {
        return "ticket." + action;
    }

    @Override
    public String idemKey() {
        return "commented".equals(action) ? ticketNo + ":comment:" + seq : ticketNo + ":" + seq;
    }

    @Override
    public Object data() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ticketNo", ticketNo);
        d.put("typeCode", typeCode);
        if (subjectType != null) {
            d.put("subject", Map.of("type", subjectType, "ref", String.valueOf(subjectRef)));
        }
        d.put("status", status);
        d.put("statusCategory", statusCategory);
        d.put("open", open);
        d.put("resolution", resolution);
        if (transition != null) {
            d.put("transition", transition);
            d.put("from", from);
        }
        d.put("seq", seq);
        d.put("actor", actor);
        if (body != null) {
            d.put("body", body);
        }
        return d;
    }

    @Override
    public Set<String> memberFields() {
        return Set.of("actor");
    }
}
