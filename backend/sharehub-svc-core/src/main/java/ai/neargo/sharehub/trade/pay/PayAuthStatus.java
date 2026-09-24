package ai.neargo.sharehub.trade.pay;

import java.util.Arrays;
import java.util.Optional;

/**
 * 预授权状态（{@code pay_auth.status}）：{@code FROZEN → CAPTURED / RELEASED}。
 *
 * <p>免押租借靠它：借出时冻结额度，归还后按实际费用扣款（CAPTURED）
 * 或全额释放（RELEASED）。
 *
 * <h3>为什么扣款与释放都只从 FROZEN 出发</h3>
 * 两者都是终态。允许从 CAPTURED 再 RELEASED，等于把已经收的钱又退回去
 * 而账上没有对应的退款单 —— 对账时那笔钱凭空消失。
 * 实现用的是<b>正向白名单</b>（{@code if (!FROZEN) throw}），
 * 所以将来加状态也不会意外放行。
 */
public enum PayAuthStatus {

    /** 已冻结额度。唯一可 capture / release 的起点。 */
    FROZEN,
    /** 已扣款 —— 终态。 */
    CAPTURED,
    /** 已释放 —— 终态。 */
    RELEASED;

    public static Optional<PayAuthStatus> of(String s) {
        return Arrays.stream(values()).filter(v -> v.name().equalsIgnoreCase(s)).findFirst();
    }

    public boolean is(String s) {
        return name().equals(s);
    }
}
