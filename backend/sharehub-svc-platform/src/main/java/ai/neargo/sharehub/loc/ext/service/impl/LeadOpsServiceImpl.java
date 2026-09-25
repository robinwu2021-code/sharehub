package ai.neargo.sharehub.loc.ext.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.port.NotifyPort;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.loc.ContractShareBase;
import ai.neargo.sharehub.loc.LocService;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.loc.dto.LocDtos.Site;
import ai.neargo.sharehub.loc.dto.SiteDtos.SiteReq;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.ext.LeadOwnerType;
import ai.neargo.sharehub.loc.ext.LeadStateMachine;
import ai.neargo.sharehub.loc.ext.LeadStatus;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.Lead;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadConversion;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadConvertReq;
import ai.neargo.sharehub.loc.ext.dto.LocExtDtos.LeadTickResult;
import ai.neargo.sharehub.loc.ext.entity.LocLead;
import ai.neargo.sharehub.loc.ext.mapper.LocLeadMapper;
import ai.neargo.sharehub.loc.ext.service.LeadOpsService;
import ai.neargo.sharehub.loc.ext.service.LeadService;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import ai.neargo.sharehub.loc.service.ContractService;
import ai.neargo.sharehub.loc.service.SiteService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 商机动作实现（对齐清单 B7–B9）。 */
@Service
public class LeadOpsServiceImpl implements LeadOpsService {

    private static final Logger log = LoggerFactory.getLogger(LeadOpsServiceImpl.class);
    private static final List<String> OPEN_STAGES =
            List.of(LeadStatus.NEW.name(), LeadStatus.CONTACTED.name(), LeadStatus.NEGOTIATING.name());
    private static final int BATCH = 500;

    private final LocLeadMapper leads;
    private final LeadService leadService;
    private final LeadStateMachine sm;
    private final LocService loc;
    private final SiteService siteService;
    private final ContractService contractService;
    private final LocMappers.SiteMapper sites;
    private final LocMappers.VenueMapper venues;
    private final NotifyPort notify;
    private final SysParamPort params;
    private final TransactionTemplate perRow;

