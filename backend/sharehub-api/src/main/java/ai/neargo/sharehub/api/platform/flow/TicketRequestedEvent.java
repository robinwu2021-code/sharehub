package ai.neargo.sharehub.api.platform.flow;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.util.List;
import java.util.Map;

/**
 * 一笔业务要在 OpsKit 开工单（M5-3，方案 23 §五）。与业务写入同事务落 outbox，提交后由 app 层送 OpsKit；
 * 失败按 outbox 退避重投。字段自带建单所需的全部信息，消费者不回查业务表。
 *
 * @param bizType     业务类型（如 {@code cs_ticket}）—— 回写时找 {@link TicketOutcomeHandler} 的键，也是 {@code subject.type}
 * @param bizNo       业务单号 = {@code subject.ref}
 * @param typeCode    OpsKit 工单类型（随 bootstrap definitions-dir 发布）
 * @param priority    类型的优先级代号（如 P1 / P2 / P3）；空 = 类型默认
 * @param fields      类型字段的原始值（选项给代号）
 * @param dedupeKey   去重键：同键的未结单已存在 → OpsKit 并入它（attached），不另开
 * @param requesterNo 提出人宿主编号（C 端用户不是 OpsKit 成员时留空，由应用自身建单）
 */
public record TicketRequestedEvent(String bizType, String bizNo, String typeCode, String title, String description,
                                   String priority, Map<String, Object> fields, String subjectTitle, List<String> scopes,
                                   String dedupeKey, String requesterNo, String requesterName) implements DomainEvent {

    public static final String TYPE = "TICKET_REQUESTED";

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

    /** 同一业务单只开一张：重投、重复点「转工单」拿到的都是同一张 */
    public String idempotencyKey() {
        return "opskit:ticket:" + bizType + ":" + bizNo;
    }
}
