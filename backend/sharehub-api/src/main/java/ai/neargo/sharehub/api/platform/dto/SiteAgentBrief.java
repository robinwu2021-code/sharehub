package ai.neargo.sharehub.api.platform.dto;

import java.math.BigDecimal;

/**
 * 站点上一位伙伴的一项责任 —— platform 暴露给 finance 的最小只读投影（ADR-027 §三）。
 *
 * <p>只含「谁、凭什么、按哪条规则」。不带比例：比例住在 {@code share_rule}（finance 侧），
 * 由调用方自己解析 —— platform 不该知道钱怎么算。
 *
 * @param agentNo   伙伴编号（→ {@code agt_agent.agent_no}）
 * @param agentName 冗余展示名，写进分润明细省一次连表
 * @param role      INVEST / DEVELOP / OPERATE / REFER
 * @param ruleNo    该责任指定的分润规则号；为空表示按 {@code (AGENT, agentNo, role)} 去找
 * @param oneOffAmount 一次性对价（牵线费），签约时付；仅 {@code REFER} 用。
 *                     {@code null} = 没配，与 0（明确不付）不是一回事
 */
public record SiteAgentBrief(String agentNo, String agentName, String role, String ruleNo,
                             BigDecimal oneOffAmount) {
}
