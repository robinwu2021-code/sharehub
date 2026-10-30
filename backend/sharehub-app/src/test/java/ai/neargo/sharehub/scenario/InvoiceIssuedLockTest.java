package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** D1：已开具的发票不能再改；编辑不带 status 不会把发票置回草稿。 */
class InvoiceIssuedLockTest extends ApiTestSupport {

    private Map<String, Object> body(String amount) {
        Map<String, Object> b = new HashMap<>();
        b.put("payeeType", "AGENT");
        b.put("payeeNo", "AG001");
        b.put("payeeName", "AG001");
        b.put("amount", amount);
        b.put("vatTrn", "100000000000003");
        b.put("currency", "AED");
        return b;
    }

    @Test
    @DisplayName("★★ 开具后改金额 / TRN → 409，库里不变；草稿编辑不带 status 仍是草稿")
    void issued_invoice_is_immutable() {
        String fin = login("FINANCE");
        String no = post("/api/trade/invoices", body("100.00"), fin).okData().path("invoice").path("invoiceNo").asText();

        JsonNode draft = post("/api/trade/invoices/" + no, body("120.00"), fin).okData().path("invoice");
        assertThat(draft.path("status").asText()).as("不带 status 的编辑保持草稿").isEqualTo("DRAFT");

        post("/api/trade/invoices/" + no + "/issue", Map.of(), fin).okData();
        Resp edit = post("/api/trade/invoices/" + no, body("1.00"), fin);
        assertThat(edit.status).isEqualTo(409);

        JsonNode after = get("/api/trade/invoices/" + no, fin).okData().path("invoice");
        assertThat(after.path("status").asText()).as("没有被悄悄置回草稿").isEqualTo("ISSUED");
        assertThat(after.path("amount").decimalValue()).isEqualByComparingTo("120.00");
    }
}
