package ai.neargo.opskit.process.core.domain.definition;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.process.api.definition.ActionDef;
import ai.neargo.opskit.process.api.definition.ApprovalMode;
import ai.neargo.opskit.process.api.definition.ApprovalStepDef;
import ai.neargo.opskit.process.api.definition.ApproverKind;
import ai.neargo.opskit.process.api.definition.ApproverRef;
import ai.neargo.opskit.process.api.definition.GateDef;
import ai.neargo.opskit.process.api.definition.ProcessDef;
import ai.neargo.opskit.process.api.definition.StatusDef;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 流程定义校验（21/02 §6.3）。**一次返回全部错误**（不是遇错即停）：控制台据此把每条错误挂到对应的输入框上。
 * 纯领域逻辑，不做 IO；角色 / 组是否存在属于目录数据，发布时由应用层另查。
 */
public final class ProcessValidator {

    private static final Pattern CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private ProcessValidator() {
    }

    public static List<FieldError> validate(ProcessDef def) {
        List<FieldError> errors = new ArrayList<>();
        code(def.code(), "code", errors);

        // ── 状态 ──
        Map<String, StatusDef> statuses = new LinkedHashMap<>();
        for (int i = 0; i < def.statuses().size(); i++) {
            StatusDef s = def.statuses().get(i);
            String path = "statuses[" + i + "]";
            code(s.code(), path + ".code", errors);
            if (s.code() != null && statuses.putIfAbsent(s.code(), s) != null) {
                errors.add(new FieldError(path + ".code", "opskit.process.def.duplicate_status"));
            }
        }
        if (statuses.isEmpty()) {
            errors.add(new FieldError("statuses", "opskit.process.def.no_status"));
        }
        if (def.initialStatus() == null || !statuses.containsKey(def.initialStatus())) {
            errors.add(new FieldError("initialStatus", "opskit.process.def.unknown_status"));
        } else if (statuses.get(def.initialStatus()).category().isTerminal()) {
            errors.add(new FieldError("initialStatus", "opskit.process.def.initial_is_terminal"));
        }

        // ── 迁移 ──
        Set<String> participantRoles = new HashSet<>(ProcessDef.BUILTIN_PARTICIPANT_ROLES);
        participantRoles.addAll(def.participantRoles());
        Map<String, TransitionDef> byCode = new HashMap<>();
        Set<String> fromCodePairs = new HashSet<>();
        Map<String, Set<String>> edges = new HashMap<>();
        for (int i = 0; i < def.transitions().size(); i++) {
            TransitionDef t = def.transitions().get(i);
            String path = "transitions[" + i + "]";
            code(t.code(), path + ".code", errors);
            byCode.putIfAbsent(t.code(), t);
            if (t.to() == null || !statuses.containsKey(t.to())) {
                errors.add(new FieldError(path + ".to", "opskit.process.def.unknown_status"));
            }
            if (t.from().isEmpty()) {
                errors.add(new FieldError(path + ".from", "opskit.process.def.no_from"));
            }
            for (String f : fromStatuses(t, statuses)) {
                if (!statuses.containsKey(f)) {
                    errors.add(new FieldError(path + ".from", "opskit.process.def.unknown_status"));
                    continue;
                }
                if (statuses.get(f).category().isTerminal()) {
                    errors.add(new FieldError(path + ".from", "opskit.process.def.terminal_has_exit"));
                }
                if (!fromCodePairs.add(f + "|" + t.code())) {
                    errors.add(new FieldError(path + ".code", "opskit.process.def.duplicate_transition"));
                }
                if (t.to() != null) {
                    edges.computeIfAbsent(f, k -> new HashSet<>()).add(t.to());
                }
            }
            t.who().participantRoles().stream().filter(r -> !participantRoles.contains(r))
                    .forEach(r -> errors.add(new FieldError(path + ".who.participantRoles", "opskit.process.def.unknown_participant_role")));
            condition(t.when(), path + ".when", errors);
            for (int a = 0; a < t.actions().size(); a++) {
                if (t.actions().get(a) instanceof ActionDef.AddParticipant ap && !participantRoles.contains(ap.role())) {
                    errors.add(new FieldError(path + ".actions[" + a + "].role", "opskit.process.def.unknown_participant_role"));
                }
                if (t.actions().get(a) instanceof ActionDef.AssignTo as) {
                    approvers(as.candidates(), ApprovalMode.ANY, path + ".actions[" + a + "].candidates", participantRoles, errors);
                }
            }
        }

        // ── 门 ──
        for (int i = 0; i < def.statuses().size(); i++) {
            StatusDef s = def.statuses().get(i);
            if (s.gate() == null) {
                continue;
            }
            String path = "statuses[" + i + "].gate";
            if (s.category().isTerminal()) {
                errors.add(new FieldError(path, "opskit.process.def.gate_on_terminal"));
            }
            for (String exit : s.gate().exits()) {
                TransitionDef t = byCode.get(exit);
                if (t == null) {
                    errors.add(new FieldError(path, "opskit.process.def.gate_exit_missing:" + exit));
                } else if (!t.system() || !fromStatuses(t, statuses).contains(s.code())) {
                    errors.add(new FieldError(path, "opskit.process.def.gate_exit_not_system_from_here:" + exit));
                }
            }
            switch (s.gate()) {
                case GateDef.Approval ag -> approvalGate(ag, path, participantRoles, errors);
                case GateDef.External ex -> {
                    if (ex.onConfirmed() == null || ex.onRejected() == null) {
                        errors.add(new FieldError(path, "opskit.process.def.external_needs_both_exits"));
                    }
                    if (ex.timeout() != null && ex.onTimeout() == null) {
                        errors.add(new FieldError(path + ".onTimeout", "opskit.validation.required"));
                    }
                }
                case GateDef.Timer tg -> {
                    if (tg.after() == null || tg.after().isNegative() || tg.after().isZero()) {
                        errors.add(new FieldError(path + ".after", "opskit.validation.positive_duration"));
                    }
                    if (tg.onElapsed() == null) {
                        errors.add(new FieldError(path + ".onElapsed", "opskit.validation.required"));
                    }
                }
            }
        }
        // 系统迁移只能由门触发：没有任何门引用的系统迁移是死代码，多半是配错了
        Set<String> gateExits = new HashSet<>();
        def.statuses().stream().filter(s -> s.gate() != null).forEach(s -> gateExits.addAll(s.gate().exits()));
        for (int i = 0; i < def.transitions().size(); i++) {
            TransitionDef t = def.transitions().get(i);
            if (t.system() && !gateExits.contains(t.code())) {
                errors.add(new FieldError("transitions[" + i + "].system", "opskit.process.def.system_transition_unused"));
            }
        }

        // ── 可达性：只在结构正确时才有意义 ──
        if (errors.isEmpty()) {
            Set<String> seen = new HashSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(def.initialStatus()));
            while (!todo.isEmpty()) {
                String s = todo.pop();
                if (seen.add(s)) {
                    todo.addAll(edges.getOrDefault(s, Set.of()));
                }
            }
            for (int i = 0; i < def.statuses().size(); i++) {
                if (!seen.contains(def.statuses().get(i).code())) {
                    errors.add(new FieldError("statuses[" + i + "]", "opskit.process.def.unreachable"));
                }
            }
            if (statuses.values().stream().noneMatch(s -> s.category().isTerminal())) {
                errors.add(new FieldError("statuses", "opskit.process.def.no_terminal"));
            }
        }
        return errors;
    }

    /** {@code *} = 任意非终态。 */
    static Set<String> fromStatuses(TransitionDef t, Map<String, StatusDef> statuses) {
        if (!t.from().contains(TransitionDef.ANY)) {
            return t.from();
        }
        Set<String> out = new HashSet<>();
        statuses.values().stream().filter(s -> !s.category().isTerminal()).forEach(s -> out.add(s.code()));
        t.from().stream().filter(f -> !TransitionDef.ANY.equals(f)).forEach(out::add);
        return out;
    }

    private static void approvalGate(GateDef.Approval g, String path, Set<String> participantRoles, List<FieldError> errors) {
        if (g.steps().isEmpty()) {
            errors.add(new FieldError(path + ".steps", "opskit.process.def.no_step"));
        }
        if (g.onApproved() == null || g.onRejected() == null) {
            errors.add(new FieldError(path, "opskit.process.def.approval_needs_exits"));
        }
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < g.steps().size(); i++) {
            ApprovalStepDef st = g.steps().get(i);
            String sp = path + ".steps[" + i + "]";
            code(st.code(), sp + ".code", errors);
            if (st.code() != null && !codes.add(st.code())) {
                errors.add(new FieldError(sp + ".code", "opskit.process.def.duplicate_step"));
            }
            condition(st.when(), sp + ".when", errors);
            approvers(st.approvers(), st.mode(), sp + ".approvers", participantRoles, errors);
            if (st.due() != null && (st.due().isNegative() || st.due().isZero())) {
                errors.add(new FieldError(sp + ".due", "opskit.validation.positive_duration"));
            }
        }
    }

    private static void approvers(ApproverRef ref, ApprovalMode mode, String path, Set<String> participantRoles, List<FieldError> errors) {
        if (ref.values().isEmpty()) {
            errors.add(new FieldError(path + ".values", "opskit.validation.required"));
        }
        if (mode == ApprovalMode.ALL && !ref.kind().enumerable()) {
            // 会签要知道「全部」是哪些人；按角色 / 权限的候选由令牌与目录动态决定，枚举不出来
            errors.add(new FieldError(path + ".kind", "opskit.process.def.all_needs_enumerable_approvers"));
        }
        if (ref.kind() == ApproverKind.PARTICIPANT) {
            ref.values().stream().filter(r -> !participantRoles.contains(r))
                    .forEach(r -> errors.add(new FieldError(path + ".values", "opskit.process.def.unknown_participant_role")));
        }
    }

    private static void condition(Condition c, String path, List<FieldError> errors) {
        if (c != null && c.depth() > Condition.MAX_DEPTH) {
            errors.add(new FieldError(path, "opskit.process.def.condition_too_deep"));
        }
    }

    private static void code(String code, String path, List<FieldError> errors) {
        if (code == null || !CODE.matcher(code).matches()) {
            errors.add(new FieldError(path, "opskit.validation.invalid_code"));
        }
    }
}
