package ai.neargo.sharehub.trade.pay.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 渠道退款引用（pay_refund，[db-design §5.3]）。
 *
 * <p><b>与 {@code ord_refund} 的分工</b>（v2 明确）：{@code ord_refund} 是**业务审批单**
 * （客服申请 → 财务审批，带幂等键与驳回原因，归 trade/order 分片）；本表是**渠道退款引用**
 * （nearpay 侧执行凭证）。**审批通过才生成本表记录，1:1 关联** {@link #ordRefundNo}。
 *
 * <p>状态 INIT / SUCCESS / FAILED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("pay_refund")
public class PayRefund extends BaseEntity {

    private String refundNo;

    /** 被退的支付单。 */
    private String payNo;

    /** 对应的业务审批单 {@code ord_refund.refund_no}（v1 DDL 未建此列，见交付报告）。 */
    private String ordRefundNo;

    private BigDecimal amount;

    private String reason;

    /** INIT / SUCCESS / FAILED。 */
    private String status;

    /** nearpay 侧退款引用。 */
    private String nearpayRefundNo;
}
