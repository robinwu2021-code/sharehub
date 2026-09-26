package ai.neargo.sharehub.api.common;

/**
 * 分润执行方式（{@code share_rule.mode}）。
 *
 * <h2>为什么住在 {@code sharehub-api} 而不是 {@code finance}</h2>
 * 这一套词表有**两列**在用：{@code share_rule.mode}（finance）与
 * {@code agt_commission.mode}（platform）。原先它在 finance 里，而 platform 不依赖 finance ——
 * 于是 platform 那一列**只能裸 String 落库**，或者另建一个同义枚举。
 * 后者更糟：两个枚举描述同一列取值，迟早只改一边，而「另一边没改」这件事没有任何症状。
 *
 * <p>⚠️ 不要和 {@code loc.ContractShareMode}（SHARE/ENTRY_FEE/GUARANTEE/FREE）搞混 ——
 * 名字像，是**合同的商业模式**，与结算路径无关。
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
    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义（见
     * {@code CampaignKind.ofNullable} 的注释：保存端点创建与编辑共用）。
     */
    public static ShareMode ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }

}
