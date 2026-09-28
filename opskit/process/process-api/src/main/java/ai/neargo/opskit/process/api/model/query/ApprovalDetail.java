package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.process.api.definition.ApprovalRules;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import java.util.List;

/** 审批详情要的定义侧信息：列表行 + 该门的步骤定义（名称 · 审批人 · 条件）+ 规则。运行时状态另取 ApprovalView。 */
public record ApprovalDetail(ApprovalRow row, List<ApprovalStepDef> steps, ApprovalRules rules) {
}
