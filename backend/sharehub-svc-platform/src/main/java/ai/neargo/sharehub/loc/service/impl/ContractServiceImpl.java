package ai.neargo.sharehub.loc.service.impl;

import ai.neargo.sharehub.common.BizException;
import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.platform.dto.FileRef;
import ai.neargo.sharehub.api.platform.event.ContractSignedEvent;
import ai.neargo.sharehub.api.platform.port.FileBindingPort;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.loc.ContractAuditStage;
import ai.neargo.sharehub.loc.ContractKind;
import ai.neargo.sharehub.loc.ContractShareBase;
import ai.neargo.sharehub.loc.ContractTermReqStatus;
import ai.neargo.sharehub.loc.ContractShareMode;
import ai.neargo.sharehub.loc.ContractStateMachine;
import ai.neargo.sharehub.loc.ContractStatus;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractFlow;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractLogItem;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractSummary;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractTerms;
import ai.neargo.sharehub.loc.dto.ContractDtos.ContractTickResult;
import ai.neargo.sharehub.loc.dto.ContractDtos.TerminationRequest;
import ai.neargo.sharehub.loc.dto.LocDtos.Contract;
import ai.neargo.sharehub.loc.dto.LocDtos.ContractAttachment;
import ai.neargo.sharehub.loc.entity.LocContract;
import ai.neargo.sharehub.loc.entity.LocSite;
import ai.neargo.sharehub.loc.entity.LocVenue;
import ai.neargo.sharehub.loc.ext.entity.LocContractAttach;
import ai.neargo.sharehub.loc.ext.entity.LocContractLog;
import ai.neargo.sharehub.loc.ext.mapper.LocContractAttachMapper;
import ai.neargo.sharehub.loc.ext.mapper.LocContractLogMapper;
import ai.neargo.sharehub.loc.mapper.LocMappers;
import ai.neargo.sharehub.loc.service.ContractService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static ai.neargo.sharehub.loc.ContractStatus.ACTIVE;
import static ai.neargo.sharehub.loc.ContractStatus.DRAFT;
import static ai.neargo.sharehub.loc.ContractStatus.EXPIRED;
import static ai.neargo.sharehub.loc.ContractStatus.PENDING;
import static ai.neargo.sharehub.loc.ContractStatus.SIGNED;

/**
 * 进场合同审批（TDD-运营核心流程/02）。
 *
 * <p><b>所有迁移走同一个骨架</b>（{@link #transit}）：带范围读 → 状态机求目标态 → 按<b>原状态</b>条件更新
 * （并发下只有一个人赢，输的一方 409）→ 写迁移日志。状态从不经编辑接口改。
 *
 * <p><b>介绍费事件只在 ACTIVATE 时发一次</b>。此前「保存为 ACTIVE 即发」意味着手改状态就能触发付费；
 * 幂等靠 finance 侧的唯一键兜底，但不该靠兜底来保证「只发一次」这件事本身。
 */
