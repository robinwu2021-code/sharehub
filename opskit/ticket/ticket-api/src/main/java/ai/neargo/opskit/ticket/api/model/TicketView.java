package ai.neargo.opskit.ticket.api.model;

import java.util.Map;
import java.util.Set;

/**
 * 工单（服务端形状；名称等由接口层按语言补）。状态与 SLA 是流程实例的只读快照。
 *
 * @param slaState 各指标中最差的：BREACHED / WARNING / RUNNING / PAUSED / MET；没有指标为空
 */
public record TicketView(String ticketNo, String typeCode, String typeVersion, String instanceNo, String title, String description, String priority,
        Integer priorityOrd, String resolution, Map<String, Object> fields, String requester, String assignee, String status, String statusCategory,
        boolean open, String slaState, Long slaNextDueAtMs, Long slaStartedAtMs, long slaPausedMs, boolean escalated, String mergedInto,
        String dedupeKey, Set<String> scopes, String createdBy, long createdAtMs, long updatedAtMs, Long closedAtMs) {
}
