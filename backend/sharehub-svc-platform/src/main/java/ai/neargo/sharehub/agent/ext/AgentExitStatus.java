package ai.neargo.sharehub.agent.ext;

/** 代理清退单状态（{@code agt_exit.status}，对齐清单 F3）：收回资产 → 结清 → 关闭账号，严格按序。 */
public enum AgentExitStatus {
    RECLAIMING, SETTLING, CLOSING, CLOSED
}
