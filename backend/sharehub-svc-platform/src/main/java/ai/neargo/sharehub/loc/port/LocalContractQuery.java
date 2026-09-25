package ai.neargo.sharehub.loc.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.dto.ContractBrief;
import ai.neargo.sharehub.api.platform.port.ContractQueryPort;
import ai.neargo.sharehub.loc.ContractStatus;
import ai.neargo.sharehub.loc.ContractTermReqStatus;
import ai.neargo.sharehub.loc.SiteStatus;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link ContractQueryPort} 的本地实现。系统判定用，豁免数据范围。 */
@Service
public class LocalContractQuery implements ContractQueryPort {

    private final LocMappers.ContractMapper contracts;
    private final LocMappers.SiteMapper sites;

    public LocalContractQuery(LocMappers.ContractMapper contracts, LocMappers.SiteMapper sites) {
        this.contracts = contracts;
        this.sites = sites;
    }

    @Override
    public Map<String, ContractBrief> activeBySites(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        return DataScopeContext.executeWithoutScope(() -> contracts.selectList(new LambdaQueryWrapper<LocContract>()
                        .in(LocContract::getSiteNo, siteNos).eq(LocContract::getStatus, ContractStatus.ACTIVE.name())))
                .stream().collect(Collectors.toMap(LocContract::getSiteNo, LocalContractQuery::brief, (a, b) -> a));
    }

    /**
     * 生效中且 {@code days} 天内到期、<b>且还没人在处理</b>的合同。已在处理的排除：
     * 同站点已有续签 / 补充合同待审批或已批、终止申请在途或已批、站点已进入撤场或关闭 ——
     * 这些再提醒只是噪音，而噪音会让真该续签的那几份被忽略。
     */
    @Override
    public List<ContractBrief> expiringWithin(int days, int limit) {
        LocalDate today = LocalDate.now();
        List<LocContract> due = DataScopeContext.executeWithoutScope(() -> contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getStatus, ContractStatus.ACTIVE.name())
                .between(LocContract::getEndAt, today.toString(), today.plusDays(days).toString())
                .and(x -> x.isNull(LocContract::getTermReqStatus).or().eq(LocContract::getTermReqStatus, ContractTermReqStatus.REJECTED.name()))
                .orderByAsc(LocContract::getEndAt).last("limit " + Math.max(1, Math.min(limit, 1000)))));
        if (due.isEmpty()) return List.of();
        List<String> siteNos = due.stream().map(LocContract::getSiteNo).filter(java.util.Objects::nonNull).distinct().toList();
        Set<String> renewing = siteNos.isEmpty() ? Set.of() : DataScopeContext.executeWithoutScope(() -> contracts.selectList(
                        new LambdaQueryWrapper<LocContract>().select(LocContract::getSiteNo).in(LocContract::getSiteNo, siteNos)
                                .in(LocContract::getStatus, ContractStatus.PENDING.name(), ContractStatus.SIGNED.name())))
                .stream().map(LocContract::getSiteNo).collect(Collectors.toSet());
        Set<String> leaving = siteNos.isEmpty() ? Set.of() : DataScopeContext.executeWithoutScope(() -> sites.selectList(
                        new LambdaQueryWrapper<LocSite>().select(LocSite::getSiteNo).in(LocSite::getSiteNo, siteNos)
                                .in(LocSite::getStatus, SiteStatus.WITHDRAWING.name(), SiteStatus.CLOSED.name())))
                .stream().map(LocSite::getSiteNo).collect(Collectors.toSet());
        return due.stream().filter(c -> !renewing.contains(c.getSiteNo()) && !leaving.contains(c.getSiteNo()))
                .map(LocalContractQuery::brief).toList();
    }

    @Override
    public List<GuaranteeTerm> guaranteesOverlapping(LocalDate from, LocalDate to) {
        return DataScopeContext.executeWithoutScope(() -> contracts.selectList(new LambdaQueryWrapper<LocContract>()
                        .eq(LocContract::getShareMode, ai.neargo.sharehub.loc.ContractShareMode.GUARANTEE.name())
                        .in(LocContract::getStatus, ContractStatus.ACTIVE.name(), ContractStatus.EXPIRED.name(), ContractStatus.TERMINATED.name())
                        .lt(LocContract::getStartAt, to.toString()).ge(LocContract::getEndAt, from.toString())
                        .isNotNull(LocContract::getGuaranteeAmount)))
                .stream().map(c -> {
                    LocalDate end = LocalDate.parse(c.getEndAt());
                    // 提前终止 / 被续签、补充协议取代：以实际结束那天为准（当天不再生效）
                    if (c.getEndedAt() != null && c.getEndedAt().toLocalDate().minusDays(1).isBefore(end)) {
                        end = c.getEndedAt().toLocalDate().minusDays(1);
                    }
                    return new GuaranteeTerm(c.getContractNo(), c.getSiteNo(), c.getVenueNo(), c.getVenueName(), c.getCurrency(),
                            c.getGuaranteeAmount(), c.getSettlePeriod(), LocalDate.parse(c.getStartAt()), end);
                })
                .filter(g -> !g.effectiveEnd().isBefore(from))
                .toList();
    }

    @Override
    public ai.neargo.sharehub.api.platform.dto.ContractMoneyTerms lastMoneyTermsOfSite(String siteNo) {
        if (siteNo == null) return null;
        List<LocContract> rows = DataScopeContext.executeWithoutScope(() -> contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getSiteNo, siteNo)
                .in(LocContract::getStatus, ContractStatus.ACTIVE.name(), ContractStatus.EXPIRED.name(), ContractStatus.TERMINATED.name())
                .orderByDesc(LocContract::getId)));
        LocContract c = rows.stream().filter(x -> ContractStatus.ACTIVE.name().equals(x.getStatus())).findFirst()
                .orElse(rows.isEmpty() ? null : rows.get(0));
        if (c == null) return null;
        return new ai.neargo.sharehub.api.platform.dto.ContractMoneyTerms(c.getContractNo(), c.getSiteNo(), c.getVenueNo(),
                c.getVenueName(), c.getStatus(), c.getCurrency(), c.getDepositAmount(),
                c.getEntryFee() == null ? null : java.math.BigDecimal.valueOf(c.getEntryFee()),
                c.getStartAt() == null ? null : LocalDate.parse(c.getStartAt()),
                c.getEndAt() == null ? null : LocalDate.parse(c.getEndAt()));
    }

    private static ContractBrief brief(LocContract c) {
        return new ContractBrief(c.getContractNo(), c.getSiteNo(), c.getVenueNo(), c.getStatus(),
                c.getStartAt() == null ? null : LocalDate.parse(c.getStartAt()),
                c.getEndAt() == null ? null : LocalDate.parse(c.getEndAt()), c.getShareMode());
    }
}
