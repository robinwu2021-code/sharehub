package ai.neargo.opskit.process.api;

import ai.neargo.opskit.commons.error.ErrorKey;

/** 流程模块错误键（21/06 §九）。通用的在 CommonErrors。 */
public final class ProcessErrors {

    public static final ErrorKey ILLEGAL_TRANSITION = new ErrorKey("opskit.process.illegal_transition", 422);
    public static final ErrorKey NOT_ALLOWED = new ErrorKey("opskit.process.not_allowed", 403);
    public static final ErrorKey CONDITION_UNMET = new ErrorKey("opskit.process.condition_unmet", 422);
    public static final ErrorKey REQUIRED_MISSING = new ErrorKey("opskit.process.required_missing", 422);
    public static final ErrorKey DEFINITION_DISABLED = new ErrorKey("opskit.definition.disabled", 422);
    public static final ErrorKey INSTANCE_CLOSED = new ErrorKey("opskit.process.instance_closed", 422);
    public static final ErrorKey SELF_REVIEW = new ErrorKey("opskit.approval.self_review", 403);
    public static final ErrorKey CROSS_STEP = new ErrorKey("opskit.approval.cross_step", 403);
    public static final ErrorKey WITHDRAW_FORBIDDEN = new ErrorKey("opskit.approval.withdraw_forbidden", 403);
    public static final ErrorKey REASON_REQUIRED = new ErrorKey("opskit.approval.reason_required", 422);
    public static final ErrorKey NOT_CANDIDATE = new ErrorKey("opskit.task.not_candidate", 403);
    public static final ErrorKey NO_APPROVER = new ErrorKey("opskit.approval.no_approver", 422);

    private ProcessErrors() {
    }
}
