package ai.neargo.sharehub.inv.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.api.core.port.InventoryStatePort;
import ai.neargo.sharehub.inv.IntakeStatus;
import ai.neargo.sharehub.inv.TransferSource;
import ai.neargo.sharehub.inv.entity.InvIntake;
import ai.neargo.sharehub.inv.entity.InvIntakeItem;
import ai.neargo.sharehub.inv.entity.InvTransfer;
import ai.neargo.sharehub.inv.mapper.InvIntakeItemMapper;
import ai.neargo.sharehub.inv.mapper.InvIntakeMapper;
import ai.neargo.sharehub.inv.mapper.InvTransferMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link InventoryStatePort} 的本地实现：直接读待检单与调拨单（豁免数据范围）。 */
@Component
public class LocalInventoryState implements InventoryStatePort {

    private final InvIntakeMapper intakes;
    private final InvIntakeItemMapper items;
    private final InvTransferMapper transfers;

    private final ai.neargo.sharehub.inv.service.StockThresholdService thresholds;
    private ai.neargo.sharehub.inv.service.MaterialService materials;

    /** 物料低库存（V210）：setter 注入 —— MaterialService 依赖远程端口，构造期注入会成环。 */
    @org.springframework.beans.factory.annotation.Autowired
    void setMaterials(@org.springframework.context.annotation.Lazy ai.neargo.sharehub.inv.service.MaterialService materials) {
        this.materials = materials;
    }

    public LocalInventoryState(InvIntakeMapper intakes, InvIntakeItemMapper items, InvTransferMapper transfers,
                               ai.neargo.sharehub.inv.service.StockThresholdService thresholds) {
        this.thresholds = thresholds;
        this.intakes = intakes;
        this.items = items;
        this.transfers = transfers;
    }

    @Override
    public List<OpenIntake> openIntakesArrivedBefore(LocalDateTime before, int limit) {
        return DataScopeContext.executeWithoutScope(() -> {
            List<InvIntake> heads = intakes.selectList(new LambdaQueryWrapper<InvIntake>()
                    .eq(InvIntake::getStatus, IntakeStatus.OPEN.name()).lt(InvIntake::getArrivedAt, before)
                    .orderByAsc(InvIntake::getArrivedAt).last("limit " + Math.max(1, Math.min(limit, 1000))));
            if (heads.isEmpty()) return List.<OpenIntake>of();
            Map<String, Long> pending = items.selectList(new LambdaQueryWrapper<InvIntakeItem>()
                            .in(InvIntakeItem::getIntakeNo, heads.stream().map(InvIntake::getIntakeNo).toList())
                            .eq(InvIntakeItem::getDisposition, "PENDING"))
                    .stream().collect(Collectors.groupingBy(InvIntakeItem::getIntakeNo, Collectors.counting()));
            return heads.stream().map(h -> new OpenIntake(h.getIntakeNo(), h.getKind(), h.getWarehouseNo(), h.getSourceRef(),
                    h.getArrivedAt(), pending.getOrDefault(h.getIntakeNo(), 0L).intValue())).toList();
        });
    }

    @Override
    public List<LowStock> lowStock() {
        List<LowStock> out = new java.util.ArrayList<>(thresholds.lowRows().stream()
                .map(t -> new LowStock(t.warehouseNo(), t.warehouseName(), t.itemType(), "CABINET".equals(t.itemType()) ? "机柜" : "充电宝",
                        t.minQty(), t.available())).toList());
        // 物料（V210）：账在库存服务，只在 SERVICE 引擎下判定；服务不可用时 lowRows 返回空，下一轮再看
        materials.lowRows().forEach(b -> out.add(new LowStock(b.warehouseNo(), b.warehouseName(), b.materialNo(), b.materialName(),
                b.minQty(), b.onHand())));
        return out;
    }

    @Override
    public Set<String> removalsWithReturnTransfer(Collection<String> woNos) {
        if (woNos == null || woNos.isEmpty()) return Set.of();
        return DataScopeContext.executeWithoutScope(() -> transfers.selectList(new LambdaQueryWrapper<InvTransfer>()
                        .eq(InvTransfer::getSourceType, TransferSource.REMOVAL.name()).in(InvTransfer::getSourceRef, woNos))
                .stream().map(InvTransfer::getSourceRef).collect(Collectors.toSet()));
    }
}
