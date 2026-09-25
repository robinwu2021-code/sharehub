package ai.neargo.sharehub.loc.port;

import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * {@link SiteQueryPort} 的本地实现，**住在 platform 侧**（`loc` 包）。
 *
 * <p>放这里而不是放调用方：拆分时它随 platform 走，调用方那边只剩接口 ——
 * 这正是「换个打包方式就换形态」成立的前提。
 */
@Service
public class LocalSiteQuery implements SiteQueryPort {

    private final SiteMapper sites;
    private final ai.neargo.sharehub.loc.ext.mapper.LocSiteAgentMapper siteAgents;

    private final ai.neargo.sharehub.loc.mapper.LocMappers.SiteSurveyMapper surveys;

    public LocalSiteQuery(SiteMapper sites, ai.neargo.sharehub.loc.ext.mapper.LocSiteAgentMapper siteAgents,
                          ai.neargo.sharehub.loc.mapper.LocMappers.SiteSurveyMapper surveys) {
        this.sites = sites;
        this.siteAgents = siteAgents;
        this.surveys = surveys;
    }

    /** 各站点最近一次勘测是否通过（按 id 倒序，每站取第一条）。 */
    private java.util.Map<String, Boolean> latestSurveyPassed(Collection<String> siteNos) {
        java.util.Map<String, Boolean> out = new java.util.HashMap<>();
        surveys.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.loc.entity.LocSiteSurvey>()
                        .in(ai.neargo.sharehub.loc.entity.LocSiteSurvey::getSiteNo, siteNos)
                        .orderByDesc(ai.neargo.sharehub.loc.entity.LocSiteSurvey::getId))
                .forEach(v -> out.putIfAbsent(v.getSiteNo(), ai.neargo.sharehub.loc.SurveyResult.PASS.name().equals(v.getResult())));
        return out;
    }

    @Override
    public List<SiteBrief> briefsByNos(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return List.of();
        List<LocSite> rows = sites.selectList(new LambdaQueryWrapper<LocSite>()
                        .in(LocSite::getSiteNo, siteNos));
        if (rows.isEmpty()) return List.of();
        // 生效中的 OPERATE 伙伴：派单与告警路由「代理优先、暂停则落到员工」要用
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.util.Map<String, String> operate = new java.util.HashMap<>();
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> siteAgents.selectList(
                        new LambdaQueryWrapper<ai.neargo.sharehub.loc.ext.entity.LocSiteAgent>()
                                .in(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getSiteNo, siteNos)
                                .eq(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getRole, "OPERATE")
                                .and(w -> w.isNull(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getEffectiveFrom)
                                        .or().le(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getEffectiveFrom, now))
                                .and(w -> w.isNull(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getEffectiveTo)
                                        .or().ge(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getEffectiveTo, now))
                                .orderByAsc(ai.neargo.sharehub.loc.ext.entity.LocSiteAgent::getId)))
                .forEach(a -> operate.putIfAbsent(a.getSiteNo(), a.getAgentNo()));
        java.util.Map<String, Boolean> surveyed = latestSurveyPassed(siteNos);
        return rows.stream()
                .map(s -> new SiteBrief(s.getSiteNo(), s.getName(), s.getRegionId(),
                        s.getVenueNo(), s.getSceneType(), s.getBrandNo(),
                        s.getStatus(), s.getOpenHours(), operate.get(s.getSiteNo()), s.getOpsEmployeeNo(), s.getAgentNo(),
                        surveyed.get(s.getSiteNo()), s.getFirstLiveAt() == null ? null : s.getFirstLiveAt().toLocalDate()))
                .toList();
    }
}
