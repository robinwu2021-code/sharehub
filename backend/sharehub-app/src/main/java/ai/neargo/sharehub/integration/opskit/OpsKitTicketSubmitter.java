package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.TicketOutcomeHandler;
import ai.neargo.sharehub.api.platform.flow.TicketRequestedEvent;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.event.OutboxConsumer;
import ai.neargo.sharehub.common.event.entity.SysOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;


/**
 * 送单：把 {@link TicketRequestedEvent} 发到 OpsKit 开工单（M5-3）。与送审同一套：至少一次投递 + OpsKit 幂等键兜重复，
 * 达上限转死信时回调 {@link TicketOutcomeHandler#onTicketFailed}。
 */
@Component
public class OpsKitTicketSubmitter implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpsKitTicketSubmitter.class);

    private final OpsKitClient client;
    private final TicketOutcomeHandlers handlers;

    public OpsKitTicketSubmitter(OpsKitClient client, TicketOutcomeHandlers handlers) {
        this.client = client;
        this.handlers = handlers;
    }

    @Override
    public boolean supports(String eventType) {
        return TicketRequestedEvent.TYPE.equals(eventType);
    }

    @Override
    public void deliver(SysOutbox row) {
        TicketRequestedEvent e = Json.read(row.getPayload(), TicketRequestedEvent.class);
        TicketOutcomeHandler h = handler(e.bizType());
        OpsKitClient.CreatedTicket t = client.createTicket(e);
        h.onTicketCreated(e.bizNo(), t.ticketNo(), t.attached());
        log.info("已在 OpsKit 开单 {}:{} ticketNo={} attached={}", e.bizType(), e.bizNo(), t.ticketNo(), t.attached());
    }

    @Override
    public void onDead(SysOutbox row) {
        TicketRequestedEvent e = Json.read(row.getPayload(), TicketRequestedEvent.class);
        handler(e.bizType()).onTicketFailed(e.bizNo(), row.getLastError());
    }

    private TicketOutcomeHandler handler(String bizType) {
        return handlers.find(bizType)
                .orElseThrow(() -> new IllegalStateException("没有业务类型 " + bizType + " 的工单回写实现"));
    }
}
