package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.core.ErrorCode;
import ai.neargo.common.core.IdGenerator;
import ai.neargo.common.core.ServerException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.event.DeviceSignalEvent;
import ai.neargo.sharehub.auth.LoginUser;
import ai.neargo.sharehub.auth.SecurityUtils;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.common.BizKey;
import ai.neargo.sharehub.dev.OnlineStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.ProtectionAction;
import ai.neargo.sharehub.dev.TrialRentStateMachine;
import ai.neargo.sharehub.dev.TrialRentStatus;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.TrialRent;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.entity.DevProtection;
import ai.neargo.sharehub.dev.entity.DevTrialRent;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.ProtectionMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.TrialRentMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.service.CabinetService;
import ai.neargo.sharehub.dev.service.TrialRentService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 试借还（TDD-运营核心流程/04 §4.2）。指令异步：下发后不等结果，回执以设备信号回推。
 *
 * <p>信号匹配：{@code COMMAND_RESULT}（attrs.commandNo + attrs.result=OK/FAIL）或 {@code SLOT_EJECT_OK}
 * （同仓）→ 已弹出；{@code EJECT_TIMEOUT}（同仓）→ 失败；{@code RETURN_SN_SEEN}（同一块宝）→ 通过。
 */
@Service
public class TrialRentServiceImpl implements TrialRentService {

    private static final Logger log = LoggerFactory.getLogger(TrialRentServiceImpl.class);
    static final int EXPIRE_MIN = 15;
    private static final List<String> IN_PROGRESS = List.of(TrialRentStatus.EJECTING.name(), TrialRentStatus.WAIT_RETURN.name());

    private final TrialRentMapper trials;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final ProtectionMapper protections;
    private final TrialRentStateMachine sm;
    private final CabinetService commands;

    public TrialRentServiceImpl(TrialRentMapper trials, CabinetMapper cabinets, PowerbankMapper powerbanks,
                                ProtectionMapper protections, TrialRentStateMachine sm, CabinetService commands) {
        this.trials = trials;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.protections = protections;
        this.sm = sm;
        this.commands = commands;
    }

