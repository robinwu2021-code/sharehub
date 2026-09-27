package ai.neargo.sharehub.user.marketing;

import ai.neargo.sharehub.common.BizException;

/**
 * 优惠券类型（{@code coupon_tpl.type}）。
 *
 * <p>取值以 **DDL 列注释为真源**（本仓库判定「哪边对」的依据）。
 * 这一列此前是裸 {@code String}，客户端传什么落什么、不报错 ——
 * 词表校验详见 {@code VocabularyRejectedOnWriteTest} 的类注释。
 */
public enum CouponType {

    /** 满减：直减固定金额 */
    CUT,
    /** 折扣：按比例打折 */
    DISCOUNT;

    /**
     * 宽松解析：非法值抛 400。文案走 i18n key，不写中文字面量 ——
     * 非 key 的字符串会被一字不改送到客户端，界面切英文/阿语之后页面是英文、报错还是中文。
     */
    public static CouponType of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.coupon.type_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.coupon.type_invalid", v);
        }
    }

    /**
     * **传了才校验** —— 空/缺省返回 {@code null}，调用方沿用原语义。
     * 保存端点创建与编辑共用，没传的字段表示「不改」；用 {@link #of} 会让
     * 「只改个名字」也被拒（实测过一次，见 {@code CampaignKind.ofNullable} 的注释）。
     */
    public static CouponType ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }
}
