package ai.neargo.sharehub.finance;

/**
 * 结算调整项状态（{@code stl_adjustment.status}）：系统按合同算出建议值 → 财务确认（可改金额）→ 下一次出账并入结算单。
 * 系统不直接出账：押金退还条件、进场费是否按天折算都写在合同的自由文本里，最终金额要人拍板。
 */
public enum AdjustmentStatus {
    PENDING, CONFIRMED, SETTLED, VOID
}
