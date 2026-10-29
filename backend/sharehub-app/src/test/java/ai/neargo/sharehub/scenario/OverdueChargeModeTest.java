package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.RentFixture;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时扣款可配置（V180，运营方原文 K4 / K5 / AC-02 / AC-03）。
 *
 * <p>TIME_LIMIT：到时限发起最高收费、订单进「已扣款未归还」；之后归还只结单、不冲销、不再请款。
 * 遗失费独立：用户主动买断按本单快照的遗失费收，不是系统默认买断价（D4）。
 */
class OverdueChargeModeTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RentOrderService rents;

    private String rentedOrder(String capTotal, String lostFee, int hoursAgo) {
        RentFixture.rentable(jdbc);
        String phone = "+97150" + (1_000_000 + (System.nanoTime() % 8_000_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
        String no = post("/mp/trade/orders/rent", Map.of("cabinetNo", RentFixture.CABINET, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();
        // 计费方案快照里定格总封顶与遗失费（等同借出时命中一个配了这两项的方案）；借出时刻往前挪
        jdbc.update("UPDATE ord_order SET status = 'IN_USE', rent_start_at = rent_start_at - INTERVAL ? HOUR, "
                + "price_snapshot = JSON_SET(COALESCE(price_snapshot, '{}'), '$.capTotal', ?" + (lostFee == null ? "" : ", '$.lostFee', ?")
                + ") WHERE order_no = ?", lostFee == null ? new Object[]{hoursAgo, capTotal, no} : new Object[]{hoursAgo, capTotal, lostFee, no});
        lastToken = token;
        return no;
    }

    private String lastToken;

    private int charges(String no) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM pay_order WHERE order_no = ? AND type IN ('RENT','BUYOUT')", Integer.class, no);
    }

    @Test
    @DisplayName("★★ 到时限发起最高收费 → 已扣款未归还；之后归还只结单，不冲销、不再请款（AC-02 / AC-03）")
    void time_limit_charges_then_return_keeps_the_charge() {
        String no = rentedOrder("69.00", null, 50);
        assertThat(rents.chargeOverdueIfDue(no, 48)).isTrue();

        Map<String, Object> o = jdbc.queryForMap("SELECT status, amount, overdue_charged_at, rent_end_at FROM ord_order WHERE order_no = ?", no);
        assertThat(o.get("status")).isEqualTo("OVERDUE_CHARGED");
        assertThat((BigDecimal) o.get("amount")).isEqualByComparingTo("69.00");
        assertThat(o.get("overdue_charged_at")).isNotNull();
        assertThat(o.get("rent_end_at")).as("没还就没有归还时刻").isNull();
        assertThat(charges(no)).isEqualTo(1);
        assertThat(rents.chargeOverdueIfDue(no, 48)).as("不重复扣").isFalse();

        assertThat(post("/internal/trade/orders/" + no + "/return", Map.of("returnCabinetNo", "CAB1006"), login("ADMIN"))
                .okData().path("ok").asBoolean()).isTrue();
        Map<String, Object> after = jdbc.queryForMap("SELECT status, amount, rent_end_at, return_cabinet_no FROM ord_order WHERE order_no = ?", no);
        assertThat(after.get("status")).isEqualTo("SETTLED");
        assertThat((BigDecimal) after.get("amount")).as("归还不冲销").isEqualByComparingTo("69.00");
        assertThat(after.get("rent_end_at")).isNotNull();
        assertThat(after.get("return_cabinet_no")).isEqualTo("CAB1006");
        assertThat(charges(no)).as("归还不再请款").isEqualTo(1);
    }

    @Test
    @DisplayName("未到时限、或方案没配总封顶 → 不收")
    void not_due_or_no_cap_does_nothing() {
        String early = rentedOrder("69.00", null, 10);
        assertThat(rents.chargeOverdueIfDue(early, 48)).isFalse();
        String noCap = rentedOrder("0", null, 50);
        jdbc.update("UPDATE ord_order SET price_snapshot = JSON_REMOVE(price_snapshot, '$.capTotal') WHERE order_no = ?", noCap);
        assertThat(rents.chargeOverdueIfDue(noCap, 48)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no = ?", String.class, noCap)).isEqualTo("IN_USE");
    }

    @Test
    @DisplayName("★ 遗失费独立：用户主动买断按本单快照的遗失费收（K5 / D4）")
    void user_buyout_charges_the_snapshot_lost_fee() {
        String no = rentedOrder("69.00", "120.00", 2);
        post("/mp/trade/orders/" + no + "/buyout", Map.of(), lastToken).okData();
        assertThat(jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no = ?", BigDecimal.class, no))
                .isEqualByComparingTo("120.00");
    }
}
