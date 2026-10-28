package ai.neargo.sharehub.finance.entity;

import ai.neargo.sharehub.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 付款任务（stl_payout_task，V158；需求方案 §八）—— 平台向商家付款的一个批次。
 *
 * <p>状态 {@link ai.neargo.sharehub.finance.PayoutTaskStatus}：待复核 → 待转账 → 已完成；复核驳回 → 已撤回。
 * 发起人见 BaseEntity.createdBy；复核人不得与发起人相同（四眼）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stl_payout_task")
public class StlPayoutTask extends BaseEntity {
    private String taskNo;
    private LocalDate cutoffDate;
    private String currency;
    private Integer itemCount;
    private BigDecimal totalAmount;
    private String status;
    private String createdName;
    private String reviewedBy;
    private String reviewedName;
    private LocalDateTime reviewedAt;
    private String rejectReason;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String bankBatchRef;
    private LocalDate transferDate;
    private Integer exportCount;
}
