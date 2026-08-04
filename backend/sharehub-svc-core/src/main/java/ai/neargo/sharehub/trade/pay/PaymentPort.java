package ai.neargo.sharehub.trade.pay;

import java.math.BigDecimal;

/**
 * 支付出站端口（ADR-005：支付执行**委托 neargo nearpay**，powerbank 只留引用与状态镜像）。
 *
 * <p><b>业务代码只依赖本接口，永远不依赖实现</b>。MVP 期绑的是 {@link StubPaymentPort}（模拟成功），
 * 接入 nearpay 时新增一个 {@code NearpayPaymentPort} 实现并把 Stub 降级为测试用，
 * 上层（订单/押金/退款）一行不改。
 *
 * <p><b>不要把渠道密钥传进这里</b>：明文密钥在 KMS/vault，由实现方自己取；
 * 端口参数里只允许出现渠道码、金额、单号这类非敏感信息。
 *
 * <p>幂等：所有出站调用都带 {@code idempotencyKey}（[db-design §1.6]），
 * 回调侧的幂等落在 {@code pay_event_log} 的 UK(ref_no,event_type)。
 */
public interface PaymentPort {

    /**
     * 发起支付。
     *
     * @param req 支付请求；{@code idempotencyKey} 必填，重复提交必须返回同一结果
     */
    PayResult pay(PayRequest req);

    /** 预授权冻结（免押）。 */
    AuthResult preAuth(AuthRequest req);

    /**
     * 请款：把已冻结额度的一部分/全部真正扣走。
     *
     * @param amount 不得超过冻结额
     */
    AuthResult capture(String authRef, BigDecimal amount, String idempotencyKey);

    /** 释放冻结（未产生费用或已另行收款）。 */
    AuthResult release(String authRef, String idempotencyKey);

    /** 退款。原路退回，金额不得超过原支付额。 */
    RefundResult refund(RefundRequest req);

    /** 查询对侧最新状态（对账 / 回调丢失时的补偿查询）。 */
    PayResult query(String txnRef);

    // ——————————————————————— 出入参 ———————————————————————

    /**
     * @param payNo          本侧支付单号
     * @param orderNo        关联业务单号
     * @param type           DEPOSIT/RENT/BUYOUT/RECHARGE/MEMBERSHIP
     * @param channelCode    渠道码（**不含密钥**）
     * @param idempotencyKey 幂等键
     */
    record PayRequest(String payNo, String orderNo, String cUserNo, String type,
                      BigDecimal amount, String currency, String channelCode, String idempotencyKey) {
    }

    /**
     * @param status INIT / PAYING / PAID / FAILED / CLOSED（与 {@code pay_order.status} 同一套取值）
     * @param txnRef 对侧交易引用，回填 {@code pay_order.nearpay_txn_no}
     */
    record PayResult(String payNo, String txnRef, String status, BigDecimal amount,
                     String currency, String failReason) {
    }

    record AuthRequest(String authNo, String orderNo, String cUserNo, BigDecimal freezeAmount,
                       String currency, String channelCode, String idempotencyKey) {
    }

    /** @param status FROZEN / CAPTURED / RELEASED（与 {@code pay_auth.status} 同一套取值） */
    record AuthResult(String authNo, String authRef, String status, BigDecimal freezeAmount,
                      BigDecimal capturedAmount, String failReason) {
    }

    record RefundRequest(String refundNo, String payNo, String txnRef, BigDecimal amount,
                         String reason, String idempotencyKey) {
    }

    /** @param status INIT / SUCCESS / FAILED（与 {@code pay_refund.status} 同一套取值） */
    record RefundResult(String refundNo, String refundRef, String status, BigDecimal amount, String failReason) {
    }
}
