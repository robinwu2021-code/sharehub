package ai.neargo.sharehub.dev.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.dev.ProtectionAction;
import ai.neargo.sharehub.dev.dto.DevLegacyDtos.Slot;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.entity.DevProtection;
import ai.neargo.sharehub.dev.mapper.DeviceOpsMappers.ProtectionMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 机柜仓位图 —— 从 {@code dev_powerbank} + {@code dev_protection} 读真实占位。
 *
 * <h3>此前这张图是算出来的，不是读出来的</h3>
 * 原来的 {@code CabinetServiceImpl.slotsOf} 只拿 {@code slotTotal} 与 {@code availableCount}
 * 两个数字凭空拼：
 * <pre>
 *   filled      = i &lt; availableCount           // 永远是前 N 个仓有宝
 *   powerbankNo = "PB" + cabinetNo.substring(3) + (i+1)   // 编出来的号，dev_powerbank 里没这个宝
 *   battery     = 40 + (i * 13) % 60           // **电量是个公式**
 *   lockStatus  = filled ? LOCKED : UNLOCKED   // 完全不看 dev_protection 的锁仓
 *   health      = 只有机柜 FAULT 时把最后一仓标 FAULT
 * </pre>
 * 那个公式产出 40/53/66/79/92/45… 看着特别像真的电量。
 * 运维照着这张图排障，看到的宝号库里不存在、电量是算出来的、锁着的仓显示没锁 ——
 * <b>页面不报错，只是每一格都不对</b>。
 *
 * <p>真实数据一直都在：{@code dev_powerbank} 带 {@code cabinet_no / slot_index / battery /
 * health / status}，{@code dev_protection} 带按仓的 {@code SLOT_LOCK / SLOT_DISABLE}
 * （{@code PowerbankTracker.blockedSlots} 挑宝时早就在读了）。
 */
@Component
public class SlotAssembler {

    private final PowerbankMapper powerbanks;
    private final ProtectionMapper protections;

    public SlotAssembler(PowerbankMapper powerbanks, ProtectionMapper protections) {
        this.powerbanks = powerbanks;
        this.protections = protections;
    }

    /**
     * 拼一个机柜的仓位图，下标 1..slotTotal。
     *
     * <p>豁免数据范围：这是服务端按机柜派生，不是用户在查设备台账 ——
     * {@code dev_powerbank} 的维度没登记，handler fail-closed 会把查询拼成 {@code 1=0}，
     * 于是<b>每个仓都显示空</b>（和「这柜子真没宝」一模一样）。
     */
    public List<Slot> of(DevCabinet c) {
        int total = c.getSlotTotal() == null ? 0 : c.getSlotTotal();
        if (c.getCabinetNo() == null || total <= 0) return List.of();

        Map<Integer, DevPowerbank> bySlot = new HashMap<>();
        Set<Integer> locked = new HashSet<>();
        DataScopeContext.executeWithoutScope(() -> {
            for (DevPowerbank b : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .eq(DevPowerbank::getCabinetNo, c.getCabinetNo())
                    .isNull(DevPowerbank::getArchivedAt))) {
                // 没有仓位号的宝挂在柜上但不知道在哪一格，宁可不画也不硬塞进某一格
                if (b.getSlotIndex() != null) bySlot.putIfAbsent(b.getSlotIndex(), b);
            }
            for (DevProtection p : protections.selectList(new LambdaQueryWrapper<DevProtection>()
                    .eq(DevProtection::getCabinetNo, c.getCabinetNo())
                    .eq(DevProtection::getActive, 1)
                    .in(DevProtection::getAction, ProtectionAction.SLOT_DISABLE.name(),
                            ProtectionAction.SLOT_LOCK.name()))) {
                if (p.getSlotIndex() != null) locked.add(p.getSlotIndex());
            }
            return null;
        });

        List<Slot> slots = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            DevPowerbank b = bySlot.get(i);
            // 锁状态答的是「这一格能不能出宝」：被保护锁住 → LOCKED，否则空仓 UNLOCKED、
            // 有宝待借也是 UNLOCKED（宝在里面不等于仓锁着，原实现把这两件事混成一件）
            String lockStatus = locked.contains(i) ? "LOCKED" : "UNLOCKED";
            slots.add(new Slot(i, b == null ? null : b.getPowerbankNo(),
                    b == null ? null : b.getBattery(), lockStatus,
                    b == null ? null : b.getHealth()));
        }
        return slots;
    }
}
