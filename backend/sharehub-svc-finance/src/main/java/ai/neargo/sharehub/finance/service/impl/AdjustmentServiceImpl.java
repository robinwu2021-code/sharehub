package ai.neargo.sharehub.finance.service.impl;

import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.dto.ContractMoneyTerms;
import ai.neargo.sharehub.api.platform.event.SiteClosedEvent;
import ai.neargo.sharehub.api.platform.port.ContractQueryPort;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.finance.AdjustmentKind;
import ai.neargo.sharehub.finance.AdjustmentStatus;
import ai.neargo.sharehub.finance.entity.StlAdjustment;
import ai.neargo.sharehub.finance.mapper.StlAdjustmentMapper;
import ai.neargo.sharehub.finance.service.AdjustmentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 结算调整项实现。
 *
 * <p><b>建议值的方向</b>：两项都是「场地方应返还平台」，记负数 —— 押金是平台付给场地方的、撤场即退；
 * 进场费是平台为整个合同期付的、提前撤场按剩余天数折算返还。合同上的「退还条件」是自由文本，
 * 系统不去解析它，所以只出<b>建议值</b>，由财务确认时改成实际金额（改了必须写说明）。
 */
@Service
public class AdjustmentServiceImpl implements AdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(AdjustmentServiceImpl.class);
    private static final String VENUE = "VENUE";
    private static final String SITE_CLOSED = "SITE_CLOSED";
    private static final String GUARANTEE = "GUARANTEE";

    private final StlAdjustmentMapper adjustments;
    private final ContractQueryPort contracts;
    private final ai.neargo.sharehub.finance.mapper.ShareRecordMapper shares;

    public AdjustmentServiceImpl(StlAdjustmentMapper adjustments, ContractQueryPort contracts,
                                 ai.neargo.sharehub.finance.mapper.ShareRecordMapper shares) {
        this.shares = shares;
        this.adjustments = adjustments;
        this.contracts = contracts;
    }

    @Override
    @Transactional
    public int onSiteClosed(SiteClosedEvent e) {
        ContractMoneyTerms c = contracts.lastMoneyTermsOfSite(e.siteNo());
        if (c == null) return 0;
        LocalDate closed = e.closedAt() == null ? LocalDate.now() : LocalDateTime.parse(e.closedAt()).toLocalDate();
        int n = 0;
        if (positive(c.depositAmount())) {
            n += insert(c, AdjustmentKind.DEPOSIT_REFUND, c.depositAmount().negate(),
                    "撤场退还押金（合同 " + c.contractNo() + "）；按合同退还条件确认金额");
        }
        if (positive(c.entryFee()) && c.startAt() != null && c.endAt() != null && closed.isBefore(c.endAt())) {
            long total = ChronoUnit.DAYS.between(c.startAt(), c.endAt()) + 1;
            long remaining = ChronoUnit.DAYS.between(closed, c.endAt());
            if (total > 0 && remaining > 0) {
                BigDecimal refund = c.entryFee().multiply(BigDecimal.valueOf(remaining))
                        .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
                n += insert(c, AdjustmentKind.ENTRY_FEE_SETTLE, refund.negate(),
                        "提前撤场，进场费按剩余 " + remaining + " / " + total + " 天折算（合同 " + c.contractNo() + "）");
            }
        }
        if (n > 0) log.info("撤场结清：站点 {} 按合同 {} 生成 {} 条结算调整项，待财务确认", e.siteNo(), c.contractNo(), n);
        return n;
    }

    private int insert(ContractMoneyTerms c, AdjustmentKind kind, BigDecimal suggested, String note) {
        Long exists = adjustments.selectCount(new LambdaQueryWrapper<StlAdjustment>().eq(StlAdjustment::getSource, SITE_CLOSED)
                .eq(StlAdjustment::getSiteNo, c.siteNo()).eq(StlAdjustment::getContractNo, c.contractNo())
                .eq(StlAdjustment::getKind, kind.name()));
        if (exists != null && exists > 0) return 0;
        StlAdjustment a = new StlAdjustment();
        a.setAdjNo(IdGenerator.next("ADJ"));
        a.setTenantId("MAIN");
        a.setPayeeType(VENUE);
        a.setPayeeNo(c.venueNo());
        a.setPayeeName(c.venueName());
        a.setKind(kind.name());
        a.setSiteNo(c.siteNo());
        a.setContractNo(c.contractNo());
        a.setAmount(suggested);
        a.setSuggestedAmount(suggested);
        a.setCurrency(c.currency() == null ? "AED" : c.currency());
        a.setStatus(AdjustmentStatus.PENDING.name());
        a.setSource(SITE_CLOSED);
        a.setNote(note);
        adjustments.insert(a);
        return 1;
    }

    @Override
    @Transactional
    public int topUpGuarantees(String period) {
        java.time.YearMonth ym = java.time.YearMonth.parse(period);
        LocalDate from = ym.atDay(1), to = ym.plusMonths(1).atDay(1);
        int n = 0;
        for (ContractQueryPort.GuaranteeTerm g : contracts.guaranteesOverlapping(from, to)) {
            if (g.settlePeriod() != null && !"MONTH".equalsIgnoreCase(g.settlePeriod())) {
                log.warn("保底合同 {} 结算周期为 {}，按月补差不适用，跳过 —— 需财务按合同周期手工补差", g.contractNo(), g.settlePeriod());
                continue;
            }
            Long exists = adjustments.selectCount(new LambdaQueryWrapper<StlAdjustment>().eq(StlAdjustment::getSource, GUARANTEE)
                    .eq(StlAdjustment::getContractNo, g.contractNo()).eq(StlAdjustment::getKind, AdjustmentKind.GUARANTEE_TOPUP.name())
                    .eq(StlAdjustment::getPeriod, period));
            if (exists != null && exists > 0) continue;
            LocalDate s = g.startAt().isAfter(from) ? g.startAt() : from;
            LocalDate e = g.effectiveEnd().isBefore(to.minusDays(1)) ? g.effectiveEnd() : to.minusDays(1);
            long days = ChronoUnit.DAYS.between(s, e) + 1;
            if (days <= 0) continue;
            BigDecimal floor = g.guaranteeAmount().multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(ym.lengthOfMonth()), 2, RoundingMode.HALF_UP);
            // 这份合同带来的场地方分成：分润明细的来源号就是合同号（ShareGenerator 按合同优先费率时写入）
            BigDecimal earned = shares.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.finance.entity.ShareRecord>()
                            .eq(ai.neargo.sharehub.finance.entity.ShareRecord::getDimension, VENUE)
                            .eq(ai.neargo.sharehub.finance.entity.ShareRecord::getSourceNo, g.contractNo())
                            .eq(ai.neargo.sharehub.finance.entity.ShareRecord::getPeriod, period))
                    .stream().map(r -> r.getAmount() == null ? BigDecimal.ZERO : r.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal gap = floor.subtract(earned);
            if (gap.signum() <= 0) continue;
            StlAdjustment a = new StlAdjustment();
            a.setAdjNo(IdGenerator.next("ADJ"));
            a.setTenantId("MAIN");
            a.setPayeeType(VENUE);
            a.setPayeeNo(g.venueNo());
            a.setPayeeName(g.venueName());
            a.setKind(AdjustmentKind.GUARANTEE_TOPUP.name());
            a.setSiteNo(g.siteNo());
            a.setContractNo(g.contractNo());
            a.setAmount(gap);
            a.setSuggestedAmount(gap);
            a.setCurrency(g.currency() == null ? "AED" : g.currency());
            a.setStatus(AdjustmentStatus.CONFIRMED.name());
            a.setSource(GUARANTEE);
            a.setPeriod(period);
            a.setNote("保底 " + floor.toPlainString() + "（" + days + " / " + ym.lengthOfMonth() + " 天）− 分成 " + earned.toPlainString());
            a.setConfirmedBy("SYSTEM");
            a.setConfirmedAt(LocalDateTime.now());
            adjustments.insert(a);
            n++;
        }
        if (n > 0) log.info("保底补差 period={} 生成 {} 条", period, n);
        return n;
    }

    @Override
    public PageResult<Adjustment> page(Integer page, Integer size, String status, String payeeNo, String siteNo) {
        LambdaQueryWrapper<StlAdjustment> w = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) w.eq(StlAdjustment::getStatus, AdjustmentStatus.valueOf(status.trim().toUpperCase()).name());
        if (payeeNo != null && !payeeNo.isBlank()) w.eq(StlAdjustment::getPayeeNo, payeeNo.trim());
        if (siteNo != null && !siteNo.isBlank()) w.eq(StlAdjustment::getSiteNo, siteNo.trim());
        w.orderByDesc(StlAdjustment::getId);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        Page<StlAdjustment> r = adjustments.selectPage(new Page<>(p, s), w);
        return new PageResult<>(r.getRecords().stream().map(AdjustmentServiceImpl::vo).toList(), r.getTotal());
    }

    @Override
    @Transactional
    public Adjustment confirm(String adjNo, BigDecimal amount, String note) {
        StlAdjustment a = require(adjNo);
        if (!AdjustmentStatus.PENDING.name().equals(a.getStatus())) throw BizException.conflict("error.adjustment.not_pending", adjNo);
        BigDecimal finalAmount = amount == null ? a.getAmount() : amount.setScale(2, RoundingMode.HALF_UP);
        boolean changed = finalAmount.compareTo(a.getSuggestedAmount() == null ? a.getAmount() : a.getSuggestedAmount()) != 0;
        if (changed && (note == null || note.isBlank())) throw BizException.badRequest("error.common.note_required");
        int n = adjustments.update(null, new LambdaUpdateWrapper<StlAdjustment>().eq(StlAdjustment::getAdjNo, adjNo)
                .eq(StlAdjustment::getStatus, AdjustmentStatus.PENDING.name())
                .set(StlAdjustment::getStatus, AdjustmentStatus.CONFIRMED.name()).set(StlAdjustment::getAmount, finalAmount)
                .set(StlAdjustment::getConfirmedBy, operator()).set(StlAdjustment::getConfirmedAt, LocalDateTime.now())
                .set(note != null && !note.isBlank(), StlAdjustment::getNote, a.getNote() + "；确认：" + (note == null ? "" : note.trim())));
        if (n == 0) throw BizException.conflict("error.common.state_changed");
        return vo(require(adjNo));
    }

    @Override
    @Transactional
    public Adjustment voidIt(String adjNo, String reason) {
        if (reason == null || reason.isBlank()) throw BizException.badRequest("error.common.reason_required");
        StlAdjustment a = require(adjNo);
        int n = adjustments.update(null, new LambdaUpdateWrapper<StlAdjustment>().eq(StlAdjustment::getAdjNo, adjNo)
                .in(StlAdjustment::getStatus, AdjustmentStatus.PENDING.name(), AdjustmentStatus.CONFIRMED.name())
                .set(StlAdjustment::getStatus, AdjustmentStatus.VOID.name())
                .set(StlAdjustment::getNote, a.getNote() + "；作废：" + reason.trim()));
        if (n == 0) throw BizException.conflict("error.adjustment.not_pending", adjNo);
        return vo(require(adjNo));
    }

    private StlAdjustment require(String adjNo) {
        StlAdjustment a = adjustments.selectOne(new LambdaQueryWrapper<StlAdjustment>().eq(StlAdjustment::getAdjNo, adjNo).last("limit 1"));
        if (a == null) throw BizException.notFound(adjNo);
        return a;
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static String operator() {
        return SecurityUtils.currentUser().map(LoginUser::userNo).orElse("SYSTEM");
    }

    private static Adjustment vo(StlAdjustment a) {
        return new Adjustment(a.getAdjNo(), a.getPayeeType(), a.getPayeeNo(), a.getPayeeName(), a.getKind(), a.getSiteNo(),
                a.getContractNo(), a.getAmount(), a.getSuggestedAmount(), a.getCurrency(), a.getStatus(), a.getSettleNo(),
                a.getSource(), a.getNote(), a.getConfirmedBy(), a.getConfirmedAt(), a.getCreatedAt());
    }
}
