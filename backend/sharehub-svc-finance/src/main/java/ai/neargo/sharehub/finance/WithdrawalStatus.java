package ai.neargo.sharehub.finance;

/**
 * 提现状态（{@code stl_withdrawal.status}）。
 *
 * <p>{@link #APPLY} 由**服务端置**，不接受入参（{@code WithdrawalServiceImpl} 注释写明）——
 * 否则调用方可以直接提交一张 {@link #PAID} 的提现单。
 */
public enum WithdrawalStatus {

    /** 已申请（服务端置，不接受入参）。 */
    APPLY,
    /** 审核中。 */
    AUDIT,
    /** 打款中。 */
    PAYING,
    /** 已打款。 */
    PAID,
    /** 失败/驳回。 */
    FAILED;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static WithdrawalStatus of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("提现状态必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("提现状态非法: " + v + "（仅 APPLY/AUDIT/PAYING/PAID/FAILED）");
        }
    }
}
