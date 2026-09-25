package ai.neargo.sharehub.loc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** 进场合同实体（loc_contract，场地方×站点）。镜像 Contract + 审计列。 */
@Data
@TableName("loc_contract")
public class LocContract {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractNo;
    private String tenantId;
    /** 场地方编号。与 {@link LocSite#getVenueNo()} 一样，列早就有、实体此前漏映射。 */
    private String venueNo;

    /** 站点编号 —— 合同是**按站点**签的，这是「站点级分成比例」的唯一存放处。 */
    private String siteNo;

    private String venueName;
    private String siteName;
    private Double shareRate;
    private Double entryFee;
    private String startAt;
    private String endAt;
    /** [db-design §1.5]：带金额语义的表一律有币种。 */
    private String currency;

    private String status;

    // —— 2026-09-25 合同走审批（V95）——
    private String shareMode;
    private String shareBase;
    private java.math.BigDecimal guaranteeAmount;
    private String settlePeriod;
    private java.math.BigDecimal depositAmount;
    private String depositTerms;
    private Boolean exclusiveFlag;
    private Integer deviceQuota;
    private String placementNote;
    private Boolean autoRenew;
    private String signerName;
    private java.time.LocalDate signedAt;
    private String submittedBy;
    private LocalDateTime submittedAt;
    private String auditedBy;
    private LocalDateTime auditedAt;
    private String auditNote;
    private LocalDateTime activatedAt;
    private LocalDateTime endedAt;
    private String endReason;
    private String prevContractNo;
    private String sourceLeadNo;
    private String remark;

    // —— 批次 B（V104）：补充协议 / 财务会签 / 终止审批 ——
    /** MAIN / SUPPLEMENT（{@link ai.neargo.sharehub.loc.ContractKind}）。 */
    private String contractKind;
    private String parentContractNo;
    /** 仅 PENDING 时有值：OPS / FINANCE（{@link ai.neargo.sharehub.loc.ContractAuditStage}）。 */
    private String auditStage;
    private String financeAuditedBy;
    private LocalDateTime financeAuditedAt;
    private String financeAuditNote;
    /** 提前终止申请：PENDING / APPROVED / REJECTED（{@link ai.neargo.sharehub.loc.ContractTermReqStatus}）。 */
    private String termReqStatus;
    private String termReqReason;
    private java.time.LocalDate termReqEffectiveAt;
    private String termReqBy;
    private LocalDateTime termReqAt;
    private String termAuditBy;
    private LocalDateTime termAuditAt;
    private String termAuditNote;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Long version;
    @TableLogic
    private Integer deleted;
}
