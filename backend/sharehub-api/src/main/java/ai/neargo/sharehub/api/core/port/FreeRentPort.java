package ai.neargo.sharehub.api.core.port;

import java.math.BigDecimal;

/**
 * 免单白名单在借还链路上的出口 —— 白名单归 {@code user.core} 管，免单发生在 {@code trade}。
 *
 * <p>走端口的理由同 {@link CouponUsePort}：{@code user} 已经依赖 {@code trade}，
 * 反向直连即成包循环。
 *
 * <h3>此前这张白名单一次都没生效过</h3>
 * {@code usr_free_whitelist} 有表、有实体、有服务、有运营端授予/撤销端点，
 * {@code ord_order.free_reason} 的列注释写着「源 usr_free_whitelist.reason」，
 * {@code ChargeChain} 的免单分支连边界用例都写好了 —— 唯独<b>没有任何一处调用
 * {@code setFreeReason}</b>。于是给 VIP 授了免单，他照样全额付费；
 * 而运营端「免费订单」那一页（查的是 {@code free_reason IS NOT NULL}）永远是空的，
 * 看起来像「这个月没人用免单」。
 */
public interface FreeRentPort {

    /**
     * 免单授权。{@code null} 表示这一单正常收费。
     *
     * @param reason    回写进 {@code ord_order.free_reason}，取值同白名单的 reason 枚举
     * @param capAmount 本单最多能免多少；{@code null}=不设上限（UNLIMITED / TIMES）。
     *                  AMOUNT 额度用剩余量兜住 —— 不兜的话「额度 100」会免出 130 来，
     *                  而那超出的部分没有任何地方会报错
     */
    record FreeGrant(String reason, BigDecimal capAmount) {
    }

    /** 下单时问：这一单免不免。已过期 / 已撤销 / 额度用尽 / 币种不符 → null。 */
    FreeGrant grantFor(String cUserNo, String currency);

    /**
     * 结算时扣额度：TIMES 记 +1，AMOUNT 记 +{@code waived}，UNLIMITED 不动。
     *
     * @return true=扣成功（本单的减免作数）；false=额度已被别处用尽，本单应按原价重算
     */
    boolean consume(String cUserNo, String orderNo, BigDecimal waived);
}
