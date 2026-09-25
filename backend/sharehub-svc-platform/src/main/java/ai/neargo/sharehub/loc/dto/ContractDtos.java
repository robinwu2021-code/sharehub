package ai.neargo.sharehub.loc.dto;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 合同审批（TDD-运营核心流程/02）的请求与响应。 */
public final class ContractDtos {

    private ContractDtos() {
    }

    /** 新建 / 编辑草稿。无 status、无审批字段 —— 状态只经动作接口改；场地方 / 站点名称由服务端取。 */
    public record ContractReq(String venueNo, String siteNo,
                              String shareMode, String shareBase,
                              BigDecimal shareRate, BigDecimal guaranteeAmount, BigDecimal entryFee,
                              String currency, String settlePeriod,
                              LocalDate startAt, LocalDate endAt, Boolean autoRenew,
                              Boolean exclusive, Integer deviceQuota, String placementNote,
                              BigDecimal depositAmount, String depositTerms, String signerName, String remark) {
    }

    public record NoteReq(String note) {
    }

    /** 审批统一走 /audit（api/README §1.5）：REJECT 时 reason 必填，审批人由服务端回填。 */
    public record AuditReq(String result, String reason) {
    }

    public record SignReq(LocalDate signedAt, List<String> fileNos) {
    }

    public record TerminateReq(String reason, LocalDate effectiveAt) {
    }

    /** 补充协议：只定生效日（缺省明天），条款在生成的草稿上改，到期日跟原合同。 */
    public record SupplementReq(LocalDate startAt) {
    }

    public record AttachReq(List<String> fileNos) {
    }

    /** 条款（除比例、进场费、期限外的约定）。 */
    public record ContractTerms(String shareMode, String shareBase, BigDecimal guaranteeAmount, String currency,
                                String settlePeriod, BigDecimal depositAmount, String depositTerms, Boolean exclusive,
                                Integer deviceQuota, String placementNote, Boolean autoRenew, String signerName,
                                String remark) {
    }

    /** 流程信息：谁在什么时候推进到了哪一步。 */
    public record ContractFlow(LocalDate signedAt, String submittedBy, LocalDateTime submittedAt, String auditedBy,
                               LocalDateTime auditedAt, String auditNote, LocalDateTime activatedAt,
                               LocalDateTime endedAt, String endReason, String prevContractNo, String sourceLeadNo,
                               String contractKind, String parentContractNo, String auditStage,
                               String financeAuditedBy, LocalDateTime financeAuditedAt, String financeAuditNote,
                               TerminationRequest termination) {
    }

    /** 提前终止申请（裁决 #4）。审批期间合同照常生效；获批后到 effectiveAt 由定时任务终止。 */
    public record TerminationRequest(String status, String reason, LocalDate effectiveAt, String requestedBy,
                                     LocalDateTime requestedAt, String auditedBy, LocalDateTime auditedAt,
                                     String auditNote) {
    }

    public record ContractLogItem(String event, String fromStatus, String toStatus, String operator, String note,
                                  LocalDateTime at) {
    }

    /** 摘要条：待我审批（运营环节）· 待财务会签 · 终止待审批 · 60 天内到期 · 已到期未续 · 缺签署件。 */
    public record ContractSummary(long pendingMine, long pendingCosign, long terminationPending, long expiring60,
                                  long expiredNotRenewed, long missingScan) {
    }

    public record ContractTickResult(int activated, int expired, int terminated) {
    }
}
