package ai.neargo.opskit.process.api.definition;

import ai.neargo.opskit.commons.condition.Condition;
import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.List;
import java.util.Set;

/**
 * @param from   出发状态；含 {@code *} 表示任意非终态
 * @param system true = 只能由门触发，界面与接口不能直接走、也不出按钮
 */
public record TransitionDef(String code, LocalizedText name, Set<String> from, String to, Who who, Condition when,
        Requires requires, List<ActionDef> actions, boolean system) {

    public static final String ANY = "*";

    public TransitionDef {
        from = from == null ? Set.of() : Set.copyOf(from);
        who = who == null ? Who.ASSIGNEE : who;
        requires = requires == null ? Requires.NONE : requires;
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
