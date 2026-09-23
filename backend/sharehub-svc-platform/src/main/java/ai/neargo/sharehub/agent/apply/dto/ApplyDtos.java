package ai.neargo.sharehub.agent.apply.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 入驻申请的出入参。 */
public final class ApplyDtos {

    private ApplyDtos() {
    }

    /**
     * 提交申请 —— **自助与代建共用同一个入参**（ADR-030 §3.1「条件相同」）。
     *
     * <p>⚠️ 这里<b>没有</b> {@code source} 字段，是有意的：来源由服务端按有无 STAFF 令牌判定。
     * 放开让客户端传的话，自助申请可以自称代建，绕开 OTP 与限流。
     *
     * @param otp 自助提交必填（证明持有该手机号）；代建由经办员工的令牌背书，可不传
     */
    public record SubmitReq(String phone, String email, String otp,
                            String operatorName, String operatorType,
                            String regionScope, BigDecimal shareRate, String payload) {
    }

    /** 申请人视角：只看得到掩码与状态，看不到内部字段。 */
    public record MyApplyView(String applyNo, String status, String operatorName,
                              String phoneMask, String emailMask,
                              String rejectReason, LocalDateTime submittedAt) {
    }

    /** 运营端视角。 */
    public record ApplyView(String applyNo, String source, String status,
                            String operatorName, String operatorType,
                            String phoneMask, String emailMask,
                            String regionScope, BigDecimal shareRate, String payload,
                            String principalNo, String rejectReason,
                            String submittedBy, LocalDateTime submittedAt,
                            String reviewedBy, LocalDateTime reviewedAt,
                            String operatorNo,
                            boolean phoneAlreadyKnown, String knownEmailMask) {
    }

    /**
     * 审核。
     *
     * @param approve      true=通过；false=驳回
     * @param rejectReason 驳回必填，**原样回显给申请人**
     * @param shareRate    通过时可改写申请人填的比例（审核时定，不是事后补）
     */
    public record AuditReq(boolean approve, String rejectReason,
                           BigDecimal shareRate, String regionScope) {
    }

    /** 提交/审核后的回执。 */
    public record ApplyResult(String applyNo, String status, String operatorNo) {
    }
}
