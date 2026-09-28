package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.Map;

/**
 * 历史的一条（实例 / 任务 / 审批 / SLA 的每次变化，21/02 §8.1 第 5 步落的日志）。
 *
 * @param entityKind INSTANCE / TASK / APPROVAL / SLA / GATE
 * @param actor      {@code member:x} / {@code app:x} / {@code system}
 * @param actionName INSTANCE 的迁移：迁移名（action 是租户定义的迁移代号）；其余为空
 * @param fromName   INSTANCE：from / to 状态的名称；其余为空
 */
public record HistoryEntry(String entityKind, String entityNo, String instanceNo, String action, String from, String to, Integer seq, String actor,
        String onBehalfOf, String reason, Map<String, Object> payload, long atMs, LocalizedText actionName, LocalizedText fromName, LocalizedText toName) {

    /** 不带名称（名称由读侧按流程定义补）。 */
    public HistoryEntry(String entityKind, String entityNo, String instanceNo, String action, String from, String to, Integer seq, String actor,
            String onBehalfOf, String reason, Map<String, Object> payload, long atMs) {
        this(entityKind, entityNo, instanceNo, action, from, to, seq, actor, onBehalfOf, reason, payload, atMs, null, null, null);
    }

    public HistoryEntry withNames(LocalizedText action, LocalizedText fromStatus, LocalizedText toStatus) {
        return new HistoryEntry(entityKind, entityNo, instanceNo, this.action, from, to, seq, actor, onBehalfOf, reason, payload, atMs, action, fromStatus,
                toStatus);
    }
}
