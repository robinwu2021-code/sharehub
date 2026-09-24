package ai.neargo.sharehub.user.asset;

import java.util.Arrays;
import java.util.Optional;

/**
 * 钱包充值单状态（{@code usr_recharge_order.status}，DDL 默认 {@code PENDING}）。
 *
 * <h3>⚠️ 与支付单不是同一套</h3>
 * 支付单（{@code pay_order}）的初始态是 {@code INIT}，这里是 {@link #PENDING}；
 * 支付单有 {@code PAYING}/{@code CLOSED}，这里有 {@link #REFUNDED}。
 * 两者都有 {@code PAID}/{@code FAILED} —— <b>相像但不相同</b>，
 * 合并的代价是「充值单永远查不到」那类静默故障（参见 V41 / V64）。
 */
public enum RechargeOrderStatus {

    /** 已下单未付。 */
    PENDING,
    /** 已到账 —— 钱包余额由这一态派生，统计只认它。 */
    PAID,
    FAILED,
    REFUNDED;

    public static Optional<RechargeOrderStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
