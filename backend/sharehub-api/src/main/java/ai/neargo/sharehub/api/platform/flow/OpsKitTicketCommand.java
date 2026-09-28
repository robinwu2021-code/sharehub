package ai.neargo.sharehub.api.platform.flow;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.util.Map;

/**
 * 对一张 OpsKit 工单做一件事（M5 告警联动，方案 23 §十）。与业务同事务落 outbox，由 app 层执行器以应用凭证执行；
 * 失败按 outbox 退避重投，幂等键取 outbox 事件号（重投同键）。
 *
 * @param bizType  业务类型（如 {@code alarm}）—— {@code ticketNo} 为空时据此向 {@link TicketOutcomeHandler#ticketNoOf} 取单号
 * @param bizNo    业务单号（如告警号）
 * @param ticketNo 工单号；开单还在途（单号未回填）时为空，执行时再取，仍取不到则重投
 * @param command  {@link #RAISE_PRIORITY} · {@link #MERGE} · {@link #COMMENT} · {@link #ALARM_RECOVERED}
 * @param args     命令参数（见各常量说明）
 */
public record OpsKitTicketCommand(String bizType, String bizNo, String ticketNo, String command, Map<String, Object> args)
        implements DomainEvent {

    public static final String TYPE = "OPSKIT_TICKET_COMMAND";
    /** args: priority（工单类型的优先级代号）· note（内部评论） */
    public static final String RAISE_PRIORITY = "RAISE_PRIORITY";
    /** args: intoTicketNo 或 intoBizNo（并入哪张）· reason */
    public static final String MERGE = "MERGE";
    /** args: body（内部评论） */
    public static final String COMMENT = "COMMENT";
    /** args: note。执行时查单：还没人接（NEW）→ 作废；已在处理 → 内部评论；已结 → 什么都不做 */
    public static final String ALARM_RECOVERED = "ALARM_RECOVERED";

    @Override
    public String aggregateType() {
        return "Ticket:" + bizType;
    }

    @Override
    public String aggregateId() {
        return bizNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}
