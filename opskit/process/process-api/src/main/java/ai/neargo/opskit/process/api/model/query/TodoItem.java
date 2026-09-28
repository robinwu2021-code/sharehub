package ai.neargo.opskit.process.api.model.query;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import ai.neargo.opskit.process.api.model.SubjectRef;

/**
 * 待办行（21/06 §七 TodoItem 的流程层形状）。名称是多语种原文，由接口层按 Accept-Language 选；
 * 工单来源的 {@code ticketNo} / {@code ticketType} 由工单模块补。
 *
 * @param title     任务标题（审批任务 = 步骤名）；为空时界面用 processName
 * @param claimable true = 在池里可领；false = 已在我名下
 * @param sla       实例上到期最近的未停 SLA 指标；没有则为空
 */
public record TodoItem(String taskNo, String kind, String origin, String title, LocalizedText processName, SubjectRef subject, Integer priorityOrd,
        long createdAtMs, Long dueAtMs, boolean claimable, String instanceNo, String approvalNo, String queueGroup, SlaSummary sla) {
}
