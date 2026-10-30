package ai.neargo.sharehub.portal.approval;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 待审批的资金操作（{@code ops_approval_request}，V189，SEC-03）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ops_approval_request")
public class OpsApprovalRequest extends BaseEntity {
    private String requestNo;
    /** ORDER_COMPENSATE / ORDER_WAIVE / LEDGER_VOUCHER。 */
    private String kind;
    private String refNo;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private String payload;
    /** PENDING / APPROVED / REJECTED / FAILED。 */
    private String status;
    private String applicantNo;
    private String applicantName;
    private String approverNo;
    private String approverName;
    private LocalDateTime decidedAt;
    private String decideNote;
    private String resultRef;
}
