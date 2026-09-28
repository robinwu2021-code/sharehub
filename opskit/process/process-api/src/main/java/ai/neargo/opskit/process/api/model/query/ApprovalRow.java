package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.model.SubjectRef;

/**
 * 审批行（21/06 §七 ApprovalRow）。
 *
 * @param stepStartedAtMs 当前步开始时刻（待审时「已等待」= 现在 − 它）
 * @param finishedAtMs    办结时刻；列表「耗时」= finishedAtMs − requestedAtMs
 */
public record ApprovalRow(String approvalNo, String instanceNo, String processCode, LocalizedText processName, SubjectRef subject, String status,
        String currentStep, LocalizedText currentStepName, int round, String requestedBy, long requestedAtMs, Long finishedAtMs,
        Long stepStartedAtMs, Long stepDueAtMs) {
}
