package ai.neargo.sharehub.scenario;

import ai.neargo.sharehub.support.ApiTestSupport;
import ai.neargo.sharehub.support.RentFixture;
import ai.neargo.sharehub.trade.order.dto.OrderDtos;
import ai.neargo.sharehub.trade.order.service.RefundService;
import ai.neargo.sharehub.trade.service.RentOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 开票主体可配 + C 端税务发票（V181，运营方原始需求 C01 / RQ-INV，第 3 批）。
 *
 * <p>收款即开（含税 5%：69.00 → 净 65.71 + 税 3.29）；主体缺项 → BLOCKED 写明缺什么，补齐后重试开出；
 * 退款批准 → 贷项通知单冲红并沿用原票税率；ON_SETTLE 模式下已扣款未归还的单等归还才开。
 */
class B2cTaxInvoiceTest extends ApiTestSupport {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RentOrderService rents;
    @Autowired
    private RefundService refunds;

    private String lastToken;

    @BeforeEach
    void enable() {
        param("invoice.b2c.enabled", "1");
        param("invoice.b2c.trigger", "ON_CHARGE");
        issuer("Junverse Technology LLC", "Office 1, Dubai, UAE", "100000000000003");
    }

    @AfterEach
    void restore() {
        param("invoice.b2c.enabled", "0");
        param("invoice.b2c.trigger", "ON_CHARGE");
    }

    private void param(String key, String value) {
        jdbc.update("UPDATE sys_param SET value = ? WHERE param_key = ?", value, key);
    }

    private void issuer(String legalName, String address, String trn) {
        jdbc.update("UPDATE sys_tax_setting SET issuer_legal_name = ?, issuer_address = ?, trn = ?, rate_percent = 5.00, "
                + "included_in_price = 1 WHERE country = 'AE'", legalName, address, trn);
    }

    /** 借出 → 回拨借出时刻 → 到时限发起最高收费 69.00（一次真实请款，走 markPaid）。 */
    private String overdueCharged() {
        RentFixture.rentable(jdbc);
        String phone = "+97151" + (1_000_000 + (System.nanoTime() % 8_000_000));
        String otp = post("/mp/auth/otp", Map.of("phone", phone), null).okData().path("devCode").asText();
        lastToken = post("/mp/auth/login", Map.of("grantType", "phone_otp", "phone", phone, "otp", otp), null)
                .okData().path("token").asText();
        String no = post("/mp/trade/orders/rent", Map.of("cabinetNo", RentFixture.CABINET, "useFreeDeposit", true), lastToken)
                .okData().path("orderNo").asText();
        jdbc.update("UPDATE ord_order SET status = 'IN_USE', rent_start_at = rent_start_at - INTERVAL 50 HOUR, "
                + "price_snapshot = JSON_SET(COALESCE(price_snapshot, '{}'), '$.capTotal', '69.00') WHERE order_no = ?", no);
        assertThat(rents.chargeOverdueIfDue(no, 48)).isTrue();
        return no;
    }

    private List<Map<String, Object>> invoices(String orderNo) {
        return jdbc.queryForList("SELECT * FROM ord_tax_invoice WHERE order_no = ? ORDER BY id", orderNo);
    }

