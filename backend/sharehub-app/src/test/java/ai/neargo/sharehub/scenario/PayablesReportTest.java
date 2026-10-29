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
 *
 * <p>逐笔口径：每笔明细 / 调整项按自己的期间计应得，按它挂的结算单状态计已出单 / 已付。
 */
class PayablesReportTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String agent = "AGPY" + tag;
    private int seq;

    private void share(String period, String amount, String settleNo) {
        seq++;
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, payee_type, payee_no, payee_name, amount, "
                        + "gross_amount, rate, currency, mode, status, period, source_no, basis, settle_no) "
                        + "VALUES (?, 'MAIN', ?, 'AGENT', 'AGENT', ?, 'Payable Cafe', ?, 100, 0.2, 'AED', 'LEDGER', 'PENDING', ?, 'T', '', ?)",
                "SRPY" + tag + seq, "ORDPY" + tag + seq, agent, amount, period, settleNo);
    }

    private String settlement(String period, String total, String status) {
        String no = "STLPY" + tag + period.replace("-", "");
        jdbc.update("INSERT INTO stl_settlement (settle_no, tenant_id, payee_type, payee_no, payee_name, period, total_amount, currency, status) "
                + "VALUES (?, 'MAIN', 'AGENT', ?, 'Payable Cafe', ?, ?, 'AED', ?)", no, agent, period, total, status);
        return no;
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
        String jan = settlement("2003-01", "50.00", "PAID");
        String feb = settlement("2003-02", "35.00", "CONFIRMED");
        share("2003-01", "20.00", jan);
        share("2003-01", "30.00", jan);
        share("2003-02", "40.00", feb);
        share("2003-02", "-5.00", feb);   // 退款冲回
        share("2003-03", "15.00", null);   // 还没出结算单

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

    @Test
    @DisplayName("★ 跨期并单：4 月的结算单收了 3 月迟到的明细 —— 3 月那行算已付，4 月不会出现「已付 > 应得」")
    void late_record_settled_in_later_period_counts_in_its_own_period() {
        String apr = settlement("2003-04", "30.00", "PAID");
        share("2003-04", "20.00", apr);
        share("2003-03", "10.00", apr);   // 3 月迟到的一笔，并进 4 月的结算单付掉了

        Map<String, JsonNode> r = rows("&fromPeriod=2003-03&toPeriod=2003-04");
        assertThat(r.get("2003-04").path("paid").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(r.get("2003-04").path("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("2003-03").path("paid").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(r.get("2003-03").path("outstanding").decimalValue()).isEqualByComparingTo("0");
    }
}
