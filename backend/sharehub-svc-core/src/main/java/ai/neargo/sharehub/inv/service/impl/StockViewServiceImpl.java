package ai.neargo.sharehub.inv.service.impl;

import ai.neargo.common.core.PageResult;
import ai.neargo.sharehub.api.platform.port.SysParamPort;
import ai.neargo.sharehub.common.BizException;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceMovement;
import ai.neargo.sharehub.dev.movement.MovementRecorder;
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
import java.util.function.Supplier;

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

    public StockViewServiceImpl(SysParamPort params, MovementRecorder movements, RemoteInventoryPort remote, InventoryLinks links) {
        this.params = params;
        this.movements = movements;
        this.remote = remote;
        this.links = links;
    }

    private boolean service() {
        return "SERVICE".equalsIgnoreCase(params.textOf("inv.engine", "LOCAL"));
    }

    @Override
    public InventoryEngine engine() {
        return service() ? new InventoryEngine("SERVICE", remote.api().configured()) : new InventoryEngine("LOCAL", true);
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

    private static <T> T call(Supplier<T> s) {
        try {
            return s.get();
        } catch (InventoryRemoteException e) {
            throw BizException.conflict("error.inventory.remote", e.code, String.join("；", e.args));
        }
    }

    private static String key(String kind) {
        return kind + "-" + UUID.randomUUID();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
