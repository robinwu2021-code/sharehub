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
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.ProtectionAction;
import ai.neargo.sharehub.dev.ProtectionHolder;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.Protection;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.entity.DevProtection;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.ProtectionMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.service.ProtectionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 保护动作（TDD-运营核心流程/04 §4.3）。
 *
 * <p><b>幂等靠唯一键</b> {@code uk_protection_holder(cabinet, slot, action, holder_type, holder_ref, active)}：
 * 先查、没有再插，并发下撞键就重读返回 —— 应用层的判断只是快捷路径。
 * 释放置 {@code active=NULL}：NULL 不参与唯一性，同键可留多条历史。
 */
@Service
public class ProtectionServiceImpl implements ProtectionService {

    private static final Logger log = LoggerFactory.getLogger(ProtectionServiceImpl.class);
    private static final int WHOLE = -1;

    private final ProtectionMapper protections;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;

    public ProtectionServiceImpl(ProtectionMapper protections, CabinetMapper cabinets, PowerbankMapper powerbanks) {
        this.protections = protections;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
    }

    // —— 告警（DeviceProtectionPort）——

    @Override
    @Transactional
    public String apply(String cabinetNo, Integer slotIndex, String action, String alarmNo, String reason) {
        DevCabinet c = DataScopeContext.executeWithoutScope(() -> requireCabinet(cabinetNo));
        return DataScopeContext.executeWithoutScope(() ->
                doApply(c, slotIndex, ProtectionAction.of(action), ProtectionHolder.ALARM, alarmNo, reason)).getProtectionNo();
    }

    @Override
    @Transactional
    public int release(String alarmNo, String releaseReason) {
        return DataScopeContext.executeWithoutScope(() -> releaseWhere(new LambdaQueryWrapper<DevProtection>()
                .eq(DevProtection::getHolderType, ProtectionHolder.ALARM.name()).eq(DevProtection::getHolderRef, alarmNo)
                .eq(DevProtection::getActive, 1), releaseReason));
    }

    @Override
    @Transactional
    public int releaseSignal(String cabinetNo, Integer slotIndex, String signalCode, String reason) {
        return releaseBySignal(signalCode, cabinetNo, slotIndex, reason);
    }

    @Override
    public List<SignalLock> activeSignalLocks(int limit) {
        return DataScopeContext.executeWithoutScope(() -> protections.selectList(new LambdaQueryWrapper<DevProtection>()
                        .eq(DevProtection::getHolderType, ProtectionHolder.SIGNAL.name())
                        .eq(DevProtection::getAction, ProtectionAction.SLOT_LOCK.name()).eq(DevProtection::getActive, 1)
                        .orderByAsc(DevProtection::getId).last("limit " + Math.max(1, Math.min(limit, 1000)))))
                .stream().map(p -> new SignalLock(p.getCabinetNo(), p.getSlotIndex() == WHOLE ? null : p.getSlotIndex(),
                        p.getHolderRef(), p.getSiteNo(), p.getAgentNo()))
                .toList();
    }

    // —— 人工 ——