    @Override
    @Transactional
    public TrialRent start(String cabinetNo) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        if (c == null) throw BizException.notFound(cabinetNo);
        if (!OnlineStatus.ONLINE.name().equals(c.getOnlineStatus())) throw ai.neargo.sharehub.common.BizException.conflict("error.trial.offline");
        Long running = trials.selectCount(new LambdaQueryWrapper<DevTrialRent>().eq(DevTrialRent::getCabinetNo, cabinetNo)
                .in(DevTrialRent::getStatus, IN_PROGRESS));
        if (running != null && running > 0) throw ai.neargo.sharehub.common.BizException.conflict("error.trial.in_progress");
        Set<Integer> blocked = DataScopeContext.executeWithoutScope(() -> protections.selectList(new LambdaQueryWrapper<DevProtection>()
                        .eq(DevProtection::getCabinetNo, cabinetNo).eq(DevProtection::getActive, 1)
                        .in(DevProtection::getAction, ProtectionAction.SLOT_DISABLE.name(), ProtectionAction.SLOT_LOCK.name())))
                .stream().map(DevProtection::getSlotIndex).collect(Collectors.toSet());
        DevPowerbank pick = DataScopeContext.executeWithoutScope(() -> powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                        .eq(DevPowerbank::getCabinetNo, cabinetNo).eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name())
                        .isNotNull(DevPowerbank::getSlotIndex).isNull(DevPowerbank::getArchivedAt)))
                .stream().filter(b -> !blocked.contains(b.getSlotIndex()))
                .max(Comparator.comparing((DevPowerbank b) -> b.getBattery() == null ? -1 : b.getBattery()))
                .orElseThrow(() -> ai.neargo.sharehub.common.BizException.conflict("error.trial.no_powerbank"));

        DevTrialRent t = new DevTrialRent();
        t.setTrialNo(IdGenerator.next(BizKey.TRIAL_RENT));
        t.setTenantId("MAIN");
        t.setCabinetNo(cabinetNo);
        t.setSlotIndex(pick.getSlotIndex());
        t.setPowerbankNo(pick.getPowerbankNo());
        t.setStatus(TrialRentStatus.EJECTING.name());
        t.setOperator(operator());
        t.setSiteNo(c.getSiteNo());
        t.setAgentNo(c.getAgentNo());
        // 指令幂等键 = 试借还号：同一次试借还重发不会弹两块宝
        String cmd = commands.sendCommand(cabinetNo, "EJECT", Map.of("slotIndex", pick.getSlotIndex(),
                "commandId", t.getTrialNo() + ":EJECT", "bizType", "TRIAL_RENT", "bizRef", t.getTrialNo())).commandId();
        t.setEjectCommandNo(cmd);
        trials.insert(t);
        log.info("试借还发起 trialNo={} cabinetNo={} slot={} command={}", t.getTrialNo(), cabinetNo, pick.getSlotIndex(), cmd);
        return toVO(t);
    }

    @Override
    public List<TrialRent> list(String cabinetNo) {
        return trials.selectList(new LambdaQueryWrapper<DevTrialRent>().eq(DevTrialRent::getCabinetNo, cabinetNo)
                .orderByDesc(DevTrialRent::getId).last("limit 50")).stream().map(TrialRentServiceImpl::toVO).toList();
    }

    @Override
    public void onSignal(DeviceSignalEvent s) {
        String code = s.code();
        if (!Set.of("COMMAND_RESULT", "SLOT_EJECT_OK", "EJECT_TIMEOUT", "RETURN_SN_SEEN").contains(code)) return;
        DevTrialRent t = DataScopeContext.executeWithoutScope(() -> trials.selectOne(new LambdaQueryWrapper<DevTrialRent>()
                .eq(DevTrialRent::getCabinetNo, s.cabinetNo()).in(DevTrialRent::getStatus, IN_PROGRESS)
                .orderByDesc(DevTrialRent::getId).last("limit 1")));
        if (t == null) return;
        LocalDateTime at = s.occurredAt() == null ? LocalDateTime.now() : s.occurredAtTime();
        boolean sameSlot = Objects.equals(s.slotIndex(), t.getSlotIndex());
        switch (code) {
            case "COMMAND_RESULT" -> {
                if (!Objects.equals(s.attr("commandNo"), t.getEjectCommandNo()) && !(t.getTrialNo() + ":EJECT").equals(s.attr("commandNo"))) return;
                if ("OK".equalsIgnoreCase(s.attr("result"))) {
                    move(t, "EJECTED", u -> u.set(DevTrialRent::getEjectedAt, at));
                } else {
                    move(t, "FAIL", u -> u.set(DevTrialRent::getFailReason, "弹出失败：" + Objects.toString(s.attr("error"), "设备回执失败")));
                }
            }
            case "SLOT_EJECT_OK" -> {
                if (sameSlot) move(t, "EJECTED", u -> u.set(DevTrialRent::getEjectedAt, at));
            }
            case "EJECT_TIMEOUT" -> {
                if (sameSlot) move(t, "FAIL", u -> u.set(DevTrialRent::getFailReason, "弹出超时"));
            }
            case "RETURN_SN_SEEN" -> {
                if (!TrialRentStatus.WAIT_RETURN.name().equals(t.getStatus())) return;
                if (t.getPowerbankNo() != null && s.powerbankNo() != null && !t.getPowerbankNo().equals(s.powerbankNo())) return;
                if (move(t, "RETURNED", u -> u.set(DevTrialRent::getReturnedAt, at))) {
                    DataScopeContext.executeWithoutScope(() -> cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>()
                            .eq(DevCabinet::getCabinetNo, t.getCabinetNo()).set(DevCabinet::getTrialPassedAt, at)));
                    log.info("试借还通过 trialNo={} cabinetNo={}", t.getTrialNo(), t.getCabinetNo());
                }
            }
            default -> {
            }
        }
    }

    @Override
    @Transactional
    public int expireStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(EXPIRE_MIN);
        List<DevTrialRent> stale = DataScopeContext.executeWithoutScope(() -> trials.selectList(new LambdaQueryWrapper<DevTrialRent>()
                .in(DevTrialRent::getStatus, IN_PROGRESS).lt(DevTrialRent::getCreatedAt, cutoff).last("limit 500")));
        return (int) stale.stream().filter(this::expire).count();
    }

    private boolean expire(DevTrialRent t) {
        return move(t, "EXPIRE", u -> u.set(DevTrialRent::getFailReason, "超过 " + EXPIRE_MIN + " 分钟未完成"));
    }

    /** 条件迁移：已被别的信号推走则跳过（信号可能乱序 / 重复）。 */
    private boolean move(DevTrialRent t, String event, Consumer<LambdaUpdateWrapper<DevTrialRent>> sets) {
        String to;
        try {
            to = sm.next(t.getStatus(), event);
        } catch (ServerException | IllegalArgumentException stale) {
            return false;
        }
        LambdaUpdateWrapper<DevTrialRent> u = new LambdaUpdateWrapper<DevTrialRent>().eq(DevTrialRent::getId, t.getId())
                .eq(DevTrialRent::getStatus, t.getStatus()).set(DevTrialRent::getStatus, to);
        sets.accept(u);
        boolean ok = DataScopeContext.executeWithoutScope(() -> trials.update(null, u)) > 0;
        if (ok) t.setStatus(to);
        return ok;
    }

    private static TrialRent toVO(DevTrialRent t) {
        return new TrialRent(t.getTrialNo(), t.getCabinetNo(), t.getSlotIndex(), t.getPowerbankNo(), t.getStatus(),
                t.getEjectedAt(), t.getReturnedAt(), t.getFailReason(), t.getOperator(), t.getCreatedAt());
    }

    private static String operator() {
        String u = SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null);
        return u == null ? "SYSTEM" : u;
    }
}
