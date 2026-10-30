package ai.neargo.sharehub.finance;

/**
 * 提现来源（{@code stl_withdrawal.origin}，V158）。
 * 一期只有 {@link #PAYOUT_TASK}：平台按结算单主动付款（需求方案 D8）；{@link #APPLY} 是商家自助申请，一期不开放。
 * 空值 = V158 之前的单，按 APPLY 看待。
 */
public enum WithdrawalOrigin {
    APPLY,
    PAYOUT_TASK
}
