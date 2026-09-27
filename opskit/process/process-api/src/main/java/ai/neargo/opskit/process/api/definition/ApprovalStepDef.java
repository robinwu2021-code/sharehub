package ai.neargo.opskit.process.api.definition;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.time.Duration;
import java.util.Objects;

/** @param when 进入条件，不满足则本轮跳过此步；null = 总是进入 */
public record ApprovalStepDef(String code, LocalizedText name, Condition when, ApproverRef approvers, ApprovalMode mode, Duration due) {
    public ApprovalStepDef {
        Objects.requireNonNull(approvers, "approvers");
        mode = mode == null ? ApprovalMode.ANY : mode;
    }
}
