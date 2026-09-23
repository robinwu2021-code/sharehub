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

    public LocalSiteQuery(SiteMapper sites) {
        this.sites = sites;
    }

    @Override
    public List<SiteBrief> briefsByNos(Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return List.of();
        return sites.selectList(new LambdaQueryWrapper<LocSite>()
                        .in(LocSite::getSiteNo, siteNos))
                .stream()
                .map(s -> new SiteBrief(s.getSiteNo(), s.getName(), s.getRegionId(),
                        s.getVenueNo(), s.getSceneType()))
                .toList();
    }
}
