package ai.neargo.sharehub.wo;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态机（独立组件，设计 §五-2）：集中定义合法迁移，非法迁移拒。
 * 状态：CREATED→DISPATCHED→ACCEPTED→PROCESSING→DONE→AUDITED→CLOSED。
 *
 * <p><b>本类是全站唯一的工单迁移定义</b>（对齐 {@code ops-web/lib/types/workorder.ts}
 * 的 {@code WO_TRANSITIONS}）。新增动作端点一律往这里加边，**不允许**在 service 里
 * 另写一套 {@code if (status.equals(...))} —— 两套规则并存必然分叉，最后是「按钮亮着点了报错」
 * 或更糟的「按钮灰着但接口放行」。
 *
 * <p>回退边（2026-07-30 补）：
 * <ul>
 *   <li>{@code REJECT} 驳回退回待派单：DISPATCHED / ACCEPTED / PROCESSING → CREATED。
 *       包含 ACCEPTED 是因为本机的 {@code ACCEPT} 落的是 ACCEPTED 而非直接 PROCESSING
 *       （前端契约把 ACCEPTED 当遗留态跳过了，见交付报告的契约分歧），
 *       接了单又推不动的工单必须也能退回，否则它卡在 ACCEPTED 上无路可走。</li>
 *   <li>{@code REWORK} 验收不合格退回返工：DONE → PROCESSING。**不回 CREATED** ——
 *       返工是「同一个人没修好，再去修」，回 CREATED 等于连派单一起重来，
 *       既丢了处理人也让「这单转了几手」的统计凭空多一手。</li>
 * </ul>
 * 两条边都不设自环、不从终态（CLOSED）出发：已归档的工单要重开就该另开一张单，
 * 否则 SLA 与达标率的时间轴会被无限延长。
 */
@Component
public class WoStateMachine {

    /**
     * event → (fromStatus → toStatus)。
     *
     * <p>注：「完工」用的是 DONE 事件（PROCESSING→DONE），端点名 /complete，不另加一条同义边。
     *
     * <p><b>状态是 {@link WorkOrderStatus}，事件仍是字符串</b>：事件是动词、状态是名词，
     * 类型不同让两者无法被混为一谈。告警域栽过这个跟头 —— 那里 {@code ACK} 是事件名，
     * 却被写进了查状态的 {@code IN} 条件，已受理的告警于是永远开不出工单（见 AlarmStatus）。
     */
    private static final Map<String, Map<WorkOrderStatus, WorkOrderStatus>> TRANSITIONS = Map.of(
            "DISPATCH", Map.of(WorkOrderStatus.CREATED, WorkOrderStatus.DISPATCHED),
            "ACCEPT", Map.of(WorkOrderStatus.DISPATCHED, WorkOrderStatus.ACCEPTED),
            "PROCESS", Map.of(WorkOrderStatus.ACCEPTED, WorkOrderStatus.PROCESSING),
            "DONE", Map.of(WorkOrderStatus.PROCESSING, WorkOrderStatus.DONE),
            "AUDIT", Map.of(WorkOrderStatus.DONE, WorkOrderStatus.AUDITED),
            "CLOSE", Map.of(WorkOrderStatus.AUDITED, WorkOrderStatus.CLOSED),
            "REJECT", Map.of(WorkOrderStatus.DISPATCHED, WorkOrderStatus.CREATED,
                             WorkOrderStatus.ACCEPTED, WorkOrderStatus.CREATED,
                             WorkOrderStatus.PROCESSING, WorkOrderStatus.CREATED),
            "REWORK", Map.of(WorkOrderStatus.DONE, WorkOrderStatus.PROCESSING));

    /**
     * 校验并返回目标状态；非法迁移抛异常。
     *
     * <p>出入参仍是 {@code String}：落库的是字符串，边界转换一次，不动实体字段类型
     * （那会牵出 MyBatis 类型处理器的连锁改动）。
     */
    public String next(String from, String event) {
        Map<WorkOrderStatus, WorkOrderStatus> m = TRANSITIONS.get(event);
        // of() 对垃圾值直接抛，不做运行期兼容
        WorkOrderStatus to = m == null ? null : m.get(WorkOrderStatus.of(from));
        if (to == null) {
            throw new IllegalArgumentException("工单状态非法迁移: " + from + " --" + event + "--> ?");
        }
        return to.name();
    }

    public Set<String> events() {
        return TRANSITIONS.keySet();
    }
}
