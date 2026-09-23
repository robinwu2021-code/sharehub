package ai.neargo.sharehub.loc.port;

import ai.neargo.sharehub.api.platform.dto.LocationOwnership;
import ai.neargo.sharehub.api.platform.port.LocationQueryPort;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

/** {@link LocationQueryPort} 的本地实现，住在 platform 侧（同 {@link LocalSiteQuery}）。 */
@Service
public class LocalLocationQuery implements LocationQueryPort {

    private final LocationMapper locations;
    private final SiteMapper sites;

    public LocalLocationQuery(LocationMapper locations, SiteMapper sites) {
        this.locations = locations;
        this.sites = sites;
    }

    @Override
    public LocationOwnership ownershipOf(String locationNo) {
        if (locationNo == null || locationNo.isBlank()) return null;
        LocLocation l = locations.selectOne(new LambdaQueryWrapper<LocLocation>()
                .eq(LocLocation::getLocationNo, locationNo).last("limit 1"));
        if (l == null) return null;
        /*
         * 代理商**以站点为准**，不读点位上的冗余 agent_no。
         * 那一列同样是冗余（划拨时级联回写），两处不一致时站点是源头 ——
         * 按点位取会让一次划拨中途失败留下的脏值变成新机柜的归属。
         */
        LocSite site = l.getSiteNo() == null ? null : sites.selectOne(
                new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, l.getSiteNo()).last("limit 1"));
        return new LocationOwnership(l.getLocationNo(), l.getName(),
                l.getSiteNo(), site == null ? l.getSiteName() : site.getName(),
                site == null ? null : site.getAgentNo());
    }
}
