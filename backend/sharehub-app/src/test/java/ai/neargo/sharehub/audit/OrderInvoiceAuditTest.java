package ai.neargo.sharehub.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单与发票的字段级改前改后（运营方原始需求 SEC-09，第 6 批）。
 */
class OrderInvoiceAuditTest extends AuditTestSupport {

    private Map<String, Object> invoice(String no, String amount) {
        Map<String, Object> m = new HashMap<>();
        if (no != null) m.put("invoiceNo", no);
        m.put("payeeType", "AGENT");
        m.put("payeeNo", "AG001");
        m.put("payeeName", "AG001");
        m.put("amount", amount);
        m.put("vatTrn", "100000000000003");
        m.put("currency", "AED");
        return m;
    }

    @Test
    @DisplayName("★ 改草稿发票金额：审计查得出 金额 100.00 → 120.00，没改的字段不出现")
    void invoice_amount_change_is_audited() {
        String admin = login("ADMIN");
        String no = post("/api/trade/invoices", invoice(null, "100.00"), admin).okData().path("invoice").path("invoiceNo").asText();
        String tp = newTraceparent();
        postWithHeaders("/api/trade/invoices/" + no, invoice(no, "120.00"), admin, "traceparent", tp).okData();
        JsonNode changes = changesOfDetail(auditOfThisRequest("admin.user", traceIdOf(tp)));
        JsonNode amount = findChange(changes, "金额");
        assertThat(new java.math.BigDecimal(amount.path("before").asText())).isEqualByComparingTo("100.00");
        assertThat(new java.math.BigDecimal(amount.path("after").asText())).isEqualByComparingTo("120.00");
        for (JsonNode c : changes) assertThat(c.path("field").asText()).isEqualTo("金额");
    }

    private JsonNode changesOfDetail(JsonNode auditRow) {
        JsonNode detail = auditRow.path("detail");
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(detail.isTextual() ? detail.asText() : detail.toString()).path("changes");
        } catch (Exception e) {
            throw new AssertionError("detail 不是 JSON：" + detail, e);
        }
    }

    private static JsonNode findChange(JsonNode changes, String field) {
        for (JsonNode c : changes) {
            if (field.equals(c.path("field").asText())) return c;
        }
        throw new AssertionError("diff 里没有字段 " + field + "：" + changes);
    }
}
