package ai.neargo.sharehub.finance;

/**
 * 结算单状态（{@code stl_settlement.status}）。
 *
 * <p>与 {@link ShareRecordStatus} 是**两级**：分润记录是逐单明细，结算单是按周期的汇总。
 * 一张 {@link #GEN} 的结算单里，它汇总的那些分润记录已经是
 * {@link ShareRecordStatus#DONE} 了 —— DONE 说的是"已被纳入某张结算单"，
 * 不是"已打款"。打款与否看本枚举的 {@link #PAID}。
 */
public enum SettlementStatus {

    /** 已生成，待确认（DDL 默认值）。 */
    GEN,
    /** 已确认。 */
    CONFIRMED,
    /** 已打款。 */
    PAID;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static SettlementStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("结算单状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("结算单状态非法: " + v + "（仅 GEN/CONFIRMED/PAID）");
        }
    }
}
