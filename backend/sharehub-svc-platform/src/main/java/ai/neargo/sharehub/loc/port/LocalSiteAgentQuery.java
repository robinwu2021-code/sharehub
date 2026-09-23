package ai.neargo.sharehub.loc.port;

import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.dto.SiteAgentBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.SiteAgentQueryPort;
import ai.neargo.sharehub.loc.ext.SiteAgentRole;
import ai.neargo.sharehub.loc.ext.entity.LocSiteAgent;
import ai.neargo.sharehub.loc.ext.mapper.LocSiteAgentMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SiteAgentQueryPort} 的本地实现，住在 platform 侧（同 {@link LocalSiteSharingQuery}）。
 */
@Service
public class LocalSiteAgentQuery implements SiteAgentQueryPort {

    private final LocSiteAgentMapper mapper;
    private final AgentDirectoryPort agents;

    public LocalSiteAgentQuery(LocSiteAgentMapper mapper, AgentDirectoryPort agents) {
        this.mapper = mapper;
        this.agents = agents;
    }

    @Override
    public List<SiteAgentBrief> agentsOf(String siteNo, String onDate) {
        if (siteNo == null || siteNo.isBlank()) return List.of();

        // 生效期在 SQL 里判、不在内存里判（同 LocalSiteSharingQuery.activeContract 的理由）：
        // 列是 DATETIME，字符串比较与日期比较在边界日会给出不同答案。
        // onDate 只给到日，比较时补足到当日 23:59:59.999 —— 否则「今天生效」的行
        // （effective_from = 今天 09:00）在拿 "今天" 比时会被判成还没开始。
        String from = onDate == null ? null : onDate + " 23:59:59.999";
        String to = onDate == null ? null : onDate + " 00:00:00.000";

        List<LocSiteAgent> rows = mapper.selectList(new LambdaQueryWrapper<LocSiteAgent>()
                .eq(LocSiteAgent::getSiteNo, siteNo)
                .and(from != null, w -> w.isNull(LocSiteAgent::getEffectiveFrom)
                        .or().le(LocSiteAgent::getEffectiveFrom, from))
                .and(to != null, w -> w.isNull(LocSiteAgent::getEffectiveTo)
                        .or().ge(LocSiteAgent::getEffectiveTo, to)));
        if (rows.isEmpty()) return List.of();

        Map<String, AgentBrief> byNo = agents.briefsOf(
                rows.stream().map(LocSiteAgent::getAgentNo).distinct().toList());

        // 按责任层序稳定排序：同一单两次补算要分出同样顺序的记录，
        // 否则逐条对账时「第一条」指的是不同的东西。
        return rows.stream()
                .sorted(Comparator
                        .comparingInt((LocSiteAgent r) -> SiteAgentRole.of(r.getRole())
                                .map(Enum::ordinal).orElse(Integer.MAX_VALUE))
                        .thenComparing(r -> r.getAgentNo() == null ? "" : r.getAgentNo()))
                .map(r -> new SiteAgentBrief(r.getAgentNo(),
                        Optional.ofNullable(byNo.get(r.getAgentNo())).map(AgentBrief::name).orElse(null),
                        r.getRole(), r.getRuleNo()))
                .toList();
    }
}
