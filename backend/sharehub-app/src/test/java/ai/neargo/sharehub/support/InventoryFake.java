package ai.neargo.sharehub.support;

import ai.neargo.sharehub.inv.remote.InventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 独立进销存的内存替身（S1.2）：按 ai-shop backend/inventory 的契约强制关键规则 ——
 * 序列号入库须 gateRef、出库须在库、出库池状态机（AVAILABLE → CLAIMED / REVOKED / RETURNED）、
 * 只有待领取的能重定向 / 撤回、事件至少一次投递。
 */
public class InventoryFake implements InventoryApi {

    public final Map<String, String> items = new HashMap<>();
    public final Map<String, String> targets = new HashMap<>();
    public final Map<String, Warehouse> warehouses = new LinkedHashMap<>();
    /** 序列号 → 所在 locationId（在库）。 */
    public final Map<String, String> stock = new HashMap<>();
    /** 序列号 → 已用过的 gateRef。 */
    public final Map<String, List<String>> gates = new HashMap<>();
    /** 「locationId|itemId」→ 数量物料在手（物料单据，V210）。 */
    public final Map<String, BigDecimal> qty = new HashMap<>();
    /** 序列号 → itemId（入库时记下，余额按物料汇总用）。 */
    public final Map<String, String> serialItem = new HashMap<>();
    /** 免检物料（gatePolicy = NONE）：入库不要 gateRef。 */
    public final java.util.Set<String> noGate = new java.util.HashSet<>();
    public final Map<String, Doc> docs = new HashMap<>();
    public final Map<String, DispatchView> pool = new LinkedHashMap<>();
    public final Map<String, Claim> claims = new HashMap<>();
    public final List<Event> events = new ArrayList<>();
    public long acked;
    /** 设了就让下一次写操作失败（模拟库存服务拒绝）。 */
    public InventoryRemoteException failNext;
    /** 精确模拟调拨第二步拒绝与已过账但响应丢失。 */
    public String failInboundPurpose;
    public boolean loseNextInboundPostResponse;
    public boolean loseNextOutboundPostResponse;
    private final Map<String, String> documentRequests = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();

    public record Doc(String kind, String locationId, String purpose, List<InboundLine> lines, List<String> serials,
                      Map<String, String> targets, boolean[] posted, String reasonCode, List<OutboundLine> outLines) {
        Doc(String kind, String locationId, String purpose, List<InboundLine> lines, List<String> serials,
            Map<String, String> targets, boolean[] posted) {
            this(kind, locationId, purpose, lines, serials, targets, posted, null, List.of());
        }
    }

    @Override
    public boolean configured() {
        return true;
    }

    private void maybeFail() {
        if (failNext != null) {
            InventoryRemoteException e = failNext;
            failNext = null;
            throw e;
        }
    }

    private static InventoryRemoteException err(int status, String code, String... args) {
        return new InventoryRemoteException(status, code, List.of(args), code, null);
    }

    @Override
    public String upsertItem(String externalRef, ItemSpec spec) {
        String id = items.computeIfAbsent(externalRef, k -> "IT-" + k);
        if ("NONE".equals(spec.gatePolicy())) noGate.add(id);
        return id;
    }

    @Override
    public void upsertTarget(String type, String ref, String name, String parentRef) {
        if ("SITE".equals(type) && parentRef == null) throw err(400, "inventory.bad_request", "target.parent:SITE");
        targets.put(type + ":" + ref, name);
    }

    @Override
    public List<Warehouse> warehouses() {
        return List.copyOf(warehouses.values());
    }

    @Override
    public String createWarehouse(String name, String idempotencyKey) {
        String id = "LOC-" + (warehouses.size() + 1);
        warehouses.put(id, new Warehouse(id, name, null, "ACTIVE"));
        return id;
    }

    @Override
    public String createInbound(String locationId, String purpose, String sourceRef, String remark, List<InboundLine> lines,
                                String idempotencyKey) {
        if (documentRequests.containsKey(idempotencyKey)) return documentRequests.get(idempotencyKey);
        if (purpose.equals(failInboundPurpose)) {
            failInboundPurpose = null;
            throw err(503, "inventory.unreachable");
        }
        maybeFail();
        String no = "IN-" + UUID.randomUUID().toString().substring(0, 8);
        docs.put(no, new Doc("IN", locationId, purpose, lines, List.of(), Map.of(), new boolean[]{false}));
        documentRequests.put(idempotencyKey, no);
        return no;
    }

