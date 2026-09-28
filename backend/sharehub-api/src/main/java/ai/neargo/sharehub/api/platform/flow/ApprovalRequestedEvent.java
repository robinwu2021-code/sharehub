package ai.neargo.sharehub.api.platform.flow;

import ai.neargo.sharehub.common.event.DomainEvent;

import java.util.List;
import java.util.Map;

/**
 * 一笔业务要送 OpsKit 审批（M5，方案 23 §三）。业务写入与本事件同事务落 outbox，
 * 提交后由 app 层的 OpsKit 送审消费者发起流程实例；失败按 outbox 退避重投。
 *
 * <p>字段自带送审所需的全部信息 —— 消费者不回查业务表（{@link DomainEvent} 的约定）。
 *
 * @param bizType      业务类型，同时是 OpsKit 业务对象的 {@code subject.type}（如 {@code withdrawal}），
 *                     也是回写时找 {@link ApprovalOutcomeHandler} 的键
 * @param bizNo        业务单号 = {@code subject.ref}
 * @param round        送审轮次，从 1 起；「重新送审」加一 —— 进幂等键，保证重投不重复建单、重送又能建新单
 * @param processCode  OpsKit 流程代号（随 bootstrap definitions-dir 发布）
 * @param subjectTitle 审批单上显示的一句话（如「提现 AED 1,200.00 · AG0007」）
 * @param fields       流程字段（条件分支、会签判断用）；值只放标量
 * @param scopes       范围标签（如 {@code agent:AG0007}）：OpsKit 据此决定谁看得见这张审批单
 * @param submitterNo  提交人宿主编号（= 内省的 sub）；以 onBehalfOf 送出，自审禁止才判得准
 * @param submitterName 提交人显示名
 */
public record ApprovalRequestedEvent(String bizType, String bizNo, int round, String processCode, String subjectTitle,
                                     Map<String, Object> fields, List<String> scopes, String submitterNo, String submitterName)
        implements DomainEvent {

    public static final String TYPE = "APPROVAL_REQUESTED";

    @Override
    public String aggregateType() {
        return "Approval:" + bizType;
    }

    @Override
    public String aggregateId() {
        return bizNo;
    }

    @Override
    public String eventType() {
        return TYPE;
    }

    /** OpsKit 的幂等键：同一业务、同一轮次无论重投多少次都是同一个实例 */
    public String idempotencyKey() {
        return "opskit:" + bizType + ":" + bizNo + ":" + round;
    }
}
