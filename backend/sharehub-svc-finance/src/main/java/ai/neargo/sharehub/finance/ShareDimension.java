package ai.neargo.sharehub.finance;

/**
 * 分润维度（{@code share_rule.dimension}）—— 这条规则分给谁那一类。
 *
 * <p>取价时按 {@code (dimension, payeeNo, basis)} 找（{@code ShareGeneratorImpl.ruleOf}），
 * 所以维度写错的症状不是报错，而是**那个分成方一条规则都命中不了** ——
 * 界面上规则配得好好的，分账时他拿不到钱。错的不是显示，是钱。
 *
 * <p>取值以 DDL 列注释为真源。
 */
public enum ShareDimension {

    /** 场地方（按合同分成）。 */
    VENUE,
    /** 代理商（按责任项分成，见 {@link ShareBasis}）。 */
    AGENT;

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static ShareDimension of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("分润维度必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分润维度非法: " + v + "（仅 VENUE/AGENT）");
        }
    }
}
