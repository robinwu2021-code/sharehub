package ai.neargo.sharehub.inv.service.impl;

import static ai.neargo.sharehub.inv.remote.InventoryCalls.call;
import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceMovement;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.sharehub.dev.CabinetStatus;
import ai.neargo.sharehub.dev.PowerbankStatus;
import ai.neargo.sharehub.dev.entity.DevCabinet;
import ai.neargo.sharehub.dev.entity.DevPowerbank;
import ai.neargo.sharehub.dev.mapper.CabinetMapper;
import ai.neargo.sharehub.dev.mapper.PowerbankMapper;
import ai.neargo.sharehub.inv.dto.StockDtos;
import ai.neargo.sharehub.inv.dto.StockDtos.StockPeriodRow;
import ai.neargo.sharehub.inv.dto.StockDtos.StockStage;
import ai.neargo.sharehub.inv.entity.InvIntakeItem;
import ai.neargo.sharehub.inv.mapper.InvIntakeItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import ai.neargo.sharehub.inv.dto.StockDtos.InventoryEngine;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCount;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCountDetail;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCountLine;
import ai.neargo.sharehub.inv.dto.StockDtos.StockCountScan;
import ai.neargo.sharehub.inv.dto.StockDtos.StockLedgerRow;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryLinks;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
import ai.neargo.sharehub.inv.remote.InventorySyncState;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.inv.service.StockViewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 库存流水与盘点（S4）。
 *
 * <p>流水：本地引擎取进出仓的设备履历（进仓 +1、出仓 −1）；独立进销存取它的流水（最近 500 条，页内分页）。
 * 盘点：只有独立进销存有盘点账。库存服务没有「列盘点单」的接口，开单时在 inventory_sync_state 登记
 * {@code count:{单号} → 仓号|开单时刻}，列表据此逐单取状态。
 */
@Service
public class StockViewServiceImpl implements StockViewService {

    private static final Logger log = LoggerFactory.getLogger(StockViewServiceImpl.class);
    /** 库存服务流水每次最多返回的条数（inventory-svc SerialServiceImpl.MAX_PAGE）。 */
    static final int REMOTE_LEDGER_PAGE = 500;
    /** 一次查询最多读这么多条流水；再多请按仓库、物料或单号筛选。 */
    static final int REMOTE_LEDGER_MAX = 10_000;

    private final ai.neargo.sharehub.inv.service.CountReviewService reviews;
    private final SysParamPort params;
    private final MovementRecorder movements;
    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final InvIntakeItemMapper intakeItems;
    private final ai.neargo.sharehub.inv.WarehouseDirectory warehouses;
    private final ai.neargo.sharehub.inv.mapper.InvIntakeMapper intakes;
    private final ai.neargo.sharehub.inv.mapper.InvTransferMapper transfers;
    private final ai.neargo.sharehub.inv.mapper.InvTransferItemMapper transferItems;
    private final ai.neargo.sharehub.inv.TransferAccess transferAccess;
    private final ai.neargo.sharehub.api.core.port.OrderQueryPort orders;

    public StockViewServiceImpl(SysParamPort params, MovementRecorder movements, RemoteInventoryPort remote, InventoryLinks links,
                                CabinetMapper cabinets, PowerbankMapper powerbanks, InvIntakeItemMapper intakeItems,
                                ai.neargo.sharehub.inv.WarehouseDirectory warehouses,
                                ai.neargo.sharehub.inv.mapper.InvIntakeMapper intakes,
                                ai.neargo.sharehub.inv.mapper.InvTransferMapper transfers,
                                ai.neargo.sharehub.inv.mapper.InvTransferItemMapper transferItems,
                                ai.neargo.sharehub.inv.TransferAccess transferAccess, ai.neargo.sharehub.api.core.port.OrderQueryPort orders, ai.neargo.sharehub.inv.service.CountReviewService reviews) {
        this.reviews=reviews;
        this.orders=orders;
        this.params = params;
        this.movements = movements;
        this.remote = remote;
        this.links = links;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.intakeItems = intakeItems;
        this.warehouses = warehouses;
        this.intakes = intakes;
        this.transfers=transfers; this.transferItems=transferItems; this.transferAccess=transferAccess;
    }

    // —— 统一库存视图 ——

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public List<StockStage> stages() {
        List<StockStage> out = new ArrayList<>();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (String stage : STAGES) {
            if ("INTAKE".equals(stage)) {
                out.add(new StockStage(stage, pendingIntake("CABINET"), pendingIntake("POWERBANK"), "LOCAL", now));
                continue;
            }
            if ("IN_STOCK".equals(stage) && service()) {
                out.add(remoteInStock(now));
                continue;
            }
            var c = CAB_STAGE.get(stage);
            var p = PB_STAGE.get(stage);
            out.add(new StockStage(stage, c == null ? 0L : cab(c), "IN_TRANSIT".equals(stage) ? (long) powerbanksInTransit().size() : p == null ? 0L : pb(p),
                    "LOCAL", now));
        }
        return out;
    }

    /**
     * 「在仓」是库存账（对接明细 B02）：独立进销存引擎下取库存服务的在手数，与库存余额（B01）同口径。
     * 库存服务查不到只让这一行为空，其余阶段是本地业务，照常给。
     */
    private StockStage remoteInStock(java.time.LocalDateTime now) {
        try {
            long cab = 0, pb = 0;
            for (StockDtos.DeviceBalance b : deviceBalances(null, null)) {
                if (InventoryLinks.CABINET.equals(b.itemType())) cab += b.onHand();
                else pb += b.onHand();
            }
            return new StockStage("IN_STOCK", cab, pb, "SERVICE", now);
        } catch (BizException e) {
            log.warn("生命周期视图「在仓」取数失败：库存服务不可用 {}，本行显示为不可用", e.getMessage());
            return new StockStage("IN_STOCK", null, null, "SERVICE_UNAVAILABLE", now);
        }
    }

