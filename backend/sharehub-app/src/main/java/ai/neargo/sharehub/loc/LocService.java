package ai.neargo.powerbank.loc;

import ai.neargo.common.core.PageResult;
import ai.neargo.powerbank.dto.Dto;
import ai.neargo.powerbank.loc.entity.LocContract;
import ai.neargo.powerbank.loc.entity.LocLocation;
import ai.neargo.powerbank.loc.entity.LocSite;
import ai.neargo.powerbank.loc.entity.LocVenue;
import ai.neargo.powerbank.loc.mapper.LocMappers.ContractMapper;
import ai.neargo.powerbank.loc.mapper.LocMappers.LocationMapper;
import ai.neargo.powerbank.loc.mapper.LocMappers.SiteMapper;
import ai.neargo.powerbank.loc.mapper.LocMappers.VenueMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/** 场所域（loc_）持久化服务：站点/点位/场地方/合同 走 MariaDB，实体↔DTO 转换。 */
@Service
public class LocService {

    private final SiteMapper siteMapper;
    private final LocationMapper locationMapper;
    private final VenueMapper venueMapper;
    private final ContractMapper contractMapper;

    public LocService(SiteMapper siteMapper, LocationMapper locationMapper,
                      VenueMapper venueMapper, ContractMapper contractMapper) {
        this.siteMapper = siteMapper;
        this.locationMapper = locationMapper;
        this.venueMapper = venueMapper;
        this.contractMapper = contractMapper;
    }

    private static int pg(Integer p) { return (p == null || p < 1) ? 1 : p; }
    private static int sz(Integer s) { return (s == null || s < 1) ? 10 : s; }
    private static boolean kw(String k) { return k != null && !k.isBlank(); }

    // —— 站点 ——
    public PageResult<Dto.Site> pageSites(Integer page, Integer size, String keyword) {
        Page<LocSite> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocSite> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) {
            w.and(q -> q.like(LocSite::getName, keyword).or().like(LocSite::getVenueName, keyword)
                    .or().like(LocSite::getRegionId, keyword));
        }
        w.orderByAsc(LocSite::getId);
        Page<LocSite> r = siteMapper.selectPage(p, w);
        List<Dto.Site> rows = r.getRecords().stream().map(LocService::toSite).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    public Dto.Site saveSite(String siteNo, Dto.Site in) {
        String no = (siteNo != null && !siteNo.isBlank()) ? siteNo
                : (in.siteNo() != null && !in.siteNo().isBlank()) ? in.siteNo()
                : "ST" + (300 + Math.toIntExact(siteMapper.selectCount(null)));
        LocSite e = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, no));
        boolean insert = (e == null);
        if (insert) { e = new LocSite(); e.setSiteNo(no); e.setTenantId("MAIN"); }
        e.setName(in.name());
        e.setVenueName(in.venueName());
        e.setAgentNo(in.agentNo());
        e.setRegionId(in.regionId());
        e.setAddress(in.address());
        e.setSceneType(in.sceneType());
        e.setPointCount(in.pointCount());
        e.setCabinetCount(in.cabinetCount());
        e.setStatus(in.status() == null ? "ACTIVE" : in.status());
        if (insert) siteMapper.insert(e); else siteMapper.updateById(e);
        return toSite(e);
    }

    // —— 点位 ——
    public PageResult<Dto.Location> pageLocations(Integer page, Integer size, String keyword) {
        Page<LocLocation> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocLocation> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) {
            w.and(q -> q.like(LocLocation::getName, keyword).or().like(LocLocation::getSiteName, keyword));
        }
        w.orderByAsc(LocLocation::getId);
        Page<LocLocation> r = locationMapper.selectPage(p, w);
        List<Dto.Location> rows = r.getRecords().stream().map(LocService::toLocation).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    public Dto.Location savePoint(String locationNo, Dto.Location in) {
        String no = (locationNo != null && !locationNo.isBlank()) ? locationNo
                : (in.locationNo() != null && !in.locationNo().isBlank()) ? in.locationNo()
                : "LOC" + (200 + Math.toIntExact(locationMapper.selectCount(null)));
        LocLocation e = locationMapper.selectOne(new LambdaQueryWrapper<LocLocation>().eq(LocLocation::getLocationNo, no));
        boolean insert = (e == null);
        if (insert) { e = new LocLocation(); e.setLocationNo(no); e.setTenantId("MAIN"); }
        e.setName(in.name());
        e.setSiteNo(in.siteNo());
        e.setSiteName(in.siteName());
        e.setSpotDesc(in.spotDesc());
        e.setCabinetCount(in.cabinetCount());
        e.setStatus(in.status() == null ? "ACTIVE" : in.status());
        if (insert) locationMapper.insert(e); else locationMapper.updateById(e);
        return toLocation(e);
    }

    // —— 场地方 ——
    public PageResult<Dto.Venue> pageVenues(Integer page, Integer size, String keyword) {
        Page<LocVenue> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocVenue> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) w.like(LocVenue::getName, keyword);
        w.orderByAsc(LocVenue::getId);
        Page<LocVenue> r = venueMapper.selectPage(p, w);
        List<Dto.Venue> rows = r.getRecords().stream()
                .map(v -> new Dto.Venue(v.getVenueNo(), v.getName(), v.getContact(), v.getIndustry(), v.getLocationCount()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    // —— 合同 ——
    public PageResult<Dto.Contract> pageContracts(Integer page, Integer size, String keyword) {
        Page<LocContract> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocContract> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) {
            w.and(q -> q.like(LocContract::getVenueName, keyword).or().like(LocContract::getSiteName, keyword));
        }
        w.orderByAsc(LocContract::getId);
        Page<LocContract> r = contractMapper.selectPage(p, w);
        List<Dto.Contract> rows = r.getRecords().stream()
                .map(c -> new Dto.Contract(c.getContractNo(), c.getVenueName(), c.getSiteName(),
                        c.getShareRate(), c.getEntryFee(), c.getStartAt(), c.getEndAt(), c.getStatus()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private static Dto.Site toSite(LocSite e) {
        return new Dto.Site(e.getSiteNo(), e.getName(), e.getVenueName(), e.getAgentNo(), e.getRegionId(),
                e.getAddress(), e.getSceneType(),
                e.getPointCount() == null ? 0 : e.getPointCount(),
                e.getCabinetCount() == null ? 0 : e.getCabinetCount(), e.getStatus());
    }

    private static Dto.Location toLocation(LocLocation e) {
        return new Dto.Location(e.getLocationNo(), e.getName(), e.getSiteNo(), e.getSiteName(),
                e.getSpotDesc(), e.getCabinetCount() == null ? 0 : e.getCabinetCount(), e.getStatus());
    }
}
