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

    private ProcessErrors() {
    }
}
