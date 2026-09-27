package ai.neargo.opskit.process.core.domain.definition;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalRules;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.Requires;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 测试用的合法流程：合同审批（两级审批门，第二步条件会签；外部确认门做业务前置校验）。 */
public final class SampleProcesses {

    private SampleProcesses() {
    }

    static LocalizedText t(String zh) {
        return LocalizedText.of("zh", zh);
    }

    public static ProcessDef contractApproval() {
        GateDef.Approval approval = new GateDef.Approval(List.of(
                new ApprovalStepDef("ops", t("运营审批"), null, new ApproverRef(ApproverKind.ROLE, List.of("contract-approver")), ApprovalMode.ANY, Duration.ofHours(24)),
                new ApprovalStepDef("finance", t("财务会签"), new Condition.Compare("needsCosign", Op.EQ, true),
                        new ApproverRef(ApproverKind.GROUP, List.of("finance")), ApprovalMode.ALL, null)),
                ApprovalRules.DEFAULT, "approved", "rejected", "withdrawn");
        GateDef.External confirm = new GateDef.External("contract.activate", Duration.ofHours(24), "confirmed", "confirmRejected", "confirmTimeout");
        return new ProcessDef("contract_approval", t("合同审批"), "draft",
                List.of(new StatusDef("draft", t("草稿"), StatusCategory.NEW, null),
                        new StatusDef("reviewing", t("审批中"), StatusCategory.IN_PROGRESS, approval),
                        new StatusDef("activating", t("生效确认中"), StatusCategory.WAITING, confirm),
                        new StatusDef("active", t("已生效"), StatusCategory.DONE, null),
                        new StatusDef("rejected", t("已驳回"), StatusCategory.CANCELLED, null)),
                List.of(new TransitionDef("submit", t("提交"), Set.of("draft"), "reviewing", new Who(Set.of("REQUESTER"), null, null, null), null, null, null, false),
                        new TransitionDef("approved", t("通过"), Set.of("reviewing"), "activating", null, null, null, null, true),
                        new TransitionDef("rejected", t("驳回"), Set.of("reviewing"), "rejected", null, null, null, null, true),
                        new TransitionDef("withdrawn", t("撤回"), Set.of("reviewing"), "draft", null, null, null, null, true),
                        new TransitionDef("confirmed", t("确认生效"), Set.of("activating"), "active", null, null, null, null, true),
                        new TransitionDef("confirmRejected", t("确认驳回"), Set.of("activating"), "rejected", null, null, null, null, true),
                        new TransitionDef("confirmTimeout", t("确认超时"), Set.of("activating"), "rejected", null, null, null, null, true),
                        new TransitionDef("cancel", t("作废"), Set.of("*"), "rejected", new Who(Set.of("REQUESTER"), null, null, null), null,
                                new Requires(Set.of(), true), null, false)),
                Set.of());
    }
}
