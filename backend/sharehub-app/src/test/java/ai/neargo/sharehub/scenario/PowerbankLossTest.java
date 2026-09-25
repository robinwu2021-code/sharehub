package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.dev.service.PowerbankLossService;
import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 充电宝疑似丢失（V113）：失联满 N 天只打标记、人工核实后确认丢失或解除，**不自动转 LOST**。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PowerbankLossTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager tm;
    @Autowired
    PowerbankLossService loss;

    private String admin;
    private final List<String> created = new ArrayList<>();

    @BeforeAll
    void setUp() {
        admin = login("ADMIN");
    }

    @AfterAll
    void tearDown() {
        for (String no : created) jdbc.update("DELETE FROM dev_powerbank WHERE powerbank_no=?", no);
    }

    @Test
    @DisplayName("① 扫描：失联满 7 天打标记且不刷新 updated_at；已归还的清标记；疑似满 30 天升级且只升级一次；状态始终不变")
    void scan_marks_clears_escalates_without_changing_status() {
        new TransactionTemplate(tm).executeWithoutResult(st -> {
            try {
                String fresh = powerbank("RENTED", 3, null);            // 失联 3 天：还不够
                String old = powerbank("RENTED", 10, null);             // 失联 10 天：该标
                String back = powerbank("IN_CABINET", 1, 5);            // 标过但已回柜：该清
                String stale = powerbank("RENTED", 40, 31);             // 疑似 31 天：该升级
                Timestamp oldUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM dev_powerbank WHERE powerbank_no=?", Timestamp.class, old);

                PowerbankLossService.ScanResult r = loss.scan();
                assertThat(r.marked()).isGreaterThanOrEqualTo(1);

                assertThat(suspected(fresh)).isNull();
                assertThat(suspected(old)).isNotNull();
                // 标记没有刷新 updated_at —— 刷新了的话宝立刻「不再失联」，已有告警跟着自动恢复
                assertThat(jdbc.queryForObject("SELECT updated_at FROM dev_powerbank WHERE powerbank_no=?", Timestamp.class, old))
                        .isEqualTo(oldUpdatedAt);
                assertThat(suspected(back)).isNull();
                assertThat(jdbc.queryForObject("SELECT lost_escalated_at FROM dev_powerbank WHERE powerbank_no=?", Timestamp.class, stale)).isNotNull();

                // 不自动转丢失：被怀疑的宝仍是借出中
                assertThat(status(old)).isEqualTo("RENTED");
                assertThat(status(stale)).isEqualTo("RENTED");

                // 再扫一次：已升级的不会再升级
                Timestamp esc = jdbc.queryForObject("SELECT lost_escalated_at FROM dev_powerbank WHERE powerbank_no=?", Timestamp.class, stale);
                loss.scan();
                assertThat(jdbc.queryForObject("SELECT lost_escalated_at FROM dev_powerbank WHERE powerbank_no=?", Timestamp.class, stale)).isEqualTo(esc);
            } finally {
                st.setRollbackOnly();
            }
        });
    }

    @Test
    @DisplayName("② 确认丢失：疑似中的宝 RENTED → LOST 并清标记；没被怀疑过的 409")
    void confirm_lost_only_for_suspected() {
        String suspect = powerbank("RENTED", 10, 3);
        Resp ok = post("/api/ops/powerbanks/" + suspect + "/confirm-lost", Map.of("note", "现场核实，用户失联"), admin);
        assertThat(ok.status).isEqualTo(200);
        assertThat(ok.body.path("data").path("status").asText()).isEqualTo("LOST");
        assertThat(ok.body.path("data").path("suspectedLostAt").isNull()).isTrue();
        assertThat(suspected(suspect)).isNull();

        String innocent = powerbank("RENTED", 10, null);
        Resp no = post("/api/ops/powerbanks/" + innocent + "/confirm-lost", Map.of("note", "x"), admin);
        assertThat(no.status).isEqualTo(409);
        assertThat(status(innocent)).isEqualTo("RENTED");
    }

    @Test
    @DisplayName("③ 已找回：说明必填；清标记并重置失联计时，状态不变；列表可按疑似筛选")
    void dismiss_requires_note_and_resets_timer() {
        String suspect = powerbank("RENTED", 10, 3);
        Resp listed = get("/api/ops/powerbanks?suspectedLost=true&keyword=" + suspect, admin);
        assertThat(listed.body.path("data").path("list").get(0).path("powerbankNo").asText()).isEqualTo(suspect);

        Resp noNote = post("/api/ops/powerbanks/" + suspect + "/dismiss-lost", Map.of(), admin);
        assertThat(noNote.status).isEqualTo(400);
        assertThat(suspected(suspect)).isNotNull();

        Resp ok = post("/api/ops/powerbanks/" + suspect + "/dismiss-lost", Map.of("note", "在仓库角落找到"), admin);
        assertThat(ok.status).isEqualTo(200);
        assertThat(suspected(suspect)).isNull();
        assertThat(status(suspect)).isEqualTo("RENTED");
        assertThat(jdbc.queryForObject("SELECT updated_at > NOW(3) - INTERVAL 1 MINUTE FROM dev_powerbank WHERE powerbank_no=?", Boolean.class, suspect))
                .as("失联计时从现在重算，否则下一轮扫描立刻又标回疑似").isTrue();
    }

    // ───────────────────────── 夹具 ─────────────────────────

    /** @param suspectedDaysAgo null = 未打标记 */
    private String powerbank(String status, int idleDays, Integer suspectedDaysAgo) {
        String no = "PBL" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        created.add(no);
        jdbc.update("INSERT INTO dev_powerbank (powerbank_no, tenant_id, sn, battery, status, suspected_lost_at, updated_at)"
                        + " VALUES (?, 'MAIN', ?, 80, ?, " + (suspectedDaysAgo == null ? "NULL" : "NOW(3) - INTERVAL " + suspectedDaysAgo + " DAY")
                        + ", NOW(3) - INTERVAL ? DAY)",
                no, no, status, idleDays);
        return no;
    }

    private Timestamp suspected(String no) {
        return jdbc.queryForObject("SELECT suspected_lost_at FROM dev_powerbank WHERE powerbank_no=?", Timestamp.class, no);
    }

    private String status(String no) {
        return jdbc.queryForObject("SELECT status FROM dev_powerbank WHERE powerbank_no=?", String.class, no);
    }
}
