package ai.neargo.sharehub.finance;

import ai.neargo.sharehub.common.BizException;

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

    /**
     * 宽松解析：非法值抛 400。
     *
     * <p>⚠️ **文案走 i18n key，不写中文字面量** —— {@code GlobalExceptionHandler} 会把
     * 非 key 的字符串**一字不改**送到客户端，于是界面切英文/阿语之后页面是英文、报错还是中文。
     * 不报错，只是读不懂（{@code ChineseErrorLiteralRatchetTest} 卡着这件事，
     * 本类第一版就是那么写的，被它当场抓住）。
     */
    public static ShareDimension of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.share.dimension_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.share.dimension_invalid", v);
        }
    }
    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，由调用方沿用它原来的语义。
     *
     * <p>⚠️ 为什么不用 {@link #of}：保存端点**创建与编辑共用**，编辑时只传要改的字段，
     * 没传的留 null 表示「不改」。第一版在那里调了 {@code of}，于是
     * **改个名字都会被拒**（活动编辑实测 400），而那正是运营台最常做的操作。
     * 是 {@code StatusMassAssignmentTest.campaign_save_still_updates_other_fields} 抓住的。
     *
     * <p>缺口并不因此变小：非法值照样被拒，只有「没提供」被放过 ——
     * 而「没提供」在这一改之前本来就是合法的。
     */
    public static ShareDimension ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }

}
