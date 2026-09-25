package ai.neargo.sharehub.api.core.port;

import java.math.BigDecimal;

/**
 * 下单/结算时「用券」的出口 —— 券归 {@code user.marketing} 管，用券发生在 {@code trade}。
 *
 * <p><b>为什么要走端口而不是直接注入 UserCouponService</b>：{@code user} 已经依赖
 * {@code trade}（钱包读订单、充值走支付），反向直连就把两个域连成了环，
 * {@code ArchitectureTest#域间无包循环} 是硬规则。端口放在 api 模块，两边都只依赖它。
 *
 * <h3>核销与抵扣必须是同一个决定</h3>
 * {@link #consume} 是**带条件的原子更新**（仅 {@code UNUSED} 才置 {@code USED}），
 * 返回值就是「这张券归不归这一单」的裁决。抵扣金额由它的返回值决定，而不是先算优惠
 * 再去改状态 —— 后者在并发下会让同一张券抵扣两单，而两单都不会报错。
 */
public interface CouponUsePort {

    /**
     * 券的抵扣规格。{@code null} 表示这张券此刻用不了（不存在 / 不是本人 / 已用 / 过期 / 币种不符）。
     *
     * @param type      CUT（满减）/ DISCOUNT（折扣）
     * @param value     CUT=减免金额；DISCOUNT=折扣率（0..1，0.8 即八折，抵扣 20%）
     * @param threshold 使用门槛金额；null 或 0 表示无门槛
     */
    record CouponOffer(String couponNo, String type, BigDecimal value, BigDecimal threshold) {
    }

    /**
     * 取券的抵扣规格，顺带做可用性校验。
     *
     * @param cUserNo  属主；必须与券上的一致，否则返回 null（别人的券不能用）
     * @param currency 订单币种；与券模板币种不符时返回 null ——
     *                 一张 AED 的券抵在 SAR 的单上，数字对得上、钱对不上
     */
    CouponOffer offerOf(String cUserNo, String couponNo, String currency);

    /**
     * 核销：{@code UNUSED → USED} 并记下 {@code used_order_no}。
     *
     * @return true=本单抢到了这张券（可以抵扣）；false=它已经被别处用掉了（本单按无券计价）
     */
    boolean consume(String couponNo, String orderNo);
}
