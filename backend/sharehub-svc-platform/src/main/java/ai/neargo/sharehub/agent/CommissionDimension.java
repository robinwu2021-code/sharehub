package ai.neargo.sharehub.agent;

import ai.neargo.sharehub.common.BizException;

/**
 * 代理分佣计费基准（{@code agt_commission.dimension}）。
 *
 * <p>取值以 **DDL 列注释为真源**（本仓库判定「哪边对」的依据）。
 * 这一列此前是裸 {@code String}，客户端传什么落什么、不报错 ——
 * 词表校验详见 {@code VocabularyRejectedOnWriteTest} 的类注释。
 *
 * <p>⚠️ **不要和 {@code share_rule.dimension} 混用**：那一列是 VENUE/AGENT（分给谁），
 * 这一列是 GMV/ORDER_COUNT（按什么算）。列名相同、词表完全不同 ——
 * 共用一个枚举就会把两边都放宽成四档，而那之后两列都不再有校验。
 *
 * <p>⚠️ 入参名叫 {@code basis}（随读出参），列名是 {@code dimension} ——
 * 这个错配本身修过一次（运营选的计费基准永远存不进去，读侧却显示正常）。
 */
public enum CommissionDimension {

    /** 按成交额比例 */
    GMV,
    /** 按单量固定额 */
    ORDER_COUNT;

    /**
     * 宽松解析：非法值抛 400。文案走 i18n key，不写中文字面量 ——
     * 非 key 的字符串会被一字不改送到客户端，界面切英文/阿语之后页面是英文、报错还是中文。
     */
    public static CommissionDimension of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.commission.dimension_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.commission.dimension_invalid", v);
        }
    }

    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。
     * 保存端点创建与编辑共用，没传的字段表示「不改」；用 {@link #of} 会让
     * 「只改个名字」也被拒（实测过一次，见 {@code CampaignKind.ofNullable} 的注释）。
     */
    public static CommissionDimension ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