    @Override
    public void postInbound(String docNo, String idempotencyKey) {
        Doc d = docs.get(docNo);
        if (d.posted()[0]) return;
        for (InboundLine l : d.lines()) {
            if (l.qty() != null && l.qty() <= 0) throw err(400, "inventory.bad_request", "qty");
            for (Serial s : l.serials() == null ? List.<Serial>of() : l.serials()) {
                if (s.gateRef() == null && !noGate.contains(l.itemId())) throw err(400, "inventory.gate.ref_missing", s.serialNo());
                if (s.gateRef() != null && gates.getOrDefault(s.serialNo(), List.of()).contains(s.gateRef()))
                    throw err(409, "inventory.serial.conflict", s.serialNo() + ":gate_reused");
                if (stock.containsKey(s.serialNo())) throw err(409, "inventory.serial.conflict", s.serialNo() + ":in_stock");
            }
        }
        for (InboundLine l : d.lines()) {
            if (l.serials() == null || l.serials().isEmpty()) {
                qty.merge(d.locationId() + "|" + l.itemId(), BigDecimal.valueOf(l.qty()), BigDecimal::add);
                continue;
            }
            for (Serial s : l.serials()) {
                stock.put(s.serialNo(), d.locationId());
                serialItem.put(s.serialNo(), l.itemId());
                if (s.gateRef() != null) gates.computeIfAbsent(s.serialNo(), k -> new ArrayList<>()).add(s.gateRef());
                if ("RETURN".equals(d.purpose())) {
                    pool.replaceAll((id, v) -> v.serialNo().equals(s.serialNo()) && ("AVAILABLE".equals(v.status()) || "CLAIMED".equals(v.status()))
                            ? with(v, "RETURNED") : v);
                }
            }
        }
        d.posted()[0] = true;
        emit("InboundPosted", docNo, "{\"purpose\":\"" + d.purpose() + "\"}");
        if (loseNextInboundPostResponse) {
            loseNextInboundPostResponse = false;
            throw err(503, "inventory.unreachable");
        }
    }

    @Override
    public void businessClearance(String docNo, String idempotencyKey) {
        emit("ReturnCleared", docNo, "{\"released\":1}");
    }

    @Override
    public String createOutbound(String locationId, String purpose, String reasonCode, Map<String, String> t, String remark,
                                 List<OutboundLine> lines, List<String> serialNos, String idempotencyKey) {
        if (documentRequests.containsKey(idempotencyKey)) return documentRequests.get(idempotencyKey);
        maybeFail();
        if (t == null) t = Map.of();
        if ("SCRAP".equals(purpose) && (reasonCode == null || reasonCode.isBlank())) throw err(400, "inventory.bad_request", "reasonCode");
        if ("RETURN_SUPPLIER".equals(purpose) && !t.containsKey("SUPPLIER")) throw err(400, "inventory.bad_request", "targets");
        if ("ISSUE".equals(purpose) && (!t.containsKey("MERCHANT") || !t.containsKey("PERSON"))) {
            throw err(400, "inventory.bad_request", "targets");
        }
        for (var e : t.entrySet()) if (!targets.containsKey(e.getKey() + ":" + e.getValue())) throw err(404, "inventory.not_found", e.getKey());
        String no = "OUT-" + UUID.randomUUID().toString().substring(0, 8);
        docs.put(no, new Doc("OUT", locationId, purpose, List.of(), serialNos == null ? List.of() : serialNos, t, new boolean[]{false},
                reasonCode, lines == null ? List.of() : lines));
        documentRequests.put(idempotencyKey, no);
        return no;
    }

    @Override
    public void postOutbound(String docNo, String idempotencyKey) {
        Doc d = docs.get(docNo);
        if (d.posted()[0]) return;
        for (String s : d.serials()) if (!d.locationId().equals(stock.get(s))) throw err(409, "inventory.serial.conflict", s + ":not_in_stock");
        Map<String, BigDecimal> need = new HashMap<>();
        for (OutboundLine l : d.outLines()) need.merge(d.locationId() + "|" + l.itemId(), BigDecimal.valueOf(l.qty()), BigDecimal::add);
        for (var e : need.entrySet())
            if (qty.getOrDefault(e.getKey(), BigDecimal.ZERO).compareTo(e.getValue()) < 0) throw err(409, "inventory.stock.insufficient", e.getKey());
        need.forEach((k, v) -> qty.merge(k, v.negate(), BigDecimal::add));
        if (!"ISSUE".equals(d.purpose())) {
            d.serials().forEach(stock::remove);
            d.posted()[0] = true;
            if (loseNextOutboundPostResponse) {
                loseNextOutboundPostResponse = false;
                throw err(503, "inventory.unreachable");
            }
            return;
        }
        for (String s : d.serials()) {
            stock.remove(s);
            String id = "DP-" + UUID.randomUUID().toString().substring(0, 8);
            pool.put(id, new DispatchView(id, docNo, items.get("CABINET"), s, BigDecimal.ONE, d.locationId(), "AVAILABLE", null,
                    "MERCHANT", d.targets().get("MERCHANT"), 1));
            emit("DispatchCreated", id, payload(pool.get(id)));
        }
        d.posted()[0] = true;
    }

