package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.port.DeviceProtectionPort;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEvent;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.DeviceEventBatch;
import ai.neargo.sharehub.dev.dto.DeviceOpsDtos.IngestResult;
import ai.neargo.sharehub.dev.service.DeviceSignalService;
import ai.neargo.sharehub.trade.service.RentOrderService;
import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 机柜状态机 / 上线门禁 / 试借还 / 保护动作 / 停借保还（TDD-运营核心流程/04 §六）。
 * 设备信号经 DeviceSignalService 注入 —— 与网关推送的 /internal/events/device 同一入口。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceLifecycleTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DeviceSignalService signals;
    @Autowired
    DeviceProtectionPort protectionPort;
    @Autowired
    RentOrderService rents;

    String admin;
    final java.util.List<String> cabinets = new java.util.ArrayList<>();

    @AfterAll
    void cleanup() {
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @Test
    @DisplayName("①② 门禁逐项：无合同 / 离线 / 未试借 → 409；心跳 + 试借还 + 生效合同后上线 → DEPLOYED，站点转营业")
    void gateThenGoLive() {
        Fx f = fixture(false);
        JsonNode gate = get("/api/ops/devices/" + f.cabinet + "/go-live-gate", admin).okData();
        assertThat(gate.path("allPassed").asBoolean()).isFalse();
        assertThat(failed(gate)).contains("CONTRACT", "ONLINE", "TRIAL", "SURVEY", "INSTALL_WO", "LOAD")
                .doesNotContain("LOCATION", "SITE_OPEN", "PRICE", "QC");
        assertThat(post("/api/ops/devices/" + f.cabinet + "/go-live", Map.of(), admin).status).isEqualTo(409);

        signal(f.cabinet, "HEARTBEAT", null, null, Map.of());
        String trialNo = post("/api/ops/devices/" + f.cabinet + "/trial-rents", Map.of(), admin).okData().path("trialNo").asText();
        String cmd = jdbc.queryForObject("SELECT eject_command_no FROM dev_trial_rent WHERE trial_no=?", String.class, trialNo);
        signal(f.cabinet, "COMMAND_RESULT", null, null, Map.of("commandNo", cmd, "result", "OK"));
        signal(f.cabinet, "RETURN_SN_SEEN", 2, f.powerbank, Map.of());
        assertThat(jdbc.queryForObject("SELECT status FROM dev_trial_rent WHERE trial_no=?", String.class, trialNo)).isEqualTo("PASSED");

        contract(f.site);
        // 批次 C 新增三项：首台上线须勘测通过 · 装机工单完工 · 在柜宝占仓位 50%–80%（此前 1/8）
        jdbc.update("INSERT INTO loc_site_survey (survey_no, site_no, signal_level, power_ok, result, surveyed_by, surveyed_at)"
                + " VALUES (?, ?, 'GOOD', 1, 'PASS', 'TEST', NOW(3))", "SVD" + rnd(), f.site);
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, status, cabinet_no, site_no) VALUES (?, 'MAIN', 'INSTALL', 'MANUAL', 'DONE', ?, ?)",
                "WOD" + rnd(), f.cabinet, f.site);
        for (int slot = 3; slot <= 6; slot++) {
            String pb = "PBD" + rnd();
            jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status, cabinet_no, slot_index) VALUES (?, 'MAIN', ?, 90, 'IN_CABINET', ?, ?)",
                    pb, pb, f.cabinet, slot);
        }
        assertThat(failed(get("/api/ops/devices/" + f.cabinet + "/go-live-gate", admin).okData())).isEmpty();
        assertThat(post("/api/ops/devices/" + f.cabinet + "/go-live", Map.of(), admin).okData().path("status").asText()).isEqualTo("DEPLOYED");
        assertThat(jdbc.queryForObject("SELECT status FROM loc_site WHERE site_no=?", String.class, f.site)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("V112 词表约束：库层拒绝空串 / 词表外的机柜状态（NOT NULL 拦不住空串，CAB1001 就是这么进来的）")
    void cabinet_status_outside_vocabulary_is_rejected_by_db() {
        Fx f = fixture(false);
        assertThatThrownBy(() -> jdbc.update("UPDATE dev_cabinet SET status='' WHERE cabinet_no=?", f.cabinet()))
                .hasMessageContaining("chk_dev_cabinet_status");
        assertThatThrownBy(() -> jdbc.update("UPDATE dev_cabinet SET status='ONLINE' WHERE cabinet_no=?", f.cabinet()))
                .hasMessageContaining("chk_dev_cabinet_status");
        // 正对照：词表内的值照常可写 —— 否则上面两条红可能只是约束写错了、把什么都拒了
        assertThat(jdbc.update("UPDATE dev_cabinet SET status='IN_TRANSIT' WHERE cabinet_no=?", f.cabinet())).isEqualTo(1);
    }

    @Test
    @DisplayName("③⑨ 编辑带 status 被忽略；已布放换点位 409；撤机后换点位 → 试借还项重新未通过")
    void editIgnoresStatusAndRelocation() {
        Fx f = fixture(true);
        assertThat(post("/api/ops/cabinets/" + f.cabinet, Map.of("status", "RETIRED"), admin).okData().path("status").asText())
                .isEqualTo("DEPLOYED");
        String other = location(f.site);
        assertThat(post("/api/ops/cabinets/" + f.cabinet, Map.of("locationNo", other), admin).status).isEqualTo(409);
        post("/api/ops/devices/" + f.cabinet + "/undeploy", Map.of("reason", "迁机"), admin).okData();
        post("/api/ops/cabinets/" + f.cabinet, Map.of("locationNo", other), admin).okData();
        assertThat(failed(get("/api/ops/devices/" + f.cabinet + "/go-live-gate", admin).okData())).contains("TRIAL");
    }

    @Test
    @DisplayName("⑤ 卡宝：首次自动重弹，10 分钟内再报 → 仓位禁用；SLOT_EJECT_OK 解除")
    void stuckSlot() {
        Fx f = fixture(true);
        signal(f.cabinet, "SLOT_STUCK", 3, null, Map.of());
        assertThat(activeCount(f.cabinet, "SLOT_DISABLE")).isZero();
        signal(f.cabinet, "SLOT_STUCK", 3, null, Map.of());
        assertThat(activeCount(f.cabinet, "SLOT_DISABLE")).isEqualTo(1);
        signal(f.cabinet, "SLOT_EJECT_OK", 3, null, Map.of());
        assertThat(activeCount(f.cabinet, "SLOT_DISABLE")).isZero();
    }

    @Test
    @DisplayName("⑥ 同一仓位被信号与人工同时禁用，信号恢复后仍禁用；人工不能释放信号持有的")
    void refCounting() {
        Fx f = fixture(true);
        signal(f.cabinet, "LOCK_FAIL", 4, null, Map.of());
        JsonNode manual = post("/api/ops/devices/" + f.cabinet + "/protections",
                Map.of("action", "SLOT_DISABLE", "slotIndex", 4, "reason", "现场检查"), admin).okData();
        assertThat(activeCount(f.cabinet, "SLOT_DISABLE")).isEqualTo(2);
        signal(f.cabinet, "LOCK_OK", 4, null, Map.of());
        assertThat(activeCount(f.cabinet, "SLOT_DISABLE")).isEqualTo(1);
        String signalHeld = jdbc.queryForObject("SELECT protection_no FROM dev_protection WHERE cabinet_no=? AND holder_type='SIGNAL' ORDER BY id DESC LIMIT 1",
                String.class, f.cabinet);
        assertThat(post("/api/ops/devices/protections/" + signalHeld + "/release", Map.of("reason", "x"), admin).status).isEqualTo(409);
        post("/api/ops/devices/protections/" + manual.path("protectionNo").asText() + "/release", Map.of("reason", "修好了"), admin).okData();
        assertThat(activeCount(f.cabinet, "SLOT_DISABLE")).isZero();
    }

    @Test
    @DisplayName("⑦⑧ 停借保还：站点暂停 → 借 409；告警整柜停借 → 借 409，释放后放行到下一关")
    void rentGuard() {
        Fx f = fixture(true);
        jdbc.update("UPDATE loc_site SET status='PAUSED' WHERE site_no=?", f.site);
        assertThatThrownBy(() -> rents.rent("CU-TEST", f.cabinet, true, null)).hasMessage("error.rent.site_paused");
        jdbc.update("UPDATE loc_site SET status='ACTIVE' WHERE site_no=?", f.site);

        protectionPort.apply(f.cabinet, null, "STOP_RENT", "ALM-TEST-" + f.cabinet, "离线");
        protectionPort.apply(f.cabinet, null, "STOP_RENT", "ALM-TEST-" + f.cabinet, "离线");   // 幂等
        assertThat(activeCount(f.cabinet, "STOP_RENT")).isEqualTo(1);
        assertThatThrownBy(() -> rents.rent("CU-TEST", f.cabinet, true, null)).hasMessage("error.rent.cabinet_unavailable");
        assertThat(protectionPort.release("ALM-TEST-" + f.cabinet, "恢复")).isEqualTo(1);
        assertThat(activeCount(f.cabinet, "STOP_RENT")).isZero();
    }

    @Test
    @DisplayName("⑩ 未知信号码与重复 eventId 不报错")
    void unknownAndDuplicate() {
        Fx f = fixture(true);
        String id = "E" + rnd();
        DeviceEvent unknown = new DeviceEvent("E" + rnd(), "VENDOR_X_42", f.cabinet, null, null, "ACME", "0x42", LocalDateTime.now(), Map.of());
        DeviceEvent hb = new DeviceEvent(id, "HEARTBEAT", f.cabinet, null, null, "ACME", null, LocalDateTime.now(), Map.of());
        IngestResult r = signals.ingest(new DeviceEventBatch(List.of(unknown, hb, hb)));
        assertThat(r.unknownCode()).isEqualTo(1);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.duplicated()).isEqualTo(1);
    }

    // —— 夹具 ——

    record Fx(String site, String location, String cabinet, String powerbank) {
    }

    /** 站点 + 点位 + 在库机柜（带一块在柜宝，仓 2）；live=true 时直接置为已上线（在线、营业、有合同）。 */
    private Fx fixture(boolean live) {
        String site = "STD" + rnd();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours) VALUES (?, 'MAIN', ?, ?, ?, '10:00-22:00')",
                site, venue, "设备测试站点 " + site, live ? "ACTIVE" : "PREPARING");
        String loc = location(site);
        String cab = "CBD" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, location_no, location_name, site_no,"
                        + " slot_total, available_count, status, online_status, last_heartbeat_at, bound_at) VALUES"
                        + " (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, '测试点位', ?, 8, 1, ?, ?, ?, NOW(3) - INTERVAL 1 HOUR)",
                cab, cab, loc, site, live ? "DEPLOYED" : "IN_STOCK", live ? "ONLINE" : "OFFLINE", live ? LocalDateTime.now().toString() : null);
        String pb = "PBD" + rnd();
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status, cabinet_no, slot_index) VALUES (?, 'MAIN', ?, 90, 'IN_CABINET', ?, 2)",
                pb, pb, cab);
        if (live) contract(site);
        return new Fx(site, loc, cab, pb);
    }

    private String location(String site) {
        String loc = "LCD" + rnd();
        jdbc.update("INSERT INTO loc_location (location_no, tenant_id, site_no, name) VALUES (?, 'MAIN', ?, '测试点位')", loc, site);
        return loc;
    }

    private void contract(String site) {
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_site WHERE site_no=?", String.class, site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status) VALUES (?, 'MAIN', ?, ?, 0.2, ?, ?, 'ACTIVE')",
                "CTD" + rnd(), venue, site, LocalDate.now().minusDays(1).toString(), LocalDate.now().plusYears(1).toString());
    }

    private void signal(String cabinet, String type, Integer slot, String powerbank, Map<String, String> attrs) {
        signals.ingest(new DeviceEventBatch(List.of(new DeviceEvent("E" + rnd(), type, cabinet, slot, powerbank, "TEST", null,
                LocalDateTime.now(), attrs))));
    }

    private int activeCount(String cabinet, String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dev_protection WHERE cabinet_no=? AND action=? AND active=1", Integer.class, cabinet, action);
    }

    private static List<String> failed(JsonNode gate) {
        List<String> out = new java.util.ArrayList<>();
        gate.path("items").forEach(i -> {
            if (!i.path("passed").asBoolean()) out.add(i.path("key").asText());
        });
        return out;
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
