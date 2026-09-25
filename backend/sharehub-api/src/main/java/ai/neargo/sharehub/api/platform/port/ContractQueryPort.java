package ai.neargo.sharehub.api.platform.port;

import ai.neargo.sharehub.api.platform.dto.ContractBrief;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 合同查询（platform.loc 实现）。系统判定用：<b>不受当前登录人的数据范围限制</b>。
 */
public interface ContractQueryPort {

    /** 各站点当前 ACTIVE 合同；无则不出现在结果里。批量，避免 N+1。 */
    Map<String, ContractBrief> activeBySites(Collection<String> siteNos);

    /** 生效中且 {@code days} 天内到期的合同（到期提醒）。 */
    List<ContractBrief> expiringWithin(int days, int limit);

    /** 站点最后一份生效过的合同（生效中优先，其次最近结束的）的钱款条款；没有则 null。撤场结清用。 */
    ai.neargo.sharehub.api.platform.dto.ContractMoneyTerms lastMoneyTermsOfSite(String siteNo);

    /**
     * 与 [from, to) 有交集的「保底 + 分成」合同（批次 G2 保底补差）。effectiveEnd = 实际结束日（终止 / 被取代时早于 endAt）。
     */
    List<GuaranteeTerm> guaranteesOverlapping(java.time.LocalDate from, java.time.LocalDate to);

    record GuaranteeTerm(String contractNo, String siteNo, String venueNo, String venueName, String currency,
                         java.math.BigDecimal guaranteeAmount, String settlePeriod, java.time.LocalDate startAt,
                         java.time.LocalDate effectiveEnd) {
    }
}
