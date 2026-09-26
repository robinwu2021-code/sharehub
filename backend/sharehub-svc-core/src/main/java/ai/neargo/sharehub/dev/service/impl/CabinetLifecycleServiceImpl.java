package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.common.Checklist;
import ai.neargo.sharehub.api.core.event.CabinetWentLiveEvent;
import ai.neargo.sharehub.api.platform.dto.ContractBrief;
import ai.neargo.sharehub.api.platform.dto.SiteBrief;
import ai.neargo.sharehub.api.platform.port.ContractQueryPort;
import ai.neargo.sharehub.api.platform.port.LocationQueryPort;
import ai.neargo.sharehub.api.platform.port.SiteQueryPort;
import ai.neargo.sharehub.audit.AuditChanges;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.event.DomainEventBus;
import ai.neargo.sharehub.dev.CabinetStateMachine;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.DeviceKind;
import ai.neargo.sharehub.dev.OnlineStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.QcStatus;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.api.ops.port.WorkOrderQueryPort;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Cabinet;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.port.PricingProbe;
import ai.neargo.sharehub.dev.service.CabinetLifecycleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 机柜状态动作 + 上线门禁（TDD-运营核心流程/04 §4.1）。
 *
 * <p>骨架与合同 / 站点同形：带范围读 → 状态机求目标态 → 按<b>原状态</b>条件更新（并发只一人赢，输的 409）。
 */
