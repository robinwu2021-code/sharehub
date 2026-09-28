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

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM md_problem WHERE problem_no LIKE 'ISRT%'");
    }

    @Test
    @DisplayName("字典说自助解决 → 直接关单")
    void selfServiceClosesTheTicket() {
        String no = problem("SELF_SERVICE", "ENABLED");
        assertThat(report(no).path("suggestedAction").asText()).isEqualTo("SELF_SERVICE");
        assertThat(report(no).path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("字典说转工单 → 出工单号；说转退款 → 出退款号")
    void workOrderAndRefundRoutesCarryTheirNumbers() {
        assertThat(report(problem("TO_WORKORDER", "ENABLED")).path("woNo").asText())
                .as("转工单要给出去向单号，否则用户与运营都追不下去").isNotBlank();
        assertThat(report(problem("TO_REFUND", "ENABLED")).path("refundNo").asText()).isNotBlank();
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
