package ai.neargo.sharehub.api.platform.flow;

/**
 * 业务侧接收 OpsKit 审批进展的 SPI（M5，方案 23 §三）。每个送审的业务实现一个，按 {@link #bizType()} 路由。
 *
 * <p>调用方是 app 层（送审消费者、webhook 接收器）。实现方<b>复用既有服务方法推进业务行</b>，
 * 业务规则一处不改；审批人取回传的宿主编号，而不是当前登录人（回写时没有登录人）。
 *
 * <p>三个回调都可能被重复调用（至少一次投递）：实现必须幂等 —— 业务行已前进就什么都不做。
 */
public interface ApprovalOutcomeHandler {

    /** 与 {@link ApprovalRequestedEvent#bizType()} 相同 */
    String bizType();

    /** OpsKit 已建单：回填审批单号，业务页据此显示「审批中」与「去审批」链接 */
    void onSubmitted(String bizNo, int round, String instanceNo, String approvalNo);

    /** 送审重投达上限（死信）：业务页显示「送审失败」与「重新送审」 */
    void onSubmitFailed(String bizNo, int round, String error);

    /** 一轮审批结束 */
    void onCompleted(String bizNo, ApprovalDecision decision);

    /**
     * @param result      APPROVED / REJECTED / WITHDRAWN
     * @param reason      驳回原因（驳回时 OpsKit 强制必填）
     * @param deciderNo   决定人宿主编号（OpsKit 成员的 externalRef）；撤回时是提交人
     * @param deciderName 决定人显示名
     * @param approvalNo  OpsKit 审批单号
     */
    record ApprovalDecision(String result, String reason, String deciderNo, String deciderName, String approvalNo) {
        public boolean approved() {
            return "APPROVED".equals(result);
        }
    }
}
