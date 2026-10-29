package ai.neargo.sharehub.agent.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.api.platform.dto.AgentType;
import ai.neargo.sharehub.agent.entity.AgtAgent;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.sharehub.agent.mapper.AgentMapper;
import ai.neargo.sharehub.agent.service.AgentService;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.agent.dto.AgentDtos.Agent;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.common.core.IdGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 代理商业务实现。数据过滤由 DataScopeInterceptor 横切（Service 不写 where）；
 * 功能权限在 Controller 层 @PreAuthorize（Service 不判权限）。
 */
@Service
public class AgentServiceImpl implements AgentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentServiceImpl.class);

    /** 取号要绕开逻辑删除过滤，只能走原生 SQL —— 见 BizNoSequence 的类注释。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final AgentMapper mapper;
    /** 只用来判「这个代理正在清退中」—— 见 save 里那条闸。 */
    private final ai.neargo.sharehub.agent.ext.service.AgentExitService exits;

    private final ai.neargo.sharehub.common.event.DomainEventBus events;

    /** 默认站点（V157）：建站、写运维责任、划拨归属。agent → loc 是既有依赖方向，loc 不反向依赖 agent。 */
    private final ai.neargo.sharehub.loc.service.SiteService sites;
    private final ai.neargo.sharehub.loc.ext.service.SiteAgentService siteAgents;
    private final ai.neargo.sharehub.agent.ext.service.AgentAssignmentService assignments;

    public AgentServiceImpl(AgentMapper mapper, ai.neargo.sharehub.common.event.DomainEventBus events,
                            ai.neargo.sharehub.agent.ext.service.AgentExitService exits,
                            org.springframework.jdbc.core.JdbcTemplate jdbc,
                            ai.neargo.sharehub.loc.service.SiteService sites,
                            ai.neargo.sharehub.loc.ext.service.SiteAgentService siteAgents,
                            ai.neargo.sharehub.agent.ext.service.AgentAssignmentService assignments) {
        this.sites = sites;
        this.siteAgents = siteAgents;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.exits = exits;
        this.events = events;
        this.mapper = mapper;
    }

    @Override
    public PageResult<Agent> page(Integer page, Integer size, String keyword) {
        int p = (page == null || page < 1) ? 1 : page;
        int s = (size == null || size < 1) ? 10 : size;
        LambdaQueryWrapper<AgtAgent> w = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            w.and(q -> q.like(AgtAgent::getName, keyword).or().like(AgtAgent::getAgentNo, keyword)
                    .or().like(AgtAgent::getRegionScope, keyword));
        }
        w.orderByAsc(AgtAgent::getId);
        Page<AgtAgent> r = mapper.selectPage(new Page<>(p, s), w);
        List<Agent> rows = r.getRecords().stream().map(AgentServiceImpl::toVO).toList();
        return new PageResult<>(rows, r.getTotal());
    }

    @Override
    @Transactional
    public Agent save(String agentNo, Agent in) {
        /*
         * **建档时一律服务端取号，不看客户端传了什么**。
         * 原先这里是「路径没给就取 body 里的」—— 于是新建时在请求体里塞一个 agentNo
         * 就能建出任意编号的代理商，而代理编号是分润、划拨、结算都认的业务键。
         * 改档走路径上的编号（`POST /agents/{agentNo}`），那条路不受影响。
         *
         * 取号形态从 ULID（`AGMUI9X6VY1M3XM5DQ`，19 位）换成 `AG` + 三位顺序号，
         * 与种子 `AG001~AG008` 同形 —— 代理编号是人要读、要念、要在对账单上核对的东西。
         */
        String no = (agentNo != null && !agentNo.isBlank()) ? agentNo
                : ai.neargo.sharehub.common.crud.BizNoSequence.next(jdbc, "agt_agent", "agent_no", "AG", 3);
        AgtAgent e = mapper.selectOne(new LambdaQueryWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, no));
        boolean insert = (e == null);
        if (insert) { e = new AgtAgent(); e.setAgentNo(no); e.setTenantId("MAIN"); }
        if (!insert) {
            // 先记后改（就地 set）。分成比例与状态是这里最该留痕的两个：
            // 前者直接决定分多少钱，后者决定这个代理还能不能开展业务 ——
            // 「他什么时候被停用的、谁停的」是争议里必问的。
            AuditChanges.record("名称", e.getName(), in.name());
            AuditChanges.record("联系人", e.getContactName(), in.contactName());
            AuditChanges.record("联系手机", e.getPhone(), in.phone());
            AuditChanges.record("联系邮箱", e.getEmail(), in.email());
            AuditChanges.record("分成比例", e.getShareRate(), in.shareRate());
            AuditChanges.record("类型", e.getAgentType(), AgentType.of(in.agentType()).name());
            AuditChanges.record("状态", e.getStatus(), in.status() == null ? "ENABLED" : in.status());
            if (in.address() != null) AuditChanges.record("位置", e.getAddress(), in.address());
            if (in.industry() != null) AuditChanges.record("行业", e.getIndustry(), in.industry());
            // 税号决定分成付款出不出自开票单据，改动要能追溯
            if (in.vatTrn() != null) AuditChanges.record("VAT 税号", e.getVatTrn(), in.vatTrn());
        }
        // 不传 = 不改；空串 = 清掉（商家注销 VAT 登记）
        if (in.vatTrn() != null) e.setVatTrn(in.vatTrn().isBlank() ? null : in.vatTrn().trim());
        String nameBefore = e.getName(), addressBefore = e.getAddress();
        java.math.BigDecimal lngBefore = e.getLng(), latBefore = e.getLat();
        applyCoreProfile(e, in);
        e.setName(in.name());
        // contact 已退役（V123）：不再从写入面接收，存量留在库里待人工归位
        e.setContactName(in.contactName());
        e.setPhone(in.phone());
        e.setEmail(in.email());
        e.setRegionScope(ai.neargo.sharehub.agent.RegionScopeJson.toJson(in.regionScope()));
        // 不传 = 不改（新建时按 0，配规则之前本来就没有默认分润）
        if (in.shareRate() != null) e.setShareRate(in.shareRate());
        else if (insert) e.setShareRate(0d);
        // 类型只认两个值；归一规则与理由见 AgentType.of
        e.setAgentType(AgentType.of(in.agentType()).name());
        // 机柜数不回写：它是聚合值不是档案属性（实体与库里都已没有这一列）
        String before = insert ? null : e.getStatus();
        String want = ai.neargo.sharehub.agent.AgentStatus
                .of(in.status() == null ? ai.neargo.sharehub.agent.AgentStatus.ENABLED.name() : in.status()).name();
        /*
         * **清退中不许改回启用**。清退单一发起就停用代理（冻结提现、名下工单改派），
         * 而这条保存路径此前直接写 status —— 于是在「档案编辑」里把状态改回启用，
         * 就绕过了整个清退流程：提现解冻、又开始派新单，而清退单还停在原处等着推进。
         * 运营端已经禁用了那个按钮，但**按钮禁用只是体验层**，闸必须在服务端。
         */
        if (!insert && ai.neargo.sharehub.agent.AgentStatus.ENABLED.name().equals(want)
                && !java.util.Objects.equals(before, want) && exits.openOf(no) != null) {
            throw BizException.conflict("error.agent.enable_while_exiting", no);
        }
        e.setStatus(want);
        if (insert) mapper.insert(e); else mapper.updateById(e);
        boolean locationChanged = !java.util.Objects.equals(nameBefore, e.getName())
                || !java.util.Objects.equals(addressBefore, e.getAddress())
                || !sameDecimal(lngBefore, e.getLng()) || !sameDecimal(latBefore, e.getLat());
        syncDefaultSite(e, locationChanged);
        if (!insert && !java.util.Objects.equals(before, e.getStatus())) {
            // 停用 → 工单侧改派名下未完结工单（F2）；提现侧在服务层按实时状态拦，不靠事件
            events.publish(new ai.neargo.sharehub.api.platform.event.AgentStatusChangedEvent(no, before, e.getStatus(), null,
                    java.time.LocalDateTime.now().toString()));
        }
        return toVO(e);
    }

    /**
     * 位置与行业（V157，需求方案 D6）。**不传 = 不改**：这几项多由运营后补，只改联系人的保存不该清空位置。
     *
     * <p>位置三项要么都给要么都不给 —— 只给地址没有坐标，建出来的站点在地图上没有落点，
     * 「找附近」排不到它，而且不报错（V121 记过同一个坑）。
     */
    private static void applyCoreProfile(AgtAgent e, Agent in) {
        boolean anyLocation = in.address() != null || in.lng() != null || in.lat() != null;
        if (anyLocation) {
            if (in.address() == null || in.address().isBlank() || in.lng() == null || in.lat() == null) {
                throw BizException.badRequest("error.agent.location_incomplete");
            }
            if (in.lng().abs().compareTo(java.math.BigDecimal.valueOf(180)) > 0
                    || in.lat().abs().compareTo(java.math.BigDecimal.valueOf(90)) > 0) {
                throw BizException.badRequest("error.common.invalid_value", in.lng() + "," + in.lat());
            }
            e.setAddress(in.address().trim());
            e.setLng(in.lng());
            e.setLat(in.lat());
        }
        if (in.industry() != null) {
            e.setIndustry(ai.neargo.sharehub.loc.VenueIndustry.of(in.industry())
                    .orElseThrow(() -> BizException.badRequest("error.agent.industry_invalid", in.industry()))
                    .name());
        }
    }

    /**
     * 默认站点（优化方案 §2.4）：位置补齐时建站，位置或店名变了同步站点地址、坐标与名称。
     *
     * <p>建站时**同一事务**写归属与运维责任：归属走划拨（留流水并级联 agent_no），
     * 责任行给分润用 —— 数据范围读 agent_no、分润读责任行，两者只建一份会让「谁看得到」与「谁分到钱」分开
     * （见 docs/technical/设备绑定与商家归属-现状分析.md §四.1）。之后位置变更只动地址坐标，不动归属。
     */
    private void syncDefaultSite(AgtAgent e, boolean locationChanged) {
        if (e.getAddress() == null || e.getLng() == null || e.getLat() == null) return;
        boolean exists = e.getDefaultSiteNo() != null && !e.getDefaultSiteNo().isBlank();
        if (exists && !locationChanged) return;
        var site = sites.upsertDefaultSite(e.getDefaultSiteNo(), e.getName(), e.getAddress(), e.getLng(), e.getLat());
        if (exists) return;

        e.setDefaultSiteNo(site.siteNo());
        mapper.updateById(e);
        attachSite(e.getAgentNo(), site.siteNo(), "商家默认站点（系统建）");
    }

    /**
     * 站点归商家（V157 / V162）：经划拨写归属（留流水并级联站点、点位、机柜的 agent_no），并写 OPERATE 责任行。
     * 数据范围读 agent_no、分润读责任行 —— 两者只写一份，「谁看得到」与「谁分到钱」就会分开。
     */
    private void attachSite(String agentNo, String siteNo, String remark) {
        // 划拨同事务写 OPERATE 责任行（V165 起由划拨服务统一处理，这里不再另写一份）
        assignments.assign(new ai.neargo.sharehub.agent.ext.dto.AgentExtDtos.AssignReq(
                agentNo, "SITE", siteNo, "ASSIGN", ai.neargo.sharehub.auth.SecurityUtils.userNo()));
        log.info("站点归商家 agentNo={} siteNo={}（{}）", agentNo, siteNo, remark);
    }

    /**
     * 运营为商家新建站点（V162；用户 2026-10-28：商家可有多个站点，一期由运营代建，商家端只读）。
     *
     * <p>一个事务：建站（含默认点位）→ 划给商家 → 写运维责任。商家须存在且已补齐核心资料 ——
     * 划拨门禁要求如此（只有默认站点例外），先在这里说清楚，比让划拨那一步抛错更好懂。
     */
    @Override
    @Transactional
    public ai.neargo.sharehub.loc.dto.LocDtos.Site createSite(String agentNo, ai.neargo.sharehub.loc.dto.SiteDtos.SiteReq req) {
        AgtAgent a = mapper.selectOne(new LambdaQueryWrapper<AgtAgent>().eq(AgtAgent::getAgentNo, agentNo).last("limit 1"));
        if (a == null) throw BizException.notFound(agentNo);
        var site = sites.createForMerchant(req);
        attachSite(agentNo, site.siteNo(), "运营为商家建站");
        return sites.get(site.siteNo());
    }

    private static boolean sameDecimal(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    // region_scope 的 JSON 转换已提取到 RegionScopeJson —— 入驻审核的激活派生也要写这一列，
    // 而此前它是 private，于是那边原样踩了同一个 500。

    private static Agent toVO(AgtAgent e) {
        return new Agent(e.getAgentNo(), e.getName(), e.getContact(), ai.neargo.sharehub.agent.RegionScopeJson.toPlain(e.getRegionScope()),
                // 存量行与缺省一律 AGENT：出参里给空会让前端多处理一个「没有类型」的不存在状态
                AgentType.of(e.getAgentType()).name(),
                e.getShareRate() == null ? 0 : e.getShareRate(),
                /*
                 * 机柜数：platform 算不出（dev_cabinet 属于 core），**给 0 而不是假装有值**。
                 * 代理商的真实机柜数在「设备/点位划拨」页按关系现算。
                 */
                0, e.getStatus(), e.getArchivedAt() == null ? null : e.getArchivedAt().toString(),
                e.getContactName(), e.getPhone(), e.getEmail(),
                e.getAddress(), e.getLng(), e.getLat(), e.getIndustry(), e.getDefaultSiteNo(), e.getVatTrn());
    }

    // ── 归档 / 取消归档（前端契约 Archivable）──
    // 本实现不走 AbstractCrudService（它有自己的业务规则），故在此实现同样语义：
    // archivedAt 时间戳，null=在用。**与 BaseEntity.deleted 是两回事**，见 Archivable。

    @Override
    @Transactional
    public Agent archive(String no) {
        return setArchived(no, java.time.LocalDateTime.now());
    }

    @Override
    @Transactional
    public Agent unarchive(String no) {
        return setArchived(no, null);
    }

    private Agent setArchived(String no, java.time.LocalDateTime at) {
        AgtAgent e = mapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgtAgent>()
                .eq(AgtAgent::getAgentNo, no).last("limit 1"));
        if (e == null) throw BizException.notFound(no);
        e.setArchivedAt(at);
        mapper.updateById(e);
        return toVO(e);
    }
}
