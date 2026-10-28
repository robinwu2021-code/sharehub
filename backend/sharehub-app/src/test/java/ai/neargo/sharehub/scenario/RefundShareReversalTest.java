package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退款冲回分成（V170）：退款批准 → 按比例给原分成方写负数明细，原行不动；同一笔退款只冲一次；不超冲。
 *
 * <p>订单与分成明细直接写库造（每次新号），退款走真实端点（客服申请、财务审批）。
 */
class RefundShareReversalTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();

    private String orderWithShare(String gross, String share) {
        String orderNo = "ORRV" + tag + (int) (Math.random() * 1000);
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, agent_no, status, paid_amount, currency, ended_at) "
                + "VALUES (?, 'MAIN', 'U-RV', 'CAB-RV', 'AGRV', 'SETTLED', ?, 'AED', NOW())", orderNo, gross);
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, basis, payee_type, payee_no, amount, rate, "
                + "currency, mode, status, period, gross_amount, base_amount) VALUES "
                + "(?, 'MAIN', ?, 'AGENT', 'OPERATE', 'AGENT', 'AGRV', ?, 0.30, 'AED', 'LEDGER', 'DONE', '2002-05', ?, ?)",
                "SRRV" + orderNo, orderNo, share, gross, gross);
        return orderNo;
    }

    private void refund(String orderNo, String amount) {
        Map<String, Object> b = new HashMap<>();
        b.put("orderNo", orderNo);
        b.put("userNo", "U-RV");
        b.put("amount", new BigDecimal(amount));
        b.put("currency", "AED");
        b.put("reason", "退款冲回测试");
        b.put("idempotencyKey", "RV-" + UUID.randomUUID());
        String refundNo = post("/api/trade/refunds", b, login("CS", "cs.rv", null)).okData().path("refundNo").asText();
        post("/api/trade/refunds/" + refundNo + "/audit", Map.of("approved", true), login("FINANCE")).okData();
    }

    private BigDecimal net(String orderNo) {
        return jdbc.queryForObject("SELECT SUM(amount) FROM share_record WHERE order_no = ?", BigDecimal.class, orderNo);
    }

    @Test
    @DisplayName("★★ 部分退款 → 按比例写一条负数冲回，原行不动、状态待结算")
    void partial_refund_reverses_proportionally() {
        String o = orderWithShare("20.00", "6.00");
        refund(o, "5.00");

        Map<String, Object> rev = jdbc.queryForMap("SELECT amount, status, refund_no, source_no FROM share_record "
                + "WHERE order_no = ? AND refund_no IS NOT NULL", o);
        assertThat((BigDecimal) rev.get("amount")).isEqualByComparingTo("-1.50");
        assertThat(rev.get("status")).isEqualTo("PENDING");
        assertThat(rev.get("source_no")).isEqualTo("SRRV" + o);
        assertThat(jdbc.queryForObject("SELECT amount FROM share_record WHERE record_no = ?", BigDecimal.class, "SRRV" + o))
                .as("原行是当时的事实，不改").isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("★ 多次部分退款合计超过实收 → 冲回封顶在原分成，不倒欠")
    void reversals_never_exceed_the_original_share() {
        String o = orderWithShare("10.00", "3.00");
        refund(o, "7.00");
        refund(o, "7.00");
        assertThat(net(o)).isEqualByComparingTo("0.00");
    }
}
