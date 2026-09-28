package ai.neargo.opskit.ticket.api.model;

import ai.neargo.opskit.process.api.model.ActionView;
import ai.neargo.opskit.process.api.model.query.SlaMetric;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单详情要的全部服务端数据（21/06 §七 TicketView）：工单 + 每个 SLA 指标 + 参与者 + 当前操作人可走的迁移 + 去重附加次数。
 *
 * @param participants 角色 → 主体（{@code member:x} …）
 */
public record TicketDetail(TicketView ticket, List<SlaMetric> slaMetrics, Map<String, Set<String>> participants, List<ActionView> actions,
        int dedupeHits) {
}
