package ai.neargo.sharehub.finance;

/**
 * 结算明细指向什么（{@code stl_settlement_detail.ref_type}）。
 *
 * <p>当前出账走 {@link #SHARE}：明细指向**分润记录**而不是订单 ——
 * 一笔订单可能产生多条分润（场地方一条、代理一条），指向订单会丢掉这个一对多。
 */
public enum SettlementRefType {

    /** 指向订单。 */
    ORDER,
    /** 指向分润记录（当前出账路径）。 */
    SHARE;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static SettlementRefType of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("结算明细类型必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("结算明细类型非法: " + v + "（仅 ORDER/SHARE）");
        }
    }
}
