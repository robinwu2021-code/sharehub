package ai.neargo.sharehub.alarm.eval;

import ai.neargo.sharehub.alarm.AlarmCause;
import ai.neargo.sharehub.alarm.AlarmSubjectType;
import ai.neargo.sharehub.api.core.port.InventoryStatePort;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.alarm.ImpactScope;
import ai.neargo.sharehub.wo.RemovalDisposition;
import ai.neargo.sharehub.wo.WorkOrderStatus;
import ai.neargo.sharehub.wo.ext.WorkOrderType;
import ai.neargo.sharehub.wo.entity.WoOrder;
import ai.neargo.sharehub.wo.mapper.WoMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存流程的两条卡点（A2 / A3，docs/technical/独立进销存/D PB-C5、PB-E1）：
 * <ul>
 *   <li>{@link #INTAKE_OVERDUE}：到仓待检单到达超过 N 天（{@code inv.intake.overdue_days}，默认 7）还没处理完 → 仓管待办；</li>
 *   <li>{@link #REMOVAL_RETURN_MISSING}：撤机完工、去向回仓，超过 N 分钟（{@code inv.removal.return_grace_minutes}，默认 30）
 *       还没有回仓调拨单 → 运营待办。没建仓、监听器持续失败都会落在这里 —— 不再只是一行日志。</li>
 * </ul>
 * 处理完（待检单完成 / 回仓单生成）即不再成立，自动恢复关闭。
 */
@Component
public class InventoryEvaluator implements StateEvaluator {

    public static final String INTAKE_OVERDUE = "INTAKE_OVERDUE";
    public static final String REMOVAL_RETURN_MISSING = "REMOVAL_RETURN_MISSING";
    /** V204：仓 × 设备类型的可用量低于安全库存 → 仓管待办；补货到下限即恢复。 */
    public static final String LOW_STOCK = "LOW_STOCK";

    private final InventoryStatePort inventory;
    private final WoMapper workOrders;
    private final SysParamPort params;

    public InventoryEvaluator(InventoryStatePort inventory, WoMapper workOrders, SysParamPort params) {
        this.inventory = inventory;
        this.workOrders = workOrders;
        this.params = params;
    }

    @Override
    public Set<String> codes() {
        return Set.of(INTAKE_OVERDUE, REMOVAL_RETURN_MISSING, LOW_STOCK);
    }

    /** 按单据全局判定，不跟站点批次走。 */
    @Override
    public boolean siteBatched() {
        return false;
    }

    @Override
    public List<Finding> evaluate(EvalScope scope, LocalDateTime now) {
        List<Finding> out = new ArrayList<>();
        int days = Math.max(1, params.intOf("inv.intake.overdue_days", 7));
        for (InventoryStatePort.OpenIntake i : inventory.openIntakesArrivedBefore(now.minusDays(days), 500)) {
            long age = ChronoUnit.DAYS.between(i.arrivedAt(), now);
            out.add(new Finding(INTAKE_OVERDUE, AlarmSubjectType.INTAKE, i.intakeNo(), null, null, null, null,
                    AlarmCause.AGED, ImpactScope.ENTITY, 0,
                    List.of(new Finding.Evidence("INTAKE_OPEN", null, null, i.arrivedAt(),
                            "到达 " + age + " 天，仓 " + i.warehouseNo() + "，未处理 " + i.pending() + " 台")),
                    Map.of("warehouseNo", String.valueOf(i.warehouseNo()))));
        }

        for (InventoryStatePort.LowStock l : inventory.lowStock()) {
            String label = "CABINET".equals(l.itemType()) ? "机柜" : "充电宝";
            out.add(new Finding(LOW_STOCK, AlarmSubjectType.WAREHOUSE, l.warehouseNo() + ":" + l.itemType(), null, null, null, null,
                    AlarmCause.NO_STOCK, ImpactScope.ENTITY, 0,
                    List.of(new Finding.Evidence("BELOW_MIN", null, null, now,
                            (l.warehouseName() == null ? l.warehouseNo() : l.warehouseName()) + " " + label + "可用 " + l.available()
                                    + " 台，低于安全库存 " + l.minQty())),
                    Map.of("warehouseNo", l.warehouseNo(), "itemType", l.itemType())));
        }

        int grace = Math.max(1, params.intOf("inv.removal.return_grace_minutes", 30));
        List<WoOrder> done = workOrders.selectList(new LambdaQueryWrapper<WoOrder>().eq(WoOrder::getType, WorkOrderType.REMOVE.name())
                .in(WoOrder::getStatus, WorkOrderStatus.DONE.name(), WorkOrderStatus.CLOSED.name())
                .and(w -> w.isNull(WoOrder::getRemovalDisposition)
                        .or().ne(WoOrder::getRemovalDisposition, RemovalDisposition.RETURN_TO_POOL.name()))
                .lt(WoOrder::getUpdatedAt, now.minusMinutes(grace)).gt(WoOrder::getUpdatedAt, now.minusDays(30))
                .last("limit 500"));
        if (!done.isEmpty()) {
            Set<String> ok = inventory.removalsWithReturnTransfer(done.stream().map(WoOrder::getWoNo).toList());
            for (WoOrder w : done) {
                if (ok.contains(w.getWoNo())) continue;
                out.add(new Finding(REMOVAL_RETURN_MISSING, AlarmSubjectType.WORK_ORDER, w.getWoNo(), w.getSiteNo(),
                        w.getCabinetNo(), w.getAgentNo(), null, AlarmCause.MISSING, ImpactScope.ENTITY, 0,
                        List.of(new Finding.Evidence("NO_RETURN_TRANSFER", w.getCabinetNo(), null, w.getUpdatedAt(),
                                "撤机完工去向回仓，未生成回仓调拨单（检查是否已建仓、inv.return_warehouse）")),
                        Map.of()));
            }
        }
        return out;
    }
}
