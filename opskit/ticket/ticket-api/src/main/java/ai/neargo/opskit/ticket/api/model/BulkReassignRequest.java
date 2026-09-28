package ai.neargo.opskit.ticket.api.model;

import java.util.List;

/**
 * 批量改派（21/06 §五）：两种选法二选一 —— 勾选的工单号（≤ 200），或某人名下的全部未结单（{@code from}）。
 *
 * @param to 受理人（{@code member:x} 或成员 ID）
 */
public record BulkReassignRequest(List<String> ticketNos, String from, String to, String reason) {
    public static final int MAX = 200;
}
