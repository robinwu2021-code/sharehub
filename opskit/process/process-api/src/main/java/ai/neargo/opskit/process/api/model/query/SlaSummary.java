package ai.neargo.opskit.process.api.model.query;

/**
 * SLA 摘要（21/06 §七 TodoItem.sla / TicketRow.sla）：到期最近的那个未停指标。剩余比例由这三项算，前端不猜起点。
 *
 * @param state RUNNING / WARNING / PAUSED / BREACHED
 */
public record SlaSummary(String state, long startedAtMs, long dueAtMs, long pausedMs) {
}