    public LeadOpsServiceImpl(LocLeadMapper leads, LeadService leadService, LeadStateMachine sm, LocService loc,
                              SiteService siteService, ContractService contractService, LocMappers.SiteMapper sites,
                              LocMappers.VenueMapper venues, NotifyPort notify, SysParamPort params,
                              PlatformTransactionManager tm) {
        this.leads = leads;
        this.leadService = leadService;
        this.sm = sm;
        this.loc = loc;
        this.siteService = siteService;
        this.contractService = contractService;
        this.sites = sites;
        this.venues = venues;
        this.notify = notify;
        this.params = params;
        this.perRow = new TransactionTemplate(tm);
        // 同 ContractServiceImpl：任务运行时无外层事务 → 逐行独立；被测试事务包着时加入它、随它回滚
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    // —— 认领 ——

    @Override
    @Transactional
    public Lead claim(String leadNo) {
        String me = SecurityUtils.userNo();
        if (me == null) throw BizException.badRequest("error.common.missing_parameter", "operator");
        // 线索池里的商机本来就没有负责人，按数据范围可能谁都看不到 —— 认领豁免范围，条件更新保证只一人拿到
        int n = DataScopeContext.executeWithoutScope(() -> leads.update(null, new LambdaUpdateWrapper<LocLead>()
                .eq(LocLead::getLeadNo, leadNo).eq(LocLead::getInPool, 1)
                .set(LocLead::getInPool, 0).set(LocLead::getOwner, me).set(LocLead::getOwnerType, LeadOwnerType.STAFF.name())
                .set(LocLead::getLastFollowAt, LocalDateTime.now()).set(LocLead::getRemindAt, null)));
        if (n == 0) throw BizException.conflict("error.lead.not_in_pool", leadNo);
        log.info("商机认领 leadNo={} owner={}", leadNo, me);
        return leadService.get(leadNo);
    }

    // —— 签约转化 ——

    @Override
    @Transactional
    public LeadConversion convert(String leadNo, LeadConvertReq req) {
        LeadConvertReq r = req == null ? new LeadConvertReq(null, null, null, null, null, null, null, null, null, null, null, null, null) : req;
        LocLead lead = leads.selectOne(new LambdaQueryWrapper<LocLead>().eq(LocLead::getLeadNo, leadNo).last("limit 1"));
        if (lead == null) throw BizException.notFound(leadNo);
        if (lead.getContractNo() != null) throw BizException.conflict("error.lead.converted", leadNo, lead.getContractNo());
        if (!LeadStatus.SIGNED.name().equals(lead.getStage())) sm.check(lead.getStage(), LeadStatus.SIGNED.name());

        // 1) 场地方：给了就关联（须存在），没给按商机的场地名新建
        String venueNo = firstNonBlank(r.venueNo(), lead.getVenueNo());
        boolean venueCreated = false;
        if (venueNo != null) {
            String v = venueNo;
            if (venues.selectCount(new LambdaQueryWrapper<LocVenue>().eq(LocVenue::getVenueNo, v)) == 0) throw BizException.notFound(v);
        } else {
            LocVenue v = new LocVenue();
            v.setName(lead.getVenueName());
            venueNo = loc.saveVenue(v).venueNo();
            venueCreated = true;
        }

        // 2) 站点：给了就关联（须属于该场地方），没给建一个筹备中的站点
        String siteNo = firstNonBlank(r.siteNo(), lead.getSiteNo());
        boolean siteCreated = false;
        if (siteNo != null) {
            String sn = siteNo;
            LocSite s = sites.selectOne(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, sn).last("limit 1"));
            if (s == null) throw BizException.notFound(sn);
            if (s.getVenueNo() != null && !s.getVenueNo().equals(venueNo)) {
                throw BizException.badRequest("error.contract.site_venue_mismatch", sn, venueNo);
            }
        } else {
            Site s = siteService.create(new SiteReq(firstNonBlank(r.siteName(), lead.getVenueName()), null, venueNo, null,
                    firstNonBlank(r.regionId(), lead.getRegionId()), firstNonBlank(r.address(), lead.getAddress()),
                    null, null, null, r.openHours(), null));
            siteNo = s.siteNo();
            siteCreated = true;
        }

        // 3) 合同草稿：条款取请求，没给的取商机上谈下来的，再没有按纯分成、12 个月
        LocalDate start = r.startAt() != null ? r.startAt() : LocalDate.now();
        int months = r.termMonths() != null ? r.termMonths() : lead.getTermMonths() != null ? lead.getTermMonths() : 12;
        if (months <= 0) throw BizException.badRequest("error.common.invalid_value", "termMonths=" + months);
        Boolean exclusive = r.exclusive() != null ? r.exclusive() : lead.getExclusiveFlag();
        Contract c = contractService.createFromLead(new ContractService.Draft(venueNo, siteNo,
                firstNonBlank(r.shareMode(), lead.getShareMode()), ContractShareBase.NET.name(),
                nvl(r.shareRate(), lead.getShareRate()), nvl(r.guaranteeAmount(), lead.getGuaranteeAmount()),
                nvl(r.entryFee(), lead.getEntryFee()), null, null, start, start.plusMonths(months).minusDays(1), false,
                Boolean.TRUE.equals(exclusive), null, null, null, null, null, "来自商机 " + leadNo), leadNo);

        // 4) 商机：SIGNED + 回填三个编号；条件按原阶段与「未转化」，并发两次转化只一次成功
        int n = leads.update(null, new LambdaUpdateWrapper<LocLead>().eq(LocLead::getId, lead.getId())
                .eq(LocLead::getStage, lead.getStage()).isNull(LocLead::getContractNo)
                .set(LocLead::getStage, LeadStatus.SIGNED.name()).set(LocLead::getVenueNo, venueNo)
                .set(LocLead::getSiteNo, siteNo).set(LocLead::getContractNo, c.contractNo())
                .set(LocLead::getLastFollowAt, LocalDateTime.now()));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        leadService.writeAttribution(leadNo);
        log.info("商机签约转化 leadNo={} venueNo={}{} siteNo={}{} contractNo={}", leadNo, venueNo, venueCreated ? "（新建）" : "",
                siteNo, siteCreated ? "（新建）" : "", c.contractNo());
        return new LeadConversion(leadNo, venueNo, venueCreated, siteNo, siteCreated, c.contractNo());
    }

    // —— 定时 ——

