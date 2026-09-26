package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.finance.service.SettlementService;
import ai.neargo.sharehub.loc.service.SiteService;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.FixtureCleanup;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行清单批次 C：勘测（C1）· 点位词表（C2）· 入库质检（C3）· 调拨逐件签收（C4）· 装机工单驱动上线 + 装宝比例（C5 / C6）·
 * 撤机清点与回仓 + 撤场自动关闭 + 结算调整项（C7 / C8 / C9）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiteAssetFlowTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SiteService sites;
    @Autowired
    SettlementService settlements;
    @Autowired
    PlatformTransactionManager tm;

    String admin;
    final List<String> siteNos = new ArrayList<>();
    final List<String> cabinets = new ArrayList<>();
    final List<String> powerbanks = new ArrayList<>();
    final List<String> warehouses = new ArrayList<>();
    final List<String> transferNos = new ArrayList<>();
    final List<String> settleNos = new ArrayList<>();

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @AfterAll
    void cleanup() {
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String pb : powerbanks) {
            jdbc.update("DELETE FROM dev_qc_record WHERE item_no=?", pb);
            jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?", pb);
        }
        for (String t : transferNos) {
            jdbc.update("DELETE FROM inv_asset_diff WHERE source_ref=?", t);
            jdbc.update("DELETE FROM inv_transfer_item WHERE transfer_no=?", t);
            jdbc.update("DELETE FROM inv_transfer WHERE transfer_no=?", t);
        }
        for (String s : settleNos) {
            jdbc.update("DELETE FROM stl_settlement_detail WHERE settle_no=?", s);
            jdbc.update("DELETE FROM stl_settlement WHERE settle_no=?", s);
        }
        for (String s : siteNos) {
            jdbc.update("DELETE FROM stl_adjustment WHERE site_no=?", s);
            jdbc.update("DELETE FROM inv_asset_diff WHERE site_no=?", s);
            jdbc.update("DELETE FROM inv_transfer_item WHERE transfer_no IN (SELECT transfer_no FROM inv_transfer WHERE from_ref=?)", s);
            jdbc.update("DELETE FROM inv_transfer WHERE from_ref=?", s);
            jdbc.update("DELETE FROM wo_handle WHERE wo_no IN (SELECT wo_no FROM wo_order WHERE site_no=?)", s);
            jdbc.update("DELETE FROM wo_order WHERE site_no=?", s);
            jdbc.update("DELETE FROM loc_site_survey WHERE site_no=?", s);
            jdbc.update("DELETE FROM loc_location WHERE site_no=?", s);
            for (String c : jdbc.queryForList("SELECT contract_no FROM loc_contract WHERE site_no=?", String.class, s)) {
                jdbc.update("DELETE FROM loc_contract_log WHERE contract_no=?", c);
                jdbc.update("DELETE FROM loc_contract WHERE contract_no=?", c);
            }
            jdbc.update("DELETE FROM loc_site_status_log WHERE site_no=?", s);
            jdbc.update("DELETE FROM loc_site WHERE site_no=?", s);
        }
        for (String w : warehouses) jdbc.update("DELETE FROM inv_warehouse WHERE warehouse_no=?", w);
    }

    @Test
    @DisplayName("C1 / C2 勘测：无信号判通过 400；不通过缺说明 400；最近一次通过开业清单才过；点位状态非法 400")
    void surveyAndLocationStatus() {
        String site = site("PREPARING");
        String url = "/api/ops/sites/" + site + "/surveys";
        assertThat(post(url, Map.of("signalLevel", "NONE", "powerOk", true, "result", "PASS"), admin).status).isEqualTo(400);
        assertThat(post(url, Map.of("signalLevel", "WEAK", "powerOk", true, "result", "FAIL"), admin).status).isEqualTo(400);
        post(url, Map.of("signalLevel", "WEAK", "powerOk", true, "result", "FAIL", "note", "信号弱，换位置再测"), admin).okData();
        assertThat(item(get("/api/ops/sites/" + site + "/opening-checklist", admin).okData(), "SURVEY").path("passed").asBoolean()).isFalse();
        post(url, Map.of("signalLevel", "GOOD", "powerOk", true, "result", "PASS", "placementNote", "入口左侧"), admin).okData();
        assertThat(item(get("/api/ops/sites/" + site + "/opening-checklist", admin).okData(), "SURVEY").path("passed").asBoolean()).isTrue();
        assertThat(get(url, admin).okData().size()).isEqualTo(2);

        assertThat(post("/api/ops/locations", Map.of("name", "非法状态点位", "siteNo", site, "status", "BOGUS"), admin).status).isEqualTo(400);
    }

    @Test
    @DisplayName("C3 入库质检：新建柜 PENDING、门禁 QC 不过；不通过须说明；检查项不过不能判通过；通过后门禁 QC 过；宝按电量 / 循环判")
    void incomingQc() {
        String cab = "CBC" + rnd();
        cabinets.add(cab);
        post("/api/ops/cabinets", Map.of("cabinetNo", cab, "sn", cab, "vendorCode", "TEST", "slotTotal", 8), admin).okData();
        assertThat(jdbc.queryForObject("SELECT qc_status FROM dev_cabinet WHERE cabinet_no=?", String.class, cab)).isEqualTo("PENDING");
        assertThat(item(get("/api/ops/devices/" + cab + "/go-live-gate", admin).okData(), "QC").path("passed").asBoolean()).isFalse();

        String qc = "/api/ops/devices/" + cab + "/qc";
        assertThat(post(qc, Map.of("powerOn", true, "slotsOk", false), admin).status).isEqualTo(400);
        assertThat(post(qc, Map.of("powerOn", true, "slotsOk", false, "result", "PASSED"), admin).status).isEqualTo(400);
        assertThat(post(qc, Map.of("powerOn", true, "slotsOk", false, "note", "3 号仓锁不回弹"), admin).okData().path("result").asText())
                .isEqualTo("FAILED");
        assertThat(post(qc, Map.of("powerOn", true, "slotsOk", true), admin).okData().path("result").asText()).isEqualTo("PASSED");
        assertThat(item(get("/api/ops/devices/" + cab + "/go-live-gate", admin).okData(), "QC").path("passed").asBoolean()).isTrue();
        assertThat(get("/api/ops/qc-records?itemNo=" + cab, admin).okData().size()).isEqualTo(2);

        String pb = powerbank(null, "IN_STOCK");
        assertThat(post("/api/ops/powerbanks/" + pb + "/qc", Map.of("battery", 40, "cycles", 10), admin).status).isEqualTo(400);
        assertThat(post("/api/ops/powerbanks/" + pb + "/qc", Map.of("battery", 90, "cycles", 100), admin).okData().path("result").asText())
                .isEqualTo("PASSED");
        assertThat(jdbc.queryForObject("SELECT qc_status FROM dev_powerbank WHERE powerbank_no=?", String.class, pb)).isEqualTo("PASSED");
    }

    @Test
    @DisplayName("★★ 一件明细都没有的调拨单不能发货——发出去等于凭空产生一次在途")
    void emptyTransferCannotShip() {
        // 实测（2026-09-25 接入）：空单真发出去了。ship() 里那一圈
        // `for (item : list) checkShippable(...)` 对空列表直接空转，
        // 状态照常推到 IN_TRANSIT，于是账上多一笔「运输中 0 件」，
        // 签收时 expected 为空又一路放过 —— 从头到尾没有任何人说过这单里有什么。
        //
        // 前端拦住了，但闸在前端就只保护这一个客户端。
        String w1 = warehouse(), w2 = warehouse();
        Map<String, Object> head = new HashMap<>();
        head.put("fromType", "WAREHOUSE");
        head.put("fromRef", w1);
        head.put("fromName", "一号仓");
        head.put("toType", "WAREHOUSE");
        head.put("toRef", w2);
        head.put("toName", "二号仓");
        head.put("itemType", "CABINET");
        String no = post("/api/ops/inventory-transfers", head, admin).okData().path("transferNo").asText();
        transferNos.add(no);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inv_transfer_item WHERE transfer_no=?", Integer.class, no))
                .as("前提：这单确实一件明细都没有").isZero();

        assertThat(post("/api/ops/inventory-transfers/" + no + "/ship", Map.of(), admin).status)
                .as("专用发货端点要拒").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?", String.class, no))
                .as("被拒之后状态不许动").isEqualTo("DRAFT");

        // **两条发货路径**：保存端点传目标状态也能发货，它走的是另一套代码。
        // 只堵 /ship 等于没堵 —— 这正是「一个动作两个入口」最典型的漏法。
        assertThat(post("/api/ops/inventory-transfers/" + no, Map.of("status", "IN_TRANSIT"), admin).status)
                .as("保存端点这条路同样要拒").isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM inv_transfer WHERE transfer_no=?", String.class, no))
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("C4 调拨：质检未过不能进明细；发货 → 运输中；签收一件缺一件多一件 → 单子照常完成、缺件留在途、差异两条；差异处理一次")
    void transferReceiving() {
        String w1 = warehouse(), w2 = warehouse();
        String a = stockCabinet("PASSED"), b = stockCabinet(null), pending = stockCabinet("PENDING");
        Map<String, Object> head = new HashMap<>();
        head.put("fromType", "WAREHOUSE");
        head.put("fromRef", w1);
        head.put("fromName", "一号仓");
        head.put("toType", "WAREHOUSE");
        head.put("toRef", w2);
        head.put("toName", "二号仓");
        head.put("itemType", "CABINET");
        String no = post("/api/ops/inventory-transfers", head, admin).okData().path("transferNo").asText();
        transferNos.add(no);

        assertThat(post("/api/ops/inventory-transfers/" + no + "/items", Map.of("itemNos", List.of(a, pending)), admin).status).isEqualTo(409);
        post("/api/ops/inventory-transfers/" + no + "/items", Map.of("itemNos", List.of(a, b)), admin).okData();
        post("/api/ops/inventory-transfers/" + no + "/ship", Map.of(), admin).okData();
        assertThat(cabStatus(a)).isEqualTo("IN_TRANSIT");
        assertThat(cabStatus(b)).isEqualTo("IN_TRANSIT");
        assertThat(post("/api/ops/inventory-transfers/" + no + "/items", Map.of("itemNos", List.of(a)), admin).status)
                .as("发出后不能改明细").isEqualTo(409);

        JsonNode r = post("/api/ops/inventory-transfers/" + no + "/receive",
                Map.of("receivedNos", List.of(a, "CAB-NOT-ON-LIST"), "note", "少一台"), admin).okData();
        assertThat(r.path("status").asText()).isEqualTo("DONE");
        assertThat(r.path("missing").get(0).asText()).isEqualTo(b);
        assertThat(r.path("extra").get(0).asText()).isEqualTo("CAB-NOT-ON-LIST");
        assertThat(jdbc.queryForMap("SELECT status, warehouse_no FROM dev_cabinet WHERE cabinet_no=?", a))
                .containsEntry("status", "IN_STOCK").containsEntry("warehouse_no", w2);
        assertThat(cabStatus(b)).as("缺件留在途，等差异查清").isEqualTo("IN_TRANSIT");

        JsonNode diffs = get("/api/ops/asset-diffs?sourceRef=" + no, admin).okData().path("list");
        assertThat(diffs.findValuesAsText("kind")).containsExactlyInAnyOrder("MISSING", "EXTRA");
        String diffNo = diffs.get(0).path("diffNo").asText();
        assertThat(post("/api/ops/asset-diffs/" + diffNo + "/resolve", Map.of(), admin).status).isEqualTo(400);
        assertThat(post("/api/ops/asset-diffs/" + diffNo + "/resolve", Map.of("note", "司机漏装，已补发"), admin).okData().path("status").asText())
                .isEqualTo("RESOLVED");
        assertThat(post("/api/ops/asset-diffs/" + diffNo + "/resolve", Map.of("note", "再来"), admin).status).isEqualTo(409);
    }

    @Test
    @DisplayName("C5 / C6 装机工单完工 → 门禁全过自动上线、站点转营业；装满的柜子门禁装宝比例不过")
    void installDrivesGoLive() {
        String site = site("PREPARING");
        contract(site, null, null);
        jdbc.update("INSERT INTO loc_site_survey (survey_no, site_no, signal_level, power_ok, result, surveyed_by, surveyed_at)"
                + " VALUES (?, ?, 'GOOD', 1, 'PASS', 'TEST', NOW(3))", "SVC" + rnd(), site);
        String loc = location(site);
        String cab = liveReadyCabinet(site, loc, 8, 5);
        String wo = "WOC" + rnd();
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, cabinet_no, site_no) VALUES (?, 'MAIN', 'INSTALL', 'MANUAL', 'PROCESSING', ?, ?)",
                wo, cab, site);
        JsonNode gate = get("/api/ops/devices/" + cab + "/go-live-gate", admin).okData();
        assertThat(failed(gate)).containsExactly("INSTALL_WO");

        post("/api/ops/work-orders/" + wo + "/complete", Map.of("photos", "[\"install.jpg\"]", "locationNo", loc), admin).okData();
        assertThat(cabStatus(cab)).as("装机完工、门禁全过 → 自动上线").isEqualTo("DEPLOYED");
        assertThat(jdbc.queryForObject("SELECT status FROM loc_site WHERE site_no=?", String.class, site)).isEqualTo("ACTIVE");

        String full = liveReadyCabinet(site, location(site), 4, 4);
        JsonNode load = item(get("/api/ops/devices/" + full + "/go-live-gate", admin).okData(), "LOAD");
        assertThat(load.path("passed").asBoolean()).isFalse();
        assertThat(load.path("detail").asText()).contains("100%");
    }

    @Test
    @DisplayName("C7 / C8 / C9 撤场：提前期不足 400；撤机须清点、不符落差异、柜子回库、生成回仓单；门禁全过自动关闭；关闭生成押金 / 进场费调整项并入出账")
    void withdrawCloseAndSettle() {
        String w = warehouse();
        // 回仓默认取第一个仓；本类别的用例也建仓，这里显式配成本用例的仓（结束时复原）
        jdbc.update("UPDATE sys_param SET value=? WHERE param_key='inv.return_warehouse'", w);
        try {
            withdrawCloseAndSettle(w);
        } finally {
            jdbc.update("UPDATE sys_param SET value='' WHERE param_key='inv.return_warehouse'");
        }
    }

    private void withdrawCloseAndSettle(String w) {
        String site = site("ACTIVE");
        LocalDate start = LocalDate.now().minusDays(100), end = LocalDate.now().plusDays(265);
        contract(site, start, end);
        String cab = "CBC" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                + " online_status) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 3, 'DEPLOYED', 'ONLINE')", cab, cab, site);
        for (int i = 0; i < 3; i++) powerbank(cab, "IN_CABINET");

        String url = "/api/ops/sites/" + site + "/withdraw";
        assertThat(post(url, Map.of("reason", "不续约", "plannedAt", LocalDate.now().plusDays(3).toString()), admin).status).isEqualTo(400);
        post(url, Map.of("reason", "不续约", "plannedAt", LocalDate.now().plusDays(10).toString()), admin).okData();
        String wo = jdbc.queryForObject("SELECT wo_no FROM wo_order WHERE site_no=? AND cabinet_no=? AND type='REMOVE'", String.class, site, cab);
        jdbc.update("UPDATE wo_order SET status='PROCESSING' WHERE wo_no=?", wo);

        String done = "/api/ops/work-orders/" + wo + "/complete";
        assertThat(post(done, Map.of("photos", "[\"remove.jpg\"]"), admin).status).as("撤机须清点").isEqualTo(400);
        post(done, Map.of("photos", "[\"remove.jpg\"]", "countedQty", 2), admin).okData();

        assertThat(jdbc.queryForMap("SELECT kind, expected_qty, actual_qty FROM inv_asset_diff WHERE source_type='REMOVAL' AND source_ref=?", wo))
                .containsEntry("kind", "COUNT_MISMATCH").containsEntry("expected_qty", 3).containsEntry("actual_qty", 2);
        assertThat(jdbc.queryForMap("SELECT status, site_no FROM dev_cabinet WHERE cabinet_no=?", cab))
                .containsEntry("status", "IN_STOCK").containsEntry("site_no", null);
        Map<String, Object> back = jdbc.queryForMap("SELECT transfer_no, status, to_ref FROM inv_transfer WHERE source_type='REMOVAL' AND source_ref=?", wo);
        assertThat(back).containsEntry("status", "DRAFT").containsEntry("to_ref", w);
        transferNos.add((String) back.get("transfer_no"));

        // 待验收的撤机单仍算未结工单 → 验收关单后，定时任务按门禁自动关闭（回滚事务里跑：全局扫描）
        assertThat(item(get("/api/ops/sites/" + site + "/close-gate", admin).okData(), "NO_OPEN_WO").path("passed").asBoolean()).isFalse();
        jdbc.update("UPDATE wo_order SET status='CLOSED' WHERE wo_no=?", wo);
        new TransactionTemplate(tm).executeWithoutResult(st -> {
            assertThat(sites.tick().autoClosed()).isGreaterThanOrEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM loc_site WHERE site_no=?", String.class, site)).isEqualTo("CLOSED");
            st.setRollbackOnly();
        });

        // 人工关闭（提交）→ 站点已关闭事件 → 结算调整项
        post("/api/ops/sites/" + site + "/close", Map.of("note", "撤场完成"), admin).okData();
        Map<String, BigDecimal> adj = new HashMap<>();
        jdbc.queryForList("SELECT kind, amount FROM stl_adjustment WHERE site_no=? AND status='PENDING'", site)
                .forEach(m -> adj.put((String) m.get("kind"), (BigDecimal) m.get("amount")));
        assertThat(adj.get("DEPOSIT_REFUND")).isEqualByComparingTo("-500");
        assertThat(adj.get("ENTRY_FEE_SETTLE")).as("3650 × 265 / 366").isEqualByComparingTo("-2642.76");

        String fee = jdbc.queryForObject("SELECT adj_no FROM stl_adjustment WHERE site_no=? AND kind='ENTRY_FEE_SETTLE'", String.class, site);
        String dep = jdbc.queryForObject("SELECT adj_no FROM stl_adjustment WHERE site_no=? AND kind='DEPOSIT_REFUND'", String.class, site);
        assertThat(post("/api/ops/settlement-adjustments/" + fee + "/confirm", Map.of("amount", -2000), admin).status)
                .as("改了建议值要写说明").isEqualTo(400);
        post("/api/ops/settlement-adjustments/" + fee + "/confirm", Map.of("amount", -2000, "note", "场地方按月结，折算到整月"), admin).okData();
        post("/api/ops/settlement-adjustments/" + dep + "/confirm", Map.of(), admin).okData();

        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_site WHERE site_no=?", String.class, site);
        List<String> created = settlements.generate("2099-12", "VENUE", null);
        settleNos.addAll(created);
        String settle = jdbc.queryForObject("SELECT settle_no FROM stl_settlement WHERE payee_no=? AND period='2099-12'", String.class, venue);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_settlement_detail WHERE settle_no=? AND ref_type='ADJUST'", Integer.class, settle))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_adjustment WHERE site_no=? AND status='SETTLED' AND settle_no=?",
                Integer.class, site, settle)).isEqualTo(2);
    }

    // —— 夹具 ——

    private String site(String status) {
        String no = "STC" + rnd();
        siteNos.add(no);
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id) VALUES (?, 'MAIN', ?, ?, ?, '00:00-23:59', 'R-TEST')",
                no, venue, "批次C测试站点 " + no, status);
        return no;
    }

    private void contract(String site, LocalDate start, LocalDate end) {
        LocalDate s = start == null ? LocalDate.now().minusDays(1) : start;
        LocalDate e = end == null ? LocalDate.now().plusYears(1) : end;
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, venue_name, share_rate, entry_fee, deposit_amount, start_at, end_at, status)"
                        + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, '测试场地方', 0.2, 3650, 500, ?, ?, 'ACTIVE')",
                "CTC" + rnd(), site, site, s.toString(), e.toString());
    }

    private String location(String site) {
        String loc = "LCC" + rnd();
        jdbc.update("INSERT INTO loc_location (location_no, tenant_id, site_no, name) VALUES (?, 'MAIN', ?, '测试点位')", loc, site);
        return loc;
    }

    /** 在库、已绑点位、在线、试借还已过（晚于绑定）的柜子，带 {@code pbs} 颗在柜宝。 */
    private String liveReadyCabinet(String site, String loc, int slots, int pbs) {
        String cab = "CBC" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, location_no, location_name, site_no, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at, bound_at, trial_passed_at) VALUES"
                + " (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, '测试点位', ?, ?, ?, 'IN_STOCK', 'ONLINE', NOW(3), NOW(3) - INTERVAL 1 HOUR, NOW(3))",
                cab, cab, loc, site, slots, pbs);
        for (int i = 0; i < pbs; i++) powerbank(cab, "IN_CABINET");
        return cab;
    }

    private String stockCabinet(String qc) {
        String cab = "CBC" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, slot_total, status, online_status, qc_status)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', 8, 'IN_STOCK', 'OFFLINE', ?)", cab, cab, qc);
        return cab;
    }

    private String powerbank(String cab, String status) {
        String pb = "PBC" + rnd();
        powerbanks.add(pb);
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status, cabinet_no) VALUES (?, 'MAIN', ?, 90, ?, ?)",
                pb, pb, status, cab);
        return pb;
    }

    private String warehouse() {
        String no = "WHC" + rnd();
        warehouses.add(no);
        jdbc.update("INSERT INTO inv_warehouse (warehouse_no, tenant_id, name) VALUES (?, 'MAIN', ?)", no, "测试仓 " + no);
        return no;
    }

    private String cabStatus(String cab) {
        return jdbc.queryForObject("SELECT status FROM dev_cabinet WHERE cabinet_no=?", String.class, cab);
    }

    private static JsonNode item(JsonNode checklist, String key) {
        for (JsonNode i : checklist.path("items")) if (key.equals(i.path("key").asText())) return i;
        throw new AssertionError("清单里没有 " + key + "：" + checklist);
    }

    private static List<String> failed(JsonNode gate) {
        List<String> out = new ArrayList<>();
        gate.path("items").forEach(i -> {
            if (!i.path("passed").asBoolean()) out.add(i.path("key").asText());
        });
        return out;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
