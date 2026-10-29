package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家分成自开票（运营方原始需求 C10，V183）。
 *
 * <p>走真实的付款任务确认到账（与 PayoutTaskFlowTest 同一套造数：2002 年账期、每次新造商家号），
 * 断言：开关开 + 商家有 TRN → 一笔提现一张单据，供方/收货方快照、含税倒推 5%；
 * 没 TRN 的商家不出，运营补录税号后补出；开关关 → 不出。
 */
class SelfBillingTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String withTrn = "AGSA" + tag;
    private final String noTrn = "AGSB" + tag;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        // 同 PayoutTaskFlowTest：本类与它造的老账期结算单不能被下一个任务一起纳入
        jdbc.update("UPDATE stl_settlement SET status = 'PAID' WHERE (settle_no LIKE 'STLP%' OR settle_no LIKE 'STLS%') "
                + "AND status = 'CONFIRMED' AND withdraw_no IS NULL AND period < '2010'");
        jdbc.update("UPDATE sys_param SET value = '1' WHERE param_key = 'finance.share.self_billing'");
        jdbc.update("UPDATE sys_tax_setting SET issuer_legal_name = 'Junverse Technology LLC', issuer_address = 'Office 1, Dubai', "
                + "trn = '100000000000003', rate_percent = 5.00, included_in_price = 1 WHERE country = 'AE'");
        merchant(withTrn, "100222333444555");
        merchant(noTrn, null);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("UPDATE sys_param SET value = '0' WHERE param_key = 'finance.share.self_billing'");
        jdbc.update("UPDATE stl_payout_task SET status = 'CANCELLED' WHERE status IN ('PENDING_REVIEW','PENDING_TRANSFER')");
    }

    private void merchant(String no, String trn) {
        jdbc.update("INSERT INTO agt_agent (agent_no, tenant_id, name, agent_type, status, address, vat_trn) "
                + "VALUES (?, 'MAIN', ?, 'AGENT', 'ENABLED', 'Shop 3, Marina Walk, Dubai', ?)", no, "Cafe " + no, trn);
        jdbc.update("INSERT INTO stl_settlement (settle_no, tenant_id, payee_type, payee_no, payee_name, period, "
                        + "total_amount, currency, status) VALUES (?, 'MAIN', 'AGENT', ?, ?, '2002-01', ?, 'AED', 'CONFIRMED')",
                "STLS" + tag + no.substring(3, 4), no, "Cafe " + no, trn == null ? "50.00" : "105.00");
        post("/api/trade/payout-accounts", Map.of("payeeType", "AGENT", "payeeNo", no, "bankCode", "BK001",
                "accountName", "Beneficiary " + no, "accountMasked", "AE070331234567890123456", "currency", "AED"), login("ADMIN")).okData();
    }

    /** 生成 → 财务复核 → 整批确认到账，返回 商家号 → 提现单号。 */
    private Map<String, String> payAll() {
        String admin = login("ADMIN");
        JsonNode task = post("/api/trade/payout-tasks", Map.of("cutoffDate", "2002-01-31"), admin).okData();
        String taskNo = task.path("taskNo").asText();
        post("/api/trade/payout-tasks/" + taskNo + "/audit", Map.of("approve", true), login("FINANCE")).okData();
        JsonNode done = post("/api/trade/payout-tasks/" + taskNo + "/confirm",
                Map.of("bankBatchRef", "SB-BATCH-" + tag, "transferDate", "2002-02-05"), admin).okData();
        Map<String, String> out = new java.util.HashMap<>();
        for (JsonNode i : done.path("items")) out.put(i.path("payeeNo").asText(), i.path("withdrawNo").asText());
        return out;
    }

    private List<Map<String, Object>> bills(String withdrawNo) {
        return jdbc.queryForList("SELECT * FROM stl_self_bill WHERE withdraw_no = ?", withdrawNo);
    }

    @Test
    @DisplayName("★★ 到账即出自开票：有 TRN 的商家一张，供方/收货方快照，105.00 = 100.00 + 5.00；没 TRN 的不出；补录后补出；PDF")
    void paid_withdrawal_issues_self_bill_for_vat_registered_merchant() throws Exception {
        Map<String, String> w = payAll();
        List<Map<String, Object>> a = bills(w.get(withTrn));
        assertThat(a).hasSize(1);
        Map<String, Object> b = a.get(0);
        assertThat((String) b.get("bill_no")).matches("SB-\\d{4}-\\d{6}");
        assertThat(b.get("supplier_trn")).isEqualTo("100222333444555");
        assertThat(b.get("recipient_name")).isEqualTo("Junverse Technology LLC");
        assertThat((BigDecimal) b.get("gross_amount")).isEqualByComparingTo("105.00");
        assertThat((BigDecimal) b.get("net_amount")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) b.get("tax_amount")).isEqualByComparingTo("5.00");

        assertThat(bills(w.get(noTrn))).as("非 VAT 登记户不出").isEmpty();
        String admin = login("ADMIN");
        assertThat(post("/api/trade/self-bills/issue", Map.of("withdrawNo", w.get(noTrn)), admin).status)
                .as("没税号时补出要说清楚").isEqualTo(409);
        jdbc.update("UPDATE agt_agent SET vat_trn = '100999888777666' WHERE agent_no = ?", noTrn);
        JsonNode issued = post("/api/trade/self-bills/issue", Map.of("withdrawNo", w.get(noTrn)), admin).okData();
        assertThat(issued.path("supplierTrn").asText()).isEqualTo("100999888777666");
        assertThat(post("/api/trade/self-bills/issue", Map.of("withdrawNo", w.get(noTrn)), admin).okData()
                .path("billNo").asText()).as("重复补出返回同一张").isEqualTo(issued.path("billNo").asText());

        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/trade/self-bills/" + b.get("bill_no") + "/pdf")).header("Authorization", "Bearer " + admin).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        try (var d = Loader.loadPDF(pdf.body())) {
            assertThat(new PDFTextStripper().getText(d)).contains("SELF-BILLING").contains("100222333444555")
                    .contains("100000000000003").contains("5.00");
        }
        assertThat(get("/api/trade/self-bills?agentNo=" + withTrn, admin).okData().path("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("开关关着 → 到账不出单据")
    void disabled_issues_nothing() {
        jdbc.update("UPDATE sys_param SET value = '0' WHERE param_key = 'finance.share.self_billing'");
        assertThat(bills(payAll().get(withTrn))).isEmpty();
    }
}
