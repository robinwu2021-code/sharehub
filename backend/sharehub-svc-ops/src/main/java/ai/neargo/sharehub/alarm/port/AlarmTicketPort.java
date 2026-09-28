package ai.neargo.sharehub.alarm.port;

import ai.neargo.sharehub.alarm.entity.DevAlarm;
import ai.neargo.sharehub.wo.ext.dto.WoExtDtos.AlarmDraft;

/**
 * 告警的「处置单」去处（方案 23 §十）。告警引擎对处置单的全部联动都经这里：开单 · 提优先级 · 并单 · 改挂说明 ·
 * 告警恢复 · 查是否已结。开关 {@code sharehub.flow.alarm-ticket.engine}：LOCAL（默认）= 本地 wo_order，
 * 即此前 {@code AlarmEngineImpl} 直接调 {@code WoOpsService} 的那几行，行为不变；OPSKIT = OpsKit 工单（异步命令）。
 *
 * <p>{@code ref} 即 {@code dev_alarm.disposition_ref}：本地是工单号，OpsKit 是 OpsKit 工单号。
 */
public interface AlarmTicketPort {

    /** 是否走 OpsKit（处置预览据此不算本地候选受理人） */
    boolean viaOpsKit();

    /** 开单或并入同键的未结单。返回单号；异步开单（OpsKit）此刻还没有号时返回 null，稍后回填 */
    String open(AlarmDraft draft, DevAlarm alarm);

    /** 单已结（或查无此单）—— 关闭联动据此跳过。异步实现一律 false，由命令执行器自行处理「已结」 */
    boolean isFinished(String ref);

    void raisePriority(String ref, DevAlarm alarm, String priority, String reason);

    /** 下层告警的单并入上层告警的单 */
    void merge(String ref, DevAlarm lower, String intoRef, DevAlarm upper, String reason);

    void annotate(String ref, DevAlarm alarm, String note);

    /** 关联告警全部自愈 / 自动修复：还没人接的撤单，已在处理的标记恢复 */
    void recovered(String ref, DevAlarm alarm, String note);
}
