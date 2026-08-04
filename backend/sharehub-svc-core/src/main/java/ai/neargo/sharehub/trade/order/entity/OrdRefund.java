package ai.neargo.sharehub.trade.order.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 退款审批单 —— **业务侧聚合根**（{@code ord_refund}，[db-design §5.1]）。
 *
 * <p><b>与 {@code pay_refund} 严格分工</b>（v2 明确，v1 混为一谈）：
 * 本表是「客服申请 → 财务审批」的业务单，带 {@link #idempotencyKey} 与 {@link #rejectReason}；
 * {@code pay_refund} 是渠道（nearpay）侧的执行凭证。**审批通过才生成 {@code pay_refund}，1:1 关联**，
 * 由 {@link #payRefundNo} 回指。所以这里没有渠道状态机，只有审批状态机。
 *
 * <p>{@link #idempotencyKey} 是 UNIQUE（[db-design §1.6]），申请时生成，用来挡住
 * 「同一笔订单被点两次退款」——这是资金操作的最后一道栅栏，不能靠前端按钮置灰。
 *
 * <p>主键/租户/审计列见 {@link BaseEntity}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_refund")
public class OrdRefund extends BaseEntity {

    /** 业务键 UK，前缀 {@code RFD}。 */
    private String refundNo;

    private String regionId;

    private String orderNo;

    private String cUserNo;

    private BigDecimal amount;

    private String currency;

    private String reason;

    /** 申请人快照名（不回溯），服务端按当前登录人回填。 */
    private String applicantName;

    private String appliedAt;

    /** PENDING / APPROVED / REJECTED / EXECUTED / FAILED */
    private String status;

    /** 审批人快照名，服务端回填 —— **不接受前端传入的审批人**。 */
    private String auditorName;

    /** 空 = 尚未审批。 */
    private String auditedAt;

    /** 驳回**必填**（资金审批合规）。 */
    private String rejectReason;

    /** 幂等键 UNIQUE，申请时生成，防重复退款。 */
    private String idempotencyKey;

    /** PSP 交易号（冗余便于对账）。注意前端 VO 里叫 {@code psgTxnNo}，见交付报告。 */
    private String pspTxnNo;

    /** → {@code pay_refund.refund_no}（渠道执行凭证），审批通过后才有值。 */
    private String payRefundNo;
}
