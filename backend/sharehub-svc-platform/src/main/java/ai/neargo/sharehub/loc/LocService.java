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
import java.util.Map;

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
        // 点位数**现算**（一次分组查询覆盖本页，不是逐行 N+1，也不是读脱节的计数列）
        Map<String, Integer> points = pointCountsOf(r.getRecords().stream().map(LocSite::getSiteNo).toList());
        List<Site> rows = r.getRecords().stream()
                .map(x -> toSite(x, points.getOrDefault(x.getSiteNo(), 0))).toList();
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
        e.setVenueNo(in.venueNo());
        e.setVenueName(in.venueName());
        e.setAgentNo(in.agentNo());
        e.setRegionId(in.regionId());
        e.setAddress(in.address());
        // 经纬度：**只在显式给了值时才写**。表单没填就把已有坐标清成 null，
        // 会让这个站点从 C 端「找附近」里静默消失，而运营完全不知道自己做了什么。
        if (in.lng() != null) e.setLng(in.lng());
        if (in.lat() != null) e.setLat(in.lat());
        e.setSceneType(in.sceneType());
        /*
         * 点位数 / 机柜数**不接受调用方写入**：它们是按关系聚合出来的数
         * （db-design §1.4「计数不是列，是聚合」）。接受前端传值的后果是
         * 列表说「有 3 台机柜」而实际一台都没有 —— 运营端已经因为这个自相矛盾过一次。
         * 实体上已经没有这两个字段（列本就不存在），这里也不再有可写之处。
         */
        e.setStatus(in.status() == null ? "ACTIVE" : in.status());
        if (insert) siteMapper.insert(e); else siteMapper.updateById(e);
        return toSite(e);
    }

    /**
     * 暂停营业（运营管理清单 OM-S3）。
     *
     * <p>语义：C 端隐藏该站点、站内机柜不允许**新借**，<b>已借出的仍可归还</b> ——
     * 停业不该把用户的充电宝扣在手里。
     *
     * <p><b>已归档的站点不允许改营业状态</b>：归档是「这个站点不在经营范围里了」，
     * 在它上面谈营业与否没有意义，先恢复归档再操作。
     */
    public Site pauseSite(String siteNo, String reason) {
        LocSite e = requireSite(siteNo);
        if (e.getArchivedAt() != null) throw new IllegalArgumentException("已归档的站点不能暂停营业");
        if ("PAUSED".equals(e.getStatus())) throw new IllegalArgumentException("站点已处于暂停营业状态");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("请填写暂停原因");
        e.setStatus("PAUSED");
        e.setPauseReason(reason.trim());
        e.setPausedAt(java.time.LocalDateTime.now());
        siteMapper.updateById(e);
        return toSite(e, pointCountsOf(List.of(siteNo)).getOrDefault(siteNo, 0));
    }

    /** 恢复营业。清空暂停原因 —— 留着会让下次停业的界面显示上一次的理由。 */
    public Site resumeSite(String siteNo) {
        LocSite e = requireSite(siteNo);
        if (e.getArchivedAt() != null) throw new IllegalArgumentException("已归档的站点不能恢复营业");
        if ("ACTIVE".equals(e.getStatus())) throw new IllegalArgumentException("站点已在营业中");
        e.setStatus("ACTIVE");
        e.setPauseReason(null);
        e.setPausedAt(null);
        siteMapper.updateById(e);
        return toSite(e, pointCountsOf(List.of(siteNo)).getOrDefault(siteNo, 0));
    }

    /**
     * 按场地方统计名下在用站点数。
     *
     * <p><b>现算而不是读列</b>：`loc_venue.location_count` 曾是实体字段，但那一列
     * 在干净库里根本不存在 —— 2026-09-23 灌演示数据时它让服务直接起不来。
     * 即便补上也必然与实际脱节：每次站点增删都要记得回写，漏一次就对不上。
     *
     * <p>一次 IN 分组查询覆盖整页，不是逐行 N+1。
     */
    private Map<String, Integer> venueSiteCounts(List<String> venueNos) {
        List<String> ids = venueNos.stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        Map<String, Integer> out = new java.util.HashMap<>();
        for (LocSite s : siteMapper.selectList(new LambdaQueryWrapper<LocSite>()
                .in(LocSite::getVenueNo, ids).isNull(LocSite::getArchivedAt))) {
            if (s.getVenueNo() != null) out.merge(s.getVenueNo(), 1, Integer::sum);
        }
        return out;
    }

    private LocSite requireSite(String siteNo) {
        LocSite e = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>()
                .eq(LocSite::getSiteNo, siteNo).last("limit 1"));
        if (e == null) throw new IllegalArgumentException("站点不存在：" + siteNo);
        return e;
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
        // 机柜数不回写：同 saveSite，它是聚合值不是属性
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
        Map<String, Integer> counts = venueSiteCounts(r.getRecords().stream().map(LocVenue::getVenueNo).toList());
        List<Venue> rows = r.getRecords().stream()
                .map(v -> new Venue(v.getVenueNo(), v.getName(), v.getContact(), v.getIndustry(),
                        counts.getOrDefault(v.getVenueNo(), 0)))
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

    /**
     * 按站点统计在用点位数。
     *
     * <p><b>为什么现算</b>：`loc_site` 上曾有 `point_count` 字段（实体里有、迁移里没有，
     * 干净库上直接 500）。补成列的话，每一次点位增删都要记得回写，漏一次就是
     * 「列表说有 3 个点位、点进去一个都没有」—— 运营端已经踩过这种自相矛盾。
     *
     * <p>一次 IN 分组查询覆盖整页，不是逐行 N+1。
     */
    private Map<String, Integer> pointCountsOf(List<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return Map.of();
        Map<String, Integer> out = new java.util.HashMap<>();
        for (LocLocation l : locationMapper.selectList(new LambdaQueryWrapper<LocLocation>()
                .in(LocLocation::getSiteNo, siteNos).isNull(LocLocation::getArchivedAt))) {
            out.merge(l.getSiteNo(), 1, Integer::sum);
        }
        return out;
    }

    private static Site toSite(LocSite e) {
        return toSite(e, null);
    }

    private static Site toSite(LocSite e, Integer pointCount) {
        return new Site(e.getSiteNo(), e.getName(), e.getVenueNo(), e.getVenueName(), e.getAgentNo(),
                e.getRegionId(), e.getAddress(), e.getLng(), e.getLat(), e.getSceneType(),
                pointCount,
                /*
                 * 机柜数**这一层算不出来**：`dev_cabinet` 属于 core，platform 不该反向依赖它。
                 * 返回 null 而不是 0 —— 0 会被读成「这个站点一台机柜都没有」，
                 * 而真相是「这里答不了」。调用方（运营端站点页）本来就按关系现算。
                 */
                null, e.getStatus());
    }

    private static Location toLocation(LocLocation e) {
        // 机柜数同 toSite：platform 算不出（dev_cabinet 属于 core），返回 null 表示「这里答不了」
        return new Location(e.getLocationNo(), e.getName(), e.getSiteNo(), e.getSiteName(),
                e.getSpotDesc(), null, e.getStatus());
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
        return new Venue(e.getVenueNo(), e.getName(), e.getContact(), e.getIndustry(),
                venueSiteCounts(List.of(e.getVenueNo())).getOrDefault(e.getVenueNo(), 0));
    }
}
