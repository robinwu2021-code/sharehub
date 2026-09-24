package ai.neargo.sharehub.trade.pay;

import java.util.Arrays;
import java.util.Optional;

/**
 * 支付单状态（{@code pay_order.status}，词表见 DDL 列注释）。
 *
 * <h3>⚠️ 与退款单不是同一套</h3>
 * {@code pay_refund.status} 也有 {@code INIT} 与 {@code FAILED}，但它的成功态是
 * {@code SUCCESS} 而不是 {@code PAID}，也没有 {@code PAYING}/{@code CLOSED}。
 * 两套词表长得像 —— <b>看到「都是状态」就合并，是本仓库反复出过事的那种动作</b>
 * （V41 的收费方案、V64 的押金买断都是这么来的）。
 */
public enum PayOrderStatus {

    /** 已下单，尚未发起支付。 */
    INIT,
    /** 已发起，等渠道回调。 */
    PAYING,
    /** 已支付 —— 唯一可退款的起点。 */
    PAID,
    FAILED,
    /** 已关闭（超时/取消）。 */
    CLOSED;

    public static Optional<PayOrderStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
