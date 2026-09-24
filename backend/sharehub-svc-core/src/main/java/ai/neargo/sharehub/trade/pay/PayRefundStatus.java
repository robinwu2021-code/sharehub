package ai.neargo.sharehub.trade.pay;

import java.util.Arrays;
import java.util.Optional;

/**
 * 退款单状态（{@code pay_refund.status}）。
 *
 * <h3>⚠️ 与支付单不是同一套</h3>
 * 成功态是 {@link #SUCCESS} 而不是支付单的 {@code PAID}。两套词表都有
 * {@code INIT} 与 {@code FAILED}，只有成功态不同 —— 这正是最容易被顺手合并的形状。
 */
public enum PayRefundStatus {

    INIT,
    SUCCESS,
    FAILED;

    public static Optional<PayRefundStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
