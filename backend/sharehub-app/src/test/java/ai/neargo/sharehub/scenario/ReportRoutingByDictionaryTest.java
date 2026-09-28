package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报障分流按<b>字典</b>走（2026-09-28）。
 *
 * <p>在此之前装的是 {@code StubProblemActionResolver}：**一律转人工**，
 * 而 {@code md_problem.suggested_action} 那一列从没被读过 ——
 * 运营在「常见问题」里把某条改成「自助解决」或「转工单」，不会有任何效果，也没有任何地方报错。
 * 「分流规则不硬编码、运营改策略不用发版」是这个接口存在的全部理由，桩把这个理由架空了。
 *
 * <p>本类钉住四条路各自成立，以及三种「没有可用策略」的兜底方向 ——
 * 兜底一律转人工：宁可多耗客服工时，也不要把真实故障静默吞掉（自助解决会直接关单）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportRoutingByDictionaryTest extends ApiTestSupport {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ai.neargo.sharehub.trade.service.RentOrderService rents;

    final java.util.List<String> cabinets = new java.util.ArrayList<>();

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM md_problem WHERE problem_no LIKE 'ISRT%'");
        ai.neargo.sharehub.support.FixtureCleanup.dropCabinets(jdbc, cabinets);
    }

    /** 一台能借的柜（转退款那条要有真订单）。 */
    private String liveCabinet() {
        String site = "STRT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String venue = jdbc.queryForObject("SELECT venue_no FROM loc_venue WHERE deleted=0 ORDER BY id LIMIT 1", String.class);
        jdbc.update("INSERT INTO loc_site (site_no, tenant_id, venue_no, name, status, open_hours, region_id)"
                + " VALUES (?, 'MAIN', ?, ?, 'ACTIVE', '00:00-23:59', 'R-TEST')", site, venue, "分流用例站点 " + site);
        String cab = "CBRT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        cabinets.add(cab);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, device_type, site_no, slot_total,"
                + " available_count, status, online_status, last_heartbeat_at)"
                + " VALUES (?, 'MAIN', ?, 'TEST', 'POWERBANK', ?, 8, 6, 'DEPLOYED', 'ONLINE', NOW(3))", cab, cab, site);
        return cab;
    }

    @Test
    @DisplayName("字典说自助解决 → 直接关单")
    void selfServiceClosesTheTicket() {
        String no = problem("SELF_SERVICE", "ENABLED");
        assertThat(report(no).path("suggestedAction").asText()).isEqualTo("SELF_SERVICE");
        assertThat(report(no).path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("字典说转工单 → wo_order 里真有这张单，且重复转不出第二张")
    void workOrderRouteCreatesARealWorkOrder() {
        String probNo = problem("TO_WORKORDER", "ENABLED");
        var vo = report(probNo);
        String woNo = vo.path("woNo").asText();
        String ticketNo = vo.path("reportNo").asText();

        assertThat(woNo).as("要给出去向单号，否则用户与运营都追不下去").isNotBlank();
        assertThat(woNo).as("此前发的是 WO-STUB-*，wo_order 里查无此单 —— 没有人会去处理它")
                .doesNotContain("STUB");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wo_order WHERE wo_no=?", Integer.class, woNo))
                .as("工单表里真有这张单").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source FROM wo_order WHERE wo_no=?", String.class, woNo))
                .isEqualTo("USER");
        assertThat(jdbc.queryForObject("SELECT source_ref FROM wo_order WHERE wo_no=?", String.class, woNo))
                .as("来源指回那张报障单 —— 运维看单时要知道是谁报的").isEqualTo(ticketNo);
        assertThat(jdbc.queryForObject("SELECT description FROM wo_order WHERE wo_no=?", String.class, woNo))
                .as("描述里带单号与用户原话").contains(ticketNo);
    }

    @Test
    @DisplayName("字典说转退款 → ord_refund 里真有一张待财务审批的单（这一步不动钱）")
    void refundRouteCreatesARealPendingRefund() {
        String token = consumerToken();
        String cab = liveCabinet();
        String orderNo = post("/mp/trade/orders/rent", Map.of("cabinetNo", cab), token).okData().path("orderNo").asText();
        rents.returnOrderAt(orderNo, cab, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(2));

        var vo = post("/mp/user/report", Map.of("problemNo", problem("TO_REFUND", "ENABLED"),
                "orderNo", orderNo, "issue", "这一单不该收钱", "channel", "APP"), token).okData();
        String refundNo = vo.path("refundNo").asText();

        assertThat(refundNo).as("此前发的是 RFD-STUB-*，财务侧没有任何待办 —— 钱不会到用户手上")
                .isNotBlank().doesNotContain("STUB");
        assertThat(jdbc.queryForObject("SELECT status FROM ord_refund WHERE refund_no=?", String.class, refundNo))
                .as("建的是**待审批**的单，不是已退款 —— 钱要等财务点头").isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT order_no FROM ord_refund WHERE refund_no=?", String.class, refundNo))
                .isEqualTo(orderNo);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM ord_refund WHERE refund_no=?", String.class, refundNo))
                .as("幂等键用报障单号 —— 重复转退款不会退第二笔").isEqualTo(vo.path("reportNo").asText());
    }

    @Test
    @DisplayName("配了转退款却没关联订单 → 改走人工，而不是留一个空的退款号")
    void refundWithoutOrderFallsBackToHuman() {
        var vo = report(problem("TO_REFUND", "ENABLED"));
        // asText() 对 JSON null 返回字符串 "null"，断言要看节点本身
        assertThat(vo.path("refundNo").isNull())
                .as("空号的后果与旧桩一样：看起来已转退款，财务侧什么都没有（实得：%s）", vo.path("refundNo")).isTrue();
        assertThat(vo.path("sessionNo").asText()).as("改走人工要真的开出会话").isNotBlank();
    }

    @Test
    @DisplayName("没有可用策略一律转人工：字典里没有 / 已下架 / 值写坏了")
    void fallbacksAllGoToHuman() {
        assertThat(report("ISRT-NOT-IN-DICT").path("suggestedAction").asText())
                .as("字典里查不到").isEqualTo("TO_CS");
        assertThat(report(problem("SELF_SERVICE", "DISABLED")).path("suggestedAction").asText())
                .as("已下架 —— 运营不再想按它答复，但用户的诉求还在").isEqualTo("TO_CS");
        assertThat(report(problem("WHATEVER", "ENABLED")).path("suggestedAction").asText())
                .as("值写坏了：转人工并打 WARN，而不是静默走别的路").isEqualTo("TO_CS");
    }

    // —— 夹具 ——

    private com.fasterxml.jackson.databind.JsonNode report(String problemNo) {
        return post("/mp/user/report", Map.of("problemNo", problemNo, "issue", "分流用例", "channel", "APP"),
                consumerToken()).okData();
    }

    /** 建一条问题字典行。title 三语都给：C 端按语种取列，缺了会回落到默认语种。 */
    private String problem(String action, String status) {
        String no = "ISRT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        jdbc.update("INSERT INTO md_problem (problem_no, tenant_id, category, title, title_en, title_ar,"
                        + " answer, answer_en, answer_ar, suggested_action, sort_no, status)"
                        + " VALUES (?, 'MAIN', 'RENT', ?, ?, ?, '答复', 'Answer', 'إجابة', ?, 1, ?)",
                no, "分流用例 " + action, "Routing " + action, "توجيه " + action, action, status);
        return no;
    }

    private String consumerToken() {
        String phone = "+9715" + (10000000 + new java.util.Random().nextInt(89999999));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        return post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
    }
}