@Service
public class ContractServiceImpl implements ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractServiceImpl.class);
    private static final String SYSTEM = "SYSTEM";
    private static final Set<String> BLOCKING = Set.of(PENDING.name(), SIGNED.name(), ACTIVE.name());

    private final LocMappers.ContractMapper contracts;
    private final LocMappers.SiteMapper sites;
    private final LocMappers.VenueMapper venues;
    private final LocContractLogMapper logs;
    private final LocContractAttachMapper attaches;
    private final ContractStateMachine sm;
    private final DomainEventBus events;
    private final FileBindingPort fileBinding;
    private final SysParamPort params;
    private final TransactionTemplate perRow;
    private final ZoneId bizZone;

    public ContractServiceImpl(LocMappers.ContractMapper contracts, LocMappers.SiteMapper sites,
                               LocMappers.VenueMapper venues, LocContractLogMapper logs, LocContractAttachMapper attaches,
                               ContractStateMachine sm, DomainEventBus events, FileBindingPort fileBinding,
                               SysParamPort params, PlatformTransactionManager tm,
                               @Value("${sharehub.biz-zone:Asia/Dubai}") String bizZone) {
        this.contracts = contracts;
        this.sites = sites;
        this.venues = venues;
        this.logs = logs;
        this.attaches = attaches;
        this.sm = sm;
        this.events = events;
        this.fileBinding = fileBinding;
        this.params = params;
        this.perRow = new TransactionTemplate(tm);
        // 逐行独立事务靠「任务运行时没有外层事务」成立（JobRegistry.trigger 不包事务）；用 REQUIRED 而不是 REQUIRES_NEW：
        // 若调用方已在事务里（测试用例 @Transactional、人工在事务内调用），就加入它、随它回滚 ——
        // REQUIRES_NEW 会绕过外层回滚直接提交，JobCatalogTest 冒烟一次就把共享测试库写脏了（2026-09-25 实测）。
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.bizZone = ZoneId.of(bizZone);
    }

    @Override
    public LocalDate today() {
        return LocalDate.now(bizZone);
    }

    // —— 查询 ——

    @Override
    public PageResult<Contract> page(Query q) {
        int page = q.page() == null || q.page() < 1 ? 1 : q.page();
        int size = q.size() == null || q.size() < 1 ? 10 : Math.min(q.size(), 200);
        LambdaQueryWrapper<LocContract> w = new LambdaQueryWrapper<>();
        if (notBlank(q.keyword())) {
            String k = q.keyword().trim();
            w.and(x -> x.like(LocContract::getVenueName, k).or().like(LocContract::getSiteName, k)
                    .or().eq(LocContract::getContractNo, k));
        }
        if (notBlank(q.status())) {
            w.in(LocContract::getStatus, List.of(q.status().split(",")).stream().map(s -> ContractStatus.of(s).name()).toList());
        }
        if (notBlank(q.venueNo())) w.eq(LocContract::getVenueNo, q.venueNo());
        if (notBlank(q.siteNo())) w.eq(LocContract::getSiteNo, q.siteNo());
        if (q.endFrom() != null) w.ge(LocContract::getEndAt, q.endFrom().toString());
        if (q.endTo() != null) w.le(LocContract::getEndAt, q.endTo().toString());
        if (Boolean.TRUE.equals(q.pendingMine())) {
            // 「待我审批」：待审批里不是我提交的（运营环节与财务会签都算，按权限各看各的按钮）+ 终止申请不是我发起的
            String me = SecurityUtils.userNo();
            w.and(x -> x.nested(y -> y.eq(LocContract::getStatus, PENDING.name()).ne(LocContract::getSubmittedBy, me))
                    .or().nested(y -> y.eq(LocContract::getStatus, ACTIVE.name())
                            .eq(LocContract::getTermReqStatus, ContractTermReqStatus.PENDING.name()).ne(LocContract::getTermReqBy, me)));
        }
        w.orderByDesc(LocContract::getId);
        Page<LocContract> r = contracts.selectPage(new Page<>(page, size), w);
        return new PageResult<>(toVOs(r.getRecords()), r.getTotal());
    }

    @Override
    public ContractSummary summary() {
        String me = SecurityUtils.userNo();
        LocalDate today = today();
        long pendingMine = contracts.selectCount(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getStatus, PENDING.name()).ne(LocContract::getSubmittedBy, me)
                .and(x -> x.isNull(LocContract::getAuditStage).or().eq(LocContract::getAuditStage, ContractAuditStage.OPS.name())));
        long pendingCosign = contracts.selectCount(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getStatus, PENDING.name()).eq(LocContract::getAuditStage, ContractAuditStage.FINANCE.name())
                .ne(LocContract::getSubmittedBy, me));
        long terminationPending = contracts.selectCount(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getStatus, ACTIVE.name()).eq(LocContract::getTermReqStatus, ContractTermReqStatus.PENDING.name())
                .ne(LocContract::getTermReqBy, me));
        long expiring = contracts.selectCount(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getStatus, ACTIVE.name())
                .between(LocContract::getEndAt, today.toString(), today.plusDays(60).toString()));
        // 已到期未续：到期合同所在站点，当前没有任何「未结束」的合同
        Set<String> expiredSites = contracts.selectList(new LambdaQueryWrapper<LocContract>()
                        .select(LocContract::getSiteNo).eq(LocContract::getStatus, EXPIRED.name()))
                .stream().map(LocContract::getSiteNo).filter(Objects::nonNull).collect(Collectors.toSet());
        long expiredNotRenewed = 0;
        if (!expiredSites.isEmpty()) {
            Set<String> covered = contracts.selectList(new LambdaQueryWrapper<LocContract>()
                            .select(LocContract::getSiteNo).in(LocContract::getSiteNo, expiredSites)
                            .in(LocContract::getStatus, BLOCKING))
                    .stream().map(LocContract::getSiteNo).collect(Collectors.toSet());
            expiredNotRenewed = expiredSites.stream().filter(s -> !covered.contains(s)).count();
        }
        // 缺签署件：已批待生效、却没有任何一份真实文件的合同
        List<String> signed = contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .select(LocContract::getContractNo).eq(LocContract::getStatus, SIGNED.name()))
                .stream().map(LocContract::getContractNo).toList();
        long missingScan = 0;
        if (!signed.isEmpty()) {
            Set<String> withScan = attaches.selectList(new LambdaQueryWrapper<LocContractAttach>()
                            .select(LocContractAttach::getContractNo).in(LocContractAttach::getContractNo, signed)
                            .isNotNull(LocContractAttach::getFileNo))
                    .stream().map(LocContractAttach::getContractNo).collect(Collectors.toSet());
            missingScan = signed.stream().filter(n -> !withScan.contains(n)).count();
        }
        return new ContractSummary(pendingMine, pendingCosign, terminationPending, expiring, expiredNotRenewed, missingScan);
    }

    @Override
    public Contract get(String contractNo) {
        return toVOs(List.of(require(contractNo))).get(0);
    }

    @Override
    public List<ContractLogItem> logs(String contractNo) {
        require(contractNo);   // 带范围：看不到合同就看不到它的日志
        return logs.selectList(new LambdaQueryWrapper<LocContractLog>()
                        .eq(LocContractLog::getContractNo, contractNo).orderByAsc(LocContractLog::getId))
                .stream().map(l -> new ContractLogItem(l.getEvent(), l.getFromStatus(), l.getToStatus(),
                        l.getOperator(), l.getNote(), l.getCreatedAt()))
                .toList();
    }

    // —— 起草 ——

    @Override
    @Transactional
    public Contract create(Draft d) {
        return insertDraft(d, null);
    }

    @Override
    @Transactional
    public Contract createFromLead(Draft d, String leadNo) {
        return insertDraft(d, leadNo);
    }

    private Contract insertDraft(Draft d, String leadNo) {
        LocContract c = new LocContract();
        c.setContractNo(IdGenerator.next(BizKey.CONTRACT));
        c.setTenantId("MAIN");
        c.setStatus(DRAFT.name());
        c.setContractKind(ContractKind.MAIN.name());
        c.setSourceLeadNo(leadNo);
        apply(c, d);
        contracts.insert(c);
        writeLog(c.getContractNo(), "CREATE", null, DRAFT.name(), operator(), leadNo == null ? null : "来自商机 " + leadNo);
        return get(c.getContractNo());
    }

    @Override
    @Transactional
    public Contract updateDraft(String contractNo, Draft d) {
        LocContract c = require(contractNo);
        if (!DRAFT.name().equals(c.getStatus())) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.contract.draft_only");
        }
        AuditChanges.record("场地方分成比率", c.getShareRate(), d.shareRate());
        AuditChanges.record("进场费", c.getEntryFee(), d.entryFee());
        AuditChanges.record("生效起", c.getStartAt(), str(d.startAt()));
        AuditChanges.record("生效止", c.getEndAt(), str(d.endAt()));
        apply(c, d);
        contracts.update(c, new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, contractNo).eq(LocContract::getStatus, DRAFT.name()));
        writeLog(contractNo, "UPDATE", DRAFT.name(), DRAFT.name(), operator(), null);
        return get(contractNo);
    }

    /** 草稿字段白名单赋值 + 校验。状态、审批字段、归属锚点以外的派生字段一律不经这里。 */
    private void apply(LocContract c, Draft d) {
        if (d.venueNo() == null || d.venueNo().isBlank()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "venueNo");
        if (d.siteNo() == null || d.siteNo().isBlank()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "siteNo");
        LocVenue venue = venues.selectOne(new LambdaQueryWrapper<LocVenue>().eq(LocVenue::getVenueNo, d.venueNo()).last("limit 1"));
        if (venue == null) throw BizException.notFound(d.venueNo());
        LocSite site = sites.selectOne(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, d.siteNo()).last("limit 1"));
        if (site == null) throw BizException.notFound(d.siteNo());
        if (site.getVenueNo() != null && !site.getVenueNo().equals(d.venueNo())) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.site_venue_mismatch", d.siteNo(), d.venueNo());
        }
        if (d.startAt() == null || d.endAt() == null) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "startAt / endAt");
        if (d.endAt().isBefore(d.startAt())) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.end_before_start");
        BigDecimal rate = nz(d.shareRate());
        // 未给分成模式（旧表单只有比例与进场费）：按填了什么推断，而不是 400 —— 存量合同就是这么录的
        ContractShareMode mode = notBlank(d.shareMode()) ? ContractShareMode.of(d.shareMode())
                : rate.signum() > 0 ? ContractShareMode.SHARE
                : nz(d.entryFee()).signum() > 0 ? ContractShareMode.ENTRY_FEE : ContractShareMode.FREE;
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.rate_range");
        switch (mode) {
            case SHARE -> { if (rate.signum() <= 0) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.rate_required"); }
            case GUARANTEE -> {
                if (rate.signum() <= 0) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.rate_required");
                if (nz(d.guaranteeAmount()).signum() <= 0) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.guarantee_required");
            }
            case ENTRY_FEE -> { if (nz(d.entryFee()).signum() <= 0) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.entry_fee_required"); }
            case FREE -> { /* 免费入驻：比例与费用置 0 */ }
        }
        c.setVenueNo(d.venueNo());
        c.setSiteNo(d.siteNo());
        c.setVenueName(venue.getName());
        c.setSiteName(site.getName());
        c.setShareMode(mode.name());
        c.setShareBase(d.shareBase() == null ? ContractShareBase.NET.name() : ContractShareBase.of(d.shareBase()).name());
        c.setShareRate(mode == ContractShareMode.FREE || mode == ContractShareMode.ENTRY_FEE ? 0d : rate.doubleValue());
        c.setGuaranteeAmount(mode == ContractShareMode.GUARANTEE ? d.guaranteeAmount() : null);
        c.setEntryFee(mode == ContractShareMode.FREE ? 0d : nz(d.entryFee()).doubleValue());
        c.setCurrency(notBlank(d.currency()) ? d.currency() : "AED");
        c.setSettlePeriod(notBlank(d.settlePeriod()) ? d.settlePeriod() : "MONTH");
        c.setStartAt(d.startAt().toString());
        c.setEndAt(d.endAt().toString());
        c.setAutoRenew(d.autoRenew());
        c.setExclusiveFlag(d.exclusive());
        c.setDeviceQuota(d.deviceQuota());
        c.setPlacementNote(d.placementNote());
        c.setDepositAmount(d.depositAmount());
        c.setDepositTerms(d.depositTerms());
        c.setSignerName(d.signerName());
        c.setRemark(d.remark());
    }

    // —— 审批流 ——

    @Override
    @Transactional
    public Contract submit(String contractNo) {
        LocContract c = require(contractNo);
        requireNoOverlap(c);
        String me = operator();
        transit(c, "SUBMIT", null, x -> { x.setSubmittedBy(me); x.setSubmittedAt(LocalDateTime.now()); },
                u -> u.set(LocContract::getSubmittedBy, me).set(LocContract::getSubmittedAt, LocalDateTime.now())
                        .set(LocContract::getAuditStage, ContractAuditStage.OPS.name())
                        .set(LocContract::getAuditedBy, null).set(LocContract::getAuditedAt, null).set(LocContract::getAuditNote, null)
                        .set(LocContract::getFinanceAuditedBy, null).set(LocContract::getFinanceAuditedAt, null)
                        .set(LocContract::getFinanceAuditNote, null));
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract withdraw(String contractNo, String note) {
        LocContract c = require(contractNo);
        if (!operator().equals(c.getSubmittedBy())) throw ai.neargo.sharehub.common.BizException.conflict("error.contract.withdraw_submitter_only");
        transit(c, "WITHDRAW", note, x -> { }, u -> u.set(LocContract::getAuditStage, null));
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract audit(String contractNo, String result, String reason) {
        LocContract c = require(contractNo);
        String me = operator();
        boolean approve = "APPROVE".equalsIgnoreCase(result);
        if (!approve && !"REJECT".equalsIgnoreCase(result)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.invalid_value", "result=" + result);
        if (me.equals(c.getSubmittedBy())) throw ai.neargo.sharehub.common.BizException.conflict("error.contract.self_audit");
        if (!approve && !notBlank(reason)) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.reason_required");
        if (ContractAuditStage.FINANCE.name().equals(c.getAuditStage())) {
            throw BizException.conflict("error.contract.awaiting_cosign", contractNo);
        }
        if (approve) requireNoOverlap(c);   // 提交之后可能已有别的合同获批
        LocalDateTime now = LocalDateTime.now();
        if (approve && needsCosign(c)) {
            // 运营通过、仍待财务：状态不动（PENDING），只推进审批环节 —— 按原状态 + 原环节条件更新，并发只一人赢
            int n = contracts.update(null, new LambdaUpdateWrapper<LocContract>()
                    .eq(LocContract::getContractNo, contractNo).eq(LocContract::getStatus, PENDING.name())
                    .and(x -> x.isNull(LocContract::getAuditStage).or().eq(LocContract::getAuditStage, ContractAuditStage.OPS.name()))
                    .set(LocContract::getAuditStage, ContractAuditStage.FINANCE.name())
                    .set(LocContract::getAuditedBy, me).set(LocContract::getAuditedAt, now).set(LocContract::getAuditNote, reason));
            if (n == 0) throw BizException.conflict("error.common.state_changed");
            writeLog(contractNo, "APPROVE", PENDING.name(), PENDING.name(), me, "运营审批通过，待财务会签：" + cosignReasons(c));
            return get(contractNo);
        }
        transit(c, approve ? "APPROVE" : "REJECT", reason, x -> { },
                u -> u.set(LocContract::getAuditedBy, me).set(LocContract::getAuditedAt, now).set(LocContract::getAuditNote, reason)
                        .set(LocContract::getAuditStage, null));
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract cosign(String contractNo, String result, String reason) {
        LocContract c = require(contractNo);
        String me = operator();
        boolean approve = "APPROVE".equalsIgnoreCase(result);
        if (!approve && !"REJECT".equalsIgnoreCase(result)) throw BizException.badRequest("error.common.invalid_value", "result=" + result);
        if (!PENDING.name().equals(c.getStatus()) || !ContractAuditStage.FINANCE.name().equals(c.getAuditStage())) {
            throw BizException.conflict("error.contract.cosign_not_pending", contractNo);
        }
        if (me.equals(c.getSubmittedBy())) throw BizException.conflict("error.contract.self_audit");
        // 会签的意义是「第二双眼睛」：与运营审批人是同一人就等于没加签
        if (me.equals(c.getAuditedBy())) throw BizException.conflict("error.contract.cosign_same_auditor");
        if (!approve && !notBlank(reason)) throw BizException.badRequest("error.common.reason_required");
        if (approve) requireNoOverlap(c);
        LocalDateTime now = LocalDateTime.now();
        transit(c, approve ? "COSIGN" : "COSIGN_REJECT", reason, x -> { },
                u -> u.eq(LocContract::getAuditStage, ContractAuditStage.FINANCE.name())
                        .set(LocContract::getFinanceAuditedBy, me).set(LocContract::getFinanceAuditedAt, now)
                        .set(LocContract::getFinanceAuditNote, reason).set(LocContract::getAuditStage, null));
        return get(contractNo);
    }

    /** 条件加签：比例 / 进场费超阈值或保底超阈值（默认带保底即加签）。阈值走 sys_param，默认值与 V104 种子一致。 */
    private boolean needsCosign(LocContract c) {
        return !cosignReasons(c).isEmpty();
    }

    private String cosignReasons(LocContract c) {
        List<String> why = new ArrayList<>();
        BigDecimal rateCap = params.decimalOf("contract.cosign.share_rate", new BigDecimal("0.30"));
        BigDecimal feeCap = params.decimalOf("contract.cosign.entry_fee", new BigDecimal("10000"));
        BigDecimal guaranteeCap = params.decimalOf("contract.cosign.guarantee", BigDecimal.ZERO);
        if (c.getShareRate() != null && BigDecimal.valueOf(c.getShareRate()).compareTo(rateCap) > 0) {
            why.add("分成比例 " + c.getShareRate() + " > " + rateCap.toPlainString());
        }
        if (c.getEntryFee() != null && BigDecimal.valueOf(c.getEntryFee()).compareTo(feeCap) > 0) {
            why.add("进场费 " + c.getEntryFee() + " > " + feeCap.toPlainString());
        }
        if (c.getGuaranteeAmount() != null && c.getGuaranteeAmount().compareTo(guaranteeCap) > 0) {
            why.add("保底 " + c.getGuaranteeAmount().toPlainString());
        }
        return String.join("；", why);
    }

    @Override
    @Transactional
    public Contract sign(String contractNo, LocalDate signedAt, List<String> fileNos) {
        LocContract c = require(contractNo);
        if (!SIGNED.name().equals(c.getStatus())) throw ai.neargo.sharehub.common.BizException.conflict("error.contract.sign_signed_only");
        if (signedAt == null || signedAt.isAfter(today())) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.date_not_future");
        if (fileNos == null || fileNos.isEmpty()) throw ai.neargo.sharehub.common.BizException.badRequest("error.contract.scan_required");
        attachFiles(c, fileNos);
        contracts.update(null, new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, contractNo).set(LocContract::getSignedAt, signedAt));
        c.setSignedAt(signedAt);
        writeLog(contractNo, "SIGN", c.getStatus(), c.getStatus(), operator(), "签署日期 " + signedAt);
        if (!LocalDate.parse(c.getStartAt()).isAfter(today())) activate(c, operator());
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract terminate(String contractNo, String reason, LocalDate effectiveAt) {
        LocContract c = require(contractNo);
        if (!ACTIVE.name().equals(c.getStatus())) throw BizException.conflict("error.contract.terminate_active_only");
        if (!notBlank(reason)) throw BizException.badRequest("error.common.reason_required");
        if (effectiveAt == null || effectiveAt.isBefore(today())) throw BizException.badRequest("error.common.date_not_past");
        if (ContractTermReqStatus.PENDING.name().equals(c.getTermReqStatus())
                || ContractTermReqStatus.APPROVED.name().equals(c.getTermReqStatus())) {
            throw BizException.conflict("error.contract.termination_pending", contractNo);
        }
        String me = operator();
        // 条件：合同仍生效、且没有在途申请（并发下两人同时发起只一人赢）
        int n = contracts.update(null, new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, contractNo).eq(LocContract::getStatus, ACTIVE.name())
                .and(x -> x.isNull(LocContract::getTermReqStatus).or().eq(LocContract::getTermReqStatus, ContractTermReqStatus.REJECTED.name()))
                .set(LocContract::getTermReqStatus, ContractTermReqStatus.PENDING.name())
                .set(LocContract::getTermReqReason, reason).set(LocContract::getTermReqEffectiveAt, effectiveAt)
                .set(LocContract::getTermReqBy, me).set(LocContract::getTermReqAt, LocalDateTime.now())
                .set(LocContract::getTermAuditBy, null).set(LocContract::getTermAuditAt, null).set(LocContract::getTermAuditNote, null));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        writeLog(contractNo, "TERM_REQUEST", ACTIVE.name(), ACTIVE.name(), me, "终止日 " + effectiveAt + "：" + reason);
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract auditTermination(String contractNo, String result, String reason) {
        LocContract c = require(contractNo);
        String me = operator();
        boolean approve = "APPROVE".equalsIgnoreCase(result);
        if (!approve && !"REJECT".equalsIgnoreCase(result)) throw BizException.badRequest("error.common.invalid_value", "result=" + result);
        if (!ACTIVE.name().equals(c.getStatus()) || !ContractTermReqStatus.PENDING.name().equals(c.getTermReqStatus())) {
            throw BizException.conflict("error.contract.termination_not_pending", contractNo);
        }
        if (me.equals(c.getTermReqBy())) throw BizException.conflict("error.contract.self_audit");
        if (!approve && !notBlank(reason)) throw BizException.badRequest("error.common.reason_required");
        ContractTermReqStatus to = approve ? ContractTermReqStatus.APPROVED : ContractTermReqStatus.REJECTED;
        int n = contracts.update(null, new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, contractNo).eq(LocContract::getStatus, ACTIVE.name())
                .eq(LocContract::getTermReqStatus, ContractTermReqStatus.PENDING.name())
                .set(LocContract::getTermReqStatus, to.name()).set(LocContract::getTermAuditBy, me)
                .set(LocContract::getTermAuditAt, LocalDateTime.now()).set(LocContract::getTermAuditNote, reason));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        writeLog(contractNo, approve ? "TERM_APPROVE" : "TERM_REJECT", ACTIVE.name(), ACTIVE.name(), me, reason);
        if (approve && !c.getTermReqEffectiveAt().isAfter(today())) {
            LocalDateTime now = LocalDateTime.now();
            transit(c, "TERMINATE", c.getTermReqReason(), x -> { },
                    u -> u.set(LocContract::getEndedAt, now).set(LocContract::getEndReason, c.getTermReqReason()));
        }
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract supplement(String contractNo, LocalDate startAt) {
        LocContract parent = require(contractNo);
        if (!ACTIVE.name().equals(parent.getStatus())) throw BizException.conflict("error.contract.supplement_active_only");
        Long inFlight = contracts.selectCount(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getParentContractNo, contractNo).in(LocContract::getStatus, DRAFT.name(), PENDING.name(), SIGNED.name()));
        if (inFlight != null && inFlight > 0) throw BizException.conflict("error.contract.supplement_in_flight", contractNo);
        LocalDate start = startAt == null ? today().plusDays(1) : startAt;
        LocalDate parentEnd = LocalDate.parse(parent.getEndAt());
        if (!start.isAfter(LocalDate.parse(parent.getStartAt())) || start.isAfter(parentEnd) || start.isBefore(today())) {
            throw BizException.badRequest("error.contract.supplement_start_range", parent.getStartAt(), parent.getEndAt());
        }
        LocContract c = new LocContract();
        c.setContractNo(IdGenerator.next(BizKey.CONTRACT));
        c.setTenantId("MAIN");
        c.setStatus(DRAFT.name());
        c.setContractKind(ContractKind.SUPPLEMENT.name());
        c.setParentContractNo(contractNo);
        c.setSourceLeadNo(parent.getSourceLeadNo());   // 归因跟着场地走，不因改条款丢失
        apply(c, templateOf(parent, start, parentEnd));
        contracts.insert(c);
        writeLog(c.getContractNo(), "SUPPLEMENT", null, DRAFT.name(), operator(), "补充 " + contractNo + "，" + start + " 起取代原条款");
        return get(c.getContractNo());
    }

    /** 以已有合同的条款为模板（续签 / 补充协议共用）。 */
    private static Draft templateOf(LocContract old, LocalDate start, LocalDate end) {
        return new Draft(old.getVenueNo(), old.getSiteNo(), nvl(old.getShareMode(), ContractShareMode.SHARE.name()),
                old.getShareBase(), BigDecimal.valueOf(old.getShareRate() == null ? 0 : old.getShareRate()),
                old.getGuaranteeAmount(), BigDecimal.valueOf(old.getEntryFee() == null ? 0 : old.getEntryFee()),
                old.getCurrency(), old.getSettlePeriod(), start, end, Boolean.TRUE.equals(old.getAutoRenew()),
                Boolean.TRUE.equals(old.getExclusiveFlag()), old.getDeviceQuota(), old.getPlacementNote(),
                old.getDepositAmount(), old.getDepositTerms(), old.getSignerName(), old.getRemark());
    }

    @Override
    @Transactional
    public Contract renew(String contractNo) {
        LocContract old = require(contractNo);
        if (!ACTIVE.name().equals(old.getStatus()) && !EXPIRED.name().equals(old.getStatus())) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.contract.renew_invalid");
        }
        LocalDate oldStart = LocalDate.parse(old.getStartAt());
        LocalDate oldEnd = LocalDate.parse(old.getEndAt());
        LocalDate start = oldEnd.plusDays(1);
        LocalDate end = start.plusDays(ChronoUnit.DAYS.between(oldStart, oldEnd));
        Draft d = templateOf(old, start, end);
        LocContract c = new LocContract();
        c.setContractNo(IdGenerator.next(BizKey.CONTRACT));
        c.setTenantId("MAIN");
        c.setStatus(DRAFT.name());
        c.setPrevContractNo(contractNo);
        c.setContractKind(ContractKind.MAIN.name());
        c.setSourceLeadNo(old.getSourceLeadNo());
        apply(c, d);
        contracts.insert(c);
        writeLog(c.getContractNo(), "RENEW", null, DRAFT.name(), operator(), "续签自 " + contractNo);
        return get(c.getContractNo());
    }

    @Override
    @Transactional
    public Contract attach(String contractNo, List<String> fileNos) {
        LocContract c = require(contractNo);
        if (Set.of(EXPIRED.name(), ContractStatus.TERMINATED.name()).contains(c.getStatus())) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.contract.ended_no_attach");
        }
        if (fileNos == null || fileNos.isEmpty()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.missing_parameter", "fileNos");
        attachFiles(c, fileNos);
        return get(contractNo);
    }

    @Override
    @Transactional
    public Contract removeAttachment(String contractNo, String attachNo) {
        require(contractNo);
        LocContractAttach a = attaches.selectOne(new LambdaQueryWrapper<LocContractAttach>()
                .eq(LocContractAttach::getContractNo, contractNo).eq(LocContractAttach::getAttachNo, attachNo).last("limit 1"));
        if (a == null) throw BizException.notFound(attachNo);
        attaches.deleteById(a.getId());   // @TableLogic：软删 —— 「曾有过一个附件后来被删了」本身是信息
        if (a.getFileNo() != null) fileBinding.remove(a.getFileNo(), "CONTRACT", contractNo);
        return get(contractNo);
    }

    // —— 定时 ——

    @Override
    public ContractTickResult tick(LocalDate today) {
        int expired = 0, activated = 0, terminated = 0;
        // 终止先于到期：终止日与到期日同一天时记为「终止」（有审批留痕的那个才是真实原因）
        List<LocContract> ending = DataScopeContext.executeWithoutScope(() -> contracts.selectList(
                new LambdaQueryWrapper<LocContract>().eq(LocContract::getStatus, ACTIVE.name())
                        .eq(LocContract::getTermReqStatus, ContractTermReqStatus.APPROVED.name())
                        .le(LocContract::getTermReqEffectiveAt, today).last("limit 500")));
        for (LocContract c : ending) {
            Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() ->
                    transitQuietly(c, "TERMINATE", c.getTermReqReason(),
                            u -> u.set(LocContract::getEndedAt, LocalDateTime.now()).set(LocContract::getEndReason, c.getTermReqReason()))));
            if (Boolean.TRUE.equals(ok)) terminated++;
        }
        List<LocContract> due = DataScopeContext.executeWithoutScope(() -> contracts.selectList(
                new LambdaQueryWrapper<LocContract>().eq(LocContract::getStatus, ACTIVE.name())
                        .lt(LocContract::getEndAt, today.toString()).last("limit 500")));
        for (LocContract c : due) {
            Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() -> {
                String note = c.getEndReason() != null && c.getEndReason().startsWith("终止计划") ? c.getEndReason() : "到期";
                return transitQuietly(c, "EXPIRE", note, u -> u.set(LocContract::getEndedAt, LocalDateTime.now()));
            }));
            if (Boolean.TRUE.equals(ok)) expired++;
        }
        List<LocContract> ready = DataScopeContext.executeWithoutScope(() -> contracts.selectList(
                new LambdaQueryWrapper<LocContract>().eq(LocContract::getStatus, SIGNED.name())
                        .le(LocContract::getStartAt, today.toString()).isNotNull(LocContract::getSignedAt).last("limit 500")));
        for (LocContract c : ready) {
            Boolean ok = perRow.execute(st -> DataScopeContext.executeWithoutScope(() -> activate(c, SYSTEM)));
            if (Boolean.TRUE.equals(ok)) activated++;
        }
        if (expired + activated + terminated > 0) {
            log.info("合同定时迁移：生效 {} 份，到期 {} 份，终止 {} 份", activated, expired, terminated);
        }
        return new ContractTickResult(activated, expired, terminated);
    }

    /**
     * 生效（签署动作与定时任务共用）。前置：有签署日期、至少一份真实签署件。
     * 同站点旧的 ACTIVE 先 EXPIRE —— 同站点任一时刻最多一份生效合同。
     *
     * @return 是否真的生效了（缺签署件时保持 SIGNED，返回 false）
     */
    private boolean activate(LocContract c, String operator) {
        if (c.getSignedAt() == null) return false;
        Long scans = attaches.selectCount(new LambdaQueryWrapper<LocContractAttach>()
                .eq(LocContractAttach::getContractNo, c.getContractNo()).isNotNull(LocContractAttach::getFileNo));
        if (scans == null || scans == 0) return false;
        for (LocContract old : contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getSiteNo, c.getSiteNo()).eq(LocContract::getStatus, ACTIVE.name())
                .ne(LocContract::getContractNo, c.getContractNo()))) {
            String note = (ContractKind.SUPPLEMENT.name().equals(c.getContractKind()) ? "被补充协议 " : "被续签合同 ")
                    + c.getContractNo() + " 替代";
            transitQuietly(old, "EXPIRE", note,
                    u -> u.set(LocContract::getEndedAt, LocalDateTime.now()).set(LocContract::getEndReason, note));
        }
        String from = c.getStatus();
        String to = sm.next(from, "ACTIVATE");
        int n = contracts.update(null, new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, c.getContractNo()).eq(LocContract::getStatus, from)
                .set(LocContract::getStatus, to).set(LocContract::getActivatedAt, LocalDateTime.now()));
        if (n == 0) return false;
        writeLog(c.getContractNo(), "ACTIVATE", from, to, operator, null);
        // 补充协议是改条款、不是新签：不发签约事件 —— 牵线费按合同号幂等，补充协议换了号会再付一次
        if (!ContractKind.SUPPLEMENT.name().equals(c.getContractKind())) {
            events.publish(new ContractSignedEvent(c.getContractNo(), c.getSiteNo(), c.getVenueNo(), c.getCurrency()));
        }
        log.info("合同生效 contractNo={} site={}", c.getContractNo(), c.getSiteNo());
        return true;
    }

    // —— 骨架 ——

    /** 状态迁移：状态机求目标态 → 按原状态条件更新（并发只一人赢，输的 409）→ 写日志。 */
    private void transit(LocContract c, String event, String note, Consumer<LocContract> local,
                         Consumer<LambdaUpdateWrapper<LocContract>> sets) {
        String from = c.getStatus();
        String to = sm.next(from, event);
        LambdaUpdateWrapper<LocContract> u = new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, c.getContractNo()).eq(LocContract::getStatus, from)
                .set(LocContract::getStatus, to);
        sets.accept(u);
        if (contracts.update(null, u) == 0) throw ai.neargo.sharehub.common.BizException.conflict("error.common.state_changed");
        local.accept(c);
        c.setStatus(to);
        writeLog(c.getContractNo(), event, from, to, operator(), note);
    }

    /** 系统迁移：条件不满足（已被别人迁走）静默跳过，返回是否迁移。 */
    private boolean transitQuietly(LocContract c, String event, String note, Consumer<LambdaUpdateWrapper<LocContract>> sets) {
        String from = c.getStatus();
        String to = sm.next(from, event);
        LambdaUpdateWrapper<LocContract> u = new LambdaUpdateWrapper<LocContract>()
                .eq(LocContract::getContractNo, c.getContractNo()).eq(LocContract::getStatus, from)
                .set(LocContract::getStatus, to);
        sets.accept(u);
        if (contracts.update(null, u) == 0) return false;
        writeLog(c.getContractNo(), event, from, to, SYSTEM, note);
        return true;
    }

    /** 期限不得与同站点其他 待审批 / 已批 / 生效中 的合同重叠（续签来源合同除外）。 */
    private void requireNoOverlap(LocContract c) {
        LocalDate s = LocalDate.parse(c.getStartAt());
        LocalDate e = LocalDate.parse(c.getEndAt());
        for (LocContract o : DataScopeContext.executeWithoutScope(() -> contracts.selectList(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getSiteNo, c.getSiteNo()).in(LocContract::getStatus, BLOCKING)
                .ne(LocContract::getContractNo, c.getContractNo())))) {
            if (o.getContractNo().equals(c.getPrevContractNo()) || o.getContractNo().equals(c.getParentContractNo())) continue;
            if (o.getStartAt() == null || o.getEndAt() == null) continue;
            LocalDate os = LocalDate.parse(o.getStartAt()), oe = LocalDate.parse(o.getEndAt());
            if (!os.isAfter(e) && !s.isAfter(oe)) {
                throw ai.neargo.sharehub.common.BizException.conflict("error.contract.overlap", o.getContractNo(), o.getStartAt(), o.getEndAt());
            }
        }
    }

    private void attachFiles(LocContract c, List<String> fileNos) {
        String agentNo = sites.selectList(new LambdaQueryWrapper<LocSite>().eq(LocSite::getSiteNo, c.getSiteNo()).last("limit 1"))
                .stream().map(LocSite::getAgentNo).filter(Objects::nonNull).findFirst().orElse(null);
        List<FileRef> bound = fileBinding.bind(fileNos, "CONTRACT", c.getContractNo(), agentNo);
        Set<String> existing = attaches.selectList(new LambdaQueryWrapper<LocContractAttach>()
                        .eq(LocContractAttach::getContractNo, c.getContractNo()).isNotNull(LocContractAttach::getFileNo))
                .stream().map(LocContractAttach::getFileNo).collect(Collectors.toSet());
        String me = operator();
        for (FileRef f : bound) {
            if (existing.contains(f.fileNo()) || !fileNos.contains(f.fileNo())) continue;
            LocContractAttach a = new LocContractAttach();
            a.setAttachNo(IdGenerator.next("ATT"));
            a.setTenantId("MAIN");
            a.setContractNo(c.getContractNo());
            a.setFileNo(f.fileNo());
            a.setFileName(f.originalName());
            a.setSize(f.sizeBytes());       // 取文件服务的实际大小，不信前端声明
            a.setUploadedBy(me);
            a.setUploadedAt(LocalDateTime.now());
            attaches.insert(a);
        }
    }

    private LocContract require(String contractNo) {
        LocContract c = contracts.selectOne(new LambdaQueryWrapper<LocContract>()
                .eq(LocContract::getContractNo, contractNo).last("limit 1"));
        if (c == null) throw BizException.notFound(contractNo);
        return c;
    }

    private void writeLog(String contractNo, String event, String from, String to, String operator, String note) {
        LocContractLog l = new LocContractLog();
        l.setContractNo(contractNo);
        l.setEvent(event);
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setOperator(operator);
        l.setNote(note);
        logs.insert(l);
    }

    // —— VO ——

    private List<Contract> toVOs(List<LocContract> rows) {
        if (rows.isEmpty()) return List.of();
        List<String> nos = rows.stream().map(LocContract::getContractNo).toList();
        Map<String, List<LocContractAttach>> byNo = new HashMap<>();
        for (LocContractAttach a : attaches.selectList(new LambdaQueryWrapper<LocContractAttach>()
                .in(LocContractAttach::getContractNo, nos).orderByAsc(LocContractAttach::getId))) {
            byNo.computeIfAbsent(a.getContractNo(), k -> new ArrayList<>()).add(a);
        }
        List<String> fileNos = byNo.values().stream().flatMap(List::stream).map(LocContractAttach::getFileNo)
                .filter(Objects::nonNull).toList();
        Map<String, FileRef> refs = fileBinding.refs(fileNos).stream()
                .collect(Collectors.toMap(FileRef::fileNo, f -> f, (a, b) -> a));
        LocalDate today = today();
        List<Contract> out = new ArrayList<>(rows.size());
        for (LocContract c : rows) {
            List<ContractAttachment> atts = byNo.getOrDefault(c.getContractNo(), List.of()).stream().map(a -> {
                FileRef f = a.getFileNo() == null ? null : refs.get(a.getFileNo());
                return new ContractAttachment(a.getAttachNo(), a.getFileName(), a.getSize(), a.getUploadedBy(),
                        a.getUploadedAt() == null ? null : a.getUploadedAt().toString(), a.getFileNo(),
                        f == null ? null : f.contentType(), f == null ? null : f.previewable());
            }).toList();
            Integer remaining = null;
            if (c.getEndAt() != null && (ACTIVE.name().equals(c.getStatus()) || SIGNED.name().equals(c.getStatus()))) {
                remaining = (int) ChronoUnit.DAYS.between(today, LocalDate.parse(c.getEndAt()));
            }
            out.add(new Contract(c.getContractNo(), c.getVenueNo(), c.getSiteNo(), c.getVenueName(), c.getSiteName(),
                    c.getShareRate() == null ? 0 : c.getShareRate(), c.getEntryFee() == null ? 0 : c.getEntryFee(),
                    c.getStartAt(), c.getEndAt(), c.getStatus(), atts,
                    new ContractTerms(c.getShareMode(), c.getShareBase(), c.getGuaranteeAmount(), c.getCurrency(),
                            c.getSettlePeriod(), c.getDepositAmount(), c.getDepositTerms(), c.getExclusiveFlag(),
                            c.getDeviceQuota(), c.getPlacementNote(), c.getAutoRenew(), c.getSignerName(), c.getRemark()),
                    new ContractFlow(c.getSignedAt(), c.getSubmittedBy(), c.getSubmittedAt(), c.getAuditedBy(),
                            c.getAuditedAt(), c.getAuditNote(), c.getActivatedAt(), c.getEndedAt(), c.getEndReason(),
                            c.getPrevContractNo(), c.getSourceLeadNo(), nvl(c.getContractKind(), ContractKind.MAIN.name()),
                            c.getParentContractNo(), c.getAuditStage(), c.getFinanceAuditedBy(), c.getFinanceAuditedAt(),
                            c.getFinanceAuditNote(), c.getTermReqStatus() == null ? null
                                    : new TerminationRequest(c.getTermReqStatus(), c.getTermReqReason(), c.getTermReqEffectiveAt(),
                                    c.getTermReqBy(), c.getTermReqAt(), c.getTermAuditBy(), c.getTermAuditAt(), c.getTermAuditNote())),
                    remaining));
        }
        return out;
    }

    // —— 工具 ——

    private static String operator() {
        String u = SecurityUtils.currentUser().map(ai.neargo.sharehub.auth.LoginUser::userNo).orElse(null);
        return u == null ? SYSTEM : u;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nvl(String v, String d) {
        return v == null ? d : v;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
