package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.remote.HttpInventoryApi;
import ai.neargo.sharehub.inv.remote.InventoryEventPuller;
import ai.neargo.sharehub.inv.remote.InventoryRemoteException;
import ai.neargo.sharehub.inv.remote.RemoteInventoryPort;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.InventoryFake;
import com.fasterxml.jackson.databind.JsonNode;
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
