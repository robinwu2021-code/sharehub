package ai.neargo.sharehub.wo.ext;

/**
 * 工单类型（[db-design §3.6] {@code wo_order.type} 的取值域，与前端
 * {@code ops-web/lib/types/workorder.ts} 的 {@code WorkOrderType} 逐项一致）。
 *
 * <p>收敛成枚举而不是裸字符串，是因为它同时充当 {@code wo_sla_rule.wo_type} 的取值域，
 * 而后者上有 UK({@code tenant_id}, {@code wo_type}) —— 一个拼错的类型串会静默建出
 * 一条永远命中不了任何工单的 SLA 规则，且不违反任何约束。
 */
public enum WorkOrderType {

    /** 故障维修。 */
    FAULT,
    /** 补货/换电。 */
    REFILL,
    /** 巡检。 */
    INSPECT,
    /** 安装进场。 */
    INSTALL,
    /** 撤场拆机。 */
    REMOVE,
    /** 投诉转单。 */
    COMPLAINT,
    /** 清洁保养。 */
    CLEAN;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static WorkOrderType of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("工单类型必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("工单类型非法: " + v
                    + "（仅 FAULT/REFILL/INSPECT/INSTALL/REMOVE/COMPLAINT/CLEAN）");
        }
    }
}
