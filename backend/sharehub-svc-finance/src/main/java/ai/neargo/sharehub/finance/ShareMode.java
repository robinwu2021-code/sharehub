package ai.neargo.sharehub.finance;

/**
 * 分润执行方式（{@code share_rule.mode}）。
 *
 * <p>这不是状态而是**结算路径的选择**，两者的资金流完全不同：
 * {@link #LEDGER} 先全额收款再记账分账（平台先拿钱），
 * {@link #CHANNEL_SPLIT} 由支付通道在收款瞬间直接分给各方（钱不经平台账户）。
 * 合规与对账口径因此不同，不能互换。
 */
public enum ShareMode {

    /** 通道分账：收款瞬间由支付通道直接分给各方。 */
    CHANNEL_SPLIT,
    /** 账务分账：平台全额收款后记账，按周期结算打款（当前路径）。 */
    LEDGER;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static ShareMode of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("分润方式必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分润方式非法: " + v + "（仅 CHANNEL_SPLIT/LEDGER）");
        }
    }
}
