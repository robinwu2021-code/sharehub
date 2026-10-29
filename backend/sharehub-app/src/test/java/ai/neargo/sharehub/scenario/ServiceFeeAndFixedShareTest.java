package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.api.core.event.OrderSettledEvent;
import ai.neargo.sharehub.finance.service.ShareGenerator;
import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.RentFixture;
import ai.neargo.sharehub.trade.price.engine.PriceResolver;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 6 批配置（V187）：C09 服务费、C11 单均固定额分成（修 SHR-02「固定额是死字段」）。
 */
class ServiceFeeAndFixedShareTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RentOrderService rents;
    @Autowired
    private ShareGenerator generator;

    private void param(String key, String value) {
        jdbc.update("UPDATE sys_param SET value = ? WHERE param_key = ?", value, key);
    }

    @AfterEach
    void restore() {
        param("rent.service_fee.amount", "0");
        param("rent.service_fee.vat_applicable", "1");
        param("rent.service_fee.in_share_base", "0");
        param("rent.service_fee.payer", "USER");
        param("finance.share.fixed_enabled", "0");
        param("invoice.b2c.enabled", "0");
    }

    private String rentedOrder() {
        RentFixture.rentable(jdbc);
        String phone = "+97154" + (1_000_000 + (System.nanoTime() % 8_000_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        String token = post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
        return post("/mp/trade/orders/rent", Map.of("cabinetNo", RentFixture.CABINET, "useFreeDeposit", true), token)
                .okData().path("orderNo").asText();
    }

    @Test
    @DisplayName("★★ 服务费随单定格：69.00 + 0.90；不进分成基数；发票只对租金拆税；借出后改配置不影响这单")
    void service_fee_snapshotted_charged_excluded_from_share_and_vat() {
        param("rent.service_fee.amount", "0.90");
        param("rent.service_fee.vat_applicable", "0");
        param("invoice.b2c.enabled", "1");
        jdbc.update("UPDATE sys_tax_setting SET issuer_legal_name = 'Junverse Technology LLC', issuer_address = 'Office 1, Dubai', "
                + "trn = '100000000000003', rate_percent = 5.00, included_in_price = 1 WHERE country = 'AE'");
        String no = rentedOrder();
        param("rent.service_fee.amount", "5.00");   // 借出之后改配置：这单仍按 0.90
        jdbc.update("UPDATE ord_order SET status = 'IN_USE', rent_start_at = rent_start_at - INTERVAL 50 HOUR, "
                + "price_snapshot = JSON_SET(COALESCE(price_snapshot, '{}'), '$.capTotal', '69.00') WHERE order_no = ?", no);
        assertThat(rents.chargeOverdueIfDue(no, 48)).isTrue();

        assertThat(jdbc.queryForObject("SELECT amount FROM ord_order WHERE order_no = ?", BigDecimal.class, no))
                .isEqualByComparingTo("69.90");
        List<Map<String, Object>> shares = jdbc.queryForList("SELECT gross_amount FROM share_record WHERE order_no = ?", no);
        assertThat(shares).as("结算要生成分润明细").isNotEmpty();
        assertThat(shares).allSatisfy(r -> assertThat((BigDecimal) r.get("gross_amount")).as("服务费不进分成基数")
                .isEqualByComparingTo("69.00"));

        Map<String, Object> inv = jdbc.queryForMap("SELECT gross_amount, tax_amount, net_amount FROM ord_tax_invoice WHERE order_no = ?", no);
        assertThat((BigDecimal) inv.get("gross_amount")).isEqualByComparingTo("69.90");
        assertThat((BigDecimal) inv.get("tax_amount")).as("税只从 69.00 里倒推").isEqualByComparingTo("3.29");
        assertThat((BigDecimal) inv.get("net_amount")).isEqualByComparingTo("66.61");
    }

    @Test
    @DisplayName("服务费只在有应付时收；payer=PLATFORM 不进订单")
    void fee_only_when_payable_and_payer_user() {
        var fee = new PriceResolver.ServiceFee(new BigDecimal("0.90"), true, false);
        assertThat(fee.addTo(BigDecimal.ZERO)).isEqualByComparingTo("0");
        assertThat(fee.addTo(new BigDecimal("3.00"))).isEqualByComparingTo("3.90");

        param("rent.service_fee.amount", "0.90");
        param("rent.service_fee.payer", "PLATFORM");
        String no = rentedOrder();
        assertThat(PriceResolver.serviceFeeFromSnapshot(
                jdbc.queryForObject("SELECT price_snapshot FROM ord_order WHERE order_no = ?", String.class, no))).isNull();
    }

    @Test
    @DisplayName("★ 单均固定额：开关开 → 商家按 ORDER_COUNT 固定额逐单分（不按比例）；关 → 照旧按比例")
    void fixed_per_order_share() {
        String agent = "AGFX" + Long.toString(System.nanoTime(), 36).toUpperCase();
        jdbc.update("INSERT INTO agt_commission (rule_no, tenant_id, agent_no, dimension, rate, fixed_amount, currency, mode, status, effective_at) "
                + "VALUES (?, 'MAIN', ?, 'ORDER_COUNT', 0, 1.50, 'AED', 'LEDGER', 'ACTIVE', '2020-01-01')", "AC" + agent, agent);
        String period = bizToday().toString().substring(0, 7);

        String off = "ORDFX-OFF-" + agent;
        generator.generate(new OrderSettledEvent(off, "CABX", "SITE-NONE-" + agent, agent, new BigDecimal("40.00"), "AED", period));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_record WHERE order_no = ? AND source_no = 'FIXED_PER_ORDER'",
                Integer.class, off)).as("开关关着照旧").isZero();

        param("finance.share.fixed_enabled", "1");
        String on = "ORDFX-ON-" + agent;
        generator.generate(new OrderSettledEvent(on, "CABX", "SITE-NONE-" + agent, agent, new BigDecimal("40.00"), "AED", period));
        Map<String, Object> r = jdbc.queryForMap("SELECT amount, rate, payee_no FROM share_record WHERE order_no = ? AND dimension = 'AGENT'", on);
        assertThat((BigDecimal) r.get("amount")).isEqualByComparingTo("1.50");
        assertThat((BigDecimal) r.get("rate")).isEqualByComparingTo("0");
        assertThat(r.get("payee_no")).isEqualTo(agent);
    }
}