    /** 阶段顺序（= 设备生命周期），与 {@link #CAB_STAGE} / {@link #PB_STAGE} 的键一致；INTAKE 读待检单。 */
    private static final List<String> STAGES = List.of("INTAKE", "IN_STOCK", "IN_TRANSIT", "DISPATCHED", "ISSUED", "BOUND", "LIVE",
            "RENTED", "FAULT", "LOST", "RETIRED");

    /** 阶段 → 柜的筛选条件。总数与下钻（{@link #stageItems}）共用这一份，口径不会分叉。 */
    private static final Map<String, java.util.function.Consumer<LambdaQueryWrapper<DevCabinet>>> CAB_STAGE = Map.of(
            "IN_STOCK", w -> w.eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name())
                    .isNotNull(DevCabinet::getWarehouseNo).isNull(DevCabinet::getLocationNo).isNull(DevCabinet::getIssuedAgentNo),
            "IN_TRANSIT", w -> w.eq(DevCabinet::getStatus, CabinetStatus.IN_TRANSIT.name()),
            "DISPATCHED", w -> w.isNotNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getLocationNo)
                    .isNull(DevCabinet::getIssueClaimedAt).eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()),
            "ISSUED", w -> w.isNotNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getLocationNo)
                    .isNotNull(DevCabinet::getIssueClaimedAt).eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()),
            "BOUND", w -> w.isNotNull(DevCabinet::getLocationNo).eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()),
            "LIVE", w -> w.eq(DevCabinet::getStatus, CabinetStatus.DEPLOYED.name()),
            "FAULT", w -> w.eq(DevCabinet::getStatus, CabinetStatus.FAULT.name()),
            "RETIRED", w -> w.eq(DevCabinet::getStatus, CabinetStatus.RETIRED.name()));

    /** 阶段 → 宝的筛选条件（同上）。 */
    private static final Map<String, java.util.function.Consumer<LambdaQueryWrapper<DevPowerbank>>> PB_STAGE = Map.of(
            "IN_STOCK", w -> w.eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNotNull(DevPowerbank::getWarehouseNo),
            "LIVE", w -> w.eq(DevPowerbank::getStatus, PowerbankStatus.IN_CABINET.name()),
            "RENTED", w -> w.eq(DevPowerbank::getStatus, PowerbankStatus.RENTED.name()),
            "FAULT", w -> w.eq(DevPowerbank::getStatus, PowerbankStatus.FAULT.name()),
            "LOST", w -> w.eq(DevPowerbank::getStatus, PowerbankStatus.LOST.name()),
            "RETIRED", w -> w.in(DevPowerbank::getStatus, PowerbankStatus.SCRAP.name(), PowerbankStatus.SOLD.name()));

    private static final int STAGE_ITEMS_MAX = 2000;

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    public ai.neargo.sharehub.inv.dto.StockDtos.StageItems stageItems(String stage, String itemType) {
        if (stage == null || !STAGES.contains(stage.trim())) throw BizException.badRequest("error.common.invalid_value", "stage");
        String st = stage.trim();
        boolean cabinet = !"POWERBANK".equals(itemType == null ? null : itemType.trim());
        if (itemType != null && !itemType.isBlank() && !"CABINET".equals(itemType.trim()) && cabinet)
            throw BizException.badRequest("error.common.invalid_value", "itemType");
        String type = cabinet ? "CABINET" : "POWERBANK";
        List<ai.neargo.sharehub.inv.dto.StockDtos.StageItem> rows = new ArrayList<>();
        long total;
        if ("IN_STOCK".equals(st) && service()) {
            // 在仓的下钻与台数同源：库存服务里各可见仓的在库序列号；total 是全量，items 最多 STAGE_ITEMS_MAX
            long all = 0;
            for (var w : warehouses.list()) {
                if (w.archivedAt() != null || !isVisibleWarehouse(w.warehouseNo())) continue;
                PageResult<StockDtos.DeviceSerial> page = deviceSerials(w.warehouseNo(), type, 1, STAGE_ITEMS_MAX);
                all += page.getTotal();
                if (rows.size() >= STAGE_ITEMS_MAX) continue;
                page.getList().forEach(x -> rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, x.serialNo(), null,
                        x.warehouseNo(), null, null, null, null)));
            }
            List<ai.neargo.sharehub.inv.dto.StockDtos.StageItem> capped = rows.size() > STAGE_ITEMS_MAX ? rows.subList(0, STAGE_ITEMS_MAX) : rows;
            return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, all, capped);
        } else if ("INTAKE".equals(st)) {
            LambdaQueryWrapper<InvIntakeItem> w = new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getItemType, type)
                    .eq(InvIntakeItem::getDisposition, "PENDING");
            scopeIntakes(w);
            total = intakeItems.selectCount(w);
            for (InvIntakeItem i : intakeItems.selectList(w.orderByAsc(InvIntakeItem::getId).last("limit " + STAGE_ITEMS_MAX)))
                rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, i.getItemNo(), null, null, null, null, null, i.getIntakeNo()));
        } else if (cabinet) {
            var f = CAB_STAGE.get(st);
            if (f == null) return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, 0, List.of());
            LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<DevCabinet>().isNull(DevCabinet::getArchivedAt);
            f.accept(w);
            scopeCabinets(w);
            total = cabinets.selectCount(w);
            for (DevCabinet c : cabinets.selectList(w.orderByAsc(DevCabinet::getId).last("limit " + STAGE_ITEMS_MAX)))
                rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, c.getCabinetNo(), c.getModel(), c.getWarehouseNo(),
                        c.getIssuedAgentNo() != null ? c.getIssuedAgentNo() : c.getAgentNo(), c.getSiteNo(), c.getLocationNo(), null));
        } else if ("IN_TRANSIT".equals(st)) {
            List<String> nos=powerbanksInTransit();
            total=nos.size();
            if(!nos.isEmpty()) for(DevPowerbank p:powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>()
                    .in(DevPowerbank::getPowerbankNo,nos).orderByAsc(DevPowerbank::getId).last("limit "+STAGE_ITEMS_MAX)))
                rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type,p.getPowerbankNo(),p.getVendorCode(),null,null,null,null,null));
        } else {
            var f = PB_STAGE.get(st);
            if (f == null) return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, 0, List.of());
            LambdaQueryWrapper<DevPowerbank> w = new LambdaQueryWrapper<DevPowerbank>().isNull(DevPowerbank::getArchivedAt);
            f.accept(w);
            scopePowerbanks(w);
            total = powerbanks.selectCount(w);
            for (DevPowerbank p : powerbanks.selectList(w.orderByAsc(DevPowerbank::getId).last("limit " + STAGE_ITEMS_MAX)))
                rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, p.getPowerbankNo(), p.getVendorCode(), p.getWarehouseNo(),
                        null, null, p.getCabinetNo(), null));
        }
        return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, total, rows);
    }

    private long cab(java.util.function.Consumer<LambdaQueryWrapper<DevCabinet>> f) {
        LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<DevCabinet>().isNull(DevCabinet::getArchivedAt);
        f.accept(w);
        scopeCabinets(w);
        return cabinets.selectCount(w);
    }

    private long pb(java.util.function.Consumer<LambdaQueryWrapper<DevPowerbank>> f) {
        LambdaQueryWrapper<DevPowerbank> w = new LambdaQueryWrapper<DevPowerbank>().isNull(DevPowerbank::getArchivedAt);
        f.accept(w);
        scopePowerbanks(w);
        return powerbanks.selectCount(w);
    }

    private long pendingIntake(String itemType) {
        LambdaQueryWrapper<InvIntakeItem> w = new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getItemType, itemType)
                .eq(InvIntakeItem::getDisposition, "PENDING");
        scopeIntakes(w);
        return intakeItems.selectCount(w);
    }

    private void scopeCabinets(LambdaQueryWrapper<DevCabinet> w) {
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible != null) w.and(q -> {
            q.isNull(DevCabinet::getWarehouseNo);
            if (!visible.isEmpty()) q.or().in(DevCabinet::getWarehouseNo, visible);
        });
    }

    private void scopePowerbanks(LambdaQueryWrapper<DevPowerbank> w) {
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible == null) return;
        LambdaQueryWrapper<DevCabinet> cabinetsInScope = new LambdaQueryWrapper<DevCabinet>().select(DevCabinet::getCabinetNo);
        scopeCabinets(cabinetsInScope);
        List<String> cabinetNos = cabinets.selectList(cabinetsInScope).stream().map(DevCabinet::getCabinetNo).toList();
        List<String> offCabinet=orders.visiblePowerbanksFromLatestOrders();
        List<String> retired=movements.visibleRetiredPowerbanks(visible);
        if (visible.isEmpty() && cabinetNos.isEmpty() && offCabinet.isEmpty() && retired.isEmpty()) { w.apply("1=0"); return; }
        w.and(q -> {
            if (!visible.isEmpty()) q.nested(x->x.isNull(DevPowerbank::getCabinetNo)
                    .in(DevPowerbank::getStatus,PowerbankStatus.IN_STOCK.name(),PowerbankStatus.FAULT.name())
                    .in(DevPowerbank::getWarehouseNo, visible));
            if (!cabinetNos.isEmpty()) {
                if (!visible.isEmpty()) q.or();
                q.nested(x->x.in(DevPowerbank::getCabinetNo, cabinetNos)
                    .in(DevPowerbank::getStatus,PowerbankStatus.IN_CABINET.name(),PowerbankStatus.FAULT.name()));
            }
            if (!retired.isEmpty()) {
                if (!visible.isEmpty() || !cabinetNos.isEmpty()) q.or();
                q.nested(x->x.eq(DevPowerbank::getStatus,PowerbankStatus.SCRAP.name()).in(DevPowerbank::getPowerbankNo,retired));
            }
            if (!offCabinet.isEmpty()) {
                if (!visible.isEmpty() || !cabinetNos.isEmpty() || !retired.isEmpty()) q.or();
                q.nested(x->x.isNull(DevPowerbank::getCabinetNo)
                    .in(DevPowerbank::getStatus,PowerbankStatus.RENTED.name(),PowerbankStatus.LOST.name(),PowerbankStatus.SOLD.name())
                    .in(DevPowerbank::getPowerbankNo,offCabinet));
            }
        });
    }

    private void scopeIntakes(LambdaQueryWrapper<InvIntakeItem> w) {
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible == null) return;
        if (visible.isEmpty()) { w.apply("1=0"); return; }
        List<String> nos = intakes.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.inv.entity.InvIntake>()
                .select(ai.neargo.sharehub.inv.entity.InvIntake::getIntakeNo)
                .in(ai.neargo.sharehub.inv.entity.InvIntake::getWarehouseNo, visible)).stream()
                .map(ai.neargo.sharehub.inv.entity.InvIntake::getIntakeNo).toList();
        if (nos.isEmpty()) w.apply("1=0"); else w.in(InvIntakeItem::getIntakeNo, nos);
    }

    private List<String> powerbanksInTransit() {
        LambdaQueryWrapper<ai.neargo.sharehub.inv.entity.InvTransfer> q=new LambdaQueryWrapper<ai.neargo.sharehub.inv.entity.InvTransfer>()
                .select(ai.neargo.sharehub.inv.entity.InvTransfer::getTransferNo)
                .in(ai.neargo.sharehub.inv.entity.InvTransfer::getStatus,ai.neargo.sharehub.inv.InvTransferStatus.IN_TRANSIT.name(),ai.neargo.sharehub.inv.InvTransferStatus.DONE.name());
        transferAccess.filter(q);
        List<String> docs=transfers.selectList(q).stream().map(ai.neargo.sharehub.inv.entity.InvTransfer::getTransferNo).toList();
        if(docs.isEmpty()) return List.of();
        var candidates=transferItems.selectList(new LambdaQueryWrapper<ai.neargo.sharehub.inv.entity.InvTransferItem>()
                .in(ai.neargo.sharehub.inv.entity.InvTransferItem::getTransferNo,docs)
                .eq(ai.neargo.sharehub.inv.entity.InvTransferItem::getChecked,0)
                .isNotNull(ai.neargo.sharehub.inv.entity.InvTransferItem::getPowerbankNo));
        var latest=movements.latestShipments(candidates.stream().map(ai.neargo.sharehub.inv.entity.InvTransferItem::getPowerbankNo).distinct().toList());
        List<String> nos=candidates.stream().filter(i->i.getTransferNo().equals(latest.get(i.getPowerbankNo())))
                .map(ai.neargo.sharehub.inv.entity.InvTransferItem::getPowerbankNo).distinct().toList();
        if(nos.isEmpty()) return List.of();
        return powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>().select(DevPowerbank::getPowerbankNo)
                .in(DevPowerbank::getPowerbankNo,nos).isNull(DevPowerbank::getWarehouseNo).isNull(DevPowerbank::getCabinetNo)
                .isNull(DevPowerbank::getArchivedAt).eq(DevPowerbank::getStatus,PowerbankStatus.IN_STOCK.name()))
                .stream().map(DevPowerbank::getPowerbankNo).toList();
    }

    // —— 期间报表 ——

    @Override
    public List<StockPeriodRow> periodSummary(LocalDate from, LocalDate to, String warehouseNo) {
        if (from == null || to == null || to.isBefore(from)) throw BizException.badRequest("error.common.invalid_value", "from / to");
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        String wh = warehouseNo == null || warehouseNo.isBlank() ? null : warehouseNo.trim();
        if (wh != null) warehouses.requireVisible(wh);
        List<String> visible = warehouses.visibleWarehouseNos();
        if (visible != null && visible.isEmpty()) return List.of();
        if (service()) {
            InventoryApi api = remote.api();
            List<String> requested = wh != null ? List.of(wh) : visible;
            return call(() -> requested == null ? api.periodSummary(start, end, null) : requested.stream()
                    .flatMap(no -> api.periodSummary(start, end, links.locationId(api, no)).stream()).toList()).stream()
                    .map(r -> new StockPeriodRow(links.warehouseNoOf(r.locationId()), links.itemTypeOf(r.itemId()), n(r.opening()),
                            sum(r.inByReason()), sum(r.outByReason()), n(r.closing()), longs(r.inByReason()), longs(r.outByReason())))
                    .toList();
        }
        return localPeriod(start, end, wh, visible);
    }

    /**
     * 本地口径：期末 = 当前在仓数 − 期末之后的净进出；期初 = 期末 − 期内入 + 期内出。
     * 履历自 V196 起才有，更早的期间期初不可信（页面提示）。
     */
    private List<StockPeriodRow> localPeriod(LocalDateTime start, LocalDateTime end, String wh, List<String> visible) {
        Map<String, long[]> now = new TreeMap<>();
        DataScopeContext.executeWithoutScope(() -> {
            for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().select(DevCabinet::getWarehouseNo)
                    .in(visible != null, DevCabinet::getWarehouseNo, visible)
                    .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).isNotNull(DevCabinet::getWarehouseNo).isNull(DevCabinet::getArchivedAt))) {
                now.computeIfAbsent(c.getWarehouseNo() + "|CABINET", k -> new long[1])[0]++;
            }
            for (DevPowerbank p : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>().select(DevPowerbank::getWarehouseNo)
                    .in(visible != null, DevPowerbank::getWarehouseNo, visible)
                    .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNotNull(DevPowerbank::getWarehouseNo).isNull(DevPowerbank::getArchivedAt))) {
                now.computeIfAbsent(p.getWarehouseNo() + "|POWERBANK", k -> new long[1])[0]++;
            }
            return null;
        });
        Map<String, Long> after = new HashMap<>();
        Map<String, Map<String, Long>> in = new TreeMap<>();
        Map<String, Map<String, Long>> out = new TreeMap<>();
        for (DeviceMovement m : movements.warehouseMovesSince(start, visible)) {
            boolean inPeriod = m.occurredAt() != null && m.occurredAt().isBefore(end);
            if (m.toRef() != null && m.toRef().startsWith("WH:")) {
                String k = m.toRef().substring(3) + "|" + m.itemType();
                if (inPeriod) in.computeIfAbsent(k, x -> new TreeMap<>()).merge(m.event(), 1L, Long::sum);
                else after.merge(k, 1L, Long::sum);
            }
            if (m.fromRef() != null && m.fromRef().startsWith("WH:")) {
                String k = m.fromRef().substring(3) + "|" + m.itemType();
                if (inPeriod) out.computeIfAbsent(k, x -> new TreeMap<>()).merge(m.event(), 1L, Long::sum);
                else after.merge(k, -1L, Long::sum);
            }
        }
        java.util.Set<String> keys = new java.util.TreeSet<>(now.keySet());
        keys.addAll(in.keySet());
        keys.addAll(out.keySet());
        List<StockPeriodRow> rows = new ArrayList<>();
        for (String k : keys) {
            String[] p = k.split("\\|", 2);
            if (visible != null && !visible.contains(p[0])) continue;
            if (wh != null && !wh.equals(p[0])) continue;
            long closing = (now.containsKey(k) ? now.get(k)[0] : 0) - after.getOrDefault(k, 0L);
            Map<String, Long> i = in.getOrDefault(k, Map.of()), o = out.getOrDefault(k, Map.of());
            long inQ = i.values().stream().mapToLong(Long::longValue).sum(), outQ = o.values().stream().mapToLong(Long::longValue).sum();
            rows.add(new StockPeriodRow(p[0], p[1], closing - inQ + outQ, inQ, outQ, closing, i, o));
        }
        return rows;
    }

    private static long n(java.math.BigDecimal v) {
        return v == null ? 0 : v.longValue();
    }

    private static long sum(Map<String, java.math.BigDecimal> m) {
        return m == null ? 0 : m.values().stream().mapToLong(StockViewServiceImpl::n).sum();
    }

    private static Map<String, Long> longs(Map<String, java.math.BigDecimal> m) {
        Map<String, Long> r = new TreeMap<>();
        if (m != null) m.forEach((k, v) -> r.put(k, n(v)));
        return r;
    }

    private boolean service() {
        return ai.neargo.sharehub.inv.port.InventoryEngineRouter.isService(params);
    }

    /** 寻址方式（展示用）：setter 注入，构造参数已经很长。 */
    private ai.neargo.sharehub.inv.remote.InventoryEndpoint endpoint;

    private ai.neargo.sharehub.inv.service.InventoryOperationJournal journal;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setJournal(ai.neargo.sharehub.inv.service.InventoryOperationJournal journal) {
        this.journal = journal;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEndpoint(ai.neargo.sharehub.inv.remote.InventoryEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public InventoryEngine engine() {
        String mode = endpoint == null ? null : endpoint.mode();
        long pending = journal == null ? 0 : journal.unfinished();
        if (!service()) return new InventoryEngine("LOCAL", true, mode, null, null, pending);
        InventoryApi api = remote.api();
        if (!api.configured()) return new InventoryEngine("SERVICE", false, mode, null, null, pending);
        // 现探一次：读仓库列表（最轻的只读接口）。页面每次打开都会问，恢复后自然变回「正常」
        try {
            api.warehouses();
            return new InventoryEngine("SERVICE", true, mode, true, null, pending);
        } catch (InventoryRemoteException e) {
            log.warn("库存服务连通性探测失败 code={} status={}：检查 sharehub.inventory.* 与库存服务健康", e.code, e.status);
            return new InventoryEngine("SERVICE", true, mode, false, e.code, pending);
        }
    }

    // —— 流水 ——

    @Override
    public PageResult<StockLedgerRow> ledger(String warehouseNo, String itemType, String keyword, Integer page, Integer size) {
        if (warehouseNo != null && !warehouseNo.isBlank()) warehouses.requireVisible(warehouseNo.trim());
        List<String> visible = warehouses.visibleWarehouseNos();
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        if (!service()) {
            PageResult<DeviceMovement> r = movements.warehouseMoves(warehouseNo, itemType, keyword, p, s, visible);
            return new PageResult<>(r.getList().stream().map(m -> localRow(m, ledgerWarehouse(m, warehouseNo, visible))).toList(), r.getTotal());
        }
        if (visible != null && visible.isEmpty()) return new PageResult<>(List.of(), 0);
        InventoryApi api = remote.api();
        Map<String, String> whOf = new HashMap<>();
        Map<String, String> typeOf = new HashMap<>();
        List<StockLedgerRow> all = call(() -> {
            String item = itemType == null || itemType.isBlank() ? null : links.itemId(api, itemType.trim());
            String doc = keyword == null || keyword.isBlank() ? null : keyword.trim();
            List<String> requested = warehouseNo == null || warehouseNo.isBlank() ? visible : List.of(warehouseNo.trim());
            if (requested == null) return fullLedger(api, item, null, doc);
            List<InventoryApi.LedgerEntry> out = new ArrayList<>();
            for (String no : requested) out.addAll(fullLedger(api, item, links.locationId(api, no), doc));
            return out;
        })
                .stream().map(e -> new StockLedgerRow("S" + e.id(), whOf.computeIfAbsent(e.locationId(), links::warehouseNoOf),
                        typeOf.computeIfAbsent(e.itemId(), links::itemTypeOf), null,
                        e.docKind(), e.docNo(), e.qtyDelta() == null ? 0 : e.qtyDelta().intValue(), e.occurredAt(), e.operator()))
                .filter(r -> visible == null || visible.contains(r.warehouseNo()))
                .sorted(Comparator.comparing(StockLedgerRow::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((p - 1) * s, all.size());
        return new PageResult<>(all.subList(from, Math.min(from + s, all.size())), all.size());
    }

    /**
     * 库存服务流水只能按 id 升序、afterId 往后翻（每次最多 {@link #REMOTE_LEDGER_PAGE} 条），而页面要「最新在前」，
     * 所以按游标读全（对接明细 B04：原先只取最近一窗、超出的静默丢了）。超过上限就明确报错让用户加筛选，
     * 不返回半截账。倒序 + 总数已列入宿主接口需求。
     */
    static List<InventoryApi.LedgerEntry> fullLedger(InventoryApi api, String itemId, String locationId, String docNo) {
        List<InventoryApi.LedgerEntry> out = new ArrayList<>();
        Long after = null;
        while (true) {
            List<InventoryApi.LedgerEntry> batch = api.ledger(itemId, locationId, docNo, after, REMOTE_LEDGER_PAGE);
            out.addAll(batch);
            if (out.size() > REMOTE_LEDGER_MAX) throw BizException.conflict("error.inventory.ledger_too_large", REMOTE_LEDGER_MAX);
            if (batch.size() < REMOTE_LEDGER_PAGE) return out;
            Long last = batch.getLast().id();
            if (last == null || (after != null && last <= after)) return out;   // 对方没按游标前进：不再循环
            after = last;
        }
    }

    private static String ledgerWarehouse(DeviceMovement m, String selected, List<String> visible) {
        if (selected != null && !selected.isBlank() || visible == null) return selected;
        if (m.toRef() != null && m.toRef().startsWith("WH:") && visible.contains(m.toRef().substring(3))) return m.toRef().substring(3);
        return m.fromRef() == null ? null : m.fromRef().replaceFirst("^WH:", "");
    }

    /** 履历 → 流水：to 是仓 = 进（+1），from 是仓 = 出（−1）；指定了仓时以该仓的视角定方向。 */
    private static StockLedgerRow localRow(DeviceMovement m, String warehouseNo) {
        boolean in = m.toRef() != null && m.toRef().startsWith("WH:")
                && (warehouseNo == null || warehouseNo.isBlank() || m.toRef().equals("WH:" + warehouseNo.trim()));
        String wh = in ? m.toRef().substring(3) : m.fromRef() == null ? null : m.fromRef().replaceFirst("^WH:", "");
        return new StockLedgerRow("L" + m.id(), wh, m.itemType(), m.itemNo(), m.event(), m.sourceRef(), in ? 1 : -1, m.occurredAt(),
                m.operatorNo());
    }

    // —— 盘点 ——

    @Override
    public List<StockCount> counts(String warehouseNo, String status) {
        requireService();
        List<String> visible = warehouses.visibleWarehouseNos();
        if (warehouseNo != null && !warehouseNo.isBlank()) warehouses.requireVisible(warehouseNo.trim());
        List<StockCount> out = new ArrayList<>();
        for (InventorySyncState r : links.listByPrefix("count:")) {
            if (out.size() >= 50) break;
            String[] v = r.getV().split("\\|", 2);
            if (visible != null && !visible.contains(v[0])) continue;
            if (warehouseNo != null && !warehouseNo.isBlank() && !warehouseNo.trim().equals(v[0])) continue;
            String no = r.getK().substring(6);
            String st;
            try {
                st = remote.api().count(no).status();
            } catch (InventoryRemoteException e) {
                log.warn("盘点单状态读取失败 countNo={} code={}：列表里显示为未知，到库存服务核对", no, e.code);
                st = "UNKNOWN";
            }
            if (status != null && !status.isBlank() && !status.trim().equals(st)) continue;
            out.add(new StockCount(no, v[0], st, v.length > 1 ? LocalDateTime.parse(v[1]) : null));
        }
        return out;
    }

    @Override
    public StockCountDetail openCount(String warehouseNo) {
        requireService();
        if (warehouseNo == null || warehouseNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "warehouseNo");
        InventoryApi api = remote.api();
        String wh = warehouseNo.trim();
        warehouses.requireVisible(wh);
        String no = call(() -> api.openCount(links.locationId(api, wh),
                List.of(links.itemId(api, InventoryLinks.CABINET), links.itemId(api, InventoryLinks.POWERBANK)), key("count")));
        links.put("count:" + no, wh + "|" + LocalDateTime.now());
        log.info("开盘点 countNo={} warehouseNo={}", no, wh);
        return count(no);
    }

    @Override
    public StockCountDetail count(String countNo) {
        requireService();
        InventoryApi.CountView v = accessibleCount(countNo);
        String wh = links.warehouseNoOf(v.locationId());
        String reg = links.get("count:" + countNo);
        LocalDateTime opened = reg != null && reg.contains("|") ? LocalDateTime.parse(reg.split("\\|", 2)[1]) : null;
        return new StockCountDetail(new StockCount(v.countNo(), wh, v.status(), opened),
                v.lines() == null ? List.of() : v.lines().stream()
                        .map(l -> new StockCountLine(links.itemTypeOf(l.itemId()), l.bookQty(), l.countedQty(), l.diffQty(), l.itemId(),
                                l.trackingMode())).toList(),
                v.exceptions() == null ? List.of() : v.exceptions().stream().map(x -> new StockCountScan(x.serialNo(), x.verdict())).toList(),
                v.missingSerials() == null ? List.of() : v.missingSerials(),reviews.view(countNo));
    }

    /**
     * 数量物料实盘（对接明细 B15）：按 itemId 录实盘数，不能拿重复扫码代替。与扫码同一道统一审核闸门：
     * 只在盘点中、未送审时可改。
     */
    @Override
    public StockCountDetail countLine(String countNo, String itemId, Integer countedQty) {
        requireService();
        var view = accessibleCount(countNo);
        if (itemId == null || itemId.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "itemId");
        if (countedQty == null || countedQty < 0 || countedQty > 10_000_000) throw BizException.badRequest("error.common.invalid_value", "countedQty");
        call(() -> reviews.edit(countNo, links.warehouseNoOf(view.locationId()), () -> {
            remote.api().countLine(countNo, itemId.trim(), countedQty, key("line"));
            return null;
        }));
        return count(countNo);
    }

    @Override
    public StockCountDetail scan(String countNo, List<String> serialNos) {
        requireService();
        var view=accessibleCount(countNo);
        List<String> nos = serialNos == null ? List.of()
                : serialNos.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).distinct().toList();
        if (nos.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "serialNos");
        call(() -> reviews.edit(countNo,links.warehouseNoOf(view.locationId()),()->remote.api().scanCount(countNo,nos,key("scan"))));
        return count(countNo);
    }

    @Override
    public StockCountDetail submit(String countNo) {
        requireService();var v=accessibleCount(countNo);
        call(()->{reviews.submit(countNo,links.warehouseNoOf(v.locationId()));return null;});return count(countNo);
    }

    @Override
    public StockCountDetail approve(String countNo, String note) {
        requireService();accessibleCount(countNo);throw BizException.conflict("error.inventory.unified_review_required");
    }

    @Override
    public StockCountDetail reject(String countNo, String note) {
        requireService();accessibleCount(countNo);throw BizException.conflict("error.inventory.unified_review_required");
    }

    @Override
    public StockCountDetail post(String countNo) {
        requireService();accessibleCount(countNo);reviews.retry(countNo);return count(countNo);
    }

    @Override
    public StockCountDetail cancel(String countNo) {
        requireService();var v=accessibleCount(countNo);
        call(()->reviews.edit(countNo,links.warehouseNoOf(v.locationId()),()->{remote.api().cancelCount(countNo,key("cancel"));return null;}));
        return count(countNo);
    }

    // —— 内部 ——

    private StockCountDetail act(String countNo, Runnable r) {
        requireService();
        accessibleCount(countNo);
        call(() -> {
            r.run();
            return null;
        });
        return count(countNo);
    }

    /** 以远端单据的实际仓位鉴权，不能只信本地索引，也不能先修改远端再鉴权。 */
    private InventoryApi.CountView accessibleCount(String countNo) {
        InventoryApi.CountView view = call(() -> remote.api().count(countNo));
        warehouses.requireVisible(links.warehouseNoOf(view.locationId()));
        return view;
    }

    // —— 设备库存余额（B01）——

    private static final List<String> DEVICE_TYPES = List.of(InventoryLinks.CABINET, InventoryLinks.POWERBANK);

    @Override
    public List<StockDtos.DeviceBalance> deviceBalances(String warehouseNo, String itemType) {
        requireService();
        String only = warehouseNo == null || warehouseNo.isBlank() ? null : warehouseNo.trim();
        if (only != null) warehouses.requireVisible(only);
        List<String> visible = warehouses.visibleWarehouseNos();
        List<String> types = deviceTypes(itemType);
        InventoryApi api = remote.api();
        // 一种设备一次取全部库位，再按本地仓映射回去；没映射过的仓 = 库存服务里没有它的账，各项为 0
        Map<String, InventoryApi.Balance> byKey = new HashMap<>();
        for (String type : types) {
            String item = links.get("item:" + type);
            if (item == null) continue;
            for (InventoryApi.Balance b : call(() -> api.balances(null, item))) byKey.put(b.locationId() + "|" + type, b);
        }
        List<StockDtos.DeviceBalance> out = new ArrayList<>();
        for (var w : warehouses.list()) {
            if (w.archivedAt() != null || (only != null && !only.equals(w.warehouseNo()))) continue;
            if (visible != null && !visible.contains(w.warehouseNo())) continue;
            String loc = links.get("wh:" + w.warehouseNo());
            for (String type : types) {
                InventoryApi.Balance b = loc == null ? null : byKey.get(loc + "|" + type);
                out.add(new StockDtos.DeviceBalance(w.warehouseNo(), w.name(), type, n(b == null ? null : b.onHand()),
                        n(b == null ? null : b.reserved()), n(b == null ? null : b.unavailable()), n(b == null ? null : b.available())));
            }
        }
        return out;
    }

    @Override
    public PageResult<StockDtos.DeviceSerial> deviceSerials(String warehouseNo, String itemType, Integer page, Integer size) {
        requireService();
        if (warehouseNo == null || warehouseNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "warehouseNo");
        String wh = warehouseNo.trim();
        warehouses.requireVisible(wh);
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, 500);
        String loc = links.get("wh:" + wh);
        if (loc == null) return new PageResult<>(List.of(), 0);
        InventoryApi api = remote.api();
        List<StockDtos.DeviceSerial> all = new ArrayList<>();
        for (String type : deviceTypes(itemType)) {
            String item = links.get("item:" + type);
            if (item == null) continue;
            call(() -> api.serials(item, loc)).forEach(x -> all.add(new StockDtos.DeviceSerial(x.serialNo(), type, wh)));
        }
        all.sort(Comparator.comparing(StockDtos.DeviceSerial::serialNo));
        int from = Math.min((p - 1) * s, all.size());
        return new PageResult<>(all.subList(from, Math.min(from + s, all.size())), all.size());
    }

    // —— 出库与安装候选（C01 / C05）——

    private static final int CANDIDATES_MAX = 2000;

    @Override
    public List<StockDtos.IssueCandidate> issueCandidates(String warehouseNo) {
        requireService();
        String only = warehouseNo == null || warehouseNo.isBlank() ? null : warehouseNo.trim();
        if (only != null) warehouses.requireVisible(only);
        String item = links.get("item:" + InventoryLinks.CABINET);
        if (item == null) return List.of();
        InventoryApi api = remote.api();
        // 库存服务说在库才算在库；本地状态只负责业务上的拦截（已绑、已出库、质检没放行）
        Map<String, String> whOfSerial = new java.util.LinkedHashMap<>();
        for (var w : warehouses.list()) {
            if (w.archivedAt() != null || (only != null && !only.equals(w.warehouseNo())) || !isVisibleWarehouse(w.warehouseNo())) continue;
            String loc = links.get("wh:" + w.warehouseNo());
            if (loc == null) continue;
            for (InventoryApi.InStock x : call(() -> api.serials(item, loc))) whOfSerial.put(x.serialNo(), w.warehouseNo());
        }
        List<StockDtos.IssueCandidate> out = new ArrayList<>();
        for (List<String> chunk : chunks(new ArrayList<>(whOfSerial.keySet()))) {
            for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().in(DevCabinet::getCabinetNo, chunk)
                    .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).isNull(DevCabinet::getLocationNo)
                    .isNull(DevCabinet::getIssuedAgentNo).isNull(DevCabinet::getArchivedAt)
                    .and(q -> q.isNull(DevCabinet::getQcStatus).or().eq(DevCabinet::getQcStatus, ai.neargo.sharehub.dev.QcStatus.PASSED.name())))) {
                out.add(new StockDtos.IssueCandidate(c.getCabinetNo(), whOfSerial.get(c.getCabinetNo()), c.getModel(), c.getVendorCode()));
            }
        }
        out.sort(Comparator.comparing(StockDtos.IssueCandidate::cabinetNo));
        return out.size() > CANDIDATES_MAX ? out.subList(0, CANDIDATES_MAX) : out;
    }

    @Override
    public PageResult<StockDtos.InstallCandidate> installationCandidates(String agentNo, String siteNo, Integer page, Integer size) {
        requireService();
        if (agentNo == null || agentNo.isBlank()) throw BizException.badRequest("error.common.missing_parameter", "agentNo");
        String agent = agentNo.trim();
        String site = siteNo == null || siteNo.isBlank() ? null : siteNo.trim();
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, 500);
        String item = links.get("item:" + InventoryLinks.CABINET);
        if (item == null) return new PageResult<>(List.of(), 0);
        InventoryApi api = remote.api();
        // 只认库存服务里该商家「已领取」的顶层出库记录：安装要先签收（待领取的还不能装）
        Map<String, String> dispatchOf = new java.util.LinkedHashMap<>();
        for (InventoryApi.DispatchView v : call(() -> api.dispatchPool(new InventoryApi.DispatchFilter(List.of("CLAIMED"), List.of(agent),
                null, null, null, null, item)))) {
            if (v.parentDispatchId() == null && v.serialNo() != null) dispatchOf.put(v.serialNo(), v.dispatchId());
        }
        List<StockDtos.InstallCandidate> all = new ArrayList<>();
        // 本地数据范围照常生效（商家只看得到自己的柜）
        for (List<String> chunk : chunks(new ArrayList<>(dispatchOf.keySet()))) {
            for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().in(DevCabinet::getCabinetNo, chunk)
                    .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).eq(DevCabinet::getIssuedAgentNo, agent)
                    .isNotNull(DevCabinet::getIssueClaimedAt).isNull(DevCabinet::getWarehouseNo).isNull(DevCabinet::getArchivedAt)
                    .and(q -> q.isNull(DevCabinet::getQcStatus).or().eq(DevCabinet::getQcStatus, ai.neargo.sharehub.dev.QcStatus.PASSED.name()))
                    .and(q -> q.isNull(DevCabinet::getLocationNo).or().eq(DevCabinet::getAgentNo, agent)))) {
                if (site != null && !site.equals(c.getIssuedSiteNo())) continue;
                all.add(new StockDtos.InstallCandidate(c.getCabinetNo(), c.getModel(), c.getIssuedSiteNo(), c.getLocationNo(),
                        dispatchOf.get(c.getCabinetNo())));
            }
        }
        all.sort(Comparator.comparing(StockDtos.InstallCandidate::cabinetNo));
        int from = Math.min((p - 1) * s, all.size());
        return new PageResult<>(all.subList(from, Math.min(from + s, all.size())), all.size());
    }

    private static List<List<String>> chunks(List<String> all) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 500) out.add(all.subList(i, Math.min(i + 500, all.size())));
        return out;
    }

    private static List<String> deviceTypes(String itemType) {
        if (itemType == null || itemType.isBlank()) return DEVICE_TYPES;
        String t = itemType.trim();
        if (!DEVICE_TYPES.contains(t)) throw BizException.badRequest("error.common.invalid_value", "itemType");
        return List.of(t);
    }

    private boolean isVisibleWarehouse(String warehouseNo) {
        List<String> visible = warehouses.visibleWarehouseNos();
        return visible == null || visible.contains(warehouseNo);
    }

    private void requireService() {
        if (!service()) throw BizException.conflict("error.inventory.service_only");
        if (!remote.api().configured()) throw BizException.conflict("error.inventory.not_configured");
    }

    private static String key(String kind) {
        return kind + "-" + UUID.randomUUID();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
