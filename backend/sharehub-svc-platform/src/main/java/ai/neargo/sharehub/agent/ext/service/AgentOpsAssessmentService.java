package ai.neargo.sharehub.agent.ext.service;

import ai.neargo.sharehub.agent.ext.entity.AgtOpsAssessment;

import java.util.List;

/**
 * 代理运维月度考核（对齐清单 F5，裁决 #1）：不逐单扣分润，按月度 SLA 达成率定下一月的运维分成系数。
 *
 * <p>达成率 = 考核月内该代理完结且未超解决时限的工单 ÷（它完结的 + 被平台接管的）。被接管的算没达成 ——
 * 否则超时不管、等平台接走，反而不影响它的考核。
 */
public interface AgentOpsAssessmentService {

    /** 考核某月（YYYY-MM）全部未归档代理；同月重跑覆盖。返回考核条数。 */
    int assess(String period);

    List<AgtOpsAssessment> history(String agentNo);
}
