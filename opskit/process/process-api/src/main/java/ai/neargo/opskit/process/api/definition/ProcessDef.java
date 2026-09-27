package ai.neargo.opskit.process.api.definition;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.List;
import java.util.Set;

/**
 * 流程定义（21/02 §6.1）：状态 + 迁移 + 状态上的门。定义是数据；版本 = 规范化 JSON 的内容哈希。
 *
 * @param participantRoles 本流程自定义的参与者角色（内建的 REQUESTER / OWNER / ASSIGNEE / WATCHER 不必声明）
 */
public record ProcessDef(String code, LocalizedText name, String initialStatus, List<StatusDef> statuses,
        List<TransitionDef> transitions, Set<String> participantRoles) {

    public static final Set<String> BUILTIN_PARTICIPANT_ROLES = Set.of("REQUESTER", "OWNER", "ASSIGNEE", "WATCHER");

    public ProcessDef {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
        participantRoles = participantRoles == null ? Set.of() : Set.copyOf(participantRoles);
    }
}
