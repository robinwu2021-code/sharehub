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

    /**
     * 该代理**当前在途的**清退单；没有则 {@code null}。
     *
     * <p>清退单号此前只能从「发起」的返回值里拿到 —— 刷新页面、换个人看，就再也找不到它了
     * （运营端只好加一个「按单号查进度」的输入框）。更要紧的是保存代理档案那条路径
     * 需要它来判断「这个代理正在清退中」，见 {@code AgentService#save}。
     */
    AgentExit openOf(String agentNo);

    /** 当前这一步的门禁（逐项、带数量）。 */
    Checklist gate(String exitNo);

    /** 门禁全过 → 推进到下一步；最后一步关闭账号并归档代理。 */
    AgentExit advance(String exitNo);
}
