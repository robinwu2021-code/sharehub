package ai.neargo.opskit.commons.actor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 把操作人展开成候选键集合。「我的待办」查询与「能不能办这条任务」校验共用这一处，
 * 两边口径才不会分叉（02 §8.2）。参与者键依赖具体实例，由调用方另行追加。
 */
public final class CandidateKeys {

    private CandidateKeys() {
    }

    public static Set<String> of(Actor actor) {
        Set<String> keys = new LinkedHashSet<>();
        actor.member().ifPresent(m -> keys.add(m.key()));
        actor.groups().forEach(g -> keys.add(PrincipalRef.group(g).key()));
        actor.roles().forEach(r -> keys.add(PrincipalRef.role(r).key()));
        actor.externalPerms().forEach(p -> keys.add(PrincipalRef.perm(p).key()));
        return keys;
    }
}
