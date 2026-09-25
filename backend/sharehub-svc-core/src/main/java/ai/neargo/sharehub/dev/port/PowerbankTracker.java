package ai.neargo.sharehub.dev.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.dev.PowerbankStateMachine;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.ProtectionAction;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.entity.DevProtection;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.ProtectionMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 借还驱动充电宝状态（TDD-运营核心流程 · 执行清单 A2）。交易域调用，豁免数据范围。
 *
 * <p>此前借出时宝号是 {@code IdGenerator} 现造的，与 {@code dev_powerbank} 毫无关系 —— 宝的状态机有、却从没被借还推动过，
 * 于是「宝在哪、在谁手里」这条资产线是断的。现在：
 * <ul>
 *   <li>借出：从柜内在库的宝里挑电量最高、电量达标、所在仓未被禁用 / 锁定的一颗 → RENTED；</li>
 *   <li>归还 / 仓位识别到宝：→ IN_CABINET，落到归还柜与仓位（丢失的宝回来 → RECOVER）；</li>
 *   <li>买断：→ SOLD（从资产中移除）。</li>
 * </ul>
 * <b>柜子没有宝台账时退回旧行为</b>（仓位级上报未接入的柜，借出仍能进行）—— 用 {@link Pick#tracked()} 区分
 * 「这柜没有台账」与「有台账但一颗能借的都没有」，后者应当拒借。
 *
 * <p>所有迁移按原状态条件更新；宝不在预期状态（已被别的路径推走 / 宝号不在台账）时静默跳过 —— 这是附带的资产回写，
 * 不能因为它让借还本身失败。
 */
@Component
public class PowerbankTracker {

    private static final Logger log = LoggerFactory.getLogger(PowerbankTracker.class);

    /** @param tracked false = 该柜没有宝台账（按旧行为放行）；true 且 powerbankNo 为空 = 有台账但无可借宝 */
    public record Pick(boolean tracked, String powerbankNo, Integer slotIndex) {
    }

    private final PowerbankMapper powerbanks;
    private final CabinetMapper cabinets;
    private final ProtectionMapper protections;
    private final PowerbankStateMachine sm;
    private final int minBattery;

    public PowerbankTracker(PowerbankMapper powerbanks, CabinetMapper cabinets, ProtectionMapper protections,
                            PowerbankStateMachine sm, @Value("${sharehub.rent.min-battery:60}") int minBattery) {
        this.powerbanks = powerbanks;
        this.cabinets = cabinets;
        this.protections = protections;
        this.sm = sm;
        this.minBattery = minBattery;
    }

    public Pick pickForRent(String cabinetNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            List<DevPowerbank> inCabinet = powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .eq(DevPowerbank::getCabinetNo, cabinetNo).eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name())
                    .isNull(DevPowerbank::getArchivedAt));
            if (inCabinet.isEmpty()) {
                long any = powerbanks.selectCount(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getCabinetNo, cabinetNo));
                return new Pick(any > 0, null, null);
            }
            Set<Integer> blocked = blockedSlots(cabinetNo);
            return inCabinet.stream()
                    .filter(b -> b.getSlotIndex() == null || !blocked.contains(b.getSlotIndex()))
                    .filter(b -> b.getBattery() == null || b.getBattery() >= minBattery)
                    .filter(b -> !ai.neargo.sharehub.dev.PowerbankHealth.AGED.name().equals(b.getHealth()))   // 老化待报废的宝不再借出（批次 D2）
                    .max(Comparator.comparing((DevPowerbank b) -> b.getBattery() == null ? -1 : b.getBattery()))
                    .map(b -> new Pick(true, b.getPowerbankNo(), b.getSlotIndex()))
                    .orElse(new Pick(true, null, null));
        });
    }

    /** 借出：IN_CABINET → RENTED，离开机柜。 */
    public void rented(String powerbankNo, String orderNo) {
        DevPowerbank b = find(powerbankNo);
        if (b == null) return;
        String from = b.getStatus();
        if (move(b, "RENT", u -> u.set(DevPowerbank::getCabinetNo, null).set(DevPowerbank::getSlotIndex, null))) {
            recount(b.getCabinetNo());
            log.info("宝借出 powerbankNo={} orderNo={} {}→RENTED", powerbankNo, orderNo, from);
        }
    }

    /** 归还 / 仓位识别到宝：RENTED → IN_CABINET；LOST → IN_CABINET（失而复得）。 */
    public void returned(String powerbankNo, String cabinetNo, Integer slotIndex) {
        DevPowerbank b = find(powerbankNo);
        if (b == null) return;
        String event = PowerbankStatus.LOST.name().equals(b.getStatus()) ? "RECOVER" : "RETURN";
        if (move(b, event, u -> u.set(DevPowerbank::getCabinetNo, cabinetNo).set(DevPowerbank::getSlotIndex, slotIndex))) {
            recount(cabinetNo);
            log.info("宝归还 powerbankNo={} cabinetNo={} slot={}", powerbankNo, cabinetNo, slotIndex);
        }
    }

    /** 买断：RENTED / LOST → SOLD，从资产中移除。 */
    public void sold(String powerbankNo) {
        DevPowerbank b = find(powerbankNo);
        if (b == null) return;
        if (move(b, "BUYOUT", u -> u.set(DevPowerbank::getCabinetNo, null).set(DevPowerbank::getSlotIndex, null))) {
            log.info("宝买断 powerbankNo={} → SOLD", powerbankNo);
        }
    }

    private boolean move(DevPowerbank b, String event, java.util.function.Consumer<LambdaUpdateWrapper<DevPowerbank>> sets) {
        String to;
        try {
            to = sm.next(b.getStatus(), event);
        } catch (IllegalArgumentException notApplicable) {
            return false;   // 已被别的路径推走（重复回执 / 人工处理过）
        }
        LambdaUpdateWrapper<DevPowerbank> u = new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getId, b.getId())
                .eq(DevPowerbank::getStatus, b.getStatus()).set(DevPowerbank::getStatus, to);
        sets.accept(u);
        return DataScopeContext.executeWithoutScope(() -> powerbanks.update(null, u)) > 0;
    }

    private DevPowerbank find(String powerbankNo) {
        if (powerbankNo == null) return null;
        return DataScopeContext.executeWithoutScope(() -> powerbanks.selectOne(new LambdaQueryWrapper<DevPowerbank>()
                .eq(DevPowerbank::getPowerbankNo, powerbankNo).last("limit 1")));
    }

    private Set<Integer> blockedSlots(String cabinetNo) {
        return protections.selectList(new LambdaQueryWrapper<DevProtection>().eq(DevProtection::getCabinetNo, cabinetNo)
                        .eq(DevProtection::getActive, 1)
                        .in(DevProtection::getAction, ProtectionAction.SLOT_DISABLE.name(), ProtectionAction.SLOT_LOCK.name()))
                .stream().map(DevProtection::getSlotIndex).collect(Collectors.toSet());
    }

    /**
     * 老化标记（批次 D2，定时任务调）：循环次数超过上限、仍在流转的宝标 AGED —— 从此不再被挑去借出，
     * 由 POWERBANK_AGED 告警开换宝回收单。受影响柜子的可借数随之回写。返回标记颗数。
     */
    public int markAged(int maxCycles) {
        return DataScopeContext.executeWithoutScope(() -> {
            List<DevPowerbank> due = powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .eq(DevPowerbank::getHealth, ai.neargo.sharehub.dev.PowerbankHealth.OK.name()).gt(DevPowerbank::getCycles, maxCycles)
                    .in(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name(), PowerbankStatus.IN_CABINET.name(), PowerbankStatus.RENTED.name())
                    .isNull(DevPowerbank::getArchivedAt).last("limit 1000"));
            int n = 0;
            Set<String> touched = new java.util.HashSet<>();
            for (DevPowerbank b : due) {
                n += powerbanks.update(null, new LambdaUpdateWrapper<DevPowerbank>().eq(DevPowerbank::getId, b.getId())
                        .eq(DevPowerbank::getHealth, ai.neargo.sharehub.dev.PowerbankHealth.OK.name())
                        .set(DevPowerbank::getHealth, ai.neargo.sharehub.dev.PowerbankHealth.AGED.name()));
                if (b.getCabinetNo() != null) touched.add(b.getCabinetNo());
            }
            touched.forEach(this::recount);
            return n;
        });
    }

    /** 回写可借数（与保护动作同一口径：在柜、电量达标、所在仓未被禁用）。柜子没有宝台账时不覆盖设备上报值。 */
    private void recount(String cabinetNo) {
        if (cabinetNo == null) return;
        DataScopeContext.executeWithoutScope(() -> {
            List<DevPowerbank> in = powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .eq(DevPowerbank::getCabinetNo, cabinetNo).eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name())
                    .isNull(DevPowerbank::getArchivedAt));
            Set<Integer> blocked = blockedSlots(cabinetNo);
            long n = in.stream().filter(b -> b.getSlotIndex() == null || !blocked.contains(b.getSlotIndex()))
                    .filter(b -> b.getBattery() == null || b.getBattery() >= minBattery)
                    .filter(b -> !ai.neargo.sharehub.dev.PowerbankHealth.AGED.name().equals(b.getHealth()))   // 老化的不算可借
                    .count();
            cabinets.update(null, new LambdaUpdateWrapper<DevCabinet>().eq(DevCabinet::getCabinetNo, cabinetNo)
                    .set(DevCabinet::getAvailableCount, (int) n));
            return null;
        });
    }
}