@Service
public class CabinetLifecycleServiceImpl implements CabinetLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CabinetLifecycleServiceImpl.class);
    /** 在线口径：与业务告警的离线推断同一个窗口（05 §4.1）。 */
    static final int ONLINE_WINDOW_MIN = 3;
    /** 门禁「站点开着」：筹备中也可以上线（首台上线正是它转营业的触发点）。 */
    private static final Set<String> SITE_OPEN = Set.of("PREPARING", "ACTIVE", "PAUSED");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CabinetMapper cabinets;
    private final CabinetStateMachine sm;
    private final SiteQueryPort sites;
    private final ContractQueryPort contracts;
    private final LocationQueryPort locations;
    private final PricingProbe pricing;
    private final DomainEventBus events;
    private final WorkOrderQueryPort workOrders;
    private final PowerbankMapper powerbanks;
    private final SysParamPort params;

    public CabinetLifecycleServiceImpl(CabinetMapper cabinets, CabinetStateMachine sm, SiteQueryPort sites,
                                       ContractQueryPort contracts, LocationQueryPort locations, PricingProbe pricing,
                                       DomainEventBus events, WorkOrderQueryPort workOrders, PowerbankMapper powerbanks,
                                       SysParamPort params) {
        this.cabinets = cabinets;
        this.sm = sm;
        this.sites = sites;
        this.contracts = contracts;
        this.locations = locations;
        this.pricing = pricing;
        this.events = events;
        this.workOrders = workOrders;
        this.powerbanks = powerbanks;
        this.params = params;
    }

    @Override
    public Checklist goLiveGate(String cabinetNo) {
        return gate(require(cabinetNo));
    }

    private Checklist gate(DevCabinet c) {
        String no = c.getCabinetNo();
        boolean bound = c.getLocationNo() != null && DataScopeContext.executeWithoutScope(() -> locations.ownershipOf(c.getLocationNo())) != null;
        SiteBrief site = c.getSiteNo() == null ? null
                : DataScopeContext.executeWithoutScope(() -> sites.briefsByNos(List.of(c.getSiteNo()))).stream().findFirst().orElse(null);
        boolean siteOpen = site != null && SITE_OPEN.contains(site.status());
        ContractBrief contract = c.getSiteNo() == null ? null : contracts.activeBySites(List.of(c.getSiteNo())).get(c.getSiteNo());
        LocalDateTime hb = parse(c.getLastHeartbeatAt());
        boolean online = OnlineStatus.ONLINE.name().equals(c.getOnlineStatus()) && hb != null
                && !hb.isBefore(LocalDateTime.now().minusMinutes(ONLINE_WINDOW_MIN));
        boolean trial = c.getTrialPassedAt() != null && (c.getBoundAt() == null || !c.getTrialPassedAt().isBefore(c.getBoundAt()));
        String plan = pricing.planFor(c.getDeviceType() == null ? DeviceKind.POWERBANK.name() : c.getDeviceType(), no,
                c.getLocationNo(), c.getSiteNo(), c.getAgentNo(), c.getVendorCode(), c.getModel());
        String siteHref = c.getSiteNo() == null ? "/operation/sites" : "/operation/sites?no=" + c.getSiteNo();
        boolean qc = QcStatus.cleared(c.getQcStatus());
        // 勘测只卡「首台上线」：站点已在营业说明现场早验过了，存量站点不补勘测也能加柜
        boolean firstLive = site != null && "PREPARING".equals(site.status());
        boolean survey = !firstLive || Boolean.TRUE.equals(site.surveyPassed());
        boolean installed = workOrders.installDoneCabinets(List.of(no)).contains(no);
        Load load = load(c);
        return Checklist.of(List.of(
                new Checklist.Item("QC", "入库质检", qc, qc ? (c.getQcStatus() == null ? "存量设备免检" : "质检通过")
                        : QcStatus.FAILED.name().equals(c.getQcStatus()) ? "质检不通过" : "未做入库质检", "/devices/detail?no=" + no + "&tab=qc"),
                new Checklist.Item("SURVEY", "现场勘测", survey,
                        !firstLive ? "站点已营业，免勘测" : site.surveyPassed() == null ? "站点首台上线须先做现场勘测" : survey ? "勘测通过" : "最近一次勘测不通过",
                        siteHref + "?tab=survey"),
                new Checklist.Item("INSTALL_WO", "装机工单", installed, installed ? "装机工单已完工" : "没有已完工的装机工单",
                        "/work-orders?view=list&type=INSTALL&cabinetNo=" + no),
                new Checklist.Item("LOAD", "装宝比例", load.ok(), load.detail(), "/devices/detail?no=" + no + "&tab=slots"),
                new Checklist.Item("LOCATION", "绑定点位", bound, bound ? c.getLocationName() : "未绑定点位或点位已归档",
                        "/devices/detail?no=" + no + "&tab=overview"),
                new Checklist.Item("SITE_OPEN", "站点开放", siteOpen,
                        site == null ? "点位所属站点不存在" : siteOpen ? site.status() : "站点状态为 " + site.status() + "，不能上线新设备", siteHref),
                new Checklist.Item("CONTRACT", "生效合同", contract != null,
                        contract != null ? contract.contractNo() : "站点没有生效中的合同", "/venues?tab=contracts&siteNo=" + c.getSiteNo()),
                new Checklist.Item("ONLINE", "设备在线", online,
                        online ? "最近心跳 " + c.getLastHeartbeatAt() : hb == null ? "从未收到心跳" : "最近心跳 " + c.getLastHeartbeatAt() + "，已超过 "
                                + ONLINE_WINDOW_MIN + " 分钟", "/devices/detail?no=" + no),
                new Checklist.Item("TRIAL", "试借还", trial,
                        trial ? "通过于 " + c.getTrialPassedAt() : c.getTrialPassedAt() == null ? "未做试借还" : "换点位后需重做试借还",
                        "/devices/detail?no=" + no + "&tab=trial"),
                new Checklist.Item("PRICE", "计费方案", plan != null, plan != null ? plan : "该设备在此站点匹配不到生效的收费方案",
                        "/pricing")));
    }

    @Override
    @Transactional
    public Cabinet goLive(String cabinetNo) {
        DevCabinet c = require(cabinetNo);
        sm.next(c.getStatus(), "GO_LIVE");   // 先判边：非法迁移 400 优先于门禁 409
        Checklist g = gate(c);
        if (!g.allPassed()) throw ai.neargo.sharehub.common.BizException.conflict("error.device.golive_blocked", g.firstFailedKey());
        LocalDateTime now = LocalDateTime.now();
        transit(c, "GO_LIVE", u -> u.set(DevCabinet::getWentLiveAt, now).set(DevCabinet::getFaultReason, null),
                x -> x.setWentLiveAt(now));
        events.publish(new CabinetWentLiveEvent(c.getCabinetNo(), c.getSiteNo(), c.getLocationNo(), now.toString()));
        return CabinetServiceImpl.toVO(c);
    }

    /** 在柜宝占仓位的比例是否落在 [下限, 上限]：满装没地方还，太少借不到（C6）。 */
    private record Load(boolean ok, String detail) {
    }

    private Load load(DevCabinet c) {
        Integer slots = c.getSlotTotal();
        if (slots == null || slots <= 0) return new Load(false, "仓位数未知，先在设备档案里补仓位数");
        Long tracked = powerbanks.selectCount(new LambdaQueryWrapper<DevPowerbank>()
                .eq(DevPowerbank::getCabinetNo, c.getCabinetNo()).eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name()));
        // 有宝台账按台账数，没有（旧柜子）按设备上报的可借数
        long in = tracked != null && tracked > 0 ? tracked : c.getAvailableCount() == null ? 0 : c.getAvailableCount();
        java.math.BigDecimal min = params.decimalOf("device.load.min_ratio", new java.math.BigDecimal("0.5"));
        java.math.BigDecimal max = params.decimalOf("device.load.max_ratio", new java.math.BigDecimal("0.8"));
        java.math.BigDecimal ratio = java.math.BigDecimal.valueOf(in).divide(java.math.BigDecimal.valueOf(slots), 4, java.math.RoundingMode.HALF_UP);
        boolean ok = ratio.compareTo(min) >= 0 && ratio.compareTo(max) <= 0;
        String span = pct(min) + "–" + pct(max);
        return new Load(ok, in + " / " + slots + " 仓（" + pct(ratio) + "）" + (ok ? "" : "，应在 " + span + " 之间"));
    }

    private static String pct(java.math.BigDecimal r) {
        return r.multiply(java.math.BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    @Override
    @Transactional
    public void ship(String cabinetNo, String transferNo) {
        DevCabinet c = require(cabinetNo);
        if (!QcStatus.cleared(c.getQcStatus())) throw BizException.conflict("error.device.qc_not_passed", cabinetNo);
        transit(c, "SHIP", u -> { }, x -> { });
        log.info("机柜随调拨发货 cabinetNo={} transferNo={}", cabinetNo, transferNo);
    }

    @Override
    @Transactional
    public void receive(String cabinetNo, String warehouseNo) {
        DevCabinet c = require(cabinetNo);
        transit(c, "RECEIVE", u -> u.set(DevCabinet::getWarehouseNo, warehouseNo), x -> x.setWarehouseNo(warehouseNo));
    }

    @Override
    @Transactional
    public boolean goLiveIfReady(String cabinetNo, String cause) {
        DevCabinet c = DataScopeContext.executeWithoutScope(() -> require(cabinetNo));
        if (!CabinetStatus.IN_STOCK.name().equals(c.getStatus())) return false;
        Checklist g = DataScopeContext.executeWithoutScope(() -> gate(c));
        if (!g.allPassed()) {
            log.info("装机完工但上线门禁未过 cabinetNo={} firstFailed={}（{}），保持在库", cabinetNo, g.firstFailedKey(), cause);
            return false;
        }
        DataScopeContext.executeWithoutScope(() -> goLive(cabinetNo));
        log.info("装机完工自动上线 cabinetNo={}（{}）", cabinetNo, cause);
        return true;
    }

    @Override
    @Transactional
    public Cabinet markFault(String cabinetNo, String reason) {
        String r = requireReason(reason);
        DevCabinet c = require(cabinetNo);
        transit(c, "MARK_FAULT", u -> u.set(DevCabinet::getFaultReason, r), x -> x.setFaultReason(r));
        return CabinetServiceImpl.toVO(c);
    }

    @Override
    @Transactional
    public Cabinet repair(String cabinetNo) {
        DevCabinet c = require(cabinetNo);
        transit(c, "REPAIR", u -> u.set(DevCabinet::getFaultReason, null), x -> x.setFaultReason(null));
        return CabinetServiceImpl.toVO(c);
    }

    @Override
    @Transactional
    public Cabinet undeploy(String cabinetNo, String reason) {
        String r = requireReason(reason);
        DevCabinet c = require(cabinetNo);
        // 撤机回仓：解绑点位 / 站点（agent_no 是划拨归属，保留）。换点位后试借还随 bound_at 失效
        AuditChanges.record("所属点位", c.getLocationNo(), null);
        transit(c, "UNDEPLOY", u -> u.set(DevCabinet::getLocationNo, null).set(DevCabinet::getLocationName, null)
                        .set(DevCabinet::getSiteNo, null).set(DevCabinet::getFaultReason, r),
                x -> {
                    x.setLocationNo(null);
                    x.setLocationName(null);
                    x.setSiteNo(null);
                    x.setFaultReason(r);
                });
        return CabinetServiceImpl.toVO(c);
    }

    @Override
    @Transactional
    public Cabinet retire(String cabinetNo, String reason) {
        String r = requireReason(reason);
        DevCabinet c = require(cabinetNo);
        LocalDateTime now = LocalDateTime.now();
        transit(c, "RETIRE", u -> u.set(DevCabinet::getRetiredAt, now).set(DevCabinet::getFaultReason, r),
                x -> {
                    x.setRetiredAt(now);
                    x.setFaultReason(r);
                });
        return CabinetServiceImpl.toVO(c);
    }

    // —— 骨架 ——

    private void transit(DevCabinet c, String event, Consumer<LambdaUpdateWrapper<DevCabinet>> sets, Consumer<DevCabinet> local) {
        String from = c.getStatus();
        String to = sm.next(from, event);
        LambdaUpdateWrapper<DevCabinet> u = new LambdaUpdateWrapper<DevCabinet>()
                .eq(DevCabinet::getCabinetNo, c.getCabinetNo()).eq(DevCabinet::getStatus, from).set(DevCabinet::getStatus, to);
        sets.accept(u);
        if (cabinets.update(null, u) == 0) throw ai.neargo.sharehub.common.BizException.conflict("error.common.state_changed");
        AuditChanges.record("状态", from, to);
        local.accept(c);
        c.setStatus(to);
        log.info("机柜状态 cabinetNo={} {} {}→{}", c.getCabinetNo(), event, from, to);
    }

    private DevCabinet require(String cabinetNo) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        if (c == null) throw BizException.notFound(cabinetNo);
        if (c.getArchivedAt() != null) throw ai.neargo.sharehub.common.BizException.conflict("error.common.archived_readonly");
        return c;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.reason_required");
        return reason.trim().length() > 512 ? reason.trim().substring(0, 512) : reason.trim();
    }

    static LocalDateTime parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            String t = s.replace('T', ' ');
            return LocalDateTime.parse(t.length() > 19 ? t.substring(0, 19) : t, TS);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
