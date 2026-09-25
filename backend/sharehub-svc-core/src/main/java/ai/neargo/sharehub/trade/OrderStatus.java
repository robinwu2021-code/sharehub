package ai.neargo.sharehub.trade;

/**
 * 租借订单状态（{@code ord_rent.status}，[db-design §5.1]）—— {@link OrdStateMachine} 的取值域。
 *
 * <p><b>词表以 DDL 列注释为准</b>，与运营端 {@code ops-web/lib/types/order.ts} 的
 * {@code OrderStatus} 一字不差（由 {@code StatusVocabularyAcrossEndsTest} 钉住）。
 *
 * <p><b>枚举里有、状态机里没有的三个值不是遗漏</b>：
 * {@link #CREATED} 是建单时直接写入的初值，{@link #DISPENSING} 由设备侧弹仓回调推进，
 * {@link #EXCEPTION} 由异常订单处置写入 —— 它们都不经 {@link OrdStateMachine} 的边。
 * 枚举是<b>取值域</b>（这一列能存什么），状态机是<b>迁移图</b>（哪一步能走到哪），
 * 两者本就不必相等；混为一谈会得出「补几条边让它们对齐」这种错误结论。
 */
public enum OrderStatus {

    /** 已创建，未出货。 */
    CREATED,
    /** 弹仓中（设备侧回调推进，不经状态机）。 */
    DISPENSING,
    /** 使用中。 */
    IN_USE,
    /** 已归还，待结算。 */
    RETURNED,
    /** 已结算（费用已定）。 */
    SETTLED,
    /** 已关闭 —— 终态。 */
    CLOSED,
    /** 异常（未弹出 / 未归还 / 重复扣费等），由异常订单处置写入。 */
    EXCEPTION;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static OrderStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("订单状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("订单状态非法: " + v
                    + "（仅 CREATED/DISPENSING/IN_USE/RETURNED/SETTLED/CLOSED/EXCEPTION）");
        }
    }
}
