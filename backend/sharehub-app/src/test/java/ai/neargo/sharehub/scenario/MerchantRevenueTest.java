package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商家营收首页（商家一期 B5，需求方案 §10.1 / §10.2，验收 AC-12 / AC-14）。
 *
 * <p>数据直接写库造：两个商家各有订单、退款、分成明细、结算单、提现单，窗口选在 2002 年 —— 不碰共享库里别的数据。
 * 守两件事：口径（交易额 − 退款 = 净交易额，税前与分成取明细，钱的状态按现在）与数据范围（商家只看自己的）。
 */
class MerchantRevenueTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = Long.toString(System.nanoTime(), 36).toUpperCase();

    private void order(String agentNo, String no, String endedAt, String paid) {
        jdbc.update("INSERT INTO ord_order (order_no, tenant_id, c_user_no, cabinet_no, agent_no, status, paid_amount, currency, ended_at) "
                + "VALUES (?, 'MAIN', 'U-REV', ?, ?, 'SETTLED', ?, 'AED', ?)", no, "CAB-" + agentNo, agentNo, paid, endedAt);
    }

    private void share(String agentNo, String orderNo, String createdAt, String base, String amount, String status) {
        jdbc.update("INSERT INTO share_record (record_no, tenant_id, order_no, dimension, basis, payee_type, payee_no, amount, rate, "
                + "currency, mode, status, period, gross_amount, base_amount, created_at) VALUES "
                + "(?, 'MAIN', ?, 'AGENT', 'OPERATE', 'AGENT', ?, ?, 0.30, 'AED', 'LEDGER', ?, '2002-03', ?, ?, ?)",
                "SRREV" + orderNo, orderNo, agentNo, amount, status, base, base, createdAt);
    }

    @Test
    @DisplayName("★★ 口径：交易额、退款、净交易额、税前营收、我的分成、钱的状态；商家只看自己的（AC-12 / AC-14）")
    void summary_follows_the_agreed_definitions_and_scope() {
        String a = "AGREVA" + tag, b = "AGREVB" + tag;
        order(a, "ORA1" + tag, "2002-03-05 10:00:00", "10.50");
        order(a, "ORA2" + tag, "2002-03-20 18:00:00", "21.00");
        order(a, "ORA3" + tag, "2002-04-02 09:00:00", "99.00");          // 窗口外
        order(b, "ORB1" + tag, "2002-03-06 12:00:00", "50.00");          // 别的商家
        jdbc.update("INSERT INTO ord_refund (refund_no, tenant_id, order_no, c_user_no, idempotency_key, amount, status, currency) "
                + "VALUES (?, 'MAIN', ?, 'U-REV', ?, 1.50, 'APPROVED', 'AED')", "RFREV" + tag, "ORA2" + tag, "IDREV" + tag);
        share(a, "ORA1" + tag, "2002-03-05 10:00:01", "10.00", "3.00", "PENDING");
        share(a, "ORA2" + tag, "2002-03-20 18:00:01", "20.00", "6.00", "DONE");
        share(b, "ORB1" + tag, "2002-03-06 12:00:01", "47.62", "14.28", "PENDING");
        jdbc.update("INSERT INTO stl_settlement (settle_no, tenant_id, payee_type, payee_no, payee_name, period, total_amount, currency, status) "
                + "VALUES (?, 'MAIN', 'AGENT', ?, 'A', '2002-03', 6.00, 'AED', 'CONFIRMED')", "STLREV" + tag, a);

        String q = "?from=2002-03-01&to=2002-03-31";
        JsonNode all = get("/api/ops/merchant-revenue/summary" + q, login("ADMIN")).okData();
        assertThat(all.path("orderCount").asInt()).as("运营看全部：A 两单 + B 一单（至少）").isGreaterThanOrEqualTo(3);

        JsonNode mine = get("/api/ops/merchant-revenue/summary" + q, login("AGENT", "rev-" + tag, a)).okData();
        assertThat(mine.path("orderCount").asInt()).as("商家只看自己的，窗口外不算").isEqualTo(2);
        assertThat(mine.path("gross").decimalValue()).isEqualByComparingTo("31.50");
        assertThat(mine.path("refund").decimalValue()).isEqualByComparingTo("1.50");
        assertThat(mine.path("net").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(mine.path("preTax").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(mine.path("myShare").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(mine.path("pending").decimalValue()).as("待结算按现在、不按窗口").isEqualByComparingTo("3.00");
        assertThat(mine.path("settledUnpaid").decimalValue()).isEqualByComparingTo("6.00");
        assertThat(mine.path("prevOrderCount").asInt()).as("2 月没有单").isZero();

        JsonNode trend = get("/api/ops/merchant-revenue/trend" + q, login("AGENT", "rev-" + tag, a)).okData();
        assertThat(trend).as("缺天补 0，折线不断").hasSize(31);
        JsonNode devices = get("/api/ops/merchant-revenue/by-device" + q, login("AGENT", "rev-" + tag, a)).okData();
        assertThat(devices).extracting(n -> n.path("cabinetNo").asText()).containsExactly("CAB-" + a);
    }

    @Test
    @DisplayName("窗口非法（止早于起、超过一年）→ 400")
    void bad_window_is_rejected() {
        String admin = login("ADMIN");
        assertThat(get("/api/ops/merchant-revenue/summary?from=2002-03-31&to=2002-03-01", admin).status).isEqualTo(400);
        assertThat(get("/api/ops/merchant-revenue/summary?from=2001-01-01&to=2002-06-01", admin).status).isEqualTo(400);
    }

    @Test
    @DisplayName("★ 设备提醒：在用设备里离线与「在线无可借」分开数，各带柜号；仓里的柜子不算")
    void device_alerts_count_deployed_only() {
        String off = "CABAL" + tag + "1", empty = "CABAL" + tag + "2", stock = "CABAL" + tag + "3";
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, slot_total, available_count, online_status, status) "
                + "VALUES (?, 'MAIN', ?, 'TEST', 8, 3, 'OFFLINE', 'DEPLOYED')", off, off);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, slot_total, available_count, online_status, status) "
                + "VALUES (?, 'MAIN', ?, 'TEST', 8, 0, 'ONLINE', 'DEPLOYED')", empty, empty);
        jdbc.update("INSERT INTO dev_cabinet (cabinet_no, tenant_id, sn, vendor_code, slot_total, available_count, online_status, status) "
                + "VALUES (?, 'MAIN', ?, 'TEST', 8, 0, 'OFFLINE', 'IN_STOCK')", stock, stock);

        JsonNode a = get("/api/ops/merchant-revenue/alerts", login("ADMIN")).okData();
        assertThat(a.path("offline").asLong()).isPositive();
        assertThat(a.path("noBorrowable").asLong()).isPositive();
        java.util.List<String> offs = new java.util.ArrayList<>(), empties = new java.util.ArrayList<>();
        a.path("offlineSample").forEach(n -> offs.add(n.asText()));
        a.path("noBorrowableSample").forEach(n -> empties.add(n.asText()));
        long all = jdbc.queryForObject("SELECT COUNT(*) FROM dev_cabinet WHERE status = 'DEPLOYED' AND online_status = 'OFFLINE' AND deleted = 0", Long.class);
        if (all <= 20) assertThat(offs).contains(off).doesNotContain(empty, stock);
        assertThat(empties).doesNotContain(off, stock);
    }
}