    @Override
    public List<Balance> balances(String locationId, String itemId) {
        Map<String, BigDecimal> all = new HashMap<>(qty);
        stock.forEach((sn, loc) -> {
            String item = serialItem.get(sn);
            if (item != null) all.merge(loc + "|" + item, BigDecimal.ONE, BigDecimal::add);
        });
        List<Balance> out = new ArrayList<>();
        all.forEach((k, v) -> {
            String[] p = k.split("\\|");
            if ((locationId == null || locationId.equals(p[0])) && (itemId == null || itemId.equals(p[1])))
                out.add(new Balance(p[1], p[0], v, BigDecimal.ZERO, BigDecimal.ZERO, v));
        });
        return out;
    }

    @Override
    public List<InStock> serials(String itemId, String locationId) {
        return stock.entrySet().stream().filter(e -> (locationId == null || locationId.equals(e.getValue()))
                        && (itemId == null || itemId.equals(serialItem.get(e.getKey()))))
                .map(e -> new InStock(e.getKey(), serialItem.get(e.getKey()), e.getValue())).toList();
    }

    @Override
    public List<DispatchView> dispatchesBySerial(String serialNo) {
        return pool.values().stream().filter(v -> serialNo.equals(v.serialNo())).toList();
    }

    @Override
    public Claim claim(List<String> dispatchIds, String businessRef, String idempotencyKey) {
        maybeFail();
        for (String id : dispatchIds) if (!"AVAILABLE".equals(pool.get(id).status())) throw err(409, "inventory.dispatch.not_available", id);
        dispatchIds.forEach(id -> pool.put(id, with(pool.get(id), "CLAIMED")));
        Claim c = new Claim("CL-" + UUID.randomUUID().toString().substring(0, 8), List.copyOf(dispatchIds));
        claims.put(c.claimNo(), c);
        dispatchIds.forEach(id -> emit("DispatchClaimed", id, payload(pool.get(id))));
        return c;
    }

    @Override
    public void releaseClaim(String claimNo, String idempotencyKey) {
        Claim c = claims.remove(claimNo);
        if (c == null) throw err(404, "inventory.not_found", claimNo);
        c.dispatchIds().forEach(id -> {
            if ("CLAIMED".equals(pool.get(id).status())) pool.put(id, with(pool.get(id), "AVAILABLE"));
        });
    }

    @Override
    public void redirect(String dispatchId, Map<String, String> t, String reason, String idempotencyKey) {
        DispatchView v = pool.get(dispatchId);
        if (!"AVAILABLE".equals(v.status())) throw err(409, "inventory.dispatch.not_available", dispatchId);
        pool.put(dispatchId, new DispatchView(v.dispatchId(), v.outboundNo(), v.itemId(), v.serialNo(), v.qty(), v.locationId(), v.status(),
                v.parentDispatchId(), "MERCHANT", t.get("MERCHANT"), v.resourceVersion() + 1));
        emit("DispatchRedirected", dispatchId, payload(pool.get(dispatchId)));
    }

    @Override
    public void revoke(String dispatchId, String reason, String idempotencyKey) {
        DispatchView v = pool.get(dispatchId);
        if (!"AVAILABLE".equals(v.status())) throw err(409, "inventory.dispatch.not_available", dispatchId);
        pool.put(dispatchId, with(v, "REVOKED"));
        stock.put(v.serialNo(), v.locationId());
        emit("DispatchRevoked", dispatchId, payload(pool.get(dispatchId)));
    }

    // —— 流水与盘点 ——

    /** 盘点单：countNo → [locationId, status, 扫到的序列号]。 */
    public final Map<String, Object[]> counts = new LinkedHashMap<>();
    public final List<String> ledgerLocations = new ArrayList<>();

    @Override
    public List<LedgerEntry> ledger(String itemId, String locationId, String docNo, Long afterId, int limit) {
        ledgerLocations.add(locationId);
        List<LedgerEntry> out = new ArrayList<>();
        long id = 0;
        for (Doc d : docs.values()) {
            if (!d.posted()[0] || (locationId != null && !locationId.equals(d.locationId()))) continue;
            int n = "IN".equals(d.kind()) ? d.lines().stream().mapToInt(l -> l.serials().size()).sum() : -d.serials().size();
            out.add(new LedgerEntry(++id, items.get("CABINET"), d.locationId(), d.kind(), "doc", 1, null, BigDecimal.valueOf(n), null,
                    LocalDateTime.now(), "fake"));
        }
        return out;
    }

    @Override
    public String openCount(String locationId, List<String> itemIds, String idempotencyKey) {
        String no = "CNT-" + UUID.randomUUID().toString().substring(0, 8);
        counts.put(no, new Object[]{locationId, "OPEN", new ArrayList<String>()});
        return no;
    }

