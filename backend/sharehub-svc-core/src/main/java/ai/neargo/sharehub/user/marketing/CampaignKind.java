package ai.neargo.sharehub.user.marketing;

import ai.neargo.sharehub.common.BizException;

/**
 * 营销活动类型（{@code mkt_campaign.kind}）。
 *
 * <h2>为什么补这个枚举</h2>
 * 这一列此前是裸 {@code String}，{@code CampaignReq.kind} 原样 {@code setKind} 进实体 ——
 * 客户端 POST {@code {"kind":"WHATEVER"}} 就落库，**不报错**。
 * {@code StoredValueInVocabularyTest} 只在**事后**比对库里的值与列注释，
 * 也就是说脏数据要先写进去才会被发现，而那时该查的是当初写它的那次请求，
 * 现场早没了 —— 「脏数据比坏代码难查」的那一类。
 *
 * <p>取值以 **DDL 列注释为真源**（本仓库判定「哪边对」的依据），
 * 与 {@code mkt_campaign.kind} 的注释一字不差。
 */
public enum CampaignKind {

    /** 新用户首单/注册礼。 */
    NEW_USER,
    /** 充值赠送。 */
    RECHARGE_GIFT,
    /** 定向发券。 */
    COUPON_PUSH,
    /** 邀请返利。 */
    REFERRAL,
    /** 节庆活动。 */
    FESTIVAL;

    /**
     * 宽松解析：非法值抛 400。
     *
     * <p>⚠️ **文案走 i18n key，不写中文字面量** —— {@code GlobalExceptionHandler} 会把
     * 非 key 的字符串**一字不改**送到客户端，于是界面切英文/阿语之后页面是英文、报错还是中文。
     * 不报错，只是读不懂（{@code ChineseErrorLiteralRatchetTest} 卡着这件事，
     * 本类第一版就是那么写的，被它当场抓住）。
     */
    public static CampaignKind of(String v) {
        if (v == null || v.isBlank()) throw BizException.badRequest("error.campaign.kind_required");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest("error.campaign.kind_invalid", v);
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
    public static CampaignKind ofNullable(String v) {
        return v == null || v.isBlank() ? null : of(v);
    }

}
