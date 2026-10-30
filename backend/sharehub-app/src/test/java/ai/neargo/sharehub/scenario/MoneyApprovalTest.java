package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.RentFixture;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客服补偿 / 免单与手工凭证的审批（运营方原始需求 SEC-03，V189）：阈值可配，缺省关。
 */
class MoneyApprovalTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private void param(String key, String value) {
        jdbc.update("UPDATE sys_param SET value = ? WHERE param_key = ?", value, key);
    }

    @AfterEach
    void off() {
        param("approval.compensation.threshold", "");
        param("approval.voucher.threshold", "");
    }

    private String settledOrder() {
        RentFixture.rentable(jdbc);
        String phone = "+97155" + (1_000_000 + (System.nanoTime() % 8_000_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
        String no = post("/mp/trade/orders/rent", Map.of("cabinetNo", RentFixture.CABINET, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();
        jdbc.update("UPDATE ord_order SET status = 'SETTLED' WHERE order_no = ?", no);
        return no;
    }

    private int interventions(String orderNo) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ord_intervention WHERE order_no = ? AND action = 'compensate'", Integer.class, orderNo);
    }

    private Map<String, Object> compensate(String amount) {
        return Map.of("action", "compensate", "reason", "charger faulty", "amount", amount);
    }

    @Test
    @DisplayName("阈值空（缺省）→ 补偿照旧即时生效")
    void off_by_default() {
        String no = settledOrder();
        post("/api/trade/orders/" + no + "/intervene", compensate("50.00"), login("CS")).okData();
        assertThat(interventions(no)).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 补偿 ≥ 阈值 → 先审后生效：客服提交只排队；客服不能批；财务批准后才执行；低于阈值照旧即时")
    void compensation_over_threshold_waits_for_finance() {
        param("approval.compensation.threshold", "20");
        String no = settledOrder();
        String cs = login("CS");

        post("/api/trade/orders/" + no + "/intervene", compensate("5.00"), cs).okData();
        assertThat(interventions(no)).as("低于阈值即时").isEqualTo(1);

        JsonNode pending = post("/api/trade/orders/" + no + "/intervene", compensate("30.00"), cs).okData();
        String req = pending.path("pendingApproval").asText();
        assertThat(req).isNotBlank();
        assertThat(interventions(no)).as("排队不执行").isEqualTo(1);

        assertThat(post("/api/trade/approvals/" + req + "/approve", Map.of(), cs).status).as("客服没有审批码").isEqualTo(403);
        JsonNode done = post("/api/trade/approvals/" + req + "/approve", Map.of(), login("FINANCE")).okData();
        assertThat(done.path("status").asText()).isEqualTo("APPROVED");
        assertThat(done.path("resultRef").asText()).isNotBlank();
        assertThat(interventions(no)).isEqualTo(2);
        assertThat(post("/api/trade/approvals/" + req + "/approve", Map.of(), login("FINANCE")).status)
                .as("不能批两次").isEqualTo(409);
    }

    @Test
    @DisplayName("★ 驳回必须写原因、驳回后不执行；申请人不能审批自己的（四眼）")
    void reject_and_four_eyes() {
        param("approval.compensation.threshold", "20");
        String no = settledOrder();
        String admin = login("ADMIN");
        String req = post("/api/trade/orders/" + no + "/intervene", compensate("30.00"), admin).okData().path("pendingApproval").asText();
        assertThat(post("/api/trade/approvals/" + req + "/approve", Map.of(), admin).status).as("自己批自己").isEqualTo(409);
        String fin = login("FINANCE");
        assertThat(post("/api/trade/approvals/" + req + "/reject", Map.of(), fin).status).isEqualTo(400);
        assertThat(post("/api/trade/approvals/" + req + "/reject", Map.of("note", "amount too high"), fin)
                .okData().path("status").asText()).isEqualTo("REJECTED");
        assertThat(interventions(no)).isZero();
    }

    @Test
    @DisplayName("★ 等待期间前提变了（订单已关单不能免单）→ 批准失败记 FAILED，订单不被改动")
    void replay_failure_is_recorded_not_forced() {
        param("approval.compensation.threshold", "0");
        String no = settledOrder();
        jdbc.update("UPDATE ord_order SET amount = 40.00, fee_amount = 40 WHERE order_no = ?", no);
        String req = post("/api/trade/orders/" + no + "/intervene", Map.of("action", "waive", "reason", "complaint"), login("CS"))
                .okData().path("pendingApproval").asText();
        jdbc.update("UPDATE ord_order SET status = 'CLOSED' WHERE order_no = ?", no);   // CLOSED 不允许 waive
        assertThat(post("/api/trade/approvals/" + req + "/approve", Map.of(), login("FINANCE")).status).isEqualTo(400);
        Map<String, Object> r = jdbc.queryForMap("SELECT status, decide_note FROM ops_approval_request WHERE request_no = ?", req);
        assertThat(r.get("status")).isEqualTo("FAILED");
        assertThat((String) r.get("decide_note")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no = ?", String.class, no)).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★ 手工凭证借方 ≥ 阈值 → 另一位财务批准后才入账")
    void voucher_over_threshold() {
        param("approval.voucher.threshold", "100");
        String summary = "approval test " + System.nanoTime();
        Map<String, Object> body = Map.of("summary", summary, "lines", List.of(
                Map.of("account", "现金-nearpay", "direction", "DEBIT", "amount", "150.00", "currency", "AED"),
                Map.of("account", "平台收入", "direction", "CREDIT", "amount", "150.00", "currency", "AED")));
        String fin = login("FINANCE");
        String req = post("/api/trade/ledger/vouchers", body, fin).okData().path("pendingApproval").asText();
        assertThat(req).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acct_ledger WHERE summary = ?", Integer.class, summary)).isZero();
        assertThat(post("/api/trade/approvals/" + req + "/approve", Map.of(), fin).status).as("自己批自己").isEqualTo(409);
        String voucherNo = post("/api/trade/approvals/" + req + "/approve", Map.of(), login("ADMIN")).okData().path("resultRef").asText();
        assertThat(voucherNo).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM acct_ledger WHERE voucher_no = ?", Integer.class, voucherNo)).isEqualTo(2);
    }
}
