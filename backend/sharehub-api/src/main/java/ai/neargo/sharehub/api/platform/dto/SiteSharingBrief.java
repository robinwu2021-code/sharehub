package ai.neargo.sharehub.api.platform.dto;

import java.math.BigDecimal;

/**
 * 站点的分成口径摘要 —— platform 暴露给 finance 的最小只读投影。
 *
 * <p>只含「这个站点的钱该分给谁、场地方按什么比例」。不放站点的其余字段：
 * 跨服务 DTO 每多一列，platform 的模型演进就多一处兼容性负担（同 {@link SiteBrief} 的取舍）。
 *
 * @param venueNo    场地方编号；为空表示站点没挂场地方，不产生 VENUE 维度分润
 * @param venueRate  **来自进场合同**的场地方分成率（0..1）；为 null 表示当前没有生效中的合同
 * @param contractNo 费率来源的合同号，写进分润明细便于追溯「这笔为什么是这个比例」
 * @param currency   合同币种
 */
public record SiteSharingBrief(String siteNo, String venueNo, String venueName,
                               BigDecimal venueRate, String contractNo, String currency) {
}
