package ai.neargo.sharehub.finance;

/** 结算调整项种类（{@code stl_adjustment.kind}，V107）。 */
public enum AdjustmentKind {
    /** 押金退还（撤场时场地方退还平台所付押金）。 */
    DEPOSIT_REFUND,
    /** 进场费结清（提前撤场时未履约期对应的进场费）。 */
    ENTRY_FEE_SETTLE,
    /** 保底补差（批次 G2）：保底 + 分成合同，账期内场地方分成不足保底的差额，平台补给场地方。 */
    GUARANTEE_TOPUP
}
