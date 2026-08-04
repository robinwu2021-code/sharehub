package ai.neargo.sharehub.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单人工干预留痕（{@code ord_intervention}，append-only）。
 *
 * <p><b>不继承 {@code BaseEntity}</b>：append 表没有更新与软删 —— 挂上 {@code version}/{@code deleted}
 * 会诱导有人去 UPDATE 它。审计记录一旦可改就不再是审计记录。
 */
@Data
@TableName("ord_intervention")
public class OrdIntervention {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String interventionNo;
    private String tenantId;
    private String orderNo;

    /** eject / force_return / waive / compensate / refund_apply（与前端 {@code OrderInterventionAction} 同名同值）。 */
    private String action;

    private String operator;

    /** 干预原因，必填。 */
    private String reason;

    /** 涉及金额；{@code eject} 无金额故可空。 */
    private BigDecimal amount;
    private String currency;

    private String beforeStatus;

    /** 与 {@link #beforeStatus} 相同 = 只留痕不改状态（waive/compensate/refund_apply）。 */
    private String afterStatus;

    private LocalDateTime createdAt;
    private String createdBy;
}
