package ai.neargo.sharehub.finance;

/**
 * 分润记录状态（{@code share_record.status}）。
 *
 * <p>⚠️ {@link #DONE} 的意思是**已被纳入某张结算单**，不是"已打款"。
 * 打款与否看 {@link SettlementStatus#PAID}。两处都叫"完成"而含义不同，
 * 是这一段最容易读错的地方。
 *
 * <p>出账时按 {@link #PENDING} + 账期捞取，回填结算号后置 {@link #DONE} ——
 * 这是防重复结算的第二道闸（第一道是账期唯一键）。
 */
public enum ShareRecordStatus {

    /** 待结算 —— 出账按这个状态 + 账期捞取。 */
    PENDING,
    /** 已纳入结算单（**不等于已打款**，见类注释）。 */
    DONE;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static ShareRecordStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("分润记录状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分润记录状态非法: " + v + "（仅 PENDING/DONE）");
        }
    }
}
