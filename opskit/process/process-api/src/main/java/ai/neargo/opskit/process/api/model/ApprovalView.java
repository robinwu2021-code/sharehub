package ai.neargo.opskit.process.api.model;

import java.util.List;

/**
 * 审批详情（21/06 §七 的服务端形状）。{@code mine.whyNot} 由服务按与「决定」完全相同的检查得出，前端不自己判断。
 */
public record ApprovalView(String approvalNo, String instanceNo, String gateStatus, String status, int currentRound, String currentStep,
        String requestedBy, long requestedAtMs, Long finishedAtMs, List<RoundView> rounds, Mine mine) {

    public record RoundView(int round, String result, String submittedBy, long submittedAtMs, Long finishedAtMs, List<StepView> steps) {
    }

    /** @param state DONE / CURRENT / TODO / SKIPPED */
    public record StepView(String code, String state, List<Decision> decisions) {
    }

    public record Decision(String taskNo, String status, String decidedBy, String decision, String reason, Long atMs) {
    }

    /** @param whyNot 不能决定的原因（错误键）；能决定时为空 */
    public record Mine(String taskNo, boolean canDecide, String whyNot, boolean canWithdraw) {
    }
}
