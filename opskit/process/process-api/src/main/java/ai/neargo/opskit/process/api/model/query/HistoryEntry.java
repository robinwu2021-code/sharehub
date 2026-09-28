package ai.neargo.opskit.process.api.model.query;

import java.util.Map;

/**
 * 历史的一条（实例 / 任务 / 审批 / SLA 的每次变化，21/02 §8.1 第 5 步落的日志）。
 *
 * @param entityKind INSTANCE / TASK / APPROVAL / SLA / GATE
 * @param actor      {@code member:x} / {@code app:x} / {@code system}
 */
public record HistoryEntry(String entityKind, String entityNo, String instanceNo, String action, String from, String to, Integer seq, String actor,
        String onBehalfOf, String reason, Map<String, Object> payload, long atMs) {
}
