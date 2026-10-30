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
    /** 取号要绕开逻辑删除过滤，只能走原生 SQL —— 见 BizNoSequence 的类注释。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final VenueMapper venueMapper;
    private final ai.neargo.sharehub.platform.md.mapper.MdRegionMapper regions;

    public LocService(SiteMapper siteMapper, LocationMapper locationMapper,
                      VenueMapper venueMapper,
                      ai.neargo.sharehub.platform.md.mapper.MdRegionMapper regions,
                      org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
     * 按站点号单查（未归档）。不存在返回 {@code null}。
     *
     * <p><b>为什么要单独一个方法</b>：调用方此前的做法是 {@code pageSites(1, 200, …)} 再按
     * {@code siteNo} 过滤 —— 那把**分页默认值当成了业务上限**。站点一过 200 个，
     * 靠后的站点查详情就报「门店不存在」：真实存在的店说不存在，而且不报错、不告警。
     * 实测测试库已有 1600+ 站点，绝大多数门店详情早就查不到了。
     *
     * <p>把上限调大不是修复，只是把同一个洞推远一点。
     */
    public Site siteOf(String siteNo) {
        if (siteNo == null || siteNo.isBlank()) return null;
        LocSite e = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>()
                .eq(LocSite::getSiteNo, siteNo).isNull(LocSite::getArchivedAt).last("limit 1"));
        if (e == null) return null;
        return toSite(e, pointCountsOf(java.util.List.of(siteNo)).getOrDefault(siteNo, 0));
    }

    /**
     * 按站点号集合批查（未归档），保持传入顺序无关、返回按 id 升序。
     *
     * <p>同 {@link #siteOf} 的理由：收藏夹此前是从 {@code pageSites(1, 200)} 里挑出
     * 「我收藏的那些」—— 收藏了靠后的店，它就是不出现在收藏夹里。
     */
    public List<Site> sitesByNos(java.util.Collection<String> siteNos) {
        if (siteNos == null || siteNos.isEmpty()) return List.of();
        List<String> keys = siteNos.stream().filter(x -> x != null && !x.isBlank()).distinct().toList();
        if (keys.isEmpty()) return List.of();
        List<LocSite> rows = siteMapper.selectList(new LambdaQueryWrapper<LocSite>()
                .in(LocSite::getSiteNo, keys).isNull(LocSite::getArchivedAt)
                .orderByAsc(LocSite::getId));
        Map<String, Integer> points = pointCountsOf(rows.stream().map(LocSite::getSiteNo).toList());
        return rows.stream().map(x -> toSite(x, points.getOrDefault(x.getSiteNo(), 0))).toList();
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
        return pageLocations(page, size, keyword, showArchived, null, null);
    }

    /** 带站点 / 商家筛选（V162）：商家详情的「站点与点位」与绑定抽屉按它取。默认点位排最前。 */
    public PageResult<Location> pageLocations(Integer page, Integer size, String keyword, Boolean showArchived,
                                              String siteNo, String agentNo) {
        Page<LocLocation> p = new Page<>(pg(page), sz(size));
        LambdaQueryWrapper<LocLocation> w = new LambdaQueryWrapper<>();
        if (!Boolean.TRUE.equals(showArchived)) w.isNull(LocLocation::getArchivedAt);
        if (kw(siteNo)) w.eq(LocLocation::getSiteNo, siteNo);
        if (kw(agentNo)) w.eq(LocLocation::getAgentNo, agentNo);
        if (kw(siteNo) || kw(agentNo)) w.orderByDesc(LocLocation::getIsDefault);
        if (kw(keyword)) {
            w.and(q -> q.like(LocLocation::getName, keyword).or().like(LocLocation::getSiteName, keyword));
        }
        w.orderByAsc(LocLocation::getId);
        Page<LocLocation> r = locationMapper.selectPage(p, w);
        List<Location> rows = r.getRecords().stream().map(LocService::toLocation).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    /**
     * 点位新建 / 编辑（V162 改造）。
     *
     * <ul>
     *   <li><b>编号服务端取</b>：路径上没有编号 = 新建，取号器给号；路径上有编号却查不到 = 404，
     *       不再「顺手按客户端给的号新建」（打错一位就多出一个真实点位）。请求体里的 locationNo 不作数。</li>
     *   <li><b>站点必须存在</b>，站点名与商家**由站点派生**：此前新建点位从不写 agent_no，
     *       商家因此看不到自己站点上的新点位；站点名也不再收客户端传的。</li>
     *   <li><b>点位不能换站点</b>：机柜的站点与商家都随点位派生，换站点会让已绑定的机柜指错地方。</li>
     *   <li>默认点位标记不经这里改（建站时定）。</li>
     * </ul>
     */
    @Transactional
    public Location savePoint(String locationNo, Location in) {
        if (in == null) throw BizException.badRequest("error.common.missing_parameter", "body");
        if (in.name() == null || in.name().isBlank()) throw BizException.badRequest("error.common.missing_parameter", "name");
        boolean insert = locationNo == null || locationNo.isBlank();
        LocLocation e;
        if (insert) {
            e = new LocLocation();
            e.setLocationNo(ai.neargo.sharehub.loc.DefaultPoints.nextNo(jdbc));
            e.setTenantId("MAIN");
            e.setIsDefault(0);
        } else {
            e = locationMapper.selectOne(new LambdaQueryWrapper<LocLocation>().eq(LocLocation::getLocationNo, locationNo).last("limit 1"));
            if (e == null) throw BizException.notFound(locationNo);
        }
        String siteNo = insert ? in.siteNo() : e.getSiteNo();
        if (!insert && in.siteNo() != null && !in.siteNo().isBlank() && !in.siteNo().equals(e.getSiteNo())) {
            throw BizException.conflict("error.location.site_immutable", e.getLocationNo());
        }
        if (siteNo == null || siteNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "siteNo");
        LocSite site = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, siteNo).last("limit 1"));
        if (site == null) throw BizException.notFound(siteNo);
        e.setName(in.name().trim());
        e.setSiteNo(site.getSiteNo());
        e.setSiteName(site.getName());
        e.setAgentNo(site.getAgentNo());
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
        /*
         * **新建的排最上**（倒序）。此前是 `orderByAsc` —— 建完一个场地方回到列表，
         * 它在最后一页，而运营的下一步通常就是接着操作这一条。
         *
         * 顺带把一处 mock 与真后端的分叉抹平：mock 的 `upsert` 用的是 `arr.unshift`，
         * 也就是**新建的本来就在最上**。离线开发时看到的顺序与线上相反，
         * 而这种差异不会报错，只会让人在线上觉得「刚建的没保存成功」。
         */
        w.orderByDesc(LocVenue::getId);
        Page<LocVenue> r = venueMapper.selectPage(p, w);
        Map<String, Integer> counts = venueSiteCounts(r.getRecords().stream().map(LocVenue::getVenueNo).toList());
        List<Venue> rows = r.getRecords().stream()
                .map(v -> new Venue(v.getVenueNo(), v.getName(), v.getContact(), v.getIndustry(),
                        counts.getOrDefault(v.getVenueNo(), 0), str(v.getArchivedAt()),
                        v.getAddress(), v.getLng(), v.getLat()))
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
        /*
         * 行业收成词表（V121）。**非法值当场拒**，不是默默存下去 ——
         * 存下去的后果是运营端下拉选不中它（显示空白），一保存又被改成别的值。
         * 空值放行：行业不是必填，「还没问清楚」是合法状态。
         */
        if (body.getIndustry() != null && !body.getIndustry().isBlank()
                && VenueIndustry.of(body.getIndustry()).isEmpty()) {
            throw BizException.badRequest("error.venue.industry_invalid", body.getIndustry());
        }

        String no = body.getVenueNo();
        LocVenue current = (no == null || no.isBlank()) ? null
                : venueMapper.selectOne(new LambdaQueryWrapper<LocVenue>()
                        .eq(LocVenue::getVenueNo, no).last("limit 1"));

        if (current == null) {
            if (no == null || no.isBlank()) {
                body.setVenueNo(nextVenueNo());
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
                        .getOrDefault(saved.getVenueNo(), 0), str(saved.getArchivedAt()),
                saved.getAddress(), saved.getLng(), saved.getLat());
    }

    /**
     * 下一个场地方编号：{@code VEN} + 三位顺序号（{@code VEN305}…），与种子 {@code VEN300~304} 同形。
     * 取号规则与已知取舍见 {@link ai.neargo.sharehub.common.crud.BizNoSequence}。
     */
    private String nextVenueNo() {
        return ai.neargo.sharehub.common.crud.BizNoSequence.next(
                jdbc, "loc_venue", "venue_no", ai.neargo.sharehub.common.BizKey.VENUE, 3);
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
                e.getSpotDesc(), null, e.getStatus(), e.getIsDefault() != null && e.getIsDefault() == 1, e.getAgentNo());
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
                str(e.getArchivedAt()), e.getAddress(), e.getLng(), e.getLat());
    }
}
