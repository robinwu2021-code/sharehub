package ai.neargo.sharehub.wo;

/**
 * 工单状态（{@code wo_order.status}，[db-design §1.6]）—— {@link WoStateMachine} 的取值域。
 *
 * <p>七个态是一条主链加两条回退边，完整的迁移规则在 {@link WoStateMachine}，本枚举只定义
 * <b>合法取值有哪些</b>。两件事分开：取值域回答「这一列能存什么」，状态机回答「能从哪到哪」。
 *
 * <p>与前端 {@code ops-web/lib/types/workorder.ts} 的 {@code WO_TRANSITIONS} 逐项对齐 ——
 * 两边任一边多一个态，表现都是「按钮亮着点了报错」或更糟的「按钮灰着但接口放行」。
 */
public enum WorkOrderStatus {

    /** 新建，待派单。{@code REJECT} 也退回到这里。 */
    CREATED,
    /** 已派单，待接单。 */
    DISPATCHED,
    /** 已接单。**本机 ACCEPT 落的是这个态而非直接 PROCESSING** —— 前端契约把它当遗留态跳过了。 */
    ACCEPTED,
    /** 处理中。{@code REWORK} 从 DONE 退回到这里，而**不是** CREATED。 */
    PROCESSING,
    /** 已完工，待验收。 */
    DONE,
    /** 已验收。 */
    AUDITED,
    /** 已关闭 —— **终态**，不设出边。要重开就另开一张单，否则 SLA 时间轴被无限延长。 */
    CLOSED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static WorkOrderStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("工单状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("工单状态非法: " + v
                    + "（仅 CREATED/DISPATCHED/ACCEPTED/PROCESSING/DONE/AUDITED/CLOSED）");
        }
    }
}
