package ai.neargo.sharehub.loc.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.api.core.port.CabinetStatePort;
import ai.neargo.sharehub.api.core.port.OrderQueryPort;
import ai.neargo.sharehub.api.ops.port.WorkOrderQueryPort;
import ai.neargo.sharehub.api.platform.dto.AgentBrief;
import ai.neargo.sharehub.api.platform.dto.ContractBrief;
import ai.neargo.sharehub.api.platform.dto.EmployeeBrief;
import ai.neargo.sharehub.api.platform.port.AgentDirectoryPort;
import ai.neargo.sharehub.api.platform.port.ContractQueryPort;
import ai.neargo.sharehub.api.platform.port.EmployeeDirectoryPort;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.loc.SiteStateMachine;
import ai.neargo.sharehub.loc.SiteStatus;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.SiteDtos.FunnelStage;
import ai.neargo.sharehub.loc.dto.SiteDtos.LifecycleRow;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteOps;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteReq;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteStatusLogItem;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteSummary;
import ai.neargo.sharehub.loc.entity.LocLocation;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocSiteSurvey;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.entity.LocLeadFollow;
import ai.neargo.sharehub.loc.ext.entity.LocSiteAgent;
import ai.neargo.sharehub.loc.ext.entity.LocSiteStatusLog;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadFollowMapper;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadMapper;
import ai.neargo.sharehub.loc.ext.mapper.LocSiteAgentMapper;
import ai.neargo.sharehub.loc.ext.mapper.LocSiteStatusLogMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import ai.neargo.sharehub.loc.service.SiteService;
import ai.neargo.sharehub.platform.md.entity.MdRegion;
import ai.neargo.sharehub.platform.md.mapper.MdRegionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static ai.neargo.sharehub.loc.SiteStatus.ACTIVE;
import static ai.neargo.sharehub.loc.SiteStatus.CLOSED;
import static ai.neargo.sharehub.loc.SiteStatus.PAUSED;
import static ai.neargo.sharehub.loc.SiteStatus.PREPARING;
import static ai.neargo.sharehub.loc.SiteStatus.WITHDRAWING;

/**
 * 站点状态机（TDD-运营核心流程/03）。
 *
 * <p>迁移骨架与合同同形：带范围读 → 状态机求目标态 → 按<b>原状态</b>条件更新（并发只一人赢，输的 409）
 * → 写 {@code loc_site_status_log}。门禁（开业清单 / 关闭门禁）同一份实现既给只读端点、也给动作端点复核。
 */
@Service
public class SiteServiceImpl implements SiteService {

    private static final Logger log = LoggerFactory.getLogger(SiteServiceImpl.class);
    private static final String SYSTEM = "SYSTEM";
    private static final String OPERATE = "OPERATE";
    /** 关闭门禁：站点下仍算「有设备」的机柜状态（RETIRED 之外）。 */
    private static final Set<String> CABINET_PRESENT = Set.of("IN_STOCK", "IN_TRANSIT", "DEPLOYED", "FAULT");
    private static final Set<String> CABINET_LIVE = Set.of("DEPLOYED");
    /** 缺运维责任人：没有平台员工责任人，且没有生效中的 OPERATE 伙伴。 */
    private static final String MISSING_OWNER_SQL = "ops_employee_no IS NULL AND NOT EXISTS (SELECT 1 FROM loc_site_agent a"
            + " WHERE a.site_no = loc_site.site_no AND a.role = 'OPERATE' AND a.deleted = 0"
            + " AND (a.effective_from IS NULL OR a.effective_from <= NOW(3))"
            + " AND (a.effective_to IS NULL OR a.effective_to >= NOW(3)))";
    /** 商机阶段顺序（漏斗展示用）；签约且已落站点的商机由站点接续，不再出现。 */
    private static final List<String> LEAD_PHASES = List.of("NEW", "CONTACTED", "NEGOTIATING", "SIGNED", "LOST");

    private final LocMappers.SiteMapper sites;
    private final LocMappers.LocationMapper locations;
    private final LocMappers.VenueMapper venues;
    private final MdRegionMapper regions;
    private final LocSiteStatusLogMapper logs;
    private final LocSiteAgentMapper siteAgents;
    private final LocLeadMapper leads;
    private final LocLeadFollowMapper leadFollows;
    private final SiteStateMachine sm;
    private final ContractQueryPort contracts;
    private final AgentDirectoryPort agents;
    private final EmployeeDirectoryPort employees;
    private final CabinetStatePort cabinets;
    private final WorkOrderQueryPort workOrders;
    private final OrderQueryPort orders;
    private final ai.neargo.sharehub.api.ops.port.WorkOrderCommandPort workOrderCommands;
    private final LocMappers.SiteSurveyMapper surveyMapper;
    private final ai.neargo.sharehub.api.platform.port.FileBindingPort fileBinding;
    private final ai.neargo.sharehub.api.platform.port.SysParamPort params;
    private final ai.neargo.sharehub.common.event.DomainEventBus events;
    private final TransactionTemplate perRow;
    private final ZoneId bizZone;

