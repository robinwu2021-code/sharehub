package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.ApprovalOutcomeHandler;
import ai.neargo.sharehub.api.platform.flow.ApprovalRequestedEvent;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.event.OutboxConsumer;
import ai.neargo.sharehub.common.event.entity.SysOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 送审：把 {@link ApprovalRequestedEvent} 发到 OpsKit（M5，方案 23 §3.1）。{@code OutboxConsumer} 的首个实现。
 *
 * <p>至少一次投递，由 OpsKit 的 {@code Idempotency-Key} 兜重复：重投拿到的是同一个实例，回填也是同一个号。
 * 抛异常 = 本次失败，outbox 按退避重投；达上限转死信时 {@link #onDead} 把业务行标成「送审失败」。
 */
@Component
public class OpsKitApprovalSubmitter implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpsKitApprovalSubmitter.class);

    private final OpsKitClient client;
    private final ApprovalOutcomeHandlers handlers;

    public OpsKitApprovalSubmitter(OpsKitClient client, ApprovalOutcomeHandlers handlers) {
        this.client = client;
        this.handlers = handlers;
    }

    @Override
    public boolean supports(String eventType) {
        return ApprovalRequestedEvent.TYPE.equals(eventType);
    }

    @Override
    public void deliver(SysOutbox row) {
        ApprovalRequestedEvent e = Json.read(row.getPayload(), ApprovalRequestedEvent.class);
        ApprovalOutcomeHandler h = handlers.require(e.bizType());
        OpsKitClient.StartedInstance s = client.startApproval(e);
        h.onSubmitted(e.bizNo(), e.round(), s.instanceNo(), s.approvalNo());
        log.info("已送审 OpsKit {}:{} 轮次={} instanceNo={} approvalNo={}", e.bizType(), e.bizNo(), e.round(), s.instanceNo(), s.approvalNo());
    }

    @Override
    public void onDead(SysOutbox row) {
        ApprovalRequestedEvent e = Json.read(row.getPayload(), ApprovalRequestedEvent.class);
        handlers.require(e.bizType()).onSubmitFailed(e.bizNo(), e.round(), row.getLastError());
    }
}
