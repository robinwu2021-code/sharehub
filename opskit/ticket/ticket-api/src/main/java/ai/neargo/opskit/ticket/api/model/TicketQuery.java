package ai.neargo.opskit.ticket.api.model;

import java.util.List;

/**
 * tickets/query（21/06 §五）：每个筛选控件对应一个参数；各项为空 = 不限。
 *
 * @param assignee     {@code "me"} · {@code "none"}（未派）· 成员 ID
 * @param group        受理方在该组里
 * @param assigneeAttr 受理方的成员属性（如 {partyType: [AGENT]}）；匹配超过 5000 人 → 422 opskit.ticket.filter_too_broad
 * @param fieldFilters 按可筛选字段筛；须只选一个 typeCode（字段由类型定义）
 * @param sort         createdAt · -createdAt（缺省）· updatedAt · -updatedAt · priority · slaDue
 */
public record TicketQuery(List<String> typeCode, List<String> statusCategory, List<String> status, List<String> priority, String assignee,
        String requester, String group, AssigneeAttr assigneeAttr, List<String> slaState, List<String> scopes, List<FieldFilter> fieldFilters,
        String keyword, Boolean open, Boolean escalated, Long createdFromMs, Long createdToMs, Long closedFromMs, Long closedToMs, String sort,
        Integer page, Integer size) {

    public record AssigneeAttr(String key, List<String> values) {
    }

    /** @param op EQ（缺省）· IN · GT · GTE · LT · LTE；IN 时 value 为数组 */
    public record FieldFilter(String field, String op, Object value) {
    }
}