    @SuppressWarnings("unchecked")
    @Override
    public CountView count(String countNo) {
        Object[] c = counts.get(countNo);
        if (c == null) throw err(404, "inventory.not_found", countNo);
        String loc = (String) c[0];
        List<String> scanned = (List<String>) c[2];
        List<String> book = stock.entrySet().stream().filter(e -> loc.equals(e.getValue())).map(Map.Entry::getKey).toList();
        int counted = (int) scanned.stream().filter(book::contains).count();
        List<String> missing = book.stream().filter(x -> !scanned.contains(x)).toList();
        List<CountScan> extra = scanned.stream().filter(x -> !book.contains(x)).map(x -> new CountScan(x, "NOT_IN_BOOK")).toList();
        return new CountView(countNo, loc, (String) c[1], 1, null, null, null,
                List.of(new CountLine(items.get("CABINET"), "SERIAL", book.size(), counted, counted - book.size())), extra, missing);
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<CountScan> scanCount(String countNo, List<String> serialNos, String idempotencyKey) {
        Object[] c = counts.get(countNo);
        if (!"OPEN".equals(c[1])) throw err(409, "inventory.conflict", countNo);
        ((List<String>) c[2]).addAll(serialNos);
        return serialNos.stream().map(x -> new CountScan(x, "OK")).toList();
    }

    private void move(String countNo, String from, String to) {
        Object[] c = counts.get(countNo);
        if (!from.equals(c[1])) throw err(409, "inventory.conflict", countNo + ":" + c[1]);
        c[1] = to;
    }

    @Override
    public void submitCount(String countNo, String idempotencyKey) {
        move(countNo, "OPEN", "SUBMITTED");
    }

    @Override
    public void approveCount(String countNo, String note, String idempotencyKey) {
        move(countNo, "SUBMITTED", "APPROVED");
    }

    @Override
    public void rejectCount(String countNo, String note, String idempotencyKey) {
        move(countNo, "SUBMITTED", "OPEN");
    }

    @Override
    public CountPosted postCount(String countNo, String idempotencyKey) {
        move(countNo, "APPROVED", "POSTED");
        return new CountPosted(null, null);
    }

    @Override
    public void cancelCount(String countNo, String idempotencyKey) {
        Object[] c = counts.get(countNo);
        if ("POSTED".equals(c[1])) throw err(409, "inventory.conflict", countNo);
        c[1] = "CANCELLED";
    }

    @Override
    public List<PeriodRow> periodSummary(LocalDateTime from, LocalDateTime to, String locationId) {
        Map<String, Long> close = new HashMap<>();
        stock.forEach((sn, loc) -> close.merge(loc, 1L, Long::sum));
        List<PeriodRow> out = new ArrayList<>();
        close.forEach((loc, n) -> {
            if (locationId == null || locationId.equals(loc)) {
                out.add(new PeriodRow(loc, items.get("CABINET"), BigDecimal.ZERO, Map.of("PURCHASE", BigDecimal.valueOf(n)), Map.of(),
                        BigDecimal.valueOf(n)));
            }
        });
        return out;
    }

    @Override
    public EventPage pullEvents(Long afterSeq, int limit) {
        long from = afterSeq == null ? acked : afterSeq;
        List<Event> page = events.stream().filter(e -> e.seq() > from).limit(limit).toList();
        return new EventPage(page, page.isEmpty() ? from : page.get(page.size() - 1).seq(), acked);
    }

    @Override
    public void ackEvents(long s) {
        acked = Math.max(acked, s);
    }

    /** 库存服务侧（不经本系统）发生的事件，测试用来模拟在进销存界面上的操作。 */
    public void emitExternal(String type, String resourceId, String payload) {
        emit(type, resourceId, payload);
    }

    private void emit(String type, String resourceId, String payload) {
        long s = seq.incrementAndGet();
        events.add(new Event(s, "EV-" + UUID.randomUUID(), type, "DISPATCH", resourceId, 1, LocalDateTime.now(), payload));
    }

    private static DispatchView with(DispatchView v, String status) {
        return new DispatchView(v.dispatchId(), v.outboundNo(), v.itemId(), v.serialNo(), v.qty(), v.locationId(), status,
                v.parentDispatchId(), v.rootTargetType(), v.rootTargetRef(), v.resourceVersion() + 1);
    }

    private static String payload(DispatchView v) {
        return "{\"serialNo\":\"" + v.serialNo() + "\",\"status\":\"" + v.status() + "\",\"parentDispatchId\":null,"
                + "\"rootTargetType\":\"MERCHANT\",\"rootTargetRef\":\"" + v.rootTargetRef() + "\"}";
    }
}
