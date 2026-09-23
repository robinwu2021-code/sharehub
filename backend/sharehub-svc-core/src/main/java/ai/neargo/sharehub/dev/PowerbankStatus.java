package ai.neargo.sharehub.dev;

/**
 * 充电宝状态（{@code dev_powerbank.status}）—— {@link PowerbankStateMachine} 的取值域。
 *
 * <h2>⚠️ DDL 注释是错的，别照着它写</h2>
 * {@code V2__device_gateway.sql} 上的列注释是
 * {@code 'IN_STOCK/DEPLOYED/IN_USE/RETURNED/SCRAP/LOST'} —— 其中
 * {@code IN_USE} / {@code RETURNED} 是**订单**的状态（见 {@code OrdStateMachine}），
 * {@code DEPLOYED} 是**机柜**的状态。那条注释抄的是别的对象的词表。
 *
 * <p>本枚举以 {@link PowerbankStateMachine} 为准 —— 它是唯一真正写这一列的地方。
 * 七个值里有四个（IN_CABINET / RENTED / FAILT→FAULT / SOLD）从没出现在 DDL 注释里，
 * 而注释列的三个值没有任何代码会写。这正是"注释不是约束"的代价：
 * **两边各写各的，谁也不会红**。
 *
 * <h2>终态有两个，且含义不同</h2>
 * {@link #SOLD} 与 {@link #SCRAP} 是终态；{@link #LOST} 是**半终态** ——
 * 失而复得（RECOVER）能回到 {@link #IN_CABINET}，超时买断（BUYOUT）则转 {@link #SOLD}。
 */
public enum PowerbankStatus {

    /** 在仓，未投放（DDL 默认值）。 */
    IN_STOCK,
    /** 已投放，在柜中可借。 */
    IN_CABINET,
    /** 已借出。 */
    RENTED,
    /** 故障（自检上报 / 坏机归还）。维修后回 {@link #IN_STOCK}。 */
    FAULT,
    /** 超时未归还 —— **半终态**，可 RECOVER 回柜，也可 BUYOUT 转 {@link #SOLD}。 */
    LOST,
    /** 买断付费，**终态**。 */
    SOLD,
    /** 报废，**终态**。 */
    SCRAP;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static PowerbankStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("充电宝状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("充电宝状态非法: " + v
                    + "（仅 IN_STOCK/IN_CABINET/RENTED/FAULT/LOST/SOLD/SCRAP）");
        }
    }
}
