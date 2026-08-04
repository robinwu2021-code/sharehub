package ai.neargo.sharehub.loc;

import ai.neargo.common.core.PageResult;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.LocDtos.Location;
import ai.neargo.sharehub.loc.dto.LocDtos.Venue;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.mapper.LocMappers.ContractMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.LocationMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.SiteMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers.VenueMapper;
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
    private final ai.neargo.sharehub.loc.ext.mapper.LocContractAttachMapper attachMapper;

    public LocService(SiteMapper siteMapper, LocationMapper locationMapper,
                      VenueMapper venueMapper, ContractMapper contractMapper,
                      ai.neargo.sharehub.loc.ext.mapper.LocContractAttachMapper attachMapper) {
        this.siteMapper = siteMapper;
        this.locationMapper = locationMapper;
        this.venueMapper = venueMapper;
        this.contractMapper = contractMapper;
        this.attachMapper = attachMapper;
    }

    private static int pg(Integer p) { return (p == null || p < 1) ? 1 : p; }
    private static int sz(Integer s) { return (s == null || s < 1) ? 10 : s; }
    private static boolean kw(String k) { return k != null && !k.isBlank(); }

    // —— 站点 ——
    public PageResult<Site> pageSites(Integer page, Integer size, String keyword) {
        Page<LocSite> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocSite> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) {
            w.and(q -> q.like(LocSite::getName, keyword).or().like(LocSite::getVenueName, keyword)
                    .or().like(LocSite::getRegionId, keyword));
        }
        w.orderByAsc(LocSite::getId);
        Page<LocSite> r = siteMapper.selectPage(p, w);
        List<Site> rows = r.getRecords().stream().map(LocService::toSite).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    public Site saveSite(String siteNo, Site in) {
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
    public PageResult<Location> pageLocations(Integer page, Integer size, String keyword) {
        Page<LocLocation> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocLocation> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) {
            w.and(q -> q.like(LocLocation::getName, keyword).or().like(LocLocation::getSiteName, keyword));
        }
        w.orderByAsc(LocLocation::getId);
        Page<LocLocation> r = locationMapper.selectPage(p, w);
        List<Location> rows = r.getRecords().stream().map(LocService::toLocation).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    public Location savePoint(String locationNo, Location in) {
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
    public PageResult<Venue> pageVenues(Integer page, Integer size, String keyword) {
        Page<LocVenue> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocVenue> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) w.like(LocVenue::getName, keyword);
        w.orderByAsc(LocVenue::getId);
        Page<LocVenue> r = venueMapper.selectPage(p, w);
        List<Venue> rows = r.getRecords().stream()
                .map(v -> new Venue(v.getVenueNo(), v.getName(), v.getContact(), v.getIndustry(), v.getLocationCount()))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    // —— 合同 ——
    public PageResult<Contract> pageContracts(Integer page, Integer size, String keyword) {
        Page<LocContract> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocContract> w = new LambdaQueryWrapper<>();
        if (kw(keyword)) {
            w.and(q -> q.like(LocContract::getVenueName, keyword).or().like(LocContract::getSiteName, keyword));
        }
        w.orderByAsc(LocContract::getId);
        Page<LocContract> r = contractMapper.selectPage(p, w);
        // 附件按本页合同号批量取（loc_contract_attach 从表，V28），避免 N+1
        java.util.List<String> nos = r.getRecords().stream().map(LocContract::getContractNo).toList();
        java.util.Map<String, java.util.List<ai.neargo.sharehub.loc.dto.LocDtos.ContractAttachment>> attachByNo =
                new java.util.HashMap<>();
        if (!nos.isEmpty()) {
            for (ai.neargo.sharehub.loc.ext.entity.LocContractAttach a : attachMapper.selectList(
                    new LambdaQueryWrapper<ai.neargo.sharehub.loc.ext.entity.LocContractAttach>()
                            .in(ai.neargo.sharehub.loc.ext.entity.LocContractAttach::getContractNo, nos)
                            .orderByAsc(ai.neargo.sharehub.loc.ext.entity.LocContractAttach::getId))) {
                attachByNo.computeIfAbsent(a.getContractNo(), k -> new java.util.ArrayList<>())
                        .add(new ai.neargo.sharehub.loc.dto.LocDtos.ContractAttachment(
                                a.getAttachNo(), a.getFileName(), a.getSize(), a.getUploadedBy(),
                                a.getUploadedAt() == null ? null : a.getUploadedAt().toString()));
            }
        }
        List<Contract> rows = r.getRecords().stream()
                .map(c -> new Contract(c.getContractNo(), c.getVenueName(), c.getSiteName(),
                        c.getShareRate(), c.getEntryFee(), c.getStartAt(), c.getEndAt(), c.getStatus(),
                        attachByNo.getOrDefault(c.getContractNo(), java.util.List.of())))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    private static Site toSite(LocSite e) {
        return new Site(e.getSiteNo(), e.getName(), e.getVenueName(), e.getAgentNo(), e.getRegionId(),
                e.getAddress(), e.getSceneType(),
                e.getPointCount() == null ? 0 : e.getPointCount(),
                e.getCabinetCount() == null ? 0 : e.getCabinetCount(), e.getStatus());
    }

    private static Location toLocation(LocLocation e) {
        return new Location(e.getLocationNo(), e.getName(), e.getSiteNo(), e.getSiteName(),
                e.getSpotDesc(), e.getCabinetCount() == null ? 0 : e.getCabinetCount(), e.getStatus());
    }

    // ───────────────── 归档 / 取消归档（前端契约 Archivable）─────────────────
    //
    // 场所域不走 AbstractCrudService（它是三个资源的统一服务，不是单表 CRUD），
    // 故在此实现同样的语义：archivedAt 时间戳，null=在用。
    // **不是删除** —— 行仍在，运营端勾「显示已归档」可见、可恢复。

    /** 归档站点。幂等：重复归档只是重新盖时间戳。 */
    @Transactional
    public Site archiveSite(String siteNo) {
        return setSiteArchived(siteNo, LocalDateTime.now());
    }

    @Transactional
    public Site unarchiveSite(String siteNo) {
        return setSiteArchived(siteNo, null);
    }

    private Site setSiteArchived(String siteNo, LocalDateTime at) {
        LocSite e = siteMapper.selectOne(
                new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, siteNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("站点不存在: " + siteNo);
        e.setArchivedAt(at);
        siteMapper.updateById(e);
        return toSite(e);
    }

    @Transactional
    public Location archiveLocation(String locationNo) {
        return setLocationArchived(locationNo, LocalDateTime.now());
    }

    @Transactional
    public Location unarchiveLocation(String locationNo) {
        return setLocationArchived(locationNo, null);
    }

    private Location setLocationArchived(String locationNo, LocalDateTime at) {
        LocLocation e = locationMapper.selectOne(
                new LambdaQueryWrapper<LocLocation>().eq(LocLocation::getLocationNo, locationNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("点位不存在: " + locationNo);
        e.setArchivedAt(at);
        locationMapper.updateById(e);
        return toLocation(e);
    }

    @Transactional
    public Venue archiveVenue(String venueNo) {
        return setVenueArchived(venueNo, LocalDateTime.now());
    }

    @Transactional
    public Venue unarchiveVenue(String venueNo) {
        return setVenueArchived(venueNo, null);
    }

    private Venue setVenueArchived(String venueNo, LocalDateTime at) {
        LocVenue e = venueMapper.selectOne(
                new LambdaQueryWrapper<LocVenue>().eq(LocVenue::getVenueNo, venueNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("场地方不存在: " + venueNo);
        e.setArchivedAt(at);
        venueMapper.updateById(e);
        return new Venue(e.getVenueNo(), e.getName(), e.getContact(), e.getIndustry(), e.getLocationCount());
    }
}
