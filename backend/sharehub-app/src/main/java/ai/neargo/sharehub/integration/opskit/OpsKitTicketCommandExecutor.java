package ai.neargo.sharehub.integration.opskit;

import ai.neargo.sharehub.api.platform.flow.OpsKitTicketCommand;
import ai.neargo.sharehub.common.Json;
import ai.neargo.sharehub.common.event.OutboxConsumer;
import ai.neargo.sharehub.common.event.entity.SysOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 执行 {@link OpsKitTicketCommand}（告警联动：提优先级 / 并单 / 评论 / 告警已恢复；方案 23 §十）。
 *
 * <h3>只有「单已结」按成功处理</h3>
 * 对已并 / 已结的单再并、再作废、再改优先级：OpsKit 回 {@code 422 opskit.ticket.closed}（b4045a1f 前是
 * {@code opskit.process.instance_closed}）—— 说明<b>事情已经不需要做了</b>，记 INFO 后返回。
 *
 * <p><b>409 不能当完成</b>（OpsKit 会话 9-28 纠正）：{@code state_changed} 是并发改动、{@code idempotency.in_progress}
 * 是同键上一次还在处理，都该重试（OpsKit 不记 409 的结果，同键重试即可）；{@code idempotency.mismatch} 说明执行器有缺陷。
 * 所以除「单已结」外一律抛出：可恢复的随 outbox 退避重试成功，不可恢复的耗尽后转死信、记 ERROR，由人处理 —— 不静默丢命令。
 *
 * <p>幂等键取 outbox 事件号：同一条命令无论重投多少次，OpsKit 只执行一次。
 */
@Component
public class OpsKitTicketCommandExecutor implements OutboxConsumer {

    private static final Logger log = LoggerFactory.getLogger(OpsKitTicketCommandExecutor.class);
    private static final Set<String> NOT_YET = Set.of("NEW");
    /** 「单已结」的错误键：新旧两版都认（生产 OpsKit 可能晚于 ShareHub 升级） */
    private static final Set<String> ALREADY_CLOSED = Set.of("opskit.ticket.closed", "opskit.process.instance_closed");

    private final OpsKitClient client;
    private final TicketOutcomeHandlers handlers;

    public OpsKitTicketCommandExecutor(OpsKitClient client, TicketOutcomeHandlers handlers) {
        this.client = client;
        this.handlers = handlers;
    }

    @Override
    public boolean supports(String eventType) {
        return OpsKitTicketCommand.TYPE.equals(eventType);
    }

    @Override
    public void deliver(SysOutbox row) {
        OpsKitTicketCommand c = Json.read(row.getPayload(), OpsKitTicketCommand.class);
        String ticketNo = ticketNo(c.bizType(), c.bizNo(), c.ticketNo());
        String key = "opskit:cmd:" + row.getEventNo();
        Map<String, Object> args = c.args() == null ? Map.of() : c.args();
        try {
            switch (c.command()) {
                case OpsKitTicketCommand.RAISE_PRIORITY -> {
                    client.patchTicketPriority(ticketNo, str(args.get("priority")), key);
                    if (args.get("note") != null) client.commentTicket(ticketNo, str(args.get("note")), key + ":c");
                }
                case OpsKitTicketCommand.MERGE -> {
                    String into = args.get("intoTicketNo") != null ? str(args.get("intoTicketNo"))
                            : ticketNo(c.bizType(), str(args.get("intoBizNo")), null);
                    if (into.equals(ticketNo)) return;   // 已是同一张（去重并过的单）：无需再并
                    client.mergeTicket(ticketNo, into, str(args.get("reason")), key);
                }
                case OpsKitTicketCommand.COMMENT -> client.commentTicket(ticketNo, str(args.get("body")), key);
                case OpsKitTicketCommand.ALARM_RECOVERED -> recovered(ticketNo, str(args.get("note")), key);
                default -> throw new IllegalArgumentException("unknown ticket command " + c.command());   // 只进日志与重投，不到界面
            }
            log.info("OpsKit 工单命令已执行 {} ticketNo={} biz={}:{}", c.command(), ticketNo, c.bizType(), c.bizNo());
        } catch (OpsKitClient.Rejected r) {
            if (r.status == 422 && ALREADY_CLOSED.contains(r.errorKey)) {
                // 单已结 / 已并：这件事已经不需要做了，不重投
                log.info("OpsKit 工单命令无需执行（{} {}）{} ticketNo={}", r.status, r.errorKey, c.command(), ticketNo);
                return;
            }
            throw r;
        }
    }

    /** 告警已恢复：执行时再查单 —— 没人接就作废，已在处理就留一句说明，已结什么都不做（方案 23 §十 第 7 条） */
    private void recovered(String ticketNo, String note, String key) {
        Map<String, Object> t = client.getTicket(ticketNo);
        if (Boolean.FALSE.equals(t.get("open"))) return;
        if (NOT_YET.contains(str(t.get("statusCategory")))) client.transitionTicket(ticketNo, "cancel", note, key);
        else client.commentTicket(ticketNo, note, key);
    }

    private String ticketNo(String bizType, String bizNo, String given) {
        if (given != null && !given.isBlank()) return given;
        String no = handlers.find(bizType).map(h -> h.ticketNoOf(bizNo)).orElse(null);
        // 开单还在途（单号没回填）：抛出等下一轮重投，届时多半已有单号
        if (no == null) throw new IllegalStateException("no OpsKit ticket yet for " + bizType + ":" + bizNo + "; retry later");   // 只进日志与重投
        return no;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
