package ai.neargo.opskit.process.store;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.definition.ActionDef;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProcessDefCodecTest {

    static ProcessDef everyFeature(Set<String> from) {
        LocalizedText n = new LocalizedText(Map.of("zh", "测试", "en", "Test"));
        GateDef.Approval ap = new GateDef.Approval(List.of(new ApprovalStepDef("s1", n,
                new Condition.All(List.of(new Condition.Compare("amount", Op.GTE, 1000), new Condition.Any(List.of(
                        new Condition.Compare("vip", Op.EQ, true), new Condition.Compare("tier", Op.IN, List.of("A", "B")))))),
                new ApproverRef(ApproverKind.MEMBER, List.of("mb_z", "mb_a")), ApprovalMode.ALL, Duration.ofHours(4))),
                new ApprovalRules(true, false), "ok", "no", null);
        return new ProcessDef("p", n, "a", List.of(
                new StatusDef("a", n, StatusCategory.NEW, null),
                new StatusDef("b", n, StatusCategory.IN_PROGRESS, ap),
                new StatusDef("c", n, StatusCategory.WAITING, new GateDef.External("topic", Duration.ofMinutes(30), "conf", "rej", "tmo")),
                new StatusDef("d", n, StatusCategory.WAITING, new GateDef.Timer(Duration.ofDays(7), "auto")),
                new StatusDef("z", n, StatusCategory.DONE, null)),
                List.of(new TransitionDef("go", n, from, "b", new Who(Set.of("REQUESTER"), Set.of("r"), Set.of("g"), Set.of("p:x")),
                        new Condition.Compare("x", Op.EXISTS, null), new Requires(Set.of("reason"), true),
                        List.of(new ActionDef.AssignTo(new ApproverRef(ApproverKind.GROUP, List.of("ops"))), new ActionDef.SetField("k", 3),
                                new ActionDef.AddParticipant("WATCHER", "watchers"), new ActionDef.ClearAssignee()), false)),
                Set.of("TECH_LEAD", "QA"));
    }

    @Test
    void roundTripsWithoutLoss() {
        ProcessDef d = everyFeature(Set.of("a"));
        assertThat(ProcessDefCodec.fromJson(ProcessDefCodec.toCanonicalJson(d))).isEqualTo(d);
    }

    @Test
    void canonicalFormIgnoresSetOrderButKeepsListOrder() {
        Set<String> ab = new LinkedHashSet<>(List.of("a", "c"));
        Set<String> ba = new LinkedHashSet<>(List.of("c", "a"));
        assertThat(ProcessDefCodec.toCanonicalJson(everyFeature(ab))).isEqualTo(ProcessDefCodec.toCanonicalJson(everyFeature(ba)));
        // 审批人列表是 List：顺序有意义，读回后保持 mb_z 在前
        ApprovalStepDef step = ((GateDef.Approval) ProcessDefCodec.fromJson(ProcessDefCodec.toCanonicalJson(everyFeature(ab)))
                .statuses().get(1).gate()).steps().get(0);
        assertThat(step.approvers().values()).containsExactly("mb_z", "mb_a");
    }
}
