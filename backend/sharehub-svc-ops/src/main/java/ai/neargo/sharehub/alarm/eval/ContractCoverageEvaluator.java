package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.api.platform.dto.ContractBrief;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.ContractQueryPort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 无合同在营业（TDD/05 §4.4，联动 E4）：营业中 / 暂停的站点没有生效合同。恢复 = 合同生效或站点进入撤场。 */
@Component
public class ContractCoverageEvaluator implements StateEvaluator {

    public static final String SITE_WITHOUT_CONTRACT = "SITE_WITHOUT_CONTRACT";
    private static final Set<String> COVERED = Set.of("ACTIVE", "PAUSED");

    private final ContractQueryPort contracts;

    public ContractCoverageEvaluator(ContractQueryPort contracts) {
        this.contracts = contracts;
    }

    @Override
    public Set<String> codes() {
        return Set.of(SITE_WITHOUT_CONTRACT);
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        List<SiteBrief> sites = scope.sites().stream().filter(s -> COVERED.contains(s.status())).toList();
        if (sites.isEmpty()) return List.of();
        Map<String, ContractBrief> active = contracts.activeBySites(sites.stream().map(SiteBrief::siteNo).toList());
        return sites.stream().filter(s -> !active.containsKey(s.siteNo()))
                .map(s -> new Finding(SITE_WITHOUT_CONTRACT, AlarmSubjectType.SITE, s.siteNo(), s.siteNo(), null, s.agentNo(),
                        s.regionId(), AlarmCause.NO_CONTRACT, ImpactScope.SITE, 0,
                        List.of(new Finding.Evidence("NO_ACTIVE_CONTRACT", null, null, now, s.status())), Map.of()))
                .toList();
    }
}
