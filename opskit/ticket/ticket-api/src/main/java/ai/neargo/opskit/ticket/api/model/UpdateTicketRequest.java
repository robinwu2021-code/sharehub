package ai.neargo.opskit.ticket.api.model;

import java.util.Map;

/** 改工单（PATCH）：给了的才改；fields 里给 null 表示清空该字段（必填字段不能清空）。 */
public record UpdateTicketRequest(String title, String description, String priority, Map<String, Object> fields) {
}
