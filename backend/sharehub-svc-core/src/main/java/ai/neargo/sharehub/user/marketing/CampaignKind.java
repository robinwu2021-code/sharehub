package ai.neargo.sharehub.user.marketing;

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

    /** 宽松解析：非法值抛 {@link IllegalArgumentException}（全局映射 400）。 */
    public static CampaignKind of(String v) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("活动类型必填");
        try {
            return valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("活动类型非法: " + v
                    + "（仅 NEW_USER/RECHARGE_GIFT/COUPON_PUSH/REFERRAL/FESTIVAL）");
        }
    }
}
