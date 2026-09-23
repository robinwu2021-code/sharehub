package ai.neargo.sharehub.dev;

/**
 * 充电宝状态（{@code dev_powerbank.status}）—— {@link PowerbankStateMachine} 的取值域。
 *
 * <p><b>本枚举与 DDL 当前定义逐项一致</b>（{@code V8__v2_alter.sql} 的 MODIFY COLUMN）。
 *
 * <p>⚠️ 写这个枚举时我曾据 {@code V2__device_gateway.sql} 的原始建表注释断定「DDL 注释是错的」——
 * <b>那个判断本身是错的</b>：V2 的注释确实与代码对不上，但 V8 早已 MODIFY 修正。
 * 50 个迁移里列定义会被后续 ALTER 改写，<b>看 DDL 必须看当前 schema，不是最初的建表语句</b>。
 * 这条留着，是因为下一个人很可能重犯同一个错。
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
