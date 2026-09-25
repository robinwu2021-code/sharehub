package ai.neargo.sharehub.agent.ext.service;

import ai.neargo.sharehub.api.common.Checklist;

import java.time.LocalDateTime;

/**
 * 代理清退（对齐清单 F3）：发起即停用代理（冻结提现、名下工单改派），之后按「收回资产 → 结清 → 关闭账号」逐步推进，
 * 每步门禁全过才放行。系统里没有代理保证金，这一步不设门禁（线下处理）。
 */
public interface AgentExitService {

    record AgentExit(String exitNo, String agentNo, String status, String reason, String startedBy, LocalDateTime startedAt,
                     LocalDateTime reclaimedAt, LocalDateTime settledAt, LocalDateTime closedAt, String closedBy) {
    }

    AgentExit start(String agentNo, String reason);

    AgentExit get(String exitNo);

    /** 当前这一步的门禁（逐项、带数量）。 */
    Checklist gate(String exitNo);

    /** 门禁全过 → 推进到下一步；最后一步关闭账号并归档代理。 */
    AgentExit advance(String exitNo);
}
