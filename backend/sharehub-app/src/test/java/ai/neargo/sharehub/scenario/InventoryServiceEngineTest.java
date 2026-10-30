package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryEventPuller;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import com.fasterxml.jackson.databind.JsonNode;
import ai.neargo.sharehub.inv.remote.InventoryApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 库存引擎切到独立进销存（S3，inv.engine = SERVICE）：对内存 Fake 跑通 入库 → 出库 → 签收 → 退回 → 事件投影，
 * 并验证库存服务拒绝时本地整笔回滚。引擎开关是运行时参数，同一个 Spring 上下文里切换，结束复原 LOCAL。
 */
class InventoryServiceEngineTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RemoteInventoryPort remote;
    @Autowired
    HttpInventoryApi http;
    @Autowired
    InventoryEventPuller puller;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;
    private InventoryFake fake;

    @BeforeEach
    void serviceEngine() {
        fake = new InventoryFake();
        remote.useApi(fake);
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
    }

    @AfterEach
    void localEngine() {
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        jdbc.update("DELETE FROM inventory_sync_state");
        jdbc.update("DELETE FROM inventory_inbox WHERE event_id LIKE 'EV-%'");
        remote.useApi(http);
    }

    private String[] merchant() {
        String admin = login("ADMIN");
        Map<String, Object> b = new HashMap<>();
        b.put("phone", "+97155" + (1000000 + (int) (Math.abs(System.nanoTime()) % 8999999)));
        b.put("email", "invsvc" + System.nanoTime() + "@example.com");
        b.put("operatorName", "进销存对接商家 " + tag + (++seq));
        b.put("operatorType", "AGENT");
        String applyNo = post("/api/agent/applies", b, login("BD")).okData().path("applyNo").asText();
        post("/api/agent/applies/" + applyNo + "/accept", Map.of(), admin).okData();
        String agentNo = post("/api/agent/applies/" + applyNo + "/audit", Map.of("approve", true), admin).okData().path("operatorNo").asText();
        JsonNode cur = get("/api/agent/agents?page=1&size=5&keyword=" + agentNo, admin).okData().path("list").get(0);
        Map<String, Object> p = new HashMap<>();
        p.put("name", cur.path("name").asText());
        p.put("agentType", "AGENT");
        p.put("status", "ENABLED");
        p.put("address", "Al Barsha");
        p.put("lng", 55.20);
        p.put("lat", 25.11);
        p.put("industry", "HOTEL");
        String site = post("/api/agent/agents/" + agentNo, p, admin).okData().path("defaultSiteNo").asText();
        return new String[]{agentNo, site};
    }

    /** 新柜经到仓待检入库到 WH002：库存服务收到 PURCHASE 入库，gateRef = 质检记录号。 */
    private String stocked() {
        String c = "CABS" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", c);
        String wh = login("WAREHOUSE");
        String no = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "cabinetNos", List.of(c)), wh)
                .okData().path("intake").path("intakeNo").asText();
        post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", Map.of("powerOn", true, "slotsOk", true), wh).okData();
        post("/api/ops/inventory/intakes/" + no + "/inbound", Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c))), wh).okData();
        return c;
    }

    /** 库存服务里含这台设备的入库单。 */
    private List<InventoryFake.Doc> inboundsOf(String serial) {
        return fake.docs.values().stream().filter(d -> "IN".equals(d.kind())
                && d.lines().stream().anyMatch(l -> l.serials() != null && l.serials().stream().anyMatch(x -> serial.equals(x.serialNo())))).toList();
    }

    @Test
    @DisplayName("A07 期初建账入库在库存服务里是 OPENING（原先记成 PURCHASE）")
    void openingIntakeUsesOpeningPurpose() {
        String c = "CABO" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", c);
        post("/api/ops/inventory/intakes", Map.of("kind", "OPENING", "warehouseNo", "WH002", "cabinetNos", List.of(c)), login("WAREHOUSE")).okData();
        assertThat(inboundsOf(c)).singleElement().satisfies(d -> assertThat(d.purpose()).isEqualTo("OPENING"));
        assertThat(fake.stock).containsKey(c);
    }

    @Test
    @DisplayName("★★ A10 入库过账后响应丢失：本地回滚、操作待复核；原样重试续做，库存服务只有一张入库单")
    void inboundLostResponseResumesWithoutDuplicate() {
        String c = "CABL" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", c, "sn", c, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", c);
        String wh = login("WAREHOUSE");
        String no = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "cabinetNos", List.of(c)), wh)
                .okData().path("intake").path("intakeNo").asText();
        post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", Map.of("powerOn", true, "slotsOk", true), wh).okData();
        Map<String, Object> body = Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c)));

        fake.loseNextInboundPostResponse = true;
        assertThat(post("/api/ops/inventory/intakes/" + no + "/inbound", body, wh).status).isEqualTo(409);
        assertThat(fake.stock).as("库存服务其实已过账").containsKey(c);
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).as("本地已回滚").isNull();
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE kind='INTAKE_INBOUND' AND request_key=?", String.class, no + ":" + c))
                .isEqualTo("NEEDS_REVIEW");

        post("/api/ops/inventory/intakes/" + no + "/inbound", body, wh).okData();
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("WH002");
        assertThat(inboundsOf(c)).as("没有多建一张入库单").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE kind='INTAKE_INBOUND' AND request_key=?", String.class, no + ":" + c))
                .isEqualTo("COMPLETED");
    }

    /** 造 n 条流水的库存服务（只覆盖流水），按真实服务的游标规则返回。 */
    private InventoryFake ledgerOf(int n) {
        return new InventoryFake() {
            @Override
            public List<LedgerEntry> ledger(String itemId, String locationId, String docNo, Long afterId, int limit) {
                long from = afterId == null ? 0 : afterId;
                return java.util.stream.LongStream.rangeClosed(from + 1, Math.min(n, from + limit))
                        .mapToObj(i -> new LedgerEntry(i, "ITM", "LOC-X", "IN", "IN-" + i, 1, null, java.math.BigDecimal.ONE, null,
                                java.time.LocalDateTime.now().minusSeconds(n - i), "fake")).toList();
            }
        };
    }

    @Test
    @DisplayName("★ B04 流水按游标读全：1200 条翻得到底，不再只取最近 500；超过上限明确报错、不给半截账")
    void ledgerReadsAllPagesAndRefusesHuge() {
        String admin = login("ADMIN");
        remote.useApi(ledgerOf(1200));
        jdbc.update("REPLACE INTO inventory_sync_state (k, v) VALUES ('wh:WH001', 'LOC-X')");
        JsonNode first = get("/api/ops/inventory/ledger?warehouseNo=WH001&page=1&size=200", admin).okData();
        assertThat(first.path("total").asInt()).isEqualTo(1200);
        assertThat(first.path("list").get(0).path("docNo").asText()).as("最新在前").isEqualTo("IN-1200");
        JsonNode last = get("/api/ops/inventory/ledger?warehouseNo=WH001&page=6&size=200", admin).okData();
        assertThat(last.path("list").get(199).path("docNo").asText()).as("最早的一条也在").isEqualTo("IN-1");

        remote.useApi(ledgerOf(10_600));
        assertThat(get("/api/ops/inventory/ledger?warehouseNo=WH001&page=1&size=20", admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("★ B06 安全库存可用数取库存服务；服务查不到时可用为空、不判低（不当 0 误报）")
    void safetyStockUsesRemoteAndUnknownIsNotLow() {
        stocked();
        String admin = login("ADMIN");
        post("/api/ops/inventory/stock-thresholds", Map.of("warehouseNo", "WH002", "itemType", "CABINET", "minQty", 5), admin).okData();
        try {
            JsonNode row = cabinetRow(get("/api/ops/inventory/stock-thresholds?warehouseNo=WH002", admin).okData());
            assertThat(row.path("available").asInt()).as("库存服务里 WH002 只有这一台").isEqualTo(1);
            assertThat(row.path("low").asBoolean()).isTrue();

            InventoryFake down = new InventoryFake() {
                @Override
                public List<Balance> balances(String locationId, String itemId) {
                    throw new InventoryRemoteException(503, "inventory.unreachable", List.of(), "down", null);
                }
            };
            remote.useApi(down);
            JsonNode unknown = cabinetRow(get("/api/ops/inventory/stock-thresholds?warehouseNo=WH002", admin).okData());
            assertThat(unknown.path("available").isNull()).as("不知道就是不知道").isTrue();
            assertThat(unknown.path("low").asBoolean()).isFalse();
        } finally {
            remote.useApi(fake);
            post("/api/ops/inventory/stock-thresholds", Map.of("warehouseNo", "WH002", "itemType", "CABINET", "minQty", 0), admin).okData();
        }
    }

    @Test
    @DisplayName("★ B01 设备库存余额取库存服务：在手 / 可用按仓 × 类型，下钻到序列号；服务不可用时报错、不给 0")
    void deviceBalancesComeFromService() {
        String c = stocked();
        String admin = login("ADMIN");
        JsonNode row = cabinetRow(get("/api/ops/inventory/balances?warehouseNo=WH002&itemType=CABINET", admin).okData());
        assertThat(row.path("onHand").asLong()).isEqualTo(1);
        assertThat(row.path("available").asLong()).isEqualTo(1);
        JsonNode serials = get("/api/ops/inventory/serials?warehouseNo=WH002&itemType=CABINET", admin).okData();
        assertThat(serials.path("total").asInt()).isEqualTo(1);
        assertThat(serials.path("list").get(0).path("serialNo").asText()).isEqualTo(c);
        assertThat(cabinetRow(get("/api/ops/inventory/balances?warehouseNo=WH003", admin).okData()).path("onHand").asLong())
                .as("没映射过的仓 = 库存服务里没有它的账").isZero();

        remote.useApi(new InventoryFake() {
            @Override
            public List<Balance> balances(String locationId, String itemId) {
                throw new InventoryRemoteException(503, "inventory.unreachable", List.of(), "down", null);
            }
        });
        jdbc.update("REPLACE INTO inventory_sync_state (k, v) VALUES ('item:CABINET', 'IT-CABINET')");
        assertThat(get("/api/ops/inventory/balances?warehouseNo=WH002", admin).status).as("不当 0").isEqualTo(409);
        remote.useApi(fake);
    }

    @Test
    @DisplayName("★ B02 生命周期视图：在仓取库存服务并标来源；库存服务查不到只这一行为空，其余阶段照常")
    void stagesLabelSources() {
        String c = stocked();
        String admin = login("ADMIN");
        JsonNode inStock = stageRow(get("/api/ops/inventory/stages", admin).okData(), "IN_STOCK");
        assertThat(inStock.path("source").asText()).isEqualTo("SERVICE");
        assertThat(inStock.path("cabinets").asLong()).isEqualTo(1);
        assertThat(stageRow(get("/api/ops/inventory/stages", admin).okData(), "LIVE").path("source").asText()).isEqualTo("LOCAL");
        JsonNode drill = get("/api/ops/inventory/stage-items?stage=IN_STOCK&itemType=CABINET", admin).okData();
        assertThat(drill.path("total").asLong()).isEqualTo(1);
        assertThat(drill.path("items").get(0).path("itemNo").asText()).as("下钻与台数同源").isEqualTo(c);

        remote.useApi(new InventoryFake() {
            @Override
            public List<Balance> balances(String locationId, String itemId) {
                throw new InventoryRemoteException(503, "inventory.unreachable", List.of(), "down", null);
            }
        });
        JsonNode stages = get("/api/ops/inventory/stages", admin).okData();
        assertThat(stageRow(stages, "IN_STOCK").path("source").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(stageRow(stages, "IN_STOCK").path("cabinets").isNull()).isTrue();
        assertThat(stageRow(stages, "LIVE").path("cabinets").isNumber()).as("其余阶段照常").isTrue();
        remote.useApi(fake);
    }

    private String topStatus(String c) {
        return fake.dispatchPool(InventoryApi.DispatchFilter.bySerial(c)).stream().filter(v -> v.parentDispatchId() == null)
                .reduce((x, y) -> y).map(InventoryApi.DispatchView::status).orElse(null);
    }

    private long outboundsOf(String c) {
        return fake.docs.values().stream().filter(d -> "OUT".equals(d.kind()) && d.serials().contains(c)).count();
    }

    @Test
    @DisplayName("★★ C02 出库过账后响应丢失：本地回滚；原样重试（同 requestKey）续做，库存服务只有一张出库单")
    void issueLostResponseResumes() {
        String c = stocked();
        String[] m = merchant();
        String admin = login("ADMIN");
        Map<String, Object> body = Map.of("agentNo", m[0], "cabinetNos", List.of(c), "requestKey", "RK-ISSUE-" + tag);
        fake.loseNextOutboundPostResponse = true;
        assertThat(post("/api/ops/devices/issue", body, admin).status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT issued_agent_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isNull();
        post("/api/ops/devices/issue", body, admin).okData();
        assertThat(outboundsOf(c)).isEqualTo(1);
        assertThat(topStatus(c)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT issued_agent_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo(m[0]);
    }

    @Test
    @DisplayName("★★ C04 领取后响应丢失：重试按原请求号查回领取，不重复领取、不漏记领取号")
    void claimLostResponseResumes() {
        String c = stocked();
        String[] m = merchant();
        String admin = login("ADMIN");
        Map<String, Object> body = Map.of("agentNo", m[0], "cabinetNos", List.of(c), "requestKey", "RK-CLAIM-" + tag);
        fake.loseNextClaimResponse = true;
        assertThat(post("/api/ops/devices/issue", body, admin).status).isEqualTo(409);
        assertThat(topStatus(c)).as("库存服务其实已领取").isEqualTo("CLAIMED");
        post("/api/ops/devices/issue", body, admin).okData();
        assertThat(fake.claims).as("只领了一次").hasSize(1);
        assertThat(jdbc.queryForObject("SELECT v FROM inventory_sync_state WHERE k=?", String.class, "claim:" + c))
                .isEqualTo(fake.claims.keySet().iterator().next());
    }

    @Test
    @DisplayName("★ C12 同一领取单两台里调整一台：另一台在库存服务里仍是已领取（释放整单后补领）")
    void redirectKeepsSameClaimPeersClaimed() {
        String a = stocked(), b = stocked();
        String[] m1 = merchant(), m2 = merchant();
        String admin = login("ADMIN");
        post("/api/ops/devices/issue", Map.of("agentNo", m1[0], "cabinetNos", List.of(a, b)), admin).okData();
        assertThat(topStatus(a)).isEqualTo("CLAIMED");
        post("/api/ops/devices/issue-redirect", Map.of("cabinetNos", List.of(a), "agentNo", m2[0], "reason", "发错商家"), admin).okData();
        assertThat(topStatus(b)).as("没有被整单释放").isEqualTo("CLAIMED");
        assertThat(fake.dispatchPool(InventoryApi.DispatchFilter.bySerial(a)).stream().filter(v -> v.parentDispatchId() == null)
                .reduce((x, y) -> y).orElseThrow().rootTargetRef()).isEqualTo(m2[0]);
    }

    @Test
    @DisplayName("★ C13 出库撤销只回原出库仓：选别的仓拒绝；撤销一台不影响同单另一台的领取")
    void revokeOnlyToOriginWarehouse() {
        String a = stocked(), b = stocked();
        String[] m = merchant();
        String admin = login("ADMIN");
        post("/api/ops/devices/issue", Map.of("agentNo", m[0], "cabinetNos", List.of(a, b)), admin).okData();
        Resp other = post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(a), "warehouseNo", "WH003", "reason", "多发了"), admin);
        assertThat(other.status).isEqualTo(409);
        assertThat(other.body.toString()).contains("WH002");
        post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(a), "reason", "多发了"), admin).okData();
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, a)).isEqualTo("WH002");
        assertThat(topStatus(a)).isEqualTo("REVOKED");
        assertThat(fake.stock).containsKey(a);
        assertThat(topStatus(b)).as("同单另一台仍已领取").isEqualTo("CLAIMED");
    }

    private static boolean hasCabinet(JsonNode rows, String c) {
        for (JsonNode r : rows) if (c.equals(r.path("cabinetNo").asText())) return true;
        return false;
    }

    @Test
    @DisplayName("★ C01 可出库候选 = 库存服务在库 ∩ 本地可出库：只在本地在库的不算，质检没放行的不算")
    void issueCandidatesFollowService() {
        String ok = stocked(), failedQc = stocked();
        jdbc.update("UPDATE dev_cabinet SET qc_status='FAILED' WHERE cabinet_no=?", failedQc);
        String localOnly = "CABLO" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", localOnly, "sn", localOnly, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no='WH002', qc_status='PASSED' WHERE cabinet_no=?", localOnly);
        JsonNode rows = get("/api/ops/inventory/issue-candidates?warehouseNo=WH002", login("ADMIN")).okData();
        assertThat(hasCabinet(rows, ok)).isTrue();
        assertThat(hasCabinet(rows, failedQc)).as("质检没放行").isFalse();
        assertThat(hasCabinet(rows, localOnly)).as("库存服务里没有它").isFalse();
    }

    @Test
    @DisplayName("★ C05 待安装候选：库存服务里该商家已领取的 ∩ 本地已签收未回仓；别的商家查不到")
    void installationCandidatesByMerchant() {
        String c = stocked();
        String[] m = merchant(), other = merchant();
        String admin = login("ADMIN");
        post("/api/ops/devices/issue", Map.of("agentNo", m[0], "cabinetNos", List.of(c)), admin).okData();
        JsonNode page = get("/api/ops/inventory/installation-candidates?agentNo=" + m[0], admin).okData();
        assertThat(hasCabinet(page.path("list"), c)).isTrue();
        assertThat(page.path("list").get(0).path("dispatchId").asText()).isNotBlank();
        assertThat(hasCabinet(get("/api/ops/inventory/installation-candidates?agentNo=" + other[0], admin).okData().path("list"), c)).isFalse();
    }

    private static JsonNode stageRow(JsonNode rows, String stage) {
        for (JsonNode r : rows) if (stage.equals(r.path("stage").asText())) return r;
        throw new AssertionError("no stage " + stage);
    }

    private static JsonNode cabinetRow(JsonNode rows) {
        for (JsonNode r : rows) if ("CABINET".equals(r.path("itemType").asText())) return r;
        throw new AssertionError("no CABINET row: " + rows);
    }

    private String pool(String c) {
        return fake.dispatchesBySerial(c).stream().filter(v -> v.parentDispatchId() == null).reduce((a, b) -> b).map(v -> v.status()).orElse(null);
    }

    @Test
    @DisplayName("★★ 入库 → 出库（只选商户，当场领取）→ 退回：库存服务与本地两边一致")
    void inbound_issue_return() {
        String c = stocked();
        assertThat(fake.stock).containsKey(c);
        assertThat(fake.gates.get(c)).hasSize(1).allMatch(g -> g.startsWith("QC"));
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("WH002");

        String[] m = merchant();
        String admin = login("ADMIN");
        post("/api/ops/devices/issue", Map.of("agentNo", m[0], "cabinetNos", List.of(c)), admin).okData();
        assertThat(pool(c)).as("A5：只选商户 = 出库即签收，库存服务侧当场领取").isEqualTo("CLAIMED");
        assertThat(fake.stock).doesNotContainKey(c);
        assertThat(fake.targets).containsKey("MERCHANT:" + m[0]);

        post("/api/ops/devices/pool-return", Map.of("cabinetNos", List.of(c), "reason", "多发了"), admin).okData();
        assertThat(pool(c)).as("退回 = 释放 + 撤回").isEqualTo("REVOKED");
        assertThat(fake.stock).containsKey(c);
        assertThat(jdbc.queryForMap("SELECT issued_agent_no, warehouse_no FROM dev_cabinet WHERE cabinet_no=?", c))
                .containsEntry("issued_agent_no", null);
    }

    @Test
    @DisplayName("★ 库存服务拒绝 → 409 带对方错误码，本地出库池不动")
    void remote_rejection_rolls_back_local() {
        String c = stocked();
        String[] m = merchant();
        fake.failNext = new InventoryRemoteException(409, "inventory.stock.short", List.of(c), "short", null);
        Resp r = post("/api/ops/devices/issue", Map.of("agentNo", m[0], "cabinetNos", List.of(c)), login("ADMIN"));
        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.toString()).contains("inventory.stock.short");
        assertThat(jdbc.queryForMap("SELECT issued_agent_no, warehouse_no FROM dev_cabinet WHERE cabinet_no=?", c))
                .containsEntry("issued_agent_no", null).containsEntry("warehouse_no", "WH002");
        assertThat(jdbc.queryForObject("SELECT phase FROM inv_operation WHERE kind='DEVICE_ISSUE' ORDER BY id DESC LIMIT 1", String.class))
                .as("库存服务明确拒绝 = 没执行，不算待查回").isEqualTo("REJECTED");
    }

    private String transfer(String admin, List<String> cabs) {
        String t = post("/api/ops/inventory-transfers", Map.of("fromType", "WAREHOUSE", "fromRef", "WH002", "toType", "WAREHOUSE",
                "toRef", "WH003", "itemType", "CABINET"), admin).okData().path("transferNo").asText();
        post("/api/ops/inventory-transfers/" + t + "/items", Map.of("itemNos", cabs), admin).okData();
        return t;
    }

    private String loc(String warehouseNo) {
        return jdbc.queryForObject("SELECT v FROM inventory_sync_state WHERE k = ?", String.class, "wh:" + warehouseNo);
    }

    @Test
    @DisplayName("★★ 仓到仓调拨：发货在库存服务调出，签收按实收调入目的仓；少收的留在已调出（与资产差异同口径）")
    void warehouse_transfer_syncs() {
        String a = stocked(), b = stocked();
        String admin = login("ADMIN");
        String t = transfer(admin, List.of(a, b));
        post("/api/ops/inventory-transfers/" + t + "/ship", Map.of(), admin).okData();
        assertThat(fake.stock).doesNotContainKeys(a, b);

        post("/api/ops/inventory-transfers/" + t + "/receive", Map.of("receivedNos", List.of(a)), admin).okData();
        assertThat(fake.stock).containsEntry(a, loc("WH003")).doesNotContainKey(b);
        assertThat(fake.gates.get(a)).contains("XFER-" + t + "-" + a);
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, a)).isEqualTo("WH003");
    }

    @Test
    @DisplayName("★ 调拨：库存服务拒绝调出 → 409，本地仍是草稿、柜子还在调出仓")
    void transfer_rejection_rolls_back() {
        String c = stocked();
        String admin = login("ADMIN");
        String t = transfer(admin, List.of(c));
        fake.failNext = new InventoryRemoteException(409, "inventory.serial.conflict", List.of(c), "conflict", null);
        Resp r = post("/api/ops/inventory-transfers/" + t + "/ship", Map.of(), admin);
        assertThat(r.status).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?", String.class, t)).isEqualTo("DRAFT");
        assertThat(fake.stock).containsEntry(c, loc("WH002"));
    }

    @Test
    @DisplayName("★ 切引擎前发出的调拨：签收不同步（库存服务没有调出记录），不拦签收")
    void transfer_shipped_under_local() {
        String c = stocked();
        String admin = login("ADMIN");
        String t = transfer(admin, List.of(c));
        jdbc.update("UPDATE sys_param SET value='LOCAL' WHERE param_key='inv.engine'");
        post("/api/ops/inventory-transfers/" + t + "/ship", Map.of(), admin).okData();
        jdbc.update("UPDATE sys_param SET value='SERVICE' WHERE param_key='inv.engine'");
        post("/api/ops/inventory-transfers/" + t + "/receive", Map.of("receivedNos", List.of(c)), admin).okData();
        assertThat(fake.stock).as("账由盘点纠正").containsEntry(c, loc("WH002"));
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("WH003");
    }

    @Test
    @DisplayName("★ 事件：库存服务侧重定向拉齐本地出库池归属；重复投递只处理一次")
    void events_project_and_dedupe() {
        String c = stocked();
        String[] a = merchant(), b = merchant();
        post("/api/ops/devices/issue", Map.of("agentNo", a[0], "cabinetNos", List.of(c)), login("ADMIN")).okData();
        puller.pullOnce();   // 吃掉本系统自己触发的事件
        fake.emitExternal("DispatchRedirected", "DP-X", "{\"serialNo\":\"" + c + "\",\"parentDispatchId\":null,\"rootTargetRef\":\"" + b[0] + "\"}");
        InventoryEventPuller.Result r = puller.pullOnce();
        assertThat(r.processed()).as("result=%s events=%s", r, fake.events).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT issued_agent_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo(b[0]);

        // 至少一次投递：游标回退重拉，同一事件不再处理
        jdbc.update("UPDATE inventory_sync_state SET v='0' WHERE k='events:seq'");
        fake.acked = 0;
        InventoryEventPuller.Result again = puller.pullOnce();
        assertThat(again.processed()).isZero();
        assertThat(again.duplicates()).isEqualTo(again.pulled());
    }
}
