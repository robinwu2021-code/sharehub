package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.SiteSharingBrief;

/**
 * 站点分成口径查询 —— platform 暴露给 finance 的**只读**面。
 *
 * <p><b>为什么场地方费率取自合同而不是分润规则</b>（运营管理清单 D2 的落地取舍）：
 * `share_rule` 按**分成方**配比例、没有站点维度，而同一个场地方在不同商场的分成完全可以
 * 不一样；`loc_contract` 是**按站点**签的、是双方签过字的那份，站点级比例只存在于它里面。
 * 所以 VENUE 维度以合同为准，没有生效合同时回落到 `share_rule(VENUE, venueNo)`
 * ——由调用方决定回落，本接口只如实回答「合同怎么写的」。
 * AGENT 维度不走这里：代理分成本来就没有站点级约定，直接读 `share_rule(AGENT, agentNo)`。
 */
public interface SiteSharingQueryPort {

    /**
     * 取某站点在**指定时刻**生效的分成口径。
     *
     * @param siteNo 站点业务键
     * @param onDate 判定合同是否生效的日期（{@code YYYY-MM-DD}）。传结算日而不是「今天」——
     *               补算历史订单时用今天会命中错误的合同版本
     * @return 站点不存在时返回 {@code null}
     */
    SiteSharingBrief sharingOf(String siteNo, String onDate);
}
