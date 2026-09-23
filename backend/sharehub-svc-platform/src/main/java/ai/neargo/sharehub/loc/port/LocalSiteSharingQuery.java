package ai.neargo.sharehub.loc.port;

import ai.neargo.sharehub.api.platform.dto.SiteSharingBrief;
import ai.neargo.sharehub.api.platform.port.SiteSharingQueryPort;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.mapper.LocMappers.ContractMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.VenueMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * {@link SiteSharingQueryPort} 的本地实现，住在 platform 侧（同 {@link LocalSiteQuery}）。
 */
@Service
public class LocalSiteSharingQuery implements SiteSharingQueryPort {

    private final SiteMapper sites;
    private final ContractMapper contracts;
    private final VenueMapper venues;

    public LocalSiteSharingQuery(SiteMapper sites, ContractMapper contracts, VenueMapper venues) {
        this.sites = sites;
        this.contracts = contracts;
        this.venues = venues;
    }

    @Override
    public SiteSharingBrief sharingOf(String siteNo, String onDate) {
        if (siteNo == null || siteNo.isBlank()) return null;
        LocSite site = sites.selectOne(new LambdaQueryWrapper<LocSite>()
                .eq(LocSite::getSiteNo, siteNo).last("limit 1"));
        if (site == null) return null;

        String venueNo = site.getVenueNo();
        String venueName = null;
        if (venueNo != null && !venueNo.isBlank()) {
            LocVenue v = venues.selectOne(new LambdaQueryWrapper<LocVenue>()
                    .eq(LocVenue::getVenueNo, venueNo).last("limit 1"));
            venueName = v == null ? null : v.getName();
        }

        LocContract c = activeContract(siteNo, onDate);
        return new SiteSharingBrief(siteNo, venueNo, venueName,
                c == null || c.getShareRate() == null ? null : java.math.BigDecimal.valueOf(c.getShareRate()),
                c == null ? null : c.getContractNo(),
                c == null ? null : c.getCurrency());
    }

    /**
     * 站点在 {@code onDate} 生效的合同。
     *
     * <p><b>日期区间在 SQL 里判、不在内存里判</b>：`start_at`/`end_at` 是 DATE 列，
     * 字符串比较与日期比较在边界日会给出不同答案。
     *
     * <p><b>同一站点可能有多份合同</b>（续签、补充协议）。取 `start_at` 最晚的那份 ——
     * 续签就是要覆盖前一份。真有两份同日生效的，那是数据问题，这里取其一并保持确定性
     * （按合同号兜底排序），而不是随机挑一份：随机会让同一单在两次补算里分出不同的钱。
     */
    private LocContract activeContract(String siteNo, String onDate) {
        List<LocContract> list = contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getSiteNo, siteNo)
                .eq(LocContract::getStatus, "ACTIVE")
                .and(w -> w.isNull(LocContract::getStartAt).or().le(LocContract::getStartAt, onDate))
                .and(w -> w.isNull(LocContract::getEndAt).or().ge(LocContract::getEndAt, onDate)));
        return list.stream()
                .max(Comparator.comparing((LocContract c) -> c.getStartAt() == null ? "" : c.getStartAt())
                        .thenComparing(c -> c.getContractNo() == null ? "" : c.getContractNo()))
                .orElse(null);
    }
}