    public SiteServiceImpl(LocMappers.SiteMapper sites, LocMappers.LocationMapper locations, LocMappers.VenueMapper venues,
                           MdRegionMapper regions, LocSiteStatusLogMapper logs, LocSiteAgentMapper siteAgents,
                           LocLeadMapper leads, LocLeadFollowMapper leadFollows, SiteStateMachine sm,
                           ContractQueryPort contracts, AgentDirectoryPort agents, EmployeeDirectoryPort employees,
                           CabinetStatePort cabinets, WorkOrderQueryPort workOrders, OrderQueryPort orders,
                           ai.neargo.sharehub.api.ops.port.WorkOrderCommandPort workOrderCommands,
                           LocMappers.SiteSurveyMapper surveyMapper,
                           ai.neargo.sharehub.api.platform.port.FileBindingPort fileBinding,
                           ai.neargo.sharehub.api.platform.port.SysParamPort params,
                           ai.neargo.sharehub.common.event.DomainEventBus events,
                           PlatformTransactionManager tm, @Value("${sharehub.biz-zone:Asia/Dubai}") String bizZone) {
        this.sites = sites;
        this.locations = locations;
        this.venues = venues;
        this.regions = regions;
        this.logs = logs;
        this.siteAgents = siteAgents;
        this.leads = leads;
        this.leadFollows = leadFollows;
        this.sm = sm;
        this.contracts = contracts;
        this.agents = agents;
        this.employees = employees;
        this.cabinets = cabinets;
        this.workOrders = workOrders;
        this.orders = orders;
        this.workOrderCommands = workOrderCommands;
        this.surveyMapper = surveyMapper;
        this.fileBinding = fileBinding;
        this.params = params;
        this.events = events;
        this.perRow = new TransactionTemplate(tm);
        // 逐行独立事务靠「任务运行时没有外层事务」成立（JobRegistry.trigger 不包事务）；用 REQUIRED 而不是 REQUIRES_NEW：
        // 若调用方已在事务里（测试用例 @Transactional、人工在事务内调用），就加入它、随它回滚 ——
        // REQUIRES_NEW 会绕过外层回滚直接提交，JobCatalogTest 冒烟一次就把共享测试库写脏了（2026-09-25 实测）。
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.bizZone = ZoneId.of(bizZone);
    }

    // —— 查询 ——

    @Override
    public PageResult<Site> page(Query q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.size() == null || q.size() < 1 ? 10 : Math.min(q.size(), 200);
        LambdaQueryWrapper<LocSite> w = new LambdaQueryWrapper<>();
        if (!Boolean.TRUE.equals(q.showArchived())) w.isNull(LocSite::getArchivedAt);
        if (notBlank(q.keyword())) {
            String k = q.keyword().trim();
            w.and(x -> x.like(LocSite::getName, k).or().like(LocSite::getVenueName, k)
                    .or().like(LocSite::getRegionId, k).or().eq(LocSite::getSiteNo, k));
        }
        if (notBlank(q.status())) {
            w.in(LocSite::getStatus, Arrays.stream(q.status().split(",")).map(s -> SiteStatus.of(s).name()).toList());
        }
        if (Boolean.TRUE.equals(q.missingOwner())) w.apply(MISSING_OWNER_SQL);
        if (Boolean.TRUE.equals(q.missingOpenHours())) w.and(x -> x.isNull(LocSite::getOpenHours).or().eq(LocSite::getOpenHours, ""));
        w.orderByAsc(LocSite::getId);
        Page<LocSite> r = sites.selectPage(new Page<>(page, size), w);
        return new PageResult<>(toVOs(r.getRecords()), r.getTotal());
    }

    @Override
    public Site get(String siteNo) {
        return toVO(require(siteNo));
    }

    @Override
    public SiteSummary summary() {
        Map<String, Long> by = new HashMap<>();
        for (LocSite s : sites.selectList(new LambdaQueryWrapper<LocSite>().select(LocSite::getStatus)
                .isNull(LocSite::getArchivedAt))) {
            by.merge(s.getStatus(), 1L, Long::sum);
        }
        Long missingOwner = sites.selectCount(new LambdaQueryWrapper<LocSite>().isNull(LocSite::getArchivedAt)
                .ne(LocSite::getStatus, CLOSED.name()).apply(MISSING_OWNER_SQL));
        Long missingHours = sites.selectCount(new LambdaQueryWrapper<LocSite>().isNull(LocSite::getArchivedAt)
                .ne(LocSite::getStatus, CLOSED.name())
                .and(x -> x.isNull(LocSite::getOpenHours).or().eq(LocSite::getOpenHours, "")));
        return new SiteSummary(by.getOrDefault(PREPARING.name(), 0L), by.getOrDefault(ACTIVE.name(), 0L),
                by.getOrDefault(PAUSED.name(), 0L), by.getOrDefault(WITHDRAWING.name(), 0L),
                by.getOrDefault(CLOSED.name(), 0L), nz(missingOwner), nz(missingHours));
    }

