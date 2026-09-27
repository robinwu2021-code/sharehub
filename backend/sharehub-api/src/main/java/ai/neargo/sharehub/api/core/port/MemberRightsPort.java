package ai.neargo.sharehub.api.core.port;

import java.math.BigDecimal;

/**
 * 会员权益在借还链路上的出口 —— 会员归 {@code user.member}/{@code user.asset} 管，
 * 计价发生在 {@code trade}。
 *
 * <p>走端口的理由同 {@link CouponUsePort}：{@code user} 已经依赖 {@code trade}
 * （钱包读订单、充值走支付），反向直连即成包循环。
 *
 * <h3>此前会员权益一次都没生效过</h3>
 * {@code mbr_benefit} 有表、有实体、有运营端配置端点（权益还带等级单调性校验），
 * {@code mbr_plan} 有表有实体、C 端有会员方案页，{@code ChargeChain} 的类注释甚至写着
 * 「券与<b>会员权益</b>在结算时才确定」—— 唯独 <b>{@code trade} 域对 membership 零引用</b>
 * （整个域 grep 不到一次）。于是给用户配了九折，他照样全额付费；
 * 而页面上那行「租金九折」是从自由文案里读出来的，看起来一切正常。
 *
 * <h3>「生效权益」是两个来源的合成</h3>
 * 等级权益来自积分成长（{@code mbr_benefit}，按 {@code usr_membership.level}），
 * 卡权益来自花钱买的方案（{@code mbr_plan.rights}，按 {@code usr_membership.plan_no}）。
 * 两者<b>正交</b>（{@code UsrMembership} 注释：等级来自成长值，卡型来自买了哪个方案），
 * 所以是<b>逐项取更优</b>而不是相互覆盖 ——
 * 白银用户买了次卡，不该因此丢掉等级带来的折扣。
 */
public interface MemberRightsPort {

    /**
     * 生效权益。
     *
     * @param level           权益出自哪个等级；回写 {@code ord_order.member_level} 用，
     *                        答「这单打折是凭什么」。纯卡权益（无等级）时为 {@code null}
     * @param planNo          权益出自哪个方案；同上，仅留痕
     * @param rentDiscount    租金折扣率，0.9 = 九折；{@code null} 或 ≥1 视为不打折。
     *                        <b>是折扣率不是折扣额</b> —— 写反是「九折变一折」，
     *                        金额照样算得出来，只是少收了 90%
     * @param freeMinutes     每单免费时长（分钟）；作用于**计费分钟数**，不改真实时长
     * @param depositFree     是否免押
     */
    record Rights(String level, String planNo, BigDecimal rentDiscount,
                  int freeMinutes, boolean depositFree) {

        /** 三项权益全是「等于没有」时为空 —— 空权益不该在账单上占一行。 */
        public boolean isEmpty() {
            return freeMinutes <= 0 && !depositFree
                    && (rentDiscount == null || rentDiscount.compareTo(BigDecimal.ONE) >= 0);
        }
    }

    /**
     * 取某用户此刻的生效权益。
     *
     * <p>{@code null} = 非会员 / 会员已过期 / 权益等于没有。
     * <b>不返回一个「全是默认值」的对象</b> —— 那样调用方要自己判「这算不算会员」，
     * 而漏判的表现是账单上多一行「会员折扣 0.00」。
     */
    Rights rightsOf(String cUserNo);
}
