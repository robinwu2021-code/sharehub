package ai.neargo.sharehub.loc;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.audit.AuditChanges;
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
    private final ai.neargo.sharehub.platform.md.mapper.MdRegionMapper regions;
    private final ai.neargo.sharehub.common.event.DomainEventBus events;

    /** 合同状态词表只有 ACTIVE / EXPIRED；录入即已签（见 saveContract 注释）。 */
    private static final String ACTIVE = "ACTIVE";

    public LocService(SiteMapper siteMapper, LocationMapper locationMapper,
                      VenueMapper venueMapper, ContractMapper contractMapper,
                      ai.neargo.sharehub.loc.ext.mapper.LocContractAttachMapper attachMapper,
                      ai.neargo.sharehub.platform.md.mapper.MdRegionMapper regions,
                      ai.neargo.sharehub.common.event.DomainEventBus events) {
        this.regions = regions;
        this.events = events;
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

    public Site saveSite(String siteNo, Site in) {
        String no = (siteNo != null && !siteNo.isBlank()) ? siteNo
                : (in.siteNo() != null && !in.siteNo().isBlank()) ? in.siteNo()
                : "ST" + (300 + Math.toIntExact(siteMapper.selectCount(null)));
        LocSite e = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, no));
        boolean insert = (e == null);
        if (insert) { e = new LocSite(); e.setSiteNo(no); e.setTenantId("MAIN"); }
        if (!insert) {
            // **先记后改**：下面全是就地 set，赋值之后旧值就没了。
            AuditChanges.record("名称", e.getName(), in.name());
            AuditChanges.record("场地方", e.getVenueNo(), in.venueNo());
            // 归属代理是**数据范围的锚点**：改了它，原代理就不该再看得见这个站点的单子。
            // 「谁在什么时候把它从某个代理改成直营」是排查范围问题时第一个要问的 ——
            // 而在此之前，这个问题在库里查不到答案。
            AuditChanges.record("归属代理", e.getAgentNo(), in.agentNo());
            AuditChanges.record("状态", e.getStatus(), in.status() == null ? "ACTIVE" : in.status());
        }
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

    private LocSite requireSite(String siteNo) {
        LocSite e = siteMapper.selectOne(new LambdaQueryWrapper<LocSite>()
                .eq(LocSite::getSiteNo, siteNo).last("limit 1"));
        if (e == null) throw BizException.notFound(siteNo);
        return e;
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
        e.setStatus(in.status() == null ? "ACTIVE" : in.status());
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
                .map(c -> new Contract(c.getContractNo(), c.getVenueNo(), c.getSiteNo(),
                        c.getVenueName(), c.getSiteName(),
                        c.getShareRate(), c.getEntryFee(), c.getStartAt(), c.getEndAt(), c.getStatus(),
                        attachByNo.getOrDefault(c.getContractNo(), java.util.List.of())))
                .toList();
        return new PageResult<>(rows, r.getTotal());
    }

    /**
     * 新建 / 修改进场合同。
     *
     * <p><b>这个写入口此前根本不存在</b>：`loc_contract` 只有种子在写，
     * 前端的「新增/编辑合同」在 {@code USE_MOCK=0} 下必 404
     * （见《功能矩阵-前后端贯通对照表》里那 7 个缺失的 save*）。
     * 而合同是**场地方分成的唯一依据**，建不了合同就等于场地方费率只能靠改库。
     *
     * <p><b>状态取 ACTIVE 即视为已签</b>：本域的状态词表只有 ACTIVE / EXPIRED，
     * 没有草稿态 —— 运营录入的本来就是一份**已经签好的**纸质合同，
     * 「录入」这个动作本身就代表签约。所以不另造 DRAFT：
     * 多一个没人会停留的状态，只会让每份合同多一次点击。
     *
     * <p><b>签约事件在事务提交后才投递</b>（{@code DomainEventBus} 走 outbox）：
     * 在事务内发事件，若随后回滚，消费方就会按一个从未发生的事实付掉一笔牵线费。
     */
    @org.springframework.transaction.annotation.Transactional
    public Contract saveContract(LocContract body) {
        if (body.getSiteNo() == null || body.getSiteNo().isBlank()) {
            // 合同不绑站点的话，场地方分成取价时找不到它，会静默回落到通用规则 ——
            // 而回落值与合同里白纸黑字写的比例往往不一样。
            throw new IllegalArgumentException("合同必须绑定站点：站点是场地方分成的连接键");
        }
        if (body.getStartAt() != null && body.getEndAt() != null
                && !body.getStartAt().isBlank() && !body.getEndAt().isBlank()
                && body.getEndAt().compareTo(body.getStartAt()) < 0) {
            throw new IllegalArgumentException("合同结束日不能早于开始日");
        }
        if (body.getStatus() == null || body.getStatus().isBlank()) body.setStatus(ACTIVE);

        String no = body.getContractNo();
        LocContract current = (no == null || no.isBlank()) ? null
                : contractMapper.selectOne(new LambdaQueryWrapper<LocContract>()
                        .eq(LocContract::getContractNo, no).last("limit 1"));

        if (current == null) {
            if (no == null || no.isBlank()) {
                body.setContractNo(ai.neargo.common.core.IdGenerator.next(
                        ai.neargo.sharehub.common.BizKey.CONTRACT));
            }
            if (body.getTenantId() == null) body.setTenantId("MAIN");
            contractMapper.insert(body);
        } else {
            // 服务端决定的字段一律从 current 取，不看客户端传了什么（同 AbstractCrudService 的加固）
            body.setId(current.getId());
            body.setVersion(current.getVersion());
            body.setTenantId(current.getTenantId());
            body.setDeleted(current.getDeleted());
            body.setCreatedAt(current.getCreatedAt());
            // LocContract 不继承 BaseEntity，没有 createdBy —— 审计列在 V10 加到了表上，
            // 但实体没跟着加。这里不顺手补：补了要连带确认 AuditMetaObjectHandler 的填充范围，
            // 是另一件事（见《实体-领域对象对账表》里那批漂移）。
            // 合同的费率与期限是场地方分成的取价依据。只记一条「改过合同」，
            // 事后对不上账时仍然答不出「这单按 8% 还是 5% 算的」。
            AuditChanges.record("场地方分成比率", current.getShareRate(), body.getShareRate());
            AuditChanges.record("进场费", current.getEntryFee(), body.getEntryFee());
            AuditChanges.record("生效起", current.getStartAt(), body.getStartAt());
            AuditChanges.record("生效止", current.getEndAt(), body.getEndAt());
            AuditChanges.record("状态", current.getStatus(), body.getStatus());
            AuditChanges.record("绑定站点", current.getSiteNo(), body.getSiteNo());
            contractMapper.updateById(body);
        }

        // 已签 → 通知 finance 结一次性牵线费。**每次保存都发**：重复投递由
        // share_record 的唯一键（order_no, dimension, payee_no, basis）挡住，
        // 不会多付；而「只在状态首次变 ACTIVE 时发」要额外判前态，
        // 漏判的后果是牵线人一分钱都收不到，且没有任何地方看得出来。
        if (ACTIVE.equals(body.getStatus())) {
            events.publish(new ai.neargo.sharehub.api.platform.event.ContractSignedEvent(
                    body.getContractNo(), body.getSiteNo(), body.getVenueNo(), body.getCurrency()));
        }
        // 按**编号**读回，不走 pageContracts —— 它的 keyword 只匹配场地方名/站点名，
        // 拿合同号去搜必然搜不到（第一版就是这么写的，每次保存都 500）。
        LocContract saved = contractMapper.selectOne(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getContractNo, body.getContractNo()).last("limit 1"));
        return toContractVO(saved);
    }

    /** 合同实体 → VO，附件单独查（同 pageContracts 的口径）。 */
    private Contract toContractVO(LocContract c) {
        java.util.List<ai.neargo.sharehub.loc.dto.LocDtos.ContractAttachment> atts =
                attachMapper.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.loc.ext.entity.LocContractAttach>()
                                .eq(ai.neargo.sharehub.loc.ext.entity.LocContractAttach::getContractNo, c.getContractNo())
                                .orderByAsc(ai.neargo.sharehub.loc.ext.entity.LocContractAttach::getId))
                        .stream()
                        .map(a -> new ai.neargo.sharehub.loc.dto.LocDtos.ContractAttachment(
                                a.getAttachNo(), a.getFileName(), a.getSize(), a.getUploadedBy(),
                                a.getUploadedAt() == null ? null : a.getUploadedAt().toString()))
                        .toList();
        return new Contract(c.getContractNo(), c.getVenueNo(), c.getSiteNo(),
                c.getVenueName(), c.getSiteName(),
                c.getShareRate(), c.getEntryFee(), c.getStartAt(), c.getEndAt(), c.getStatus(), atts);
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
        if (e == null) throw BizException.notFound(siteNo);
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