    @Override
    public List<SiteStatusLogItem> statusLogs(String siteNo) {
        require(siteNo);   // 带范围读：看不到站点就看不到它的日志
        return logs.selectList(new LambdaQueryWrapper<LocSiteStatusLog>().eq(LocSiteStatusLog::getSiteNo, siteNo)
                        .orderByAsc(LocSiteStatusLog::getId)).stream()
                .map(l -> new SiteStatusLogItem(l.getEvent(), l.getFromStatus(), l.getToStatus(), l.getOperator(),
                        l.getReason(), l.getCreatedAt()))
                .toList();
    }

    // —— 建档 / 编辑 ——

    @Override
    @Transactional
    public Site create(SiteReq r) {
        LocSite e = new LocSite();
        e.setSiteNo(IdGenerator.next(BizKey.SITE));
        e.setTenantId("MAIN");
        apply(e, r);
        e.setStatus(PREPARING.name());
        sites.insert(e);
        writeLog(e.getSiteNo(), "CREATE", null, PREPARING.name(), operator(), null);
        log.info("站点建档 siteNo={} venueNo={}", e.getSiteNo(), e.getVenueNo());
        return toVO(e);
    }

    @Override
    @Transactional
    public Site update(String siteNo, SiteReq r) {
        LocSite e = require(siteNo);
        if (CLOSED.name().equals(e.getStatus())) throw ai.neargo.sharehub.common.BizException.conflict("error.site.closed_readonly");
        // 先记后改：apply 是就地 set，赋值之后旧值就没了
        AuditChanges.record("名称", e.getName(), r.name());
        AuditChanges.record("场地方", e.getVenueNo(), r.venueNo());
        AuditChanges.record("营业时间", e.getOpenHours(), r.openHours());
        AuditChanges.record("运维责任人", e.getOpsEmployeeNo(), blankToNull(r.opsEmployeeNo()));
        apply(e, r);
        sites.updateById(e);
        return toVO(e);
    }

