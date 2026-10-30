package ai.neargo.sharehub.inv;

/** 仅业务送审／执行状态；审批步骤、决定与历史由OpsKit管理。 */
public enum CountReviewState {
    DRAFT, PREPARING, SUBMITTING, IN_REVIEW, SUBMIT_FAILED, DECIDED, APPLY_FAILED, APPLIED
}
