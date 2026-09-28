package ai.neargo.sharehub.finance;

/**
 * 提现「审批这件事」走到哪（M5，方案 23 §9.2）。与提现状态机 {@link WithdrawalStatus} <b>正交</b>：
 * 后者说钱走到哪，本枚举只说审批在不在 OpsKit、卡在哪。LOCAL 模式下为空（原样）。
 */
public enum WithdrawalApprovalState {
    /** 已申请，送审事件在途 */
    SUBMITTING,
    /** OpsKit 审批中（approval_no 已回填） */
    IN_REVIEW,
    /** 送审重投达上限（死信）—— 可「重新送审」 */
    SUBMIT_FAILED,
    /** OpsKit 已通过，但 ShareHub 的业务检查不让过（无收款账户 / 代理停用）—— 补齐后「重试生效」 */
    APPROVED_BLOCKED
}
