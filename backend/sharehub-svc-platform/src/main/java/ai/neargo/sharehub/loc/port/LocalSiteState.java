package ai.neargo.sharehub.loc.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.SiteStatePort;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

/** {@link SiteStatePort} 的本地实现（系统读，豁免数据范围）。 */
@Service
public class LocalSiteState implements SiteStatePort {

    private final SiteMapper sites;
    private final LocalSiteQuery query;

    public LocalSiteState(SiteMapper sites, LocalSiteQuery query) {
        this.sites = sites;
        this.query = query;
    }

    @Override
    public List<SiteBrief> listByStatus(List<String> statuses, long afterId, int limit) {
        List<String> nos = DataScopeContext.executeWithoutScope(() -> sites.selectList(new LambdaQueryWrapper<LocSite>()
                        .select(LocSite::getId, LocSite::getSiteNo)
                        .in(LocSite::getStatus, statuses).isNull(LocSite::getArchivedAt).gt(LocSite::getId, afterId)
                        .orderByAsc(LocSite::getId).last("limit " + Math.max(1, Math.min(limit, 1000)))))
                .stream().map(LocSite::getSiteNo).toList();
        return nos.isEmpty() ? List.of() : DataScopeContext.executeWithoutScope(() -> query.briefsByNos(nos));
    }

    @Override
    public long idOf(String siteNo) {
        LocSite s = DataScopeContext.executeWithoutScope(() -> sites.selectOne(new LambdaQueryWrapper<LocSite>()
                .select(LocSite::getId).eq(LocSite::getSiteNo, siteNo).last("limit 1")));
        return s == null ? 0 : s.getId();
    }
}
