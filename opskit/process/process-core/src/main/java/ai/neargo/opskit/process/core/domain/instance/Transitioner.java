package ai.neargo.opskit.process.core.domain.instance;

import ai.neargo.opskit.commons.actor.Actor;
import ai.neargo.opskit.commons.condition.ConditionEvaluator;
import ai.neargo.opskit.commons.error.FieldError;
import ai.neargo.opskit.commons.error.OpsKitException;
import ai.neargo.opskit.process.api.ProcessErrors;
import ai.neargo.opskit.process.api.definition.CompiledProcess;
import ai.neargo.opskit.process.api.definition.TransitionDef;
import ai.neargo.opskit.process.api.definition.Who;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 迁移的纯领域判断（21/02 §8.1 第 2–3 步）：找边 → 谁能走 → 必填 → 条件。不做 IO，也不改任何东西。
 * 判断顺序固定：先确认边存在（422），再确认你有资格（403），最后才看你交的东西够不够（422）。
 */
public final class Transitioner {

    private Transitioner() {
    }

    /**
     * @param systemCall      门触发的系统迁移（只有它能走 system=true 的边）
     * @param actorRoles      操作人在该实例上的参与者角色（由调用方按成员本人 + 所在组查好）
     * @param mergedFields    实例字段合并本次提交字段之后的结果
     */
    public static TransitionDef decide(CompiledProcess process, String currentStatus, String code, boolean systemCall, Actor actor,
            Set<String> actorRoles, Map<String, Object> mergedFields, String comment) {
        TransitionDef t = process.transition(currentStatus, code)
                .filter(x -> systemCall || !x.system())
                .orElseThrow(() -> new OpsKitException(ProcessErrors.ILLEGAL_TRANSITION,
                        "no transition '" + code + "' from '" + currentStatus + "'"));
        if (!systemCall && !allowed(t.who(), actor, actorRoles)) {
            throw new OpsKitException(ProcessErrors.NOT_ALLOWED, "not allowed to fire '" + code + "'");
        }
        List<FieldError> missing = t.requires().fields().stream().filter(f -> isBlank(mergedFields.get(f)))
                .sorted().map(f -> new FieldError("fields." + f, "opskit.validation.required")).toList();
        if (t.requires().comment() && (comment == null || comment.isBlank())) {
            missing = new java.util.ArrayList<>(missing);
            missing.add(new FieldError("comment", "opskit.validation.required"));
        }
        if (!missing.isEmpty()) {
            throw new OpsKitException(ProcessErrors.REQUIRED_MISSING, "required input missing for '" + code + "'", Map.of(), missing);
        }
        if (t.when() != null && !ConditionEvaluator.evaluate(t.when(), mergedFields)) {
            throw new OpsKitException(ProcessErrors.CONDITION_UNMET, "condition not met for '" + code + "'");
        }
        return t;
    }

    /** 能不能走（不校验输入）：用于算「当前操作人可走的迁移」按钮集合。 */
    public static boolean canSee(TransitionDef t, Actor actor, Set<String> actorRoles, Map<String, Object> fields) {
        return !t.system() && allowed(t.who(), actor, actorRoles) && (t.when() == null || ConditionEvaluator.evaluate(t.when(), fields));
    }

    /** 任一集合匹配即可；四个集合全空 = 只有当前办理人。应用自身（受信系统）一律可以。 */
    static boolean allowed(Who who, Actor actor, Set<String> actorRoles) {
        if (actor.isAppItself()) {
            return true;
        }
        if (who.isDefault()) {
            return actorRoles.contains("ASSIGNEE");
        }
        return intersects(who.participantRoles(), actorRoles) || intersects(who.roles(), actor.roles())
                || intersects(who.groups(), actor.groups()) || intersects(who.perms(), actor.externalPerms());
    }

    private static boolean intersects(Collection<String> a, Collection<String> b) {
        return a.stream().anyMatch(b::contains);
    }

    private static boolean isBlank(Object v) {
        return v == null || (v instanceof String s && s.isBlank()) || (v instanceof Collection<?> c && c.isEmpty());
    }
}