    @Override
    @Transactional
    public Protection applyManual(String cabinetNo, Integer slotIndex, String action, String reason) {
        if (reason == null || reason.isBlank()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.reason_required");
        DevCabinet c = requireCabinet(cabinetNo);   // 带范围读：看不到的柜子不能操作
        return toVO(doApply(c, slotIndex, ProtectionAction.of(action), ProtectionHolder.MANUAL, operator(), reason.trim()));
    }

    @Override
    @Transactional
    public Protection releaseManual(String protectionNo, String reason) {
        if (reason == null || reason.isBlank()) throw ai.neargo.sharehub.common.BizException.badRequest("error.common.reason_required");
        DevProtection p = protections.selectOne(new LambdaQueryWrapper<DevProtection>()
                .eq(DevProtection::getProtectionNo, protectionNo).last("limit 1"));
        if (p == null) throw BizException.notFound(protectionNo);
        if (!ProtectionHolder.MANUAL.name().equals(p.getHolderType())) {
            throw ai.neargo.sharehub.common.BizException.conflict("error.protection.not_manual", p.getHolderType());
        }
        if (p.getActive() == null) throw ai.neargo.sharehub.common.BizException.conflict("error.protection.already_released");
        releaseWhere(new LambdaQueryWrapper<DevProtection>().eq(DevProtection::getId, p.getId()).eq(DevProtection::getActive, 1),
                reason.trim());
        return toVO(protections.selectById(p.getId()));
    }

    @Override
    public List<Protection> list(String cabinetNo, boolean activeOnly) {
        requireCabinet(cabinetNo);
        return protections.selectList(new LambdaQueryWrapper<DevProtection>().eq(DevProtection::getCabinetNo, cabinetNo)
                        .eq(activeOnly, DevProtection::getActive, 1).orderByDesc(DevProtection::getId).last("limit 200"))
                .stream().map(ProtectionServiceImpl::toVO).toList();
    }

    // —— 信号 ——

    @Override
    @Transactional
    public void applyBySignal(DeviceSignalEvent s, String action) {
        ProtectionAction a = ProtectionAction.of(action);
        DevCabinet c = DataScopeContext.executeWithoutScope(() -> requireCabinet(s.cabinetNo()));
        Integer slot = a.wholeCabinet() ? null : s.slotIndex();
        DataScopeContext.executeWithoutScope(() -> doApply(c, slot, a, ProtectionHolder.SIGNAL,
                signalRef(s.code(), s.cabinetNo(), slot), "设备信号 " + s.code()));
    }

    @Override
    @Transactional
    public int releaseBySignal(String clearedCode, String cabinetNo, Integer slotIndex, String reason) {
        // 整柜信号的持有键里仓位是 -1；仓位信号按仓位精确匹配
        return DataScopeContext.executeWithoutScope(() -> releaseWhere(new LambdaQueryWrapper<DevProtection>()
                .eq(DevProtection::getHolderType, ProtectionHolder.SIGNAL.name())
                .in(DevProtection::getHolderRef, List.of(signalRef(clearedCode, cabinetNo, slotIndex), signalRef(clearedCode, cabinetNo, null)))
                .eq(DevProtection::getActive, 1), reason));
    }

    // —— 骨架 ——

    private DevProtection doApply(DevCabinet c, Integer slotIndex, ProtectionAction action, ProtectionHolder holder,
                                  String holderRef, String reason) {
        int slot = action.wholeCabinet() || slotIndex == null ? WHOLE : slotIndex;
        if (!action.wholeCabinet() && slot == WHOLE) throw ai.neargo.sharehub.common.BizException.badRequest("error.protection.slot_required", action.name());
        if (slot != WHOLE && c.getSlotTotal() != null && (slot < 1 || slot > c.getSlotTotal())) {
            throw ai.neargo.sharehub.common.BizException.badRequest("error.protection.slot_out_of_range", slot, c.getSlotTotal());
        }
        DevProtection existing = activeOf(c.getCabinetNo(), slot, action, holder, holderRef);
        if (existing != null) return existing;
        DevProtection p = new DevProtection();
        p.setProtectionNo(IdGenerator.next(BizKey.PROTECTION));
        p.setTenantId("MAIN");
        p.setCabinetNo(c.getCabinetNo());
        p.setSlotIndex(slot);
        p.setAction(action.name());
        p.setHolderType(holder.name());
        p.setHolderRef(holderRef);
        p.setReason(reason == null ? action.name() : truncate(reason, 256));
        p.setActive(1);
        p.setSiteNo(c.getSiteNo());
        p.setAgentNo(c.getAgentNo());
        try {
            protections.insert(p);
        } catch (DuplicateKeyException race) {
            // 并发下另一方先插入了同一持有者的同一动作：以它为准
            return activeOf(c.getCabinetNo(), slot, action, holder, holderRef);
        }
        log.info("保护动作生效 cabinetNo={} slot={} action={} holder={}:{}", c.getCabinetNo(), slot, action, holder, holderRef);
        refreshAvailable(c.getCabinetNo());
        return p;
    }

    private DevProtection activeOf(String cabinetNo, int slot, ProtectionAction action, ProtectionHolder holder, String ref) {
        return protections.selectOne(new LambdaQueryWrapper<DevProtection>().eq(DevProtection::getCabinetNo, cabinetNo)
                .eq(DevProtection::getSlotIndex, slot).eq(DevProtection::getAction, action.name())
                .eq(DevProtection::getHolderType, holder.name()).eq(DevProtection::getHolderRef, ref)
                .eq(DevProtection::getActive, 1).last("limit 1"));
    }

    private int releaseWhere(LambdaQueryWrapper<DevProtection> where, String reason) {
        List<DevProtection> rows = protections.selectList(where);
        Set<String> touched = new HashSet<>();
        int n = 0;
        for (DevProtection p : rows) {
            int u = protections.update(null, new LambdaUpdateWrapper<DevProtection>().eq(DevProtection::getId, p.getId())
                    .eq(DevProtection::getActive, 1).set(DevProtection::getActive, null)
                    .set(DevProtection::getReleasedAt, LocalDateTime.now())
                    .set(DevProtection::getReleaseReason, reason == null ? null : truncate(reason, 256)));
            if (u > 0) {
                n++;
                touched.add(p.getCabinetNo());
                log.info("保护动作解除 protectionNo={} cabinetNo={} action={}", p.getProtectionNo(), p.getCabinetNo(), p.getAction());
            }
        }
        touched.forEach(this::refreshAvailable);
        return n;
    }

    /**
     * 回写可借数：在柜宝数减去被禁用 / 锁定仓位上的宝。<b>整柜停借不计入</b>（借出校验单独查它）——
     * 若把它算成 0，离线告警持有的停借会让柜子在心跳恢复后仍显示「无宝」，告警永远不恢复。
     * 设备心跳上报的 available_count 会在下一次心跳覆盖 —— 这里保证保护生效的那一刻列表就对。
     */
    private void refreshAvailable(String cabinetNo) {
        DataScopeContext.executeWithoutScope(() -> {
            List<DevProtection> active = protections.selectList(new LambdaQueryWrapper<DevProtection>()
                    .eq(DevProtection::getCabinetNo, cabinetNo).eq(DevProtection::getActive, 1));
            Set<Integer> blockedSlots = new HashSet<>();
            for (DevProtection p : active) {
                if (ProtectionAction.SLOT_DISABLE.name().equals(p.getAction()) || ProtectionAction.SLOT_LOCK.name().equals(p.getAction())) {
                    blockedSlots.add(p.getSlotIndex());
                }
            }
            List<DevPowerbank> inCabinet = powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .select(DevPowerbank::getSlotIndex).eq(DevPowerbank::getCabinetNo, cabinetNo)
                    .eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name()).isNull(DevPowerbank::getArchivedAt));
            // 没有宝台账（仓位级上报未接入的柜）就不覆盖设备上报的可借数 —— 否则一次保护动作会把它清成 0
            if (inCabinet.isEmpty()) return null;
            long rentable = inCabinet.stream().filter(b -> b.getSlotIndex() == null || !blockedSlots.contains(b.getSlotIndex())).count();
            cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo)
                    .set(DevCabinet::getAvailableCount, (int) rentable));
            return null;
        });
    }

    private DevCabinet requireCabinet(String cabinetNo) {
        DevCabinet c = cabinets.selectOne(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo).last("limit 1"));
        if (c == null) throw BizException.notFound(cabinetNo);
        return c;
    }

    static String signalRef(String code, String cabinetNo, Integer slot) {
        return code + ":" + cabinetNo + ":" + (slot == null ? WHOLE : slot);
    }

    private static Protection toVO(DevProtection p) {
        return new Protection(p.getProtectionNo(), p.getCabinetNo(), p.getSlotIndex() == null || p.getSlotIndex() == WHOLE ? null : p.getSlotIndex(),
                p.getAction(), p.getHolderType(), p.getHolderRef(), p.getReason(), Integer.valueOf(1).equals(p.getActive()),
                p.getCreatedAt(), p.getReleasedAt(), p.getReleaseReason());
    }

    private static String operator() {
        String u = SecurityUtils.currentUser().map(LoginUser::userNo).orElse(null);
        return u == null ? "SYSTEM" : u;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
