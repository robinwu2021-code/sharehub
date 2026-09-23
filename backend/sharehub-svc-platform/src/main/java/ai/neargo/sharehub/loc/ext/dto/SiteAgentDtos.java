package ai.neargo.sharehub.loc.ext.dto;

/** 站点伙伴责任的跨层出入参（ADR-027 §三）。 */
public final class SiteAgentDtos {

    private SiteAgentDtos() {
    }

    /**
     * 一行责任。
     *
     * @param id        本表没有业务号（它不是独立对象，是站点与伙伴之间的一条关系），故用 id
     * @param role      INVEST / DEVELOP / OPERATE / REFER
     * @param ruleNo    该责任对应的分润规则；空 = 用登记类型默认费率
     * @param agentName 冗余展示名，出参才有 —— 入参传了也不采信（同「存编号不存名字」纪律）
     */
    public record SiteAgentRow(Long id, String siteNo, String agentNo, String agentName,
                               String agentType, String role, String ruleNo,
                               String effectiveFrom, String effectiveTo, String remark) {
    }
}