    /** 属性写入。status 与 agentNo 不在请求里 —— 状态只经动作改，归属只经划拨改。 */
    private void apply(LocSite e, SiteReq r) {
        if (r == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "body");
        if (!notBlank(r.name())) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "name");
        if (!notBlank(r.venueNo())) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "venueNo");
        if (!notBlank(r.regionId())) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "regionId");
        if (!notBlank(r.openHours())) throw ai.neargo.sharehub.common.BizException.badRequest("error.site.open_hours_required");
        LocVenue v = venues.selectOne(new LambdaQueryWrapper<LocVenue>().eq(LocVenue::getVenueNo, r.venueNo()).last("limit 1"));
        if (v == null) throw BizException.notFound(r.venueNo());
        String ops = blankToNull(r.opsEmployeeNo());
        if (ops != null) {
            EmployeeBrief b = employees.briefsOf(List.of(ops)).get(ops);
            if (b == null || !b.active()) throw ai.neargo.sharehub.common.BizException.badRequest("error.site.owner_not_active", ops);
        }
        e.setName(r.name().trim());
        e.setNameAr(r.nameAr());
        e.setVenueNo(v.getVenueNo());
        e.setVenueName(v.getName());
        e.setBrandNo(r.brandNo());
        e.setRegionId(r.regionId());
        e.setAddress(r.address());
        // 经纬度只在显式给值时写：清成 null 会让站点从 C 端「找附近」里静默消失
        if (r.lng() != null) e.setLng(r.lng());
        if (r.lat() != null) e.setLat(r.lat());
        e.setSceneType(r.sceneType());
        e.setOpenHours(r.openHours().trim());
        e.setOpsEmployeeNo(ops);
    }

    // —— 状态动作 ——

    @Override
    @Transactional
    public Site pause(String siteNo, String reason, LocalDate pauseUntil) {
        if (!notBlank(reason)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.reason_required");
        if (pauseUntil != null && pauseUntil.isBefore(today())) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.date_not_past");
        LocSite e = requireLive(siteNo);
        LocalDateTime now = LocalDateTime.now();
        transit(e, "PAUSE", reason.trim(),
                u -> u.set(LocSite::getPauseReason, reason.trim()).set(LocSite::getPausedAt, now)
                        .set(LocSite::getPauseUntil, pauseUntil),
                x -> {
                    x.setPauseReason(reason.trim());
                    x.setPausedAt(now);
                    x.setPauseUntil(pauseUntil);
                });
        return toVO(e);
    }

    @Override
    @Transactional
    public Site resume(String siteNo) {
        LocSite e = requireLive(siteNo);
        if (contracts.activeBySites(List.of(siteNo)).get(siteNo) == null) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.site.no_contract");
        }
        transit(e, "RESUME", null,
                u -> u.set(LocSite::getPauseReason, null).set(LocSite::getPausedAt, null).set(LocSite::getPauseUntil, null),
                x -> {
                    x.setPauseReason(null);
                    x.setPausedAt(null);
                    x.setPauseUntil(null);
                });
        return toVO(e);
    }

    /**
     * 发起撤场（L1）。停借由借出校验读站点状态生效；为每台已布放 / 故障的机柜开一张撤机工单（WorkOrderCommandPort）。
     * 开单与状态迁移同事务：开单失败则撤场不成立，不会出现「站点在撤场、机柜却没人去撤」。
     */
    @Override
    @Transactional
    public Site withdraw(String siteNo, String reason, LocalDate plannedAt) {
        if (!notBlank(reason)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.reason_required");
        if (plannedAt == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "plannedAt");
        // 裁决 #3：撤场至少提前 N 天通知场地方 —— 给运维排撤机、给在借用户留归还时间
        int lead = params.intOf("site.withdraw.lead_days", 7);
        if (plannedAt.isBefore(today().plusDays(lead))) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.site.withdraw_lead_time", lead);
        }
        LocSite e = requireLive(siteNo);
        LocalDateTime now = LocalDateTime.now();
        transit(e, "WITHDRAW", reason.trim(),
                u -> u.set(LocSite::getWithdrawReason, reason.trim()).set(LocSite::getWithdrawPlannedAt, plannedAt)
                        .set(LocSite::getWithdrawStartedAt, now),
                x -> {
                    x.setWithdrawReason(reason.trim());
                    x.setWithdrawPlannedAt(plannedAt);
                    x.setWithdrawStartedAt(now);
                });
        // 每台已布放 / 故障的机柜开一张撤机单（幂等：同站点同机柜只一张）。停借由借出校验读站点状态生效，不靠工单
        for (var c : cabinets.availabilityBySites(List.of(siteNo))) {
            workOrderCommands.openRemoval(siteNo, c.cabinetNo(), reason.trim());
        }
        return toVO(e);
    }

    @Override
    @Transactional
    public Site close(String siteNo, String note) {
        if (!notBlank(note)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.note_required");
        LocSite e = requireLive(siteNo);
        sm.next(e.getStatus(), "CLOSE");   // 先判边：非法迁移 400 优先于门禁 409
        Checklist gate = closeGate(e);
        if (!gate.allPassed()) throw ai.neargo.sharehub.common.BizException.conflict("error.site.close_blocked", gate.firstFailedKey());
        closeNow(e, note.trim());
        return toVO(e);
    }

    /** 关闭（人工与自动共用）：迁移 + 发「站点已关闭」（finance 据此生成押金 / 进场费结算调整项）。 */
    private void closeNow(LocSite e, String note) {
        LocalDateTime now = LocalDateTime.now();
        transit(e, "CLOSE", note, u -> u.set(LocSite::getClosedAt, now), x -> x.setClosedAt(now));
        events.publish(new ai.neargo.sharehub.api.platform.event.SiteClosedEvent(e.getSiteNo(), e.getVenueNo(), now.toString()));
    }

    // —— 现场勘测 ——

    @Override
    @Transactional
    public ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey recordSurvey(String siteNo, ai.neargo.sharehub.loc.dto.SiteDtos.SurveyReq r) {
        LocSite site = requireLive(siteNo);
        if (CLOSED.name().equals(site.getStatus())) throw ai.neargo.sharehub.common.BizException.conflict("error.site.closed_readonly");
        if (r == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "body");
        ai.neargo.sharehub.loc.SignalLevel signal = ai.neargo.sharehub.loc.SignalLevel.of(r.signalLevel());
        if (r.powerOk() == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "powerOk");
        ai.neargo.sharehub.loc.SurveyResult result = ai.neargo.sharehub.loc.SurveyResult.of(r.result());
        if (result == ai.neargo.sharehub.loc.SurveyResult.PASS
                && (signal == ai.neargo.sharehub.loc.SignalLevel.NONE || !r.powerOk())) {
            // 没信号借不出也还不了、没电源柜子就是摆设：这两项不满足，勘测不能判通过
            throw ai.neargo.sharehub.common.BizException.badRequest("error.site.survey_not_passable");
        }
        if (result == ai.neargo.sharehub.loc.SurveyResult.FAIL && !notBlank(r.note())) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.common.note_required");
        }
        List<String> fileNos = r.fileNos() == null ? List.of() : r.fileNos().stream().filter(SiteServiceImpl::notBlank).distinct().toList();
        LocSiteSurvey v = new LocSiteSurvey();
        v.setSurveyNo(ai.neargo.common.core.IdGenerator.next("SVY"));
        v.setTenantId("MAIN");
        v.setSiteNo(siteNo);
        v.setSignalLevel(signal.name());
        v.setPowerOk(r.powerOk());
        v.setPlacementNote(blankToNull(r.placementNote()));
        v.setFileNos(fileNos.isEmpty() ? null : String.join(",", fileNos));
        v.setResult(result.name());
        v.setNote(blankToNull(r.note()));
        v.setSurveyedBy(operator());
        v.setSurveyedAt(LocalDateTime.now());
        surveyMapper.insert(v);
        if (!fileNos.isEmpty()) fileBinding.bind(fileNos, "SITE_SURVEY", v.getSurveyNo(), site.getAgentNo());
        log.info("站点勘测 siteNo={} result={} signal={}", siteNo, result, signal);
        return surveyVO(v);
    }

    @Override
    public List<ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey> surveys(String siteNo) {
        require(siteNo);   // 带范围：看不到站点就看不到勘测
        return surveyMapper.selectList(new LambdaQueryWrapper<LocSiteSurvey>().eq(LocSiteSurvey::getSiteNo, siteNo)
                .orderByDesc(LocSiteSurvey::getId)).stream().map(SiteServiceImpl::surveyVO).toList();
    }

    private static ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey surveyVO(LocSiteSurvey v) {
        return new ai.neargo.sharehub.loc.dto.SiteDtos.SiteSurvey(v.getSurveyNo(), v.getSiteNo(), v.getSignalLevel(),
                Boolean.TRUE.equals(v.getPowerOk()), v.getPlacementNote(),
                v.getFileNos() == null ? List.of() : List.of(v.getFileNos().split(",")), v.getResult(), v.getNote(),
                v.getSurveyedBy(), v.getSurveyedAt());
    }

    private Boolean latestSurveyPassed(String siteNo) {
        LocSiteSurvey v = surveyMapper.selectOne(new LambdaQueryWrapper<LocSiteSurvey>().eq(LocSiteSurvey::getSiteNo, siteNo)
                .orderByDesc(LocSiteSurvey::getId).last("limit 1"));
        return v == null ? null : ai.neargo.sharehub.loc.SurveyResult.PASS.name().equals(v.getResult());
    }

    @Override
    @Transactional
    public Site archive(String siteNo) {
        LocSite e = require(siteNo);
        if (!CLOSED.name().equals(e.getStatus())) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.site.archive_closed_only");
        }
        e.setArchivedAt(LocalDateTime.now());
        sites.updateById(e);
        return toVO(e);
    }

    @Override
    @Transactional
    public Site unarchive(String siteNo) {
        LocSite e = require(siteNo);
        e.setArchivedAt(null);
        sites.updateById(e);
        return toVO(e);
    }

    // —— 门禁 ——

    @Override
    public Checklist openingChecklist(String siteNo) {
        LocSite e = require(siteNo);
        String no = e.getSiteNo();
        ContractBrief c = contracts.activeBySites(List.of(no)).get(no);
        Long points = locations.selectCount(new LambdaQueryWrapper<LocLocation>().eq(LocLocation::getSiteNo, no)
                .isNull(LocLocation::getArchivedAt));
        String owner = ownerProblem(e);
        long live = cabinets.countBySites(List.of(no), CABINET_LIVE).getOrDefault(no, 0L);
        Boolean surveyed = latestSurveyPassed(no);
        return Checklist.of(List.of(
                new Checklist.Item("SURVEY", "现场勘测", Boolean.TRUE.equals(surveyed),
                        surveyed == null ? "还没有勘测记录" : surveyed ? "最近一次勘测通过" : "最近一次勘测不通过",
                        "/sites/" + no + "?tab=survey"),
                new Checklist.Item("CONTRACT", "生效合同", c != null,
                        c != null ? c.contractNo() : "站点没有生效中的合同", "/venues?tab=contracts&siteNo=" + no),
                new Checklist.Item("LOCATION", "点位", nz(points) > 0,
                        nz(points) > 0 ? points + " 个点位" : "还没有点位", "/sites/" + no + "?tab=locations"),
                new Checklist.Item("OPS_OWNER", "运维责任人", owner == null,
                        owner == null ? "已指定" : owner, "/sites/" + no + "?tab=partners"),
                new Checklist.Item("OPEN_HOURS", "营业时间", notBlank(e.getOpenHours()),
                        notBlank(e.getOpenHours()) ? e.getOpenHours() : "未设置", "/sites/" + no + "?edit=1"),
                new Checklist.Item("DEVICE_LIVE", "设备上线", live > 0,
                        live > 0 ? live + " 台已上线" : "还没有设备上线（首台上线后站点自动转营业）", "/devices?siteNo=" + no)));
    }

    @Override
    public Checklist closeGate(String siteNo) {
        return closeGate(require(siteNo));
    }

    private Checklist closeGate(LocSite e) {
        String no = e.getSiteNo();
        long devices = cabinets.countBySites(List.of(no), CABINET_PRESENT).getOrDefault(no, 0L);
        long wos = workOrders.openCountBySites(List.of(no)).getOrDefault(no, 0L);
        long inFlight = orders.inFlightCountBySites(List.of(no)).getOrDefault(no, 0L);
        return Checklist.of(List.of(
                new Checklist.Item("NO_DEVICE", "设备已撤", devices == 0,
                        devices == 0 ? "无设备" : "还有 " + devices + " 台设备", "/devices?siteNo=" + no),
                new Checklist.Item("NO_OPEN_WO", "工单已结", wos == 0,
                        wos == 0 ? "无未结工单" : "还有 " + wos + " 张未结工单", "/work-orders?siteNo=" + no),
                new Checklist.Item("NO_IN_FLIGHT_ORDER", "订单已还", inFlight == 0,
                        inFlight == 0 ? "无在借订单" : "还有 " + inFlight + " 笔在借订单", "/orders?siteNo=" + no)));
    }

    /** 运维责任人是否就位；就位返回 null，否则返回原因。 */
    private String ownerProblem(LocSite e) {
        String agentNo = operateAgentsOf(List.of(e.getSiteNo())).get(e.getSiteNo());
        if (agentNo != null) {
            AgentBrief a = agents.briefOf(agentNo);
            if (a != null && a.enabled()) return null;
        }
        if (e.getOpsEmployeeNo() != null) {
            EmployeeBrief b = employees.briefsOf(List.of(e.getOpsEmployeeNo())).get(e.getOpsEmployeeNo());
            if (b != null && b.active()) return null;
            return "员工责任人 " + e.getOpsEmployeeNo() + " 已离职";
        }
        return agentNo != null ? "运维伙伴 " + agentNo + " 已暂停，且没有员工责任人" : "未指定运维伙伴或员工责任人";
    }

    // —— 系统：上线 / 对账 ——

    @Override
    @Transactional
    public boolean goLiveIfPreparing(String siteNo, LocalDateTime at, String cause) {
        if (siteNo == null) return false;
        LocalDateTime when = at == null ? LocalDateTime.now() : at;
        String to = sm.next(PREPARING.name(), "GO_LIVE");
        int n = DataScopeContext.executeWithoutScope(() -> sites.update(null, new LambdaUpdateWrapper<LocSite>()
                .eq(LocSite::getSiteNo, siteNo).eq(LocSite::getStatus, PREPARING.name())
                .set(LocSite::getStatus, to).set(LocSite::getFirstLiveAt, when)));
        if (n == 0) return false;
        writeLog(siteNo, "GO_LIVE", PREPARING.name(), to, SYSTEM, cause);
        log.info("站点转营业 siteNo={} cause={}", siteNo, cause);
        return true;
    }

    @Override
    public SiteTickResult tick() {
        int wentLive = 0;
        long afterId = 0;
        while (true) {
            final long from = afterId;
            List<LocSite> batch = DataScopeContext.executeWithoutScope(() -> sites.selectList(new LambdaQueryWrapper<LocSite>()
                    .eq(LocSite::getStatus, PREPARING.name()).isNull(LocSite::getArchivedAt).gt(LocSite::getId, from)
                    .orderByAsc(LocSite::getId).last("limit 200")));
            if (batch.isEmpty()) break;
            afterId = batch.get(batch.size() - 1).getId();
            Map<String, Long> live = cabinets.countBySites(batch.stream().map(LocSite::getSiteNo).toList(), CABINET_LIVE);
            for (LocSite s : batch) {
                if (live.getOrDefault(s.getSiteNo(), 0L) == 0) continue;
                try {
                    if (Boolean.TRUE.equals(perRow.execute(st -> goLiveIfPreparing(s.getSiteNo(), LocalDateTime.now(), "reconcile")))) {
                        wentLive++;
                    }
                } catch (RuntimeException ex) {
                    // 单站失败不拖垮整批；下个小时重试。需要人看的是持续失败的那一个
                    log.error("站点上线对账失败 siteNo={}，下轮重试", s.getSiteNo(), ex);
                }
            }
            if (batch.size() < 200) break;
        }
        // 暂停到期只提醒不自动恢复：恢复营业取决于场地实际情况，必须人确认（待办生成为 L1）
        Long overdue = DataScopeContext.executeWithoutScope(() -> sites.selectCount(new LambdaQueryWrapper<LocSite>()
                .eq(LocSite::getStatus, PAUSED.name()).isNull(LocSite::getArchivedAt).lt(LocSite::getPauseUntil, today())));
        int autoClosed = autoCloseWithdrawn();
        if (wentLive > 0 || nz(overdue) > 0 || autoClosed > 0) {
            log.info("站点对账 wentLive={} pauseOverdue={} autoClosed={}", wentLive, overdue, autoClosed);
        }
        return new SiteTickResult(wentLive, (int) nz(overdue), autoClosed);
    }

    /**
     * 撤场中的站点关闭门禁全过（设备撤完、工单结清、在借订单还完）→ 自动关闭（C7）。
     * 撤场的每一步都有人在做，最后一步「点关闭」没有信息量，只会让站点在撤场里挂很久。
     */
    private int autoCloseWithdrawn() {
        int closed = 0;
        List<LocSite> withdrawing = DataScopeContext.executeWithoutScope(() -> sites.selectList(new LambdaQueryWrapper<LocSite>()
                .eq(LocSite::getStatus, WITHDRAWING.name()).isNull(LocSite::getArchivedAt).orderByAsc(LocSite::getId).last("limit 500")));
        for (LocSite s : withdrawing) {
            try {
                Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() -> {
                    if (!closeGate(s).allPassed()) return false;
                    closeNow(s, "撤场条件已满足，系统自动关闭");
                    return true;
                }));
                if (Boolean.TRUE.equals(ok)) closed++;
            } catch (RuntimeException ex) {
                log.error("撤场站点自动关闭失败 siteNo={}，下轮重试", s.getSiteNo(), ex);
            }
        }
        return closed;
    }

    // —— 门店生命周期（只读）——

    @Override
    public PageResult<LifecycleRow> lifecycle(Integer page, Integer size, String keyword, String phase) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 10 : Math.min(size, 200);
        List<LifecycleRow> all = lifecycleRows().stream()
                .filter(r -> !notBlank(phase) || phase.equalsIgnoreCase(r.phase()))
                .filter(r -> !notBlank(keyword) || (r.name() != null && r.name().contains(keyword.trim()))
                        || keyword.trim().equals(r.no()))
                .toList();
        int fromIdx = Math.min((p - 1) * s, all.size());
        return new PageResult<>(all.subList(fromIdx, Math.min(fromIdx + s, all.size())), all.size());
    }

    @Override
    public List<FunnelStage> funnel() {
        Map<String, List<LifecycleRow>> by = lifecycleRows().stream()
                .collect(Collectors.groupingBy(r -> r.kind() + ":" + r.phase(), LinkedHashMap::new, Collectors.toList()));
        List<FunnelStage> out = new ArrayList<>();
        for (String ph : LEAD_PHASES) addStage(out, "LEAD", ph, by.get("LEAD:" + ph));
        for (SiteStatus st : SiteStatus.values()) addStage(out, "SITE", st.name(), by.get("SITE:" + st.name()));
        return out;
    }

    private static void addStage(List<FunnelStage> out, String kind, String phase, List<LifecycleRow> rows) {
        List<LifecycleRow> rs = rows == null ? List.of() : rows;
        Double avg = rs.stream().map(LifecycleRow::daysInPhase).filter(Objects::nonNull)
                .mapToLong(Long::longValue).average().stream().boxed().findFirst().orElse(null);
        out.add(new FunnelStage(kind, phase, rs.size(), avg));
    }

    private List<LifecycleRow> lifecycleRows() {
        LocalDateTime now = LocalDateTime.now();
        List<LifecycleRow> out = new ArrayList<>();
        // 签约前：商机。已签约且已落站点的由站点接续
        List<LocLead> ls = leads.selectList(new LambdaQueryWrapper<LocLead>().orderByAsc(LocLead::getId).last("limit 5000"));
        List<LocLead> open = ls.stream().filter(l -> !("SIGNED".equals(l.getStage()) && l.getSiteNo() != null)).toList();
        Map<String, LocalDateTime> leadSince = new HashMap<>();
        if (!open.isEmpty()) {
            for (LocLeadFollow f : leadFollows.selectList(new LambdaQueryWrapper<LocLeadFollow>()
                    .in(LocLeadFollow::getLeadNo, open.stream().map(LocLead::getLeadNo).toList()))) {
                if (Objects.equals(f.getFromStage(), f.getToStage()) || f.getCreatedAt() == null) continue;
                leadSince.merge(f.getLeadNo() + ":" + f.getToStage(), f.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        for (LocLead l : open) {
            LocalDateTime since = leadSince.getOrDefault(l.getLeadNo() + ":" + l.getStage(), l.getCreatedAt());
            out.add(new LifecycleRow("LEAD", l.getLeadNo(), l.getVenueName(), l.getStage(), since, days(since, now), l.getOwner()));
        }
        // 签约后：站点。进入当前状态的时刻取状态日志，无日志的存量站点取 updated_at
        List<LocSite> ss = sites.selectList(new LambdaQueryWrapper<LocSite>().isNull(LocSite::getArchivedAt)
                .orderByAsc(LocSite::getId).last("limit 5000"));
        Map<String, LocalDateTime> siteSince = new HashMap<>();
        if (!ss.isEmpty()) {
            for (LocSiteStatusLog l : logs.selectList(new LambdaQueryWrapper<LocSiteStatusLog>()
                    .in(LocSiteStatusLog::getSiteNo, ss.stream().map(LocSite::getSiteNo).toList()))) {
                siteSince.merge(l.getSiteNo() + ":" + l.getToStatus(), l.getCreatedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Map<String, String> operate = operateAgentsOf(ss.stream().map(LocSite::getSiteNo).toList());
        for (LocSite s : ss) {
            LocalDateTime since = siteSince.getOrDefault(s.getSiteNo() + ":" + s.getStatus(), s.getUpdatedAt());
            String owner = operate.getOrDefault(s.getSiteNo(), s.getOpsEmployeeNo());
            out.add(new LifecycleRow("SITE", s.getSiteNo(), s.getName(), s.getStatus(), since, days(since, now), owner));
        }
        out.sort(Comparator.comparing(LifecycleRow::kind).thenComparing(LifecycleRow::no));
        return out;
    }

    private static Long days(LocalDateTime since, LocalDateTime now) {
        return since == null ? null : ChronoUnit.DAYS.between(since, now);
    }

    // —— 骨架 ——

    private void transit(LocSite e, String event, String reason, Consumer<LambdaUpdateWrapper<LocSite>> sets,
                         Consumer<LocSite> local) {
        String from = e.getStatus();
        String to = sm.next(from, event);
        LambdaUpdateWrapper<LocSite> u = new LambdaUpdateWrapper<LocSite>()
                .eq(LocSite::getSiteNo, e.getSiteNo()).eq(LocSite::getStatus, from).set(LocSite::getStatus, to);
        sets.accept(u);
        if (sites.update(null, u) == 0) throw ai.neargo.sharehub.common.BizException.conflict("error.common.state_changed");
        local.accept(e);
        e.setStatus(to);
        writeLog(e.getSiteNo(), event, from, to, operator(), reason);
        log.info("站点状态 siteNo={} {} {}→{}", e.getSiteNo(), event, from, to);
    }

    private void writeLog(String siteNo, String event, String from, String to, String operator, String reason) {
        LocSiteStatusLog l = new LocSiteStatusLog();
        l.setSiteNo(siteNo);
        l.setEvent(event);
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setOperator(operator);
        l.setReason(reason);
        logs.insert(l);
    }

    private LocSite require(String siteNo) {
        LocSite e = sites.selectOne(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, siteNo).last("limit 1"));
        if (e == null) throw BizException.notFound(siteNo);
        return e;
    }

    /** 已归档的站点不谈营业与否：先取消归档再操作。 */
    private LocSite requireLive(String siteNo) {
        LocSite e = require(siteNo);
        if (e.getArchivedAt() != null) throw ai.neargo.sharehub.common.BizException.conflict("error.common.archived_readonly");
        return e;
    }

    /** 各站点当前生效的 OPERATE 伙伴（多条取最早生效的一条）。 */
    private Map<String, String> operateAgentsOf(Collection<String> siteNos) {
        if (siteNos.isEmpty()) return Map.of();
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> out = new HashMap<>();
        DataScopeContext.executeWithoutScope(() -> siteAgents.selectList(new LambdaQueryWrapper<LocSiteAgent>()
                        .in(LocSiteAgent::getSiteNo, siteNos).eq(LocSiteAgent::getRole, OPERATE)
                        .and(w -> w.isNull(LocSiteAgent::getEffectiveFrom).or().le(LocSiteAgent::getEffectiveFrom, now))
                        .and(w -> w.isNull(LocSiteAgent::getEffectiveTo).or().ge(LocSiteAgent::getEffectiveTo, now))
                        .orderByAsc(LocSiteAgent::getId)))
                .forEach(a -> out.putIfAbsent(a.getSiteNo(), a.getAgentNo()));
        return out;
    }

    // —— VO ——

    private Site toVO(LocSite e) {
        return toVOs(List.of(e)).get(0);
    }

    private List<Site> toVOs(List<LocSite> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> nos = rows.stream().map(LocSite::getSiteNo).toList();
        Map<String, Integer> points = new HashMap<>();
        for (LocLocation l : locations.selectList(new LambdaQueryWrapper<LocLocation>()
                .in(LocLocation::getSiteNo, nos).isNull(LocLocation::getArchivedAt))) {
            points.merge(l.getSiteNo(), 1, Integer::sum);
        }
        Map<String, ContractBrief> active = contracts.activeBySites(nos);
        Map<String, String> operate = operateAgentsOf(nos);
        Set<String> regionIds = rows.stream().map(LocSite::getRegionId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, String> regionNames = regionIds.isEmpty() ? Map.of() : regions.selectList(new LambdaQueryWrapper<MdRegion>()
                .in(MdRegion::getRegionId, regionIds)).stream()
                .filter(r -> r.getName() != null)
                .collect(Collectors.toMap(MdRegion::getRegionId, MdRegion::getName, (a, b) -> a));
        return rows.stream().map(e -> new Site(e.getSiteNo(), e.getName(), e.getVenueNo(), e.getVenueName(), e.getAgentNo(),
                e.getBrandNo(), e.getRegionId(),
                e.getRegionId() == null ? null : regionNames.getOrDefault(e.getRegionId(), e.getRegionId()),
                e.getAddress(), e.getLng(), e.getLat(), e.getSceneType(), points.getOrDefault(e.getSiteNo(), 0),
                null,   // 机柜数 platform 算不出（dev_cabinet 属于 core），null =「这里答不了」
                e.getStatus(), e.getArchivedAt() == null ? null : e.getArchivedAt().toString(),
                e.getNameAr(), e.getOpenHours(),
                new SiteOps(e.getOpsEmployeeNo(), operate.get(e.getSiteNo()), e.getFirstLiveAt(), e.getPauseReason(),
                        e.getPauseUntil(), e.getWithdrawReason(), e.getWithdrawPlannedAt(), e.getClosedAt(),
                        active.containsKey(e.getSiteNo()) ? active.get(e.getSiteNo()).contractNo() : null)))
                .toList();
    }

    // —— 工具 ——

    private LocalDate today() {
        return LocalDate.now(bizZone);
    }

    private static String operator() {
        String u = SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null);
        return u == null ? SYSTEM : u;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
