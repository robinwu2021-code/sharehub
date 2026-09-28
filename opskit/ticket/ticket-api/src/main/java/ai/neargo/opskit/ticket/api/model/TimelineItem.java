package ai.neargo.opskit.ticket.api.model;

import ai.neargo.opskit.commons.i18n.LocalizedText;
import java.util.List;
import java.util.Map;

/**
 * 时间线的一条（21/06 §七 TimelineEntry 的服务端形状；标题由接口层按语言拼）。
 *
 * @param kind       CREATED · ASSIGNED · TRANSITION · SLA · DEDUPE · COMMENT · ESCALATED · APPROVAL · MERGE
 * @param actor      {@code member:x} / {@code app:x} / {@code system}
 * @param action     历史的动作代号（迁移代号 · claimed · approve …）；评论为空
 * @param visibility 评论：PUBLIC / INTERNAL；历史为空
 * @param hot        需要强调（逾期 · 驳回 · 作废 · 升级 · 领取超时）
 */
public record TimelineItem(int seq, long atMs, String kind, String actor, String action, String from, String to, LocalizedText actionName,
        LocalizedText fromName, LocalizedText toName, String body, String visibility, List<Map<String, Object>> attachments, String reason, boolean hot) {
}
