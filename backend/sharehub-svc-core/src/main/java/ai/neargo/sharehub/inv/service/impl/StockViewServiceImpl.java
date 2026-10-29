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
    private static final int REMOTE_LEDGER_WINDOW = 500;

    private final SysParamPort params;
    private final MovementRecorder movements;
    private final RemoteInventoryPort remote;
    private final InventoryLinks links;
    private final CabinetMapper cabinets;
    private final PowerbankMapper powerbanks;
    private final InvIntakeItemMapper intakeItems;

    public StockViewServiceImpl(SysParamPort params, MovementRecorder movements, RemoteInventoryPort remote, InventoryLinks links,
                                CabinetMapper cabinets, PowerbankMapper powerbanks, InvIntakeItemMapper intakeItems) {
        this.params = params;
        this.movements = movements;
        this.remote = remote;
        this.links = links;
        this.cabinets = cabinets;
        this.powerbanks = powerbanks;
        this.intakeItems = intakeItems;
    }

    // —— 统一库存视图 ——

    @Override
    public List<StockStage> stages() {
        return DataScopeContext.executeWithoutScope(() -> {
            List<StockStage> out = new ArrayList<>();
            for (String stage : STAGES) {
                if ("INTAKE".equals(stage)) {
                    out.add(new StockStage(stage, pendingIntake("CABINET"), pendingIntake("POWERBANK")));
                    continue;
                }
                var c = CAB_STAGE.get(stage);
                var p = PB_STAGE.get(stage);
                out.add(new StockStage(stage, c == null ? 0 : cab(c), p == null ? 0 : pb(p)));
            }
            return out;
        });
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
    public ai.neargo.sharehub.inv.dto.StockDtos.StageItems stageItems(String stage, String itemType) {
        if (stage == null || !STAGES.contains(stage.trim())) throw BizException.badRequest("error.common.invalid_value", "stage");
        String st = stage.trim();
        boolean cabinet = !"POWERBANK".equals(itemType == null ? null : itemType.trim());
        if (itemType != null && !itemType.isBlank() && !"CABINET".equals(itemType.trim()) && cabinet)
            throw BizException.badRequest("error.common.invalid_value", "itemType");
        String type = cabinet ? "CABINET" : "POWERBANK";
        return DataScopeContext.executeWithoutScope(() -> {
            List<ai.neargo.sharehub.inv.dto.StockDtos.StageItem> rows = new ArrayList<>();
            long total;
            if ("INTAKE".equals(st)) {
                LambdaQueryWrapper<InvIntakeItem> w = new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getItemType, type)
                        .eq(InvIntakeItem::getDisposition, "PENDING");
                total = intakeItems.selectCount(w);
                for (InvIntakeItem i : intakeItems.selectList(w.orderByAsc(InvIntakeItem::getId).last("limit " + STAGE_ITEMS_MAX)))
                    rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, i.getItemNo(), null, null, null, null, null, i.getIntakeNo()));
            } else if (cabinet) {
                var f = CAB_STAGE.get(st);
                if (f == null) return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, 0, List.of());
                LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<DevCabinet>().isNull(DevCabinet::getArchivedAt);
                f.accept(w);
                total = cabinets.selectCount(w);
                for (DevCabinet c : cabinets.selectList(w.orderByAsc(DevCabinet::getId).last("limit " + STAGE_ITEMS_MAX)))
                    rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, c.getCabinetNo(), c.getModel(), c.getWarehouseNo(),
                            c.getIssuedAgentNo() != null ? c.getIssuedAgentNo() : c.getAgentNo(), c.getSiteNo(), c.getLocationNo(), null));
            } else {
                var f = PB_STAGE.get(st);
                if (f == null) return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, 0, List.of());
                LambdaQueryWrapper<DevPowerbank> w = new LambdaQueryWrapper<DevPowerbank>().isNull(DevPowerbank::getArchivedAt);
                f.accept(w);
                total = powerbanks.selectCount(w);
                for (DevPowerbank p : powerbanks.selectList(w.orderByAsc(DevPowerbank::getId).last("limit " + STAGE_ITEMS_MAX)))
                    rows.add(new ai.neargo.sharehub.inv.dto.StockDtos.StageItem(type, p.getPowerbankNo(), p.getVendorCode(), p.getWarehouseNo(),
                            null, null, p.getCabinetNo(), null));
            }
            return new ai.neargo.sharehub.inv.dto.StockDtos.StageItems(st, type, total, rows);
        });
    }

    private long cab(java.util.function.Consumer<LambdaQueryWrapper<DevCabinet>> f) {
        LambdaQueryWrapper<DevCabinet> w = new LambdaQueryWrapper<DevCabinet>().isNull(DevCabinet::getArchivedAt);
        f.accept(w);
        return cabinets.selectCount(w);
    }

    private long pb(java.util.function.Consumer<LambdaQueryWrapper<DevPowerbank>> f) {
        LambdaQueryWrapper<DevPowerbank> w = new LambdaQueryWrapper<DevPowerbank>().isNull(DevPowerbank::getArchivedAt);
        f.accept(w);
        return powerbanks.selectCount(w);
    }

    private long pendingIntake(String itemType) {
        return intakeItems.selectCount(new LambdaQueryWrapper<InvIntakeItem>().eq(InvIntakeItem::getItemType, itemType)
                .eq(InvIntakeItem::getDisposition, "PENDING"));
    }

    // —— 期间报表 ——

    @Override
    public List<StockPeriodRow> periodSummary(LocalDate from, LocalDate to, String warehouseNo) {
        if (from == null || to == null || to.isBefore(from)) throw BizException.badRequest("error.common.invalid_value", "from / to");
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        String wh = warehouseNo == null || warehouseNo.isBlank() ? null : warehouseNo.trim();
        if (service()) {
            InventoryApi api = remote.api();
            return call(() -> api.periodSummary(start, end, wh == null ? null : links.locationId(api, wh))).stream()
                    .map(r -> new StockPeriodRow(links.warehouseNoOf(r.locationId()), links.itemTypeOf(r.itemId()), n(r.opening()),
                            sum(r.inByReason()), sum(r.outByReason()), n(r.closing()), longs(r.inByReason()), longs(r.outByReason())))
                    .toList();
        }
        return localPeriod(start, end, wh);
    }

    /**
     * 本地口径：期末 = 当前在仓数 − 期末之后的净进出；期初 = 期末 − 期内入 + 期内出。
     * 履历自 V196 起才有，更早的期间期初不可信（页面提示）。
     */
    private List<StockPeriodRow> localPeriod(LocalDateTime start, LocalDateTime end, String wh) {
        Map<String, long[]> now = new TreeMap<>();
        DataScopeContext.executeWithoutScope(() -> {
            for (DevCabinet c : cabinets.selectList(new LambdaQueryWrapper<DevCabinet>().select(DevCabinet::getWarehouseNo)
                    .eq(DevCabinet::getStatus, CabinetStatus.IN_STOCK.name()).isNotNull(DevCabinet::getWarehouseNo).isNull(DevCabinet::getArchivedAt))) {
                now.computeIfAbsent(c.getWarehouseNo() + "|CABINET", k -> new long[1])[0]++;
            }
            for (DevPowerbank p : powerbanks.selectList(new LambdaQueryWrapper<DevPowerbank>().select(DevPowerbank::getWarehouseNo)
                    .eq(DevPowerbank::getStatus, PowerbankStatus.IN_STOCK.name()).isNotNull(DevPowerbank::getWarehouseNo).isNull(DevPowerbank::getArchivedAt))) {
                now.computeIfAbsent(p.getWarehouseNo() + "|POWERBANK", k -> new long[1])[0]++;
            }
            return null;
        });
        Map<String, Long> after = new HashMap<>();
        Map<String, Map<String, Long>> in = new TreeMap<>();
        Map<String, Map<String, Long>> out = new TreeMap<>();
        for (DeviceMovement m : movements.warehouseMovesSince(start)) {
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
        return "SERVICE".equalsIgnoreCase(params.textOf("inv.engine", "LOCAL"));
    }

    /** 寻址方式（展示用）：setter 注入，构造参数已经很长。 */
    private ai.neargo.sharehub.inv.remote.InventoryEndpoint endpoint;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEndpoint(ai.neargo.sharehub.inv.remote.InventoryEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @Override
    public InventoryEngine engine() {
        String mode = endpoint == null ? null : endpoint.mode();
        if (!service()) return new InventoryEngine("LOCAL", true, mode, null, null);
        InventoryApi api = remote.api();
        if (!api.configured()) return new InventoryEngine("SERVICE", false, mode, null, null);
        // 现探一次：读仓库列表（最轻的只读接口）。页面每次打开都会问，恢复后自然变回「正常」
        try {
            api.warehouses();
            return new InventoryEngine("SERVICE", true, mode, true, null);
        } catch (InventoryRemoteException e) {
            log.warn("库存服务连通性探测失败 code={} status={}：检查 sharehub.inventory.* 与库存服务健康", e.code, e.status);
            return new InventoryEngine("SERVICE", true, mode, false, e.code);
        }
    }

    // —— 流水 ——

    @Override
    public PageResult<StockLedgerRow> ledger(String warehouseNo, String itemType, String keyword, Integer page, Integer size) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 20 : Math.min(size, 200);
        if (!service()) {
            PageResult<DeviceMovement> r = movements.warehouseMoves(warehouseNo, itemType, keyword, p, s);
            return new PageResult<>(r.getList().stream().map(m -> localRow(m, warehouseNo)).toList(), r.getTotal());
        }
        InventoryApi api = remote.api();
        List<StockLedgerRow> all = call(() -> api.ledger(
                        itemType == null || itemType.isBlank() ? null : links.itemId(api, itemType.trim()),
                        warehouseNo == null || warehouseNo.isBlank() ? null : links.locationId(api, warehouseNo.trim()),
                        keyword == null || keyword.isBlank() ? null : keyword.trim(), null, REMOTE_LEDGER_WINDOW))
                .stream().map(e -> new StockLedgerRow("S" + e.id(), links.warehouseNoOf(e.locationId()), links.itemTypeOf(e.itemId()), null,
                        e.docKind(), e.docNo(), e.qtyDelta() == null ? 0 : e.qtyDelta().intValue(), e.occurredAt(), e.operator()))
                .sorted(Comparator.comparing(StockLedgerRow::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int from = Math.min((p - 1) * s, all.size());
        return new PageResult<>(all.subList(from, Math.min(from + s, all.size())), all.size());
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
        List<StockCount> out = new ArrayList<>();
        for (InventorySyncState r : links.listByPrefix("count:")) {
            if (out.size() >= 50) break;
            String[] v = r.getV().split("\\|", 2);
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
        String no = call(() -> api.openCount(links.locationId(api, wh),
                List.of(links.itemId(api, InventoryLinks.CABINET), links.itemId(api, InventoryLinks.POWERBANK)), key("count")));
        links.put("count:" + no, wh + "|" + LocalDateTime.now());
        log.info("开盘点 countNo={} warehouseNo={}", no, wh);
        return count(no);
    }

    @Override
    public StockCountDetail count(String countNo) {
        requireService();
        InventoryApi.CountView v = call(() -> remote.api().count(countNo));
        String wh = links.warehouseNoOf(v.locationId());
        String reg = links.get("count:" + countNo);
        LocalDateTime opened = reg != null && reg.contains("|") ? LocalDateTime.parse(reg.split("\\|", 2)[1]) : null;
        return new StockCountDetail(new StockCount(v.countNo(), wh, v.status(), opened),
                v.lines() == null ? List.of() : v.lines().stream()
                        .map(l -> new StockCountLine(links.itemTypeOf(l.itemId()), l.bookQty(), l.countedQty(), l.diffQty())).toList(),
                v.exceptions() == null ? List.of() : v.exceptions().stream().map(x -> new StockCountScan(x.serialNo(), x.verdict())).toList(),
                v.missingSerials() == null ? List.of() : v.missingSerials());
    }

    @Override
    public StockCountDetail scan(String countNo, List<String> serialNos) {
        requireService();
        List<String> nos = serialNos == null ? List.of()
                : serialNos.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).distinct().toList();
        if (nos.isEmpty()) throw BizException.badRequest("error.common.missing_parameter", "serialNos");
        call(() -> remote.api().scanCount(countNo, nos, key("scan")));
        return count(countNo);
    }

    @Override
    public StockCountDetail submit(String countNo) {
        return act(countNo, () -> remote.api().submitCount(countNo, key("submit")));
    }

    @Override
    public StockCountDetail approve(String countNo, String note) {
        return act(countNo, () -> remote.api().approveCount(countNo, blankToNull(note), key("approve")));
    }

    @Override
    public StockCountDetail reject(String countNo, String note) {
        if (note == null || note.isBlank()) throw BizException.badRequest("error.common.note_required");
        return act(countNo, () -> remote.api().rejectCount(countNo, note.trim(), key("reject")));
    }

    @Override
    public StockCountDetail post(String countNo) {
        requireService();
        InventoryApi.CountPosted r = call(() -> remote.api().postCount(countNo, key("post")));
        log.info("盘点过账 countNo={} 盘盈入库={} 盘亏出库={}", countNo, r.gainInboundNo(), r.lossOutboundNo());
        return count(countNo);
    }

    @Override
    public StockCountDetail cancel(String countNo) {
        return act(countNo, () -> remote.api().cancelCount(countNo, key("cancel")));
    }

    // —— 内部 ——

    private StockCountDetail act(String countNo, Runnable r) {
        requireService();
        call(() -> {
            r.run();
            return null;
        });
        return count(countNo);
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
