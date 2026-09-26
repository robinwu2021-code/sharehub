package ai.neargo.sharehub.finance;

/**
 * 分润依据（{@code share_rule.basis}，V53 / ADR-027）—— 同一个代理凭哪一项责任分这笔钱。
 *
 * <p>「他出资拿 5%、运维拿 8%」是两条规则，靠这一列区分。
 *
 * <h2>⚠️ 空串是合法值，不是缺失</h2>
 * DDL 注释写着「VENUE 维度留空串」，而**实测 1108 行 AGENT 规则里有 188 行 basis 为空** ——
 * 那是「同分成方的通用规则」（取价找不到精确 basis 时的退路，见
 * {@code ShareGeneratorImpl.ruleOf}）。所以校验必须用 {@link #ofNullable}：
 * 按「必填」写会**把这 188 行的写入路径一并拒掉**，而它们是对的。
 *
 * <p>这也是为什么先查了数据才动手 —— 只看 DDL 注释那句「VENUE 维度留空串」，
 * 会推出「AGENT 维度必填」这个错结论。
 */
public enum ShareBasis {

    /** 出资。 */
    INVEST,
    /** 拓展（谈点位、签场地）。 */
    DEVELOP,
    /** 运维。 */
    OPERATE,
    /** 牵线介绍。 */
    REFER;

    /**
     * 宽松解析，**允许空**（空 = 通用规则，见类注释）。
     * 非空且非法时抛 {@link IllegalArgumentException}（全局映射 400）。
     */
    public static ShareBasis ofNullable(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("分润依据非法: " + v
                    + "（仅 INVEST/DEVELOP/OPERATE/REFER，或留空表示通用规则）");
        }
    }
}
