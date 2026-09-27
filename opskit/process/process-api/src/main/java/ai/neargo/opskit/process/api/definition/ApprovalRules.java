package ai.neargo.opskit.process.api.definition;

/**
 * 可配置的两条内建规则（其余三条不可关：驳回必填原因 · 只有发起人能撤回 · 并发只一人赢）。
 *
 * @param selfApproval       是否允许审批自己发起的（缺省不允许）
 * @param crossStepExclusive 同一轮同一人不能审两步（缺省开启）
 */
public record ApprovalRules(boolean selfApproval, boolean crossStepExclusive) {
    public static final ApprovalRules DEFAULT = new ApprovalRules(false, true);
}