    @Override
    public LeadTickResult tick(LocalDateTime now) {
        int remindDays = params.intOf("lead.follow.remind_days", 7);
        int poolDays = params.intOf("lead.pool.recycle_days", 30);
        int beforeDays = params.intOf("lead.reactivate.before_days", 60);
        int pooled = 0, reminded = 0, reactivated = 0;

        // 1) 回收：先回收再提醒 —— 已经要回收的不必再提醒一次
        LocalDateTime poolCutoff = now.minusDays(poolDays);
        for (LocLead l : scan(openStaff().lt(LocLead::getLastFollowAt, poolCutoff))) {
            Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() -> leads.update(null,
                    new LambdaUpdateWrapper<LocLead>().eq(LocLead::getId, l.getId()).eq(LocLead::getInPool, 0)
                            .lt(LocLead::getLastFollowAt, poolCutoff)
                            .set(LocLead::getInPool, 1).set(LocLead::getPooledAt, now).set(LocLead::getPrevOwner, l.getOwner())
                            .set(LocLead::getOwner, null).set(LocLead::getRemindAt, null)) > 0));
            if (Boolean.TRUE.equals(ok)) {
                pooled++;
                notify.push(l.getOwner(), "LEAD_RECYCLED", "商机「" + l.getVenueName() + "」超过 " + poolDays + " 天未跟进，已回收到公共线索池");
            }
        }

        // 2) 提醒：一段沉默只提醒一次（remind_at 早于最近跟进才算新的一段）
        LocalDateTime remindCutoff = now.minusDays(remindDays);
        for (LocLead l : scan(openStaff().isNotNull(LocLead::getOwner).lt(LocLead::getLastFollowAt, remindCutoff)
                .apply("(remind_at IS NULL OR remind_at < last_follow_at)"))) {
            Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() -> leads.update(null,
                    new LambdaUpdateWrapper<LocLead>().eq(LocLead::getId, l.getId())
                            .apply("(remind_at IS NULL OR remind_at < last_follow_at)").set(LocLead::getRemindAt, now)) > 0));
            if (Boolean.TRUE.equals(ok)) {
                reminded++;
                notify.push(l.getOwner(), "LEAD_FOLLOW_REMIND", "商机「" + l.getVenueName() + "」已超过 " + remindDays
                        + " 天未跟进；" + poolDays + " 天未跟进将回收到公共线索池");
            }
        }

        // 3) 竞品独家到期前重新激活：一次丢单只激活一次（reactivated_at 早于 lost_at 才算新的一次）
        LocalDate horizon = now.toLocalDate().plusDays(beforeDays);
        for (LocLead l : scan(new LambdaQueryWrapper<LocLead>().eq(LocLead::getStage, LeadStatus.LOST.name())
                .isNotNull(LocLead::getCompetitorExclusiveUntil).le(LocLead::getCompetitorExclusiveUntil, horizon)
                .apply("(reactivated_at IS NULL OR reactivated_at < lost_at)"))) {
            Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() -> leads.update(null,
                    new LambdaUpdateWrapper<LocLead>().eq(LocLead::getId, l.getId()).eq(LocLead::getStage, LeadStatus.LOST.name())
                            .set(LocLead::getStage, LeadStatus.NEW.name()).set(LocLead::getReactivatedAt, now)
                            .set(LocLead::getLastFollowAt, now).set(LocLead::getRemindAt, null)) > 0));
            if (Boolean.TRUE.equals(ok)) {
                reactivated++;
                notify.push(l.getOwner(), "LEAD_REACTIVATED", "商机「" + l.getVenueName() + "」的竞品（" + nvl(l.getCompetitorName(), "未记录")
                        + "）独家将于 " + l.getCompetitorExclusiveUntil() + " 到期，已重新激活，请尽快接触");
                log.info("商机重新激活 leadNo={} competitorUntil={}", l.getLeadNo(), l.getCompetitorExclusiveUntil());
            }
        }
        if (pooled + reminded + reactivated > 0) {
            log.info("商机定时：提醒 {} 条，回收 {} 条，重新激活 {} 条", reminded, pooled, reactivated);
        }
        return new LeadTickResult(reminded, pooled, reactivated);
    }

    /** 在跟、未入池、自有员工负责的商机。伙伴（AGENT）负责的不提醒不回收 —— 那是伙伴自己的客户。 */
    private static LambdaQueryWrapper<LocLead> openStaff() {
        return new LambdaQueryWrapper<LocLead>().in(LocLead::getStage, OPEN_STAGES).eq(LocLead::getInPool, 0)
                .eq(LocLead::getOwnerType, LeadOwnerType.STAFF.name());
    }

    private List<LocLead> scan(LambdaQueryWrapper<LocLead> w) {
        return DataScopeContext.executeWithoutScope(() -> leads.selectList(w.orderByAsc(LocLead::getId).last("limit " + BATCH)));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        return b == null || b.isBlank() ? null : b.trim();
    }

    private static <T> T nvl(T a, T b) {
        return a != null ? a : b;
    }
}
