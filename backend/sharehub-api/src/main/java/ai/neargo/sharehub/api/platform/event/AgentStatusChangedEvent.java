package ai.neargo.sharehub.api.platform.event;

import ai.neargo.sharehub.common.event.DomainEvent;

/**
 * 代理主体状态变更（批次 F2）。停用（ENABLED → SUSPENDED）时工单侧据此把名下未完结的工单改派平台员工。
 *
 * @param at ISO-8601 本地时间（Outbox 序列化器不带 Java 时间模块）
 */
public record AgentStatusChangedEvent(String agentNo, String from, String to, String reason, String at) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "AgtAgent";
    }

    @Override
    public String aggregateId() {
        return agentNo;
    }

    @Override
    public String eventType() {
        return "AGENT_STATUS_CHANGED";
    }

    public boolean suspended() {
        return "SUSPENDED".equals(to) && !"SUSPENDED".equals(from);
    }
}
