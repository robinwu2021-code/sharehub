package ai.neargo.sharehub.api.core.port;

/**
 * 退款**申请**（trade.order 实现）—— 建一张待财务审批的单，<b>不动钱</b>。
 *
 * <p>钱要等财务 {@code audit} 通过之后才由支付域真的退，所以这个端口是安全的：
 * 它把用户的诉求变成财务的一条待办，而不是一次扣款。
 *
 * <p>为什么要有它：报障转退款在 {@code cs} 域（svc-ops），而退款审批单在 trade（svc-core），
 * 两者之间被 enforcer 的 banned dependency 隔开。
 * 在此之前 cs 侧装的是一个只发号的桩 —— 报障单看起来「已转退款」，
 * 而财务侧没有任何待办，**钱不会到用户手上**（2026-09-28 查实）。
 */
public interface RefundApplyPort {

    /**
     * 按订单建退款申请，返回退款单号。
     *
     * @param idempotencyKey 幂等键（列上有 UNIQUE）：同一键只受理一次，重复调用返回已有单号。
     *                       报障这条路传报障单号，于是「重复点转退款」不会退第二笔。
     * @return 退款单号；订单不存在 / 没有可退金额时返回 {@code null} —— 调用方据此改走人工，
     *         而不是编一个号出来。
     */
    String applyForOrder(String orderNo, String idempotencyKey, String reason);
}