    @Test
    @DisplayName("★★ 收款即开：主体快照 + 含税倒推（69.00 = 65.71 + 3.29），重复回调不重开；C 端能看到")
    void charge_issues_invoice_with_issuer_snapshot() {
        String no = overdueCharged();
        List<Map<String, Object>> rows = invoices(no);
        assertThat(rows).hasSize(1);
        Map<String, Object> inv = rows.get(0);
        assertThat(inv.get("status")).isEqualTo("ISSUED");
        assertThat(inv.get("kind")).isEqualTo("INVOICE");
        assertThat((String) inv.get("invoice_no")).matches("INV-\\d{4}-\\d{6}");
        assertThat(inv.get("issuer_legal_name")).isEqualTo("Junverse Technology LLC");
        assertThat(inv.get("issuer_trn")).isEqualTo("100000000000003");
        assertThat((BigDecimal) inv.get("gross_amount")).isEqualByComparingTo("69.00");
        assertThat((BigDecimal) inv.get("net_amount")).isEqualByComparingTo("65.71");
        assertThat((BigDecimal) inv.get("tax_amount")).isEqualByComparingTo("3.29");

        // 改主体不影响已开的票（快照）
        issuer("Jun Sheng Technology LLC", "Office 2, Dubai, UAE", "100000000000003");
        assertThat(invoices(no).get(0).get("issuer_legal_name")).isEqualTo("Junverse Technology LLC");

        var mine = get("/mp/trade/orders/" + no + "/invoices", lastToken).okData();
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).path("invoiceNo").asText()).isEqualTo(inv.get("invoice_no"));
    }

    @Test
    @DisplayName("★ 主体缺 TRN → 不开票，BLOCKED 写明缺哪项；补齐后运营重试开出")
    void missing_issuer_field_blocks_then_retry_issues() {
        issuer("Junverse Technology LLC", "Office 1, Dubai, UAE", null);
        String no = overdueCharged();
        Map<String, Object> inv = invoices(no).get(0);
        assertThat(inv.get("status")).isEqualTo("BLOCKED");
        assertThat(inv.get("fail_reason")).isEqualTo("ISSUER_MISSING:trn");
        assertThat(jdbc.queryForObject("SELECT pay_status FROM ord_order WHERE order_no = ?", String.class, no))
                .as("开票失败不影响收款").isEqualTo("PAID");

        issuer("Junverse Technology LLC", "Office 1, Dubai, UAE", "100000000000003");
        var r = post("/api/trade/tax-invoices/" + inv.get("invoice_no") + "/retry", Map.of(), login("ADMIN")).okData();
        assertThat(r.path("status").asText()).isEqualTo("ISSUED");
        assertThat(r.path("issuerTrn").asText()).isEqualTo("100000000000003");
        assertThat(r.path("taxAmount").decimalValue()).isEqualByComparingTo("3.29");
    }

    @Test
    @DisplayName("★ 退款批准 → 贷项通知单冲红，引用原票、金额为负")
    void refund_approval_issues_credit_note() {
        String no = overdueCharged();
        String invoiceNo = (String) invoices(no).get(0).get("invoice_no");
        String cUser = jdbc.queryForObject("SELECT c_user_no FROM ord_order WHERE order_no = ?", String.class, no);
        var applied = refunds.apply(new OrderDtos.RefundApplyReq(no, cUser, new BigDecimal("21.00"), "AED",
                "returned late, goodwill", UUID.randomUUID().toString()));
        refunds.audit(applied.refundNo(), new OrderDtos.RefundAuditReq(true, null));

        List<Map<String, Object>> rows = invoices(no);
        assertThat(rows).hasSize(2);
        Map<String, Object> cn = rows.get(1);
        assertThat(cn.get("kind")).isEqualTo("CREDIT_NOTE");
        assertThat((String) cn.get("invoice_no")).startsWith("CN-");
        assertThat(cn.get("ref_invoice_no")).isEqualTo(invoiceNo);
        assertThat((BigDecimal) cn.get("gross_amount")).isEqualByComparingTo("-21.00");
        assertThat((BigDecimal) cn.get("net_amount")).isEqualByComparingTo("-20.00");
        assertThat((BigDecimal) cn.get("tax_amount")).isEqualByComparingTo("-1.00");
    }

    @Test
    @DisplayName("开关关着 → 不开；ON_SETTLE → 已扣款未归还的单等归还结单才开")
    void disabled_and_on_settle() {
        param("invoice.b2c.enabled", "0");
        assertThat(invoices(overdueCharged())).isEmpty();

        param("invoice.b2c.enabled", "1");
        param("invoice.b2c.trigger", "ON_SETTLE");
        String no = overdueCharged();
        assertThat(invoices(no)).as("还没结单").isEmpty();
        post("/internal/trade/orders/" + no + "/return", Map.of("returnCabinetNo", "CAB1006"), login("ADMIN")).okData();
        assertThat(invoices(no)).hasSize(1);
        assertThat(invoices(no).get(0).get("status")).isEqualTo("ISSUED");
    }
}
