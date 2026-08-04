package ai.neargo.sharehub.trade.pay.service;

import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayAuthEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayOrderEntry;
import ai.neargo.sharehub.trade.pay.dto.PayDtos.PayRefundEntry;

import java.math.BigDecimal;

/**
 * 支付编排（pay_order / pay_auth / pay_refund 三张引用表的写入口）。
 *
 * <p>有状态机 → **手写实现**，不走通用 CRUD。所有出站调用经 {@code PaymentPort}
 * （ADR-005，MVP 绑 Stub），本服务只负责：建引用行 → 调端口 → 用返回结果镜像状态。
 *
 * <p>状态取值与 [db-design §5.3] 一致：
 * {@code pay_order} INIT/PAYING/PAID/FAILED/CLOSED ·
 * {@code pay_auth} FROZEN/CAPTURED/RELEASED · {@code pay_refund} INIT/SUCCESS/FAILED。
 * 非法迁移一律抛异常（由全局异常处理转 409），**不要**在别处直接 update 状态列。
 *
 * <p>本服务不含运营端只读列表 —— 支付引用在订单详情页内展示，由 trade/order 分片的详情接口聚合。
 */
public interface PaymentService {

    /** 发起支付：建 {@code pay_order} → {@code PaymentPort#pay} → 镜像状态。 */
    PayOrderEntry pay(String orderNo, String cUserNo, String type, BigDecimal amount,
                      String currency, String channelCode, String idempotencyKey);

    /** 预授权冻结（免押）：建 {@code pay_auth} → {@code PaymentPort#preAuth}。 */
    PayAuthEntry preAuth(String orderNo, String cUserNo, BigDecimal freezeAmount,
                         String currency, String channelCode, String idempotencyKey);

    /**
     * 请款：把冻结额的一部分/全部扣走。
     *
     * @throws IllegalStateException    非 FROZEN 态
     * @throws IllegalArgumentException 金额超过冻结额
     */
    PayAuthEntry capture(String authNo, BigDecimal amount, String idempotencyKey);

    /** 释放冻结。仅 FROZEN 态可释放。 */
    PayAuthEntry release(String authNo, String idempotencyKey);

    /**
     * 渠道退款：**必须由已通过审批的 {@code ord_refund} 触发**，1:1 关联。
     *
     * @param ordRefundNo 业务审批单号，为空则拒绝（防止绕过审批链直接退款）
     */
    PayRefundEntry refund(String payNo, String ordRefundNo, BigDecimal amount,
                          String reason, String idempotencyKey);

    /**
     * 回调状态镜像：把 nearpay 事件落到 {@code pay_order}。
     *
     * <p>调用方需先过 {@link PayEventLogService#alreadyProcessed} 幂等闸。
     */
    void mirrorPayStatus(String txnRef, String status, String paidAt);

    PayOrderEntry getPay(String payNo);

    PayAuthEntry getAuth(String authNo);
}
