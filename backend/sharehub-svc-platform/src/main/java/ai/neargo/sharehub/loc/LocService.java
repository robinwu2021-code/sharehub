package ai.neargo.sharehub.loc;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import java.time.LocalDateTime;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.LocDtos.Location;
import ai.neargo.sharehub.loc.dto.LocDtos.Venue;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocVenue;
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
    private final ai.neargo.sharehub.platform.md.mapper.MdRegionMapper regions;

    public LocService(SiteMapper siteMapper, LocationMapper locationMapper,
                      VenueMapper venueMapper,
                      ai.neargo.sharehub.platform.md.mapper.MdRegionMapper regions) {
        this.regions = regions;
        this.siteMapper = siteMapper;
        this.locationMapper = locationMapper;
        this.venueMapper = venueMapper;
    }

    private static int pg(Integer p) { return (p == null || p < 1) ? 1 : p; }
    private static int sz(Integer s) { return (s == null || s < 1) ? 10 : s; }
    private static boolean kw(String k) { return k != null && !k.isBlank(); }

    // —— 站点 ——
    /**
     * 「默认不看已归档」的统一口径。
     *
     * <p><b>补的是一个让归档形同虚设的缺陷</b>：场所域三张表的归档端点一直在写
     * {@code archived_at}，而三个列表查询**从来没有读过它** —— 归了档的场地方/站点/点位
     * 照样出现在列表里，前端传的 {@code showArchived} 也被整个丢掉。
     * 运营点了归档、提示成功、东西还在原地。
     *
     * <p>其余 15 个可归档资源走 {@code AbstractCrudService}，本来就有这个语义；
     * 场所域因为是三资源合一的服务没继承它，于是漏掉了。
     */
    public PageResult<Site> pageSites(Integer page, Integer size, String keyword, Boolean showArchived) {
        Page<LocSite> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocSite> w = new LambdaQueryWrapper<>();
        if (!Boolean.TRUE.equals(showArchived)) w.isNull(LocSite::getArchivedAt);
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

    /**
     * 区域展示名。查不到就回落成 ID —— 显示一个陌生的 ID 也好过显示空白：
     * 前者能让人去地区库里对，后者只会让人以为这个站点没设区域。
     */
    private String regionName(String regionId) {
        if (regionId == null || regionId.isBlank()) return null;
        var r = regions.selectOne(new LambdaQueryWrapper<ai.neargo.sharehub.platform.md.entity.MdRegion>()
                .eq(ai.neargo.sharehub.platform.md.entity.MdRegion::getRegionId, regionId).last("limit 1"));
        return r == null || r.getName() == null ? regionId : r.getName();
    }

    // —— 点位 ——
    public PageResult<Location> pageLocations(Integer page, Integer size, String keyword, Boolean showArchived) {
        Page<LocLocation> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocLocation> w = new LambdaQueryWrapper<>();
        if (!Boolean.TRUE.equals(showArchived)) w.isNull(LocLocation::getArchivedAt);
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
        // 词表校验（C2）：此前任意字符串都能落库，两端徽标映射不上、按状态筛也筛不到
        e.setStatus(in.status() == null || in.status().isBlank() ? LocationStatus.ACTIVE.name() : LocationStatus.of(in.status()).name());
        if (insert) locationMapper.insert(e); else locationMapper.updateById(e);
        return toLocation(e);
    }

    // —— 场地方 ——
    public PageResult<Venue> pageVenues(Integer page, Integer size, String keyword, Boolean showArchived) {
        Page<LocVenue> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocVenue> w = new LambdaQueryWrapper<>();
        if (!Boolean.TRUE.equals(showArchived)) w.isNull(LocVenue::getArchivedAt);
        if (kw(keyword)) w.like(LocVenue::getName, keyword);
        w.orderByAsc(LocVenue::getId);
        Page<LocVenue> r = venueMapper.selectPage(p, w);
        Map<String, Integer> counts = venueSiteCounts(r.getRecords().stream().map(LocVenue::getVenueNo).toList());
        List<Venue> rows = r.getRecords().stream()
                .map(v -> new Venue(v.getVenueNo(), v.getName(), v.getContact(), v.getIndustry(),
                        counts.getOrDefault(v.getVenueNo(), 0), str(v.getArchivedAt())))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    /**
     * 新建 / 修改场地方。
     *
     * <p><b>这条链的第一环此前是断的</b>：`loc_venue` 只有种子在写，
     * 前端的「新增/编辑场地方」在 {@code USE_MOCK=0} 下必 404。
     * 于是运营能签合同、能配站点责任、能按责任分账，**却建不出一个新场地方** ——
     * 而场地方是「场地方 → 合同 → 站点 → 责任 → 分账」整条链的起点。
     *
     * <p><b>同名不拦</b>：同一个品牌在不同城市各有主体、同名不同主体是常态
     *（`Emaar Malls` 在这份种子里就出现两次）。靠名字判重会把合法的第二家挡在门外，
     * 而运营只能改名绕过去 —— 绕出来的名字日后没人认得。判重靠业务键。
     */
    @org.springframework.transaction.annotation.Transactional
    public Venue saveVenue(LocVenue body) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new IllegalArgumentException("场地方名称必填");
        }
        body.setName(body.getName().trim());

        String no = body.getVenueNo();
        LocVenue current = (no == null || no.isBlank()) ? null
                : venueMapper.selectOne(new LambdaQueryWrapper<LocVenue>()
                        .eq(LocVenue::getVenueNo, no).last("limit 1"));

        if (current == null) {
            if (no == null || no.isBlank()) {
                body.setVenueNo(ai.neargo.common.core.IdGenerator.next(
                        ai.neargo.sharehub.common.BizKey.VENUE));
            }
            if (body.getTenantId() == null) body.setTenantId("MAIN");
            venueMapper.insert(body);
        } else {
            // 服务端决定的字段一律从库取，不看客户端传了什么（同 AbstractCrudService 的批量赋值加固）。
            // archivedAt 也在其中：归档/恢复有专门端点，编辑表单不该顺手把一个已归档的场地方复活。
            body.setId(current.getId());
            body.setVersion(current.getVersion());
            body.setTenantId(current.getTenantId());
            body.setDeleted(current.getDeleted());
            body.setCreatedAt(current.getCreatedAt());
            body.setArchivedAt(current.getArchivedAt());
            venueMapper.updateById(body);
        }

        LocVenue saved = venueMapper.selectOne(new LambdaQueryWrapper<LocVenue>()
                .eq(LocVenue::getVenueNo, body.getVenueNo()).last("limit 1"));
        return new Venue(saved.getVenueNo(), saved.getName(), saved.getContact(), saved.getIndustry(),
                venueSiteCounts(java.util.List.of(saved.getVenueNo()))
                        .getOrDefault(saved.getVenueNo(), 0), str(saved.getArchivedAt()));
    }

    // —— 合同 —— 已迁入 loc.service.ContractService（2026-09-25 合同走审批）

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

    private Site toSite(LocSite e) {
        return toSite(e, null);
    }

    private Site toSite(LocSite e, Integer pointCount) {
        return new Site(e.getSiteNo(), e.getName(), e.getVenueNo(), e.getVenueName(), e.getAgentNo(),
                e.getBrandNo(), e.getRegionId(), regionName(e.getRegionId()),
                e.getAddress(), e.getLng(), e.getLat(), e.getSceneType(),
                pointCount,
                /*
                 * 机柜数**这一层算不出来**：`dev_cabinet` 属于 core，platform 不该反向依赖它。
                 * 返回 null 而不是 0 —— 0 会被读成「这个站点一台机柜都没有」，
                 * 而真相是「这里答不了」。调用方（运营端站点页）本来就按关系现算。
                 */
                null, e.getStatus(), str(e.getArchivedAt()),
                e.getNameAr(), e.getOpenHours());
    }

    /** 归档时间统一转字符串；`null` 原样传下去（前端按它判在用/已归档）。 */
    private static String str(java.time.LocalDateTime t) {
        return t == null ? null : t.toString();
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
        if (e == null) throw BizException.notFound(locationNo);
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
        if (e == null) throw BizException.notFound(venueNo);
        e.setArchivedAt(at);
        venueMapper.updateById(e);
        return new Venue(e.getVenueNo(), e.getName(), e.getContact(), e.getIndustry(),
                venueSiteCounts(List.of(e.getVenueNo())).getOrDefault(e.getVenueNo(), 0),
                str(e.getArchivedAt()));
    }
}
