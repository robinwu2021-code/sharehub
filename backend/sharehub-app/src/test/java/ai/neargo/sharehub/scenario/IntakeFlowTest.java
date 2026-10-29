package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.inv.service.TransferOpsService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到仓待检（V192）：到货登记 → 质检 → 合格入库 / 不合格处置。
 * 本地模式下「入库」= 设备所在仓写成待检单的仓（独立进销存上线前）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntakeFlowTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransferOpsService transferOps;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private int seq;
    private final List<String> cabinets = new ArrayList<>();
    private final List<String> intakeNos = new ArrayList<>();
    private final List<String> removalWos = new ArrayList<>();

    @AfterAll
    void cleanup() {
        for (String wo : removalWos) {
            jdbc.update("DELETE FROM inv_transfer_item WHERE transfer_no IN (SELECT transfer_no FROM inv_transfer WHERE source_type='REMOVAL' AND source_ref=?)", wo);
            jdbc.update("DELETE FROM inv_transfer WHERE source_type='REMOVAL' AND source_ref=?", wo);
            for (String no : jdbc.queryForList("SELECT intake_no FROM inv_intake WHERE source_ref=?", String.class, wo)) intakeNos.add(no);
        }
        for (String no : intakeNos) {
            jdbc.update("DELETE FROM inv_intake_item WHERE intake_no=?", no);
            jdbc.update("DELETE FROM inv_intake WHERE intake_no=?", no);
        }
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    /** 新到货的柜：在库、待质检、不在任何仓。 */
    private String newCabinet() {
        String no = "CABI" + tag + (++seq);
        post("/api/ops/cabinets", Map.of("cabinetNo", no, "sn", no, "vendorCode", "TEST", "slotTotal", 8), login("ADMIN")).okData();
        jdbc.update("UPDATE dev_cabinet SET warehouse_no = NULL WHERE cabinet_no = ?", no);
        cabinets.add(no);
        return no;
    }

    private JsonNode create(String token, List<String> cabinetNos) {
        JsonNode d = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "sourceRef", "DN-" + tag, "cabinetNos", cabinetNos), token).okData();
        intakeNos.add(d.path("intake").path("intakeNo").asText());
        return d;
    }

    /** 判不合格必须写原因（QcServiceImpl.decide）。 */
    private Map<String, Object> qc(boolean pass) {
        return pass ? Map.of("powerOn", true, "slotsOk", true) : Map.of("powerOn", false, "slotsOk", true, "note", "通电失败");
    }

    @Test
    @DisplayName("★ 登记 → 质检合格 → 入库：所在仓写成待检单的仓，单据完成")
    void arrival_qc_inbound() {
        String token = login("WAREHOUSE");
        String c = newCabinet();
        JsonNode d = create(token, List.of(c));
        String no = d.path("intake").path("intakeNo").asText();
        assertThat(d.path("intake").path("status").asText()).isEqualTo("OPEN");
        assertThat(d.path("items").get(0).path("qcResult").asText()).isEqualTo("PENDING");

        JsonNode after = post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", qc(true), token).okData();
        assertThat(after.path("items").get(0).path("qcResult").asText()).isEqualTo("PASSED");
        assertThat(after.path("items").get(0).path("qcNo").asText()).as("质检记录号 = 放行凭证").isNotBlank();

        JsonNode done = post("/api/ops/inventory/intakes/" + no + "/inbound",
                Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c))), token).okData();
        assertThat(done.path("intake").path("status").asText()).isEqualTo("DONE");
        assertThat(done.path("items").get(0).path("disposition").asText()).isEqualTo("INBOUND");
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo("WH002");
    }

    @Test
    @DisplayName("★ 不合格不能入库、合格不能处置；处置说明必填")
    void qc_gates_inbound_and_dispose() {
        String token = login("WAREHOUSE");
        String bad = newCabinet();
        String good = newCabinet();
        String no = create(token, List.of(bad, good)).path("intake").path("intakeNo").asText();
        post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + bad + "/qc", qc(false), token).okData();
        post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + good + "/qc", qc(true), token).okData();

        assertThat(post("/api/ops/inventory/intakes/" + no + "/inbound",
                Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", bad))), token).status).isEqualTo(409);
        assertThat(post("/api/ops/inventory/intakes/" + no + "/dispose",
                Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", good)), "disposition", "SCRAP", "note", "x"), token).status)
                .isEqualTo(409);
        assertThat(post("/api/ops/inventory/intakes/" + no + "/dispose",
                Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", bad)), "disposition", "SCRAP"), token).status)
                .as("说明必填").isEqualTo(400);

        JsonNode d = post("/api/ops/inventory/intakes/" + no + "/dispose",
                Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", bad)), "disposition", "RETURN_SUPPLIER", "note", "通电失败"), token).okData();
        assertThat(d.path("intake").path("status").asText()).as("还有一台合格未入库").isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, bad)).isNull();
    }

    @Test
    @DisplayName("★ 撤机回仓：签收不直接进仓 —— 离开出库池、质检打回待检、生成回仓待检单；复检合格才入库")
    void removal_return_goes_through_intake() {
        String admin = login("ADMIN");
        String c = newCabinet();
        // 撤机后的柜：在库、已解绑、旧质检是合格、仍挂在商家出库池（撤机监听器的 undeploy 之后就是这个样子）
        jdbc.update("UPDATE dev_cabinet SET qc_status='PASSED', issued_agent_no='AG-TEST', issued_at=NOW() WHERE cabinet_no=?", c);
        String wo = "WO-RM-" + tag;
        removalWos.add(wo);
        String transferNo = transferOps.openReturn("ST-TEST-" + tag, "测试站点", c, wo);
        String wh = jdbc.queryForObject("SELECT to_ref FROM inv_transfer WHERE transfer_no=?", String.class, transferNo);

        post("/api/ops/inventory-transfers/" + transferNo + "/ship", Map.of(), admin).okData();
        post("/api/ops/inventory-transfers/" + transferNo + "/receive", Map.of("receivedNos", List.of(c)), admin).okData();

        assertThat(jdbc.queryForMap("SELECT status, warehouse_no, issued_agent_no, qc_status FROM dev_cabinet WHERE cabinet_no=?", c))
                .containsEntry("status", "IN_STOCK").containsEntry("warehouse_no", null)
                .containsEntry("issued_agent_no", null).containsEntry("qc_status", "PENDING");
        Map<String, Object> ik = jdbc.queryForMap("SELECT intake_no, kind, warehouse_no, status FROM inv_intake WHERE source_ref=?", wo);
        assertThat(ik).containsEntry("kind", "RETURN").containsEntry("warehouse_no", wh).containsEntry("status", "OPEN");
        String no = (String) ik.get("intake_no");
        intakeNos.add(no);

        String token = login("WAREHOUSE");
        JsonNode after = post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", qc(true), token).okData();
        assertThat(after.path("items").get(0).path("qcNo").asText()).as("复检新开一条质检记录").isNotBlank();
        post("/api/ops/inventory/intakes/" + no + "/inbound", Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c))), token).okData();
        assertThat(jdbc.queryForObject("SELECT warehouse_no FROM dev_cabinet WHERE cabinet_no=?", String.class, c)).isEqualTo(wh);
    }

    @Test
    @DisplayName("★ 放行凭证：只认本单上新做的通过（旧的通过不能复用）；机柜质检记下判定规则快照")
    void voucher_must_be_fresh_and_single_use() {
        String token = login("WAREHOUSE");
        String c = newCabinet();
        String no = create(token, List.of(c)).path("intake").path("intakeNo").asText();
        // 本单上检过一次通过 → 凭证挂单号、带规则快照
        JsonNode d = post("/api/ops/inventory/intakes/" + no + "/items/CABINET/" + c + "/qc", qc(true), token).okData();
        String fresh = d.path("items").get(0).path("qcNo").asText();
        assertThat(jdbc.queryForMap("SELECT intake_no, rule_snapshot, consumed_by FROM dev_qc_record WHERE qc_no=?", fresh))
                .containsEntry("intake_no", no).containsEntry("consumed_by", null)
                .extractingByKey("rule_snapshot").asString().contains("powerOn");
        // 把明细上的凭证换成一条不属于本单的旧通过（模拟回仓设备拿旧凭证入库）→ 拒
        String old = "QCOLD" + tag;
        jdbc.update("INSERT INTO dev_qc_record (qc_no, tenant_id, item_type, item_no, power_on, slots_ok, result, inspected_by, inspected_at)"
                + " VALUES (?, 'MAIN', 'CABINET', ?, 1, 1, 'PASSED', 'old', NOW(3))", old, c);
        jdbc.update("UPDATE inv_intake_item SET qc_no=? WHERE intake_no=? AND item_no=?", old, no, c);
        Map<String, Object> body = Map.of("items", List.of(Map.of("itemType", "CABINET", "itemNo", c)));
        assertThat(post("/api/ops/inventory/intakes/" + no + "/inbound", body, token).status).isEqualTo(409);
        // 换回本单的凭证 → 入库成功，凭证被用掉
        jdbc.update("UPDATE inv_intake_item SET qc_no=? WHERE intake_no=? AND item_no=?", fresh, no, c);
        post("/api/ops/inventory/intakes/" + no + "/inbound", body, token).okData();
        assertThat(jdbc.queryForObject("SELECT consumed_by FROM dev_qc_record WHERE qc_no=?", String.class, fresh)).isEqualTo(no);
        jdbc.update("DELETE FROM dev_qc_record WHERE qc_no=?", old);
    }

    @Test
    @DisplayName("登记拦截：一台设备不能同时在两张未处理的待检单上；原因一次列全")
    void duplicate_open_intake_is_refused() {
        String token = login("WAREHOUSE");
        String c = newCabinet();
        create(token, List.of(c));
        Resp r = post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "cabinetNos", List.of(c, "CAB-NOPE-" + tag)), token);
        assertThat(r.status).isEqualTo(409);
        assertThat(r.body.toString()).contains(c).contains("CAB-NOPE-" + tag);
    }

    @Test
    @DisplayName("权限：只读能查不能登记；仓管能登记")
    void permissions() {
        assertThat(get("/api/ops/inventory/intakes?page=1&size=5", login("VIEWER")).status).isEqualTo(200);
        assertThat(post("/api/ops/inventory/intakes", Map.of("warehouseNo", "WH002", "cabinetNos", List.of(newCabinet())), login("VIEWER")).status)
                .isEqualTo(403);
    }
}
