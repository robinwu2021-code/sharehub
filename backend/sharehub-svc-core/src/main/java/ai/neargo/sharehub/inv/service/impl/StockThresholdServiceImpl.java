package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.inv.port.InventoryEngineRouter;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryLinks;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.BigDecimal;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.sharehub.inv.WarehouseDirectory;
import ai.neargo.sharehub.inv.dto.InvOpsDtos.WarehouseItem;
import ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThreshold;
import ai.neargo.sharehub.inv.dto.ThresholdDtos.StockThresholdReq;
import ai.neargo.sharehub.inv.entity.InvStockThreshold;
import ai.neargo.sharehub.inv.mapper.InvStockThresholdMapper;
import ai.neargo.sharehub.inv.service.StockThresholdService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 安全库存（V204），口径见 {@link StockThresholdService}。 */
@Service
public class StockThresholdServiceImpl implements StockThresholdService {

    private static final Logger log = LoggerFactory.getLogger(StockThresholdServiceImpl.class);
    private static final List<String> TYPES = List.of(MovementRecorder.CABINET, MovementRecorder.POWERBANK);
    private static final int MAX_QTY = 100_000;

    private final InvStockThresholdMapper thresholds;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final WarehouseDirectory warehouses;

    public StockThresholdServiceImpl(InvStockThresholdMapper thresholds, CabinetMapper cabinets, PowerbankMapper powerbanks,
                                     WarehouseDirectory warehouses) {
        this.thresholds = thresholds;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.warehouses = warehouses;
    }

    @Override
    public List<StockThreshold> list(String warehouseNo) {
        Map<String, InvStockThreshold> set = thresholds.selectList(new LambdaQueryWrapper<InvStockThreshold>()).stream()
                .collect(Collectors.toMap(t -> t.getWarehouseNo() + ":" + t.getItemType(), Function.identity(), (a, b) -> a));
        List<StockThreshold> out = new ArrayList<>();
        for (WarehouseItem w : warehouses.list()) {
            if (w.archivedAt() != null) continue;
            if (warehouseNo != null && !warehouseNo.isBlank() && !w.warehouseNo().equals(warehouseNo.trim())) continue;
            for (String type : TYPES) {
                InvStockThreshold t = set.get(w.warehouseNo() + ":" + type);
                out.add(row(w, type, t == null || t.getMinQty() == null ? 0 : t.getMinQty()));
            }
        }
        return out;
    }

    @Override
    @Transactional
    public StockThreshold save(StockThresholdReq r) {
        if (r == null || r.warehouseNo() == null || r.warehouseNo().isBlank())
            throw BizException.badRequest("error.common.missing_parameter", "warehouseNo");
        if (r.itemType() == null || !TYPES.contains(r.itemType().trim())) throw BizException.badRequest("error.common.invalid_value", "itemType");
        if (r.minQty() == null || r.minQty() < 0 || r.minQty() > MAX_QTY) throw BizException.badRequest("error.common.invalid_value", "minQty");
        String type = r.itemType().trim();
        WarehouseItem w = warehouses.list().stream().filter(x -> x.warehouseNo().equals(r.warehouseNo().trim()) && x.archivedAt() == null)
                .findFirst().orElseThrow(() -> BizException.notFound(r.warehouseNo()));
        InvStockThreshold t = thresholds.selectOne(new LambdaQueryWrapper<InvStockThreshold>()
                .eq(InvStockThreshold::getWarehouseNo, w.warehouseNo()).eq(InvStockThreshold::getItemType, type).last("limit 1"));
        if (t == null) {
            t = new InvStockThreshold();
            t.setTenantId("MAIN");
            t.setWarehouseNo(w.warehouseNo());
            t.setItemType(type);
            t.setMinQty(r.minQty());
            thresholds.insert(t);
        } else {
            t.setMinQty(r.minQty());
            thresholds.updateById(t);
        }
        log.info("安全库存设置 warehouseNo={} itemType={} minQty={}", w.warehouseNo(), type, r.minQty());
        return row(w, type, r.minQty());
    }

    @Override
    public List<StockThreshold> lowRows() {
        return DataScopeContext.executeWithoutScope(() -> {
            List<InvStockThreshold> set = thresholds.selectList(new LambdaQueryWrapper<InvStockThreshold>().gt(InvStockThreshold::getMinQty, 0));
            if (set.isEmpty()) return List.<StockThreshold>of();
            Map<String, WarehouseItem> live = warehouses.list().stream().filter(w -> w.archivedAt() == null)
                    .collect(Collectors.toMap(WarehouseItem::warehouseNo, Function.identity()));
            return set.stream().filter(t -> live.containsKey(t.getWarehouseNo()))
                    .map(t -> row(live.get(t.getWarehouseNo()), t.getItemType(), t.getMinQty())).filter(StockThreshold::low).toList();
        });
    }

    private StockThreshold row(WarehouseItem w, String type, int min) {
        // 显式装箱：三元里一边 Integer 一边 int 会整体拆箱，库存服务查不到（null）时就是空指针
        Integer available = service() ? remoteAvailable(w.warehouseNo(), type) : Integer.valueOf(localAvailable(w.warehouseNo(), type));
        return new StockThreshold(w.warehouseNo(), w.name(), type, min, available, min > 0 && available != null && available < min);
    }

    /** 独立进销存引擎：库存服务按仓、按设备类型取可用数（对接明细 B06）。 */
    private RemoteInventoryPort remote;
    private InventoryLinks links;
    private SysParamPort params;

    @Autowired
    void setInventory(RemoteInventoryPort remote, InventoryLinks links, SysParamPort params) {
        this.remote = remote;
        this.links = links;
        this.params = params;
    }

    private boolean service() {
        return params != null && InventoryEngineRouter.isService(params);
    }

    /**
     * 库存服务里的可用数。查不到返回 null —— 告警每分钟判一次，服务一时不可用就当「不知道」、不报低，
     * 而不是当 0 报一屏误告警。仓还没映射过 = 库存服务里没有这个仓的账，可用为 0。
     */
    private Integer remoteAvailable(String warehouseNo, String type) {
        String loc = links.get("wh:" + warehouseNo);
        if (loc == null) return 0;
        try {
            InventoryApi api = remote.api();
            String item = links.get("item:" + type);
            if (item == null) return 0;
            return api.balances(loc, item).stream().map(InventoryApi.Balance::available).filter(q -> q != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).intValue();
        } catch (InventoryRemoteException e) {
            log.warn("安全库存取数跳过：库存服务不可用 warehouseNo={} itemType={} code={}，下一轮重试", warehouseNo, type, e.code);
            return null;
        }
    }

    private int localAvailable(String warehouseNo, String type) {
        long n = MovementRecorder.CABINET.equals(type)
                ? cabinets.selectCount(new LambdaQueryWrapper<DevCabinet>().eq(DevCabinet::getWarehouseNo, warehouseNo)
                .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).isNull(DevCabinet::getLocationNo)
                .isNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getArchivedAt))
                : powerbanks.selectCount(new LambdaQueryWrapper<DevPowerbank>().eq(DevPowerbank::getWarehouseNo, warehouseNo)
                .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNull(DevPowerbank::getCabinetNo)
                .isNull(DevPowerbank::getArchivedAt));
        return (int) n;
    }
}
