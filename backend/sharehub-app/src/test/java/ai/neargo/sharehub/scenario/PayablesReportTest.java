package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨期应付（运营方原始需求 SHR-05，第 6 批）：收款方 × 期间 的 应得 / 已出结算单 / 已付 / 未付。
 */
class PayablesReportTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String agent = "AGPY" + tag;

    private void share(String period, String amount) {
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, payee_type, payee_no, payee_name, amount, "
                        + "gross_amount, rate, currency, mode, status, period, source_no, basis) "
                        + "VALUES (?, 'MAIN', ?, 'AGENT', 'AGENT', ?, 'Payable Cafe', ?, 100, 0.2, 'AED', 'LEDGER', 'PENDING', ?, 'T', '')",
                "SRPY" + tag + period + amount.replace(".", "").replace("-", "N"), "ORDPY" + tag + period + amount, agent, amount, period);
    }

    private void settlement(String period, String total, String status) {
        jdbc.update("INSERT INTO stl_settlement (settle_no, tenant_id, payee_type, payee_no, payee_name, period, total_amount, currency, status) "
                + "VALUES (?, 'MAIN', 'AGENT', ?, 'Payable Cafe', ?, ?, 'AED', ?)", "STLPY" + tag + period.replace("-", ""), agent, period, total, status);
    }

    private Map<String, JsonNode> rows(String query) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode r : get("/api/trade/payables?size=50&payeeNo=" + agent + query, login("ADMIN")).okData().path("list")) {
            out.put(r.path("period").asText(), r);
        }
        return out;
    }

    @Test
    @DisplayName("★★ 三个月：已付 / 已确认未付 / 未出结算单；退款冲回的负数明细计入应得；只看未付可筛")
    void earned_settled_paid_outstanding_per_period() {
        share("2003-01", "20.00");
        share("2003-01", "30.00");
        share("2003-02", "40.00");
        share("2003-02", "-5.00");   // 退款冲回
        share("2003-03", "15.00");
        settlement("2003-01", "50.00", "PAID");
        settlement("2003-02", "35.00", "CONFIRMED");

        Map<String, JsonNode> r = rows("");
        assertThat(r).containsOnlyKeys("2003-01", "2003-02", "2003-03");
        assertThat(r.get("2003-01").path("earned").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(r.get("2003-01").path("paid").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(r.get("2003-01").path("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("2003-02").path("earned").decimalValue()).isEqualByComparingTo("35.00");
        assertThat(r.get("2003-02").path("settled").decimalValue()).isEqualByComparingTo("35.00");
        assertThat(r.get("2003-02").path("outstanding").decimalValue()).isEqualByComparingTo("35.00");
        assertThat(r.get("2003-03").path("settled").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("2003-03").path("outstanding").decimalValue()).isEqualByComparingTo("15.00");

        assertThat(rows("&onlyOutstanding=true")).containsOnlyKeys("2003-02", "2003-03");
        assertThat(rows("&fromPeriod=2003-02&toPeriod=2003-02")).containsOnlyKeys("2003-02");
        assertThat(get("/api/trade/payables?fromPeriod=2003-1", login("ADMIN")).status).isEqualTo(400);
    }
}
