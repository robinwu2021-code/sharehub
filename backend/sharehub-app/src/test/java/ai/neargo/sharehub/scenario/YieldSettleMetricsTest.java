package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.alarm.engine.AlarmEngine;
import ai.neargo.sharehub.finance.service.SettlementService;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行清单批次 G：低效站点（G1）· 保底补差（G2）· 对账单（G3）· 工单成本（G4）· 运营指标（G5）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YieldSettleMetricsTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlarmEngine engine;
    @Autowired
    SettlementService settlements;

    String admin;
    final List<String> sites = new ArrayList<>();
    final List<String> cabinets = new ArrayList<>();
    final List<String> contracts = new ArrayList<>();
    final List<String> settleNos = new ArrayList<>();
    final List<String> wos = new ArrayList<>();
    final List<String> leads = new ArrayList<>();

    @BeforeAll
    void tokens() {
        admin = login("ADMIN");
    }

    @AfterAll
    void cleanup() {
        for (String s : settleNos) {
            jdbc.update("DELETE FROM stl_settlement_detail WHERE settle_no=?", s);
            jdbc.update("DELETE FROM stl_settlement WHERE settle_no=?", s);
        }
        for (String c : contracts) {
            jdbc.update("DELETE FROM stl_adjustment WHERE contract_no=?", c);
            jdbc.update("DELETE FROM share_record WHERE source_no=?", c);
            jdbc.update("DELETE FROM loc_contract WHERE contract_no=? OR prev_contract_no=?", c, c);
        }
        for (String w : wos) {
            jdbc.update("DELETE FROM wo_handle WHERE wo_no=?", w);
            jdbc.update("DELETE FROM wo_order WHERE wo_no=?", w);
        }
        for (String l : leads) jdbc.update("DELETE FROM loc_lead WHERE lead_no=?", l);
        for (String s : sites) {
            for (String a : jdbc.queryForList("SELECT alarm_no FROM dev_alarm WHERE site_no=?", String.class, s)) {
                for (String t : List.of("dev_alarm_log", "dev_alarm_todo", "dev_alarm_notice")) jdbc.update("DELETE FROM " + t + " WHERE alarm_no=?", a);
                jdbc.update("DELETE FROM dev_alarm WHERE alarm_no=?", a);
            }
            jdbc.update("DELETE FROM dev_alarm_condition WHERE subject_site=?", s);
            jdbc.update("DELETE FROM ord_order WHERE site_no=?", s);
        }
        FixtureCleanup.dropCabinets(jdbc, cabinets);
        for (String s : sites) jdbc.update("DELETE FROM loc_site WHERE site_no=?", s);
    }

    @Test
    @DisplayName("G1 连续两个整月单柜日均 < 5 → 低效站点（BD 待办）；收入正常的、上线不满两个月的不判")
    void lowYield() {
        YearMonth last = YearMonth.now().minusMonths(1), prev = last.minusMonths(1);
        String poor = site(LocalDateTime.now().minusMonths(4)), rich = site(LocalDateTime.now().minusMonths(4)),
                fresh = site(LocalDateTime.now().minusDays(20));
        for (String s : List.of(poor, rich, fresh)) {
            cabinet(s);
            cabinet(s);
        }
        for (YearMonth m : List.of(prev, last)) {
            order(poor, m.atDay(5).atTime(12, 0), "30");
            order(rich, m.atDay(5).atTime(12, 0), "2000");
        }
        engine.tickSites(LocalDateTime.now(), List.of(poor, rich, fresh));
        Map<String, Object> a = jdbc.queryForMap("SELECT disposition_type, status FROM dev_alarm WHERE subject_no=? AND alarm_code='SITE_LOW_YIELD'", poor);
        assertThat(a).containsEntry("status", "OPEN").containsEntry("disposition_type", "TODO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dev_alarm WHERE subject_no IN (?, ?) AND alarm_code='SITE_LOW_YIELD'", Integer.class, rich, fresh))
                .isZero();
    }

    @Test
    @DisplayName("G2 / G3 保底 1000：整月分成 300 → 补差 700；半月生效的另一份保底按天折算 500；出账并入场地方结算单，对账单逐项对得上")
    void guaranteeTopUpAndStatement() {
        String period = "2099-11";
        String s1 = site(LocalDateTime.now().minusYears(1)), s2 = site(LocalDateTime.now().minusYears(1));
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_site WHERE site_no=?", String.class, s1);
        String full = guaranteeContract(s1, "2099-10-01", "2100-12-31");
        String half = guaranteeContract(s2, "2099-11-16", "2100-12-31");
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, payee_type, payee_no, payee_name, amount, gross_amount, rate, currency, mode, status, period, source_no, basis)"
                + " VALUES (?, 'MAIN', ?, 'VENUE', 'VENUE', ?, '测试场地方', 300, 1500, 0.2, 'AED', 'LEDGER', 'PENDING', ?, ?, '')", "SRY" + rnd(), "ORDY" + rnd(), venue, period, full);

        settleNos.addAll(settlements.generate(period, "VENUE"));
        Map<String, Object> topUp = jdbc.queryForMap("SELECT amount, status, settle_no FROM stl_adjustment WHERE contract_no=? AND kind='GUARANTEE_TOPUP' AND period=?", full, period);
        assertThat((BigDecimal) topUp.get("amount")).isEqualByComparingTo("700");
        assertThat(topUp).containsEntry("status", "SETTLED");
        assertThat(jdbc.queryForObject("SELECT amount FROM stl_adjustment WHERE contract_no=? AND kind='GUARANTEE_TOPUP'", BigDecimal.class, half))
                .as("11-16 起生效：15 / 30 天").isEqualByComparingTo("500");
        settleNos.addAll(settlements.generate(period, "VENUE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stl_adjustment WHERE contract_no=? AND kind='GUARANTEE_TOPUP'", Integer.class, full))
                .as("重跑出账不重复补差").isEqualTo(1);

        String settle = (String) topUp.get("settle_no");
        JsonNode st = get("/api/trade/settlements/" + settle + "/statement", admin).okData();
        assertThat(st.path("shareTotal").decimalValue()).isEqualByComparingTo("300");
        assertThat(st.path("adjustments").findValuesAsText("kind")).contains("GUARANTEE_TOPUP");
        assertThat(st.path("total").decimalValue()).isEqualByComparingTo(st.path("shareTotal").decimalValue().add(st.path("adjustTotal").decimalValue()));

        HttpResponse<String> html = raw("/api/trade/settlements/" + settle + "/statement.html?lang=ar");
        assertThat(html.statusCode()).isEqualTo(200);
        assertThat(html.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(html.body()).contains("dir=\"rtl\"").contains("كشف حساب الموقع").contains(settle);
    }

    @Test
    @DisplayName("G4 工单成本：代理运维的单记到代理，员工的单记到站点；汇总按承担方聚合；负数 400")
    void workOrderCost() {
        String site = site(LocalDateTime.now().minusYears(1));
        String agentWo = workOrder(site, "AG-COST-" + rnd(), "AGENT");
        String empWo = workOrder(site, "E-COST", "EMPLOYEE");
        Map<String, Object> body = Map.of("photos", "[\"fix.jpg\"]", "faultReasonCode", "LOCK", "partCost", 30, "laborCost", 50);
        assertThat(post("/api/ops/work-orders/" + agentWo + "/complete",
                Map.of("photos", "[\"fix.jpg\"]", "faultReasonCode", "LOCK", "partCost", -1), admin).status).isEqualTo(400);
        post("/api/ops/work-orders/" + agentWo + "/complete", body, admin).okData();
        post("/api/ops/work-orders/" + empWo + "/complete", body, admin).okData();
        String agent = jdbc.queryForObject("SELECT assignee_name FROM wo_order WHERE wo_no=?", String.class, agentWo);
        assertThat(jdbc.queryForMap("SELECT cost_total, cost_bearer_type, cost_bearer_no FROM wo_order WHERE wo_no=?", agentWo))
                .containsEntry("cost_bearer_type", "AGENT").containsEntry("cost_bearer_no", agent);
        assertThat(jdbc.queryForMap("SELECT cost_bearer_type, cost_bearer_no FROM wo_order WHERE wo_no=?", empWo))
                .containsEntry("cost_bearer_type", "SITE").containsEntry("cost_bearer_no", site);
        JsonNode rows = get("/api/ops/work-orders/costs?bearerType=AGENT", admin).okData();
        assertThat(rows.findValuesAsText("bearerNo")).contains(agent);
    }

    @Test
    @DisplayName("G5 运营指标：历史窗口内 1 份到期主合同已续签 → 续约率 1；1 条商机签约 → 转化率 1；无数据的比率为空")
    void metrics() {
        String site = site(LocalDateTime.now().minusYears(1));
        String old = "CTY" + rnd();
        contracts.add(old);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status, contract_kind)"
                + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, '2000-01-15', '2001-01-15', 'EXPIRED', 'MAIN')", old, site, site);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, site_no, share_rate, start_at, end_at, status, prev_contract_no)"
                + " VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), ?, 0.2, '2001-01-16', '2002-01-15', 'ACTIVE', ?)", "CTY" + rnd(), site, site, old);
        String lead = "LDY" + rnd();
        leads.add(lead);
        jdbc.update("INSERT INTO loc_lead (lead_no, tenant_id, venue_name, stage, created_at) VALUES (?, 'MAIN', '指标测试场地', 'SIGNED', '2001-01-10 10:00:00')", lead);

        JsonNode m = get("/api/ops/ops-flow-metrics?from=2001-01-01&to=2001-02-01", admin).okData();
        assertThat(m.path("contractsEnded").asLong()).isEqualTo(1);
        assertThat(m.path("renewalRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(m.path("expiredNotRenewedRatio").decimalValue()).isEqualByComparingTo("0");
        assertThat(m.path("leadsCreated").asLong()).isEqualTo(1);
        assertThat(m.path("leadConversionRate").decimalValue()).isEqualByComparingTo("1");
        assertThat(m.path("woSlaRate").isNull()).as("窗口内没有工单").isTrue();
    }

    // —— 夹具 ——

    private String site(LocalDateTime firstLive) {
        String no = "STY" + rnd();
        sites.add(no);
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id, first_live_at) VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST', ?)",
                no, venue, "批次G测试站点 " + no, firstLive);
        return no;
    }

    private void cabinet(String site) {
        String cab = "CBY" + rnd();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total, available_count, status,"
                + " online_status, last_heartbeat_at) VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 4, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
    }

    private void order(String site, LocalDateTime at, String amount) {
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, site_no, status, device_type, currency, amount, started_at)"
                + " VALUES (?, 'MAIN', 'CU-Y', 'CAB-Y', ?, 'SETTLED', 'POWERBANK', 'AED', ?, ?)", "ORDY" + rnd(), site, new BigDecimal(amount), at);
    }

    private String guaranteeContract(String site, String start, String end) {
        String no = "CTY" + rnd();
        contracts.add(no);
        jdbc.update("INSERT INTO loc_contract (contract_no, tenant_id, venue_no, venue_name, site_no, share_rate, share_mode, guarantee_amount, settle_period,"
                        + " currency, start_at, end_at, status) VALUES (?, 'MAIN', (SELECT venue_no FROM loc_site WHERE site_no=?), '测试场地方', ?, 0.2,"
                        + " 'GUARANTEE', 1000, 'MONTH', 'AED', ?, ?, 'ACTIVE')",
                no, site, site, start, end);
        return no;
    }

    private String workOrder(String site, String assignee, String type) {
        String no = "WOY" + rnd();
        wos.add(no);
        jdbc.update("INSERT INTO wo_order (wo_no, tenant_id, type, source, priority, status, site_no, assignee_name, assignee_type) VALUES (?, 'MAIN', 'FAULT', 'MANUAL', 'MEDIUM', 'PROCESSING', ?, ?, ?)",
                no, site, assignee, type);
        return no;
    }

    private HttpResponse<String> raw(String path) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("Authorization", "Bearer " + admin).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String rnd() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
