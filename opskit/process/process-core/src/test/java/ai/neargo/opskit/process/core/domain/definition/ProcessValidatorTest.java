package ai.neargo.opskit.process.core.domain.definition;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.condition.Op;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.ProcessValidator;
import ai.neargo.opskit.process.api.definition.StatusCategory;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 21/07 O2-1：每条校验一个负用例；多处错误一次全部返回。 */
class ProcessValidatorTest {

    private static final ProcessDef BASE = SampleProcesses.contractApproval();

    private static List<String> errors(ProcessDef d) {
        return ProcessValidator.validate(d).stream().map(e -> e.path() + " " + e.error()).toList();
    }

    private static ProcessDef statuses(UnaryOperator<List<StatusDef>> f) {
        return new ProcessDef(BASE.code(), BASE.name(), BASE.initialStatus(), f.apply(new ArrayList<>(BASE.statuses())), BASE.transitions(), BASE.participantRoles());
    }

    private static ProcessDef transitions(UnaryOperator<List<TransitionDef>> f) {
        return new ProcessDef(BASE.code(), BASE.name(), BASE.initialStatus(), BASE.statuses(), f.apply(new ArrayList<>(BASE.transitions())), BASE.participantRoles());
    }

    private static TransitionDef tr(String code, Set<String> from, String to, boolean system) {
        return new TransitionDef(code, SampleProcesses.t(code), from, to, null, null, null, null, system);
    }

    @Test
    void theSampleIsValid() {
        assertThat(ProcessValidator.validate(BASE)).isEmpty();
    }

    @Test
    void initialStatusMustExistAndNotBeTerminal() {
        assertThat(errors(new ProcessDef(BASE.code(), BASE.name(), "nope", BASE.statuses(), BASE.transitions(), Set.of())))
                .contains("initialStatus opskit.process.def.unknown_status");
        assertThat(errors(new ProcessDef(BASE.code(), BASE.name(), "active", BASE.statuses(), BASE.transitions(), Set.of())))
                .contains("initialStatus opskit.process.def.initial_is_terminal");
    }

    @Test
    void statusCodesAreUnique() {
        assertThat(errors(statuses(l -> {
            l.add(new StatusDef("draft", SampleProcesses.t("重复"), StatusCategory.NEW, null));
            return l;
        }))).anyMatch(e -> e.endsWith("opskit.process.def.duplicate_status"));
    }

    @Test
    void terminalStatusesHaveNoExit() {
        assertThat(errors(transitions(l -> {
            l.add(tr("reopen", Set.of("active"), "draft", false));
            return l;
        }))).anyMatch(e -> e.endsWith("opskit.process.def.terminal_has_exit"));
    }

    @Test
    void transitionsReferenceExistingStatuses() {
        assertThat(errors(transitions(l -> {
            l.add(tr("x", Set.of("draft"), "limbo", false));
            l.add(tr("y", Set.of("ghost"), "draft", false));
            return l;
        }))).contains("transitions[8].to opskit.process.def.unknown_status", "transitions[9].from opskit.process.def.unknown_status");
    }

    @Test
    void sameCodeFromTheSameStatusIsAmbiguous() {
        assertThat(errors(transitions(l -> {
            l.add(tr("submit", Set.of("draft"), "rejected", false));
            return l;
        }))).anyMatch(e -> e.endsWith("opskit.process.def.duplicate_transition"));
    }

    @Test
    void gateExitsMustBeSystemTransitionsLeavingThatStatus() {
        // 把门的出口「approved」改成非系统迁移 → 门可能被人手动绕过
        List<String> notSystem = errors(transitions(l -> {
            l.set(1, tr("approved", Set.of("reviewing"), "activating", false));
            return l;
        }));
        assertThat(notSystem).anyMatch(e -> e.contains("gate_exit_not_system_from_here:approved"));
        // 出口迁移不存在
        List<String> missing = errors(transitions(l -> {
            l.remove(4);
            return l;
        }));
        assertThat(missing).anyMatch(e -> e.contains("gate_exit_missing:confirmed"));
    }

    @Test
    void unusedSystemTransitionsAreFlagged() {
        assertThat(errors(transitions(l -> {
            l.add(tr("ghostExit", Set.of("draft"), "rejected", true));
            return l;
        }))).anyMatch(e -> e.endsWith("opskit.process.def.system_transition_unused"));
    }

    @Test
    void countersignNeedsEnumerableApprovers() {
        ProcessDef d = statuses(l -> {
            GateDef.Approval g = (GateDef.Approval) l.get(1).gate();
            GateDef.Approval byRole = new GateDef.Approval(List.of(new ApprovalStepDef("finance", SampleProcesses.t("财务会签"), null,
                    new ApproverRef(ApproverKind.ROLE, List.of("finance")), ApprovalMode.ALL, null)), g.rules(), g.onApproved(), g.onRejected(), g.onWithdrawn());
            l.set(1, new StatusDef("reviewing", l.get(1).name(), StatusCategory.IN_PROGRESS, byRole));
            return l;
        });
        assertThat(errors(d)).anyMatch(e -> e.endsWith("opskit.process.def.all_needs_enumerable_approvers"));
    }

    @Test
    void conditionsAreAtMostTwoLevelsDeep() {
        Condition deep = new Condition.All(List.of(new Condition.Any(List.of(new Condition.All(List.of(new Condition.Compare("a", Op.EQ, 1)))))));
        assertThat(errors(transitions(l -> {
            TransitionDef s = l.get(0);
            l.set(0, new TransitionDef(s.code(), s.name(), s.from(), s.to(), s.who(), deep, s.requires(), s.actions(), s.system()));
            return l;
        }))).contains("transitions[0].when opskit.process.def.condition_too_deep");
    }

    @Test
    void participantRolesMustBeBuiltinOrDeclared() {
        assertThat(errors(transitions(l -> {
            TransitionDef s = l.get(0);
            l.set(0, new TransitionDef(s.code(), s.name(), s.from(), s.to(), new Who(Set.of("TECH_LEAD"), null, null, null), null, null, null, false));
            return l;
        }))).anyMatch(e -> e.endsWith("unknown_participant_role"));
        ProcessDef declared = new ProcessDef(BASE.code(), BASE.name(), BASE.initialStatus(), BASE.statuses(), transitions(l -> {
            TransitionDef s = l.get(0);
            l.set(0, new TransitionDef(s.code(), s.name(), s.from(), s.to(), new Who(Set.of("TECH_LEAD"), null, null, null), null, null, null, false));
            return l;
        }).transitions(), Set.of("TECH_LEAD"));
        assertThat(ProcessValidator.validate(declared)).isEmpty();
    }

    @Test
    void unreachableStatusesAndMissingTerminalAreFlagged() {
        assertThat(errors(statuses(l -> {
            l.add(new StatusDef("orphan", SampleProcesses.t("孤儿"), StatusCategory.IN_PROGRESS, null));
            return l;
        }))).contains("statuses[5] opskit.process.def.unreachable");
    }

    @Test
    void reportsEveryProblemAtOnce() {
        ProcessDef broken = new ProcessDef("bad code!", BASE.name(), "nope", BASE.statuses(), transitions(l -> {
            l.add(tr("x", Set.of("draft"), "limbo", false));
            return l;
        }).transitions(), Set.of());
        List<FieldError> all = ProcessValidator.validate(broken);
        assertThat(all).extracting(FieldError::path).contains("code", "initialStatus", "transitions[8].to");
    }
}
