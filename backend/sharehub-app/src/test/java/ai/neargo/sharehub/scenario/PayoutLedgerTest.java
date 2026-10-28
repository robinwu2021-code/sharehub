package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应付商家的自动记账（商家一期 G19）：结算确认记应付，到账冲减应付。
 *
 * <p>守的是「应付余额 = 已确认未付的结算单合计」这一条 —— 财务对账最先查的就是它。
 * 2002 年账期、每次新造商家号，只碰本类自己的数据。
 */
class PayoutLedgerTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();
    private final String merchant = "AGLG" + tag;

    @BeforeEach
    @AfterEach
    void noOpenTasks() {
        jdbc.update("UPDATE stl_payout_task SET status = 'CANCELLED' WHERE status IN ('PENDING_REVIEW','PENDING_TRANSFER')");
        jdbc.update("UPDATE stl_settlement SET status = 'PAID' WHERE status = 'CONFIRMED' AND withdraw_no IS NULL AND period < '2010'");
    }

    private String settlement(String period, String amount) {
        String no = "STLG" + tag + period.replace("-", "");
        jdbc.update("INSERT INTO stl_settlement (settle_no, tenant_id, payee_type, payee_no, payee_name, period, "
                + "total_amount, currency, status) VALUES (?, 'MAIN', 'AGENT', ?, 'L', ?, ?, 'AED', 'GEN')", no, merchant, period, amount);
        return no;
    }

    private List<Map<String, Object>> lines(String bizType, String bizNo) {
        return jdbc.queryForList("SELECT account_no, direction, amount FROM acct_ledger WHERE biz_type = ? AND biz_no = ? ORDER BY id",
                bizType, bizNo);
    }

    /** 负债科目余额 = Σ贷 − Σ借。 */
    private BigDecimal payable() {
        return jdbc.queryForObject("SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0) "
                + "FROM acct_ledger WHERE account_no = ?", BigDecimal.class, "ACC-PAY-AGENT-" + merchant);
    }

    @Test
    @DisplayName("★★ 结算确认 → 借分成成本、贷应付商家；同一张单只记一次")
    void confirm_books_the_payable_once() {
        String no = settlement("2002-01", "150.00");
        String admin = login("ADMIN");
        post("/api/trade/settlements/" + no + "/confirm", Map.of(), admin).okData();

        assertThat(lines("SETTLE_CONFIRM", no)).extracting(r -> r.get("account_no") + ":" + r.get("direction"))
                .containsExactly("ACC-PLT-SHARE-COST:DEBIT", "ACC-PAY-AGENT-" + merchant + ":CREDIT");
        assertThat(payable()).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("SELECT acct_type FROM acct_account WHERE account_no = ?", String.class,
                "ACC-PAY-AGENT-" + merchant)).as("应付是负债科目").isEqualTo("LIABILITY");

        assertThat(post("/api/trade/settlements/" + no + "/confirm", Map.of(), admin).status)
                .as("重复确认被状态机拒，账也不会记第二次").isNotEqualTo(200);
        assertThat(lines("SETTLE_CONFIRM", no)).hasSize(2);
    }

    @Test
    @DisplayName("★★ 付款任务到账 → 应付冲减到 0；失败的不记账")
    void paid_withdrawal_clears_the_payable() {
        String s1 = settlement("2002-02", "120.00"), s2 = settlement("2002-03", "80.00");
        String admin = login("ADMIN"), fin = login("FINANCE");
        post("/api/trade/settlements/" + s1 + "/confirm", Map.of(), admin).okData();
        post("/api/trade/settlements/" + s2 + "/confirm", Map.of(), admin).okData();
        assertThat(payable()).isEqualByComparingTo("200.00");
        post("/api/trade/payout-accounts", Map.of("payeeType", "AGENT", "payeeNo", merchant, "bankCode", "BK001",
                "accountName", "Beneficiary", "accountMasked", "AE070331234567890123456", "currency", "AED"), admin).okData();

        JsonNode task = post("/api/trade/payout-tasks", Map.of("cutoffDate", "2002-03-31"), admin).okData();
        String taskNo = task.path("taskNo").asText();
        String withdrawNo = null;
        for (JsonNode it : task.path("items")) if (merchant.equals(it.path("payeeNo").asText())) withdrawNo = it.path("withdrawNo").asText();
        assertThat(withdrawNo).isNotNull();
        post("/api/trade/payout-tasks/" + taskNo + "/audit", Map.of("approve", true), fin).okData();
        post("/api/trade/payout-tasks/" + taskNo + "/confirm", Map.of("bankBatchRef", "B-" + tag, "transferDate", "2002-04-05",
                "results", List.of(Map.of("withdrawNo", withdrawNo, "success", true))), admin).okData();

        assertThat(lines("WITHDRAW_PAID", withdrawNo)).extracting(r -> r.get("direction").toString())
                .first().isEqualTo("DEBIT");
        assertThat(payable()).as("到账后应付清零").isEqualByComparingTo("0");
    }
}
